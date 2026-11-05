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
