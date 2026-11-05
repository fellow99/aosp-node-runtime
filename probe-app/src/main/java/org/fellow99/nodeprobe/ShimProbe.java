package org.fellow99.nodeprobe;

/**
 * libprobe_shim.so 的 Java 面（specs/101-probe-app, FR-101-PROBE-004）。
 *
 * 垫片由 NDK r28 交叉编译（probe_shim.c，不链接 libnode、按名 dlsym），
 * 编译命令见 docs/probe-shim-build.md。二进制不入库（probe-app/.gitignore）。
 */
public final class ShimProbe {

    private static boolean loaded;
    private static String loadError;

    private ShimProbe() {
    }

    /**
     * 幂等加载 libprobe_shim.so；返回 null = 成功，否则错误文本（具体异常）。
     */
    public static synchronized String ensureLoaded() {
        if (loaded) {
            return null;
        }
        try {
            System.loadLibrary("probe_shim");
            loaded = true;
            loadError = null;
            return null;
        } catch (Throwable t) {
            loadError = t.getClass().getName() + ": " + t.getMessage();
            return loadError;
        }
    }

    /**
     * 引擎入口符号（node::Start，mangled `_ZN4node5StartEiPPc`）解析地址；
     * 0 = 失败（getDlError() 取 dlerror 文本）。
     */
    public static native long dlsymNodeStart();

    /**
     * 最后一次 dlsym/dlopen 失败的 dlerror 文本。
     */
    public static native String getDlError();
}
