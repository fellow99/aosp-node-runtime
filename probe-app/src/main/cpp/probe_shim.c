/*
 * probe_shim.c —— 引擎载荷探针用 JNI 垫片（specs/101-probe-app, FR-101-PROBE-004）
 *
 * 只做一件事：按名解析 libnode 的 node::Start 入口符号，把函数指针地址交回 Java。
 *
 * 关键决策：
 *  1) 不链接 libnode —— 符号一律按名 dlsym 解析（task 强制 "不链接 libnode"：
 *     避免 NDK 链接期对引擎符号的依赖；加载期由 System.loadLibrary("node") 把
 *     libnode.so 映射进进程后，dlsym(RTLD_DEFAULT,...) 即可命中全局符号表）。
 *  2) 先 dlopen("libnode.so")（任务语义优先显式句柄；失败不视为终态），再退回
 *     dlsym(h?h:RTLD_DEFAULT, "_ZN4node5StartEiPPc")。
 *  3) 失败时把 dlerror() 文本存入 g_err，getDlError() 带回 Java（任务 "0=失败/记录
 *     dlerror 文本"）。
 *  4) 无 JNI_OnLoad、无 Android logging、零额外链接（dlopen/dlsym 在 bionic libc，
 *     API 21+ 无需 -ldl）。
 */

#include <unistd.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>

#include <jni.h>
#include <dlfcn.h>
#include <stdint.h>
#include <stdio.h>

#define NODE_START_SYM "_ZN4node5StartEiPPc"

static char g_err[512] = {0};

JNIEXPORT jlong JNICALL
Java_org_fellow99_nodeprobe_ShimProbe_dlsymNodeStart(JNIEnv *env, jclass clazz) {
    g_err[0] = '\0';

    /* 先尝试显式 dlopen（若还未经 loadLibrary 入进程，这里会载入；已载入则命中同一句柄路径）。 */
    void *h = dlopen("libnode.so", RTLD_NOW | RTLD_GLOBAL);
    const char *dlopenErr = h ? NULL : dlerror();

    /* 显式句柄失败则退回 RTLD_DEFAULT（全局符号表，命中 loadLibrary 已映射的 libnode.so）。 */
    void *p = dlsym(h ? h : RTLD_DEFAULT, NODE_START_SYM);
    const char *dlsymErr = dlerror();

    if (p) {
        return (jlong)(intptr_t)p;
    }
    if (dlsymErr) {
        snprintf(g_err, sizeof(g_err), "dlsym(%s) 失败: %s (dlopen=%s)",
                 NODE_START_SYM, dlsymErr, h ? "ok" : (dlopenErr ? dlopenErr : "default-handle"));
    } else if (dlopenErr) {
        snprintf(g_err, sizeof(g_err), "dlopen(libnode.so) 失败: %s；dlsym(RTLD_DEFAULT) 亦未解析到 %s",
                 dlopenErr, NODE_START_SYM);
    } else {
        snprintf(g_err, sizeof(g_err), "解析 %s 失败（无 dlerror 文本）", NODE_START_SYM);
    }
    return 0;
}

JNIEXPORT jstring JNICALL
Java_org_fellow99_nodeprobe_ShimProbe_getDlError(JNIEnv *env, jclass clazz) {
    return (*env)->NewStringUTF(env, g_err[0] ? g_err : "no dlerror captured");
}

/* ── PRB-05 进程内执行（FR-101-PROBE-005）─────────────────────────────────
 * 在独立 32MB 栈线程上调用 node::Start(argc, argv)（argv = {node, -e, script}，
 * argc 不含尾部 NULL 哨兵——node 对 argc/argv 的连续内存与哨兵语义有硬性要求），
 * 并把 fd 1/2 重定向到 outFile（node stdout/stderr 落文件供 Java 回读）。
 * node::Start 每进程一次：g_dispatched 守卫，二次调用直接返回 -5。
 * 返回：node::Start 退出码；负值为本垫片错误（-2 参数 · -3 dlsym 失败 ·
 * -4 open 失败 · -5 重复调用）。
 */
static volatile int g_dispatched = 0;

typedef int (*node_start_fn)(int, char **);

static int g_run_rc = -1;
static char *g_run_script = NULL;
static const char *g_run_out = NULL;

static void *run_node_thread(void *arg) {
    (void)arg;
    node_start_fn node_start = (node_start_fn)arg ? (node_start_fn)arg : NULL;
    /* node::Start(argv) —— argv 生命周期须覆盖调用全程 */
    static char *args[3];  /* 单次调用，静态即可 */
    args[0] = "node";
    args[1] = "-e";
    args[2] = g_run_script;
    g_run_rc = node_start(3, args);
    return NULL;
}

JNIEXPORT jint JNICALL
Java_org_fellow99_nodeprobe_ShimProbe_runNodeE(JNIEnv *env, jclass clazz,
                                                 jstring jScript, jstring jOut) {
    if (g_dispatched) {
        snprintf(g_err, sizeof(g_err), "node::Start 每进程一次（已调用过）");
        return -5;
    }
    const char *script = (*env)->GetStringUTFChars(env, jScript, NULL);
    const char *out = (*env)->GetStringUTFChars(env, jOut, NULL);
    if (!script || !out) return -2;

    void *h = dlopen("libnode.so", RTLD_NOW | RTLD_GLOBAL);
    void *start = dlsym(h ? h : RTLD_DEFAULT, NODE_START_SYM);
    if (!start) {
        const char *de = dlerror();
        snprintf(g_err, sizeof(g_err), "dlsym(%s) 失败: %s", NODE_START_SYM, de ? de : "no detail");
        (*env)->ReleaseStringUTFChars(env, jScript, script);
        (*env)->ReleaseStringUTFChars(env, jOut, out);
        return -3;
    }

    int fd = open(out, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
    if (fd < 0) {
        snprintf(g_err, sizeof(g_err), "open(%s) 失败: %s", out, strerror(errno));
        (*env)->ReleaseStringUTFChars(env, jScript, script);
        (*env)->ReleaseStringUTFChars(env, jOut, out);
        return -4;
    }
    dup2(fd, 1);
    dup2(fd, 2);
    close(fd);
    fflush(NULL);

    /* 32MB 栈（RFC：V8 初始化需要大栈；bridge 同款纪律） */
    char *scriptCopy = strdup(script);
    /* g_run_script 生命周期 = 调用全程（线程 join 前不释放） */
    g_run_script = scriptCopy;
    g_run_out = out;
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setstacksize(&attr, 32 * 1024 * 1024);
    pthread_t tid;
    if (pthread_create(&tid, &attr, run_node_thread, start) != 0) {
        pthread_attr_destroy(&attr);
        snprintf(g_err, sizeof(g_err), "pthread_create 失败: %s", strerror(errno));
        (*env)->ReleaseStringUTFChars(env, jScript, script);
        (*env)->ReleaseStringUTFChars(env, jOut, out);
        return -6;
    }
    pthread_attr_destroy(&attr);
    void *ignored;
    pthread_join(tid, &ignored);
    g_dispatched = 1;

    free(scriptCopy);
    (*env)->ReleaseStringUTFChars(env, jScript, script);
    (*env)->ReleaseStringUTFChars(env, jOut, out);
    return g_run_rc;
}
