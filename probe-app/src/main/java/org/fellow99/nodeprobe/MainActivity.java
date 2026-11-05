package org.fellow99.nodeprobe;

/**
 * 引擎载荷探针载体 —— RUN ALL PROBES + 报告（specs/101-probe-app）。
 *
 * 探针：PRB-01 载荷就位 / PRB-02 引擎加载 / PRB-03 版本契约 / PRB-04 入口符号 / PRB-05 进程内执行(⬜)。
 * 加载链与 aosp-node-bridge NativeBridge 同源：System.loadLibrary("node") → dlsym("_ZN4node5StartEiPPc")，
 * 本模块多一层极小 JNI 垫片（libprobe_shim.so, probe_shim.c）把 dlerror 文本与符号地址带回判据层。
 *
 * 期望值常量镜像根仓 runtime.lock.json（active node=26.13.0 / releases#26.13.0.sha256）——
 * 升版需同步（C2 唯一事实源）。
 *
 * 报告：filesDir/probe-report.json（{runAt,device,expected,summary,probes[]}），三态 PASS/FAIL/BLOCKED。
 * 无头：adb shell am start -n org.fellow99.nodeprobe/.MainActivity --ez autorun true 启动即跑。
 */
public class MainActivity extends android.app.Activity {

    // ── 期望值：镜像 runtime.lock.json active + releases#26.13.0.sha256（== node-runtime.properties.sha256）──
    private static final String EXPECTED_NODE_VERSION = "26.13.0";
    private static final String EXPECTED_NODE_SHA256 =
            "bc5d15f825a74ad089087cb4f5eaddb1c178d6152de7171270483e31651094d5";

    private android.widget.TextView logView;
    private final StringBuilder uiLog = new StringBuilder();
    private final java.util.concurrent.atomic.AtomicBoolean running =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private String nativeDir;

    private interface Check {
        /** null = PASS；"BLOCKED:…" = BLOCKED；"PASS-" 前缀 = PASS(事实)；其余 = FAIL */
        String check();
    }

    private static final class Probe {
        final String id, title;
        final long timeoutMs;
        final Check check;
        Probe(String id, String title, long timeoutMs, Check check) {
            this.id = id;
            this.title = title;
            this.timeoutMs = timeoutMs;
            this.check = check;
        }
    }

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        nativeDir = getApplicationInfo().nativeLibraryDir;

        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        android.widget.Button run = new android.widget.Button(this);
        run.setText("RUN ALL PROBES");
        run.setOnClickListener(v -> runAll());
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        logView = new android.widget.TextView(this);
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logView.setTextSize(11f);
        int pad = (int) (getResources().getDisplayMetrics().density * 8);
        logView.setPadding(pad, pad, pad, pad);
        scroll.addView(logView);
        root.addView(run, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        log("probe-app ready\nnativeLibraryDir=" + nativeDir
                + "\nabi=" + abisLine()
                + "\n点 RUN ALL PROBES 或 am start --ez autorun true 无头跑批");
        if (getIntent().getBooleanExtra("autorun", false)) {
            log("autorun=true：启动即自动跑批");
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this::runAll, 300);
        }
    }

    private void log(String s) {
        runOnUiThread(() -> {
            uiLog.append(s).append('\n');
            if (logView != null) logView.setText(uiLog.toString());
        });
    }

    // ── 数据小工具 ────────────────────────────────────────────────────────────

    private String abisLine() {
        StringBuilder sb = new StringBuilder();
        for (String a : android.os.Build.SUPPORTED_ABIS) {
            if (sb.length() > 0) sb.append(',');
            sb.append(a);
        }
        return sb.toString();
    }

    private java.util.Properties readNodeProperties() {
        java.io.InputStream is = null;
        try {
            is = getClassLoader().getResourceAsStream("META-INF/node-runtime.properties");
        } catch (Throwable ignored) {
        }
        if (is == null) {
            try {
                is = getAssets().open("META-INF/node-runtime.properties");
            } catch (Exception ignored) {
            }
        }
        if (is == null) {
            return null;
        }
        try {
            java.util.Properties p = new java.util.Properties();
            p.load(is);
            return p;
        } catch (Exception ignored) {
            return null;
        } finally {
            try {
                is.close();
            } catch (Exception ignored) {
            }
        }
    }

    private String sha256File(java.io.File f) {
        try (java.io.FileInputStream fis = new java.io.FileInputStream(f)) {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            int r;
            while ((r = fis.read(buf)) > 0) {
                md.update(buf, 0, r);
            }
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format(java.util.Locale.ROOT, "%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    // ── 探针表 ────────────────────────────────────────────────────────────────

    private java.util.List<Probe> probes() {
        java.util.List<Probe> l = new java.util.ArrayList<>();
        final String nd = nativeDir;

        // PRB-01 载荷就位（FR-101-PROBE-001）
        l.add(new Probe("PRB-01", "载荷就位（libnode.so/libc++_shared.so 在 nativeLibraryDir，arm64 唯一 ABI）", 30_000, () -> {
            java.io.File node = new java.io.File(nd, "libnode.so");
            java.io.File cxx = new java.io.File(nd, "libc++_shared.so");
            String abis = abisLine();
            boolean arm64Only = true;
            for (String a : android.os.Build.SUPPORTED_ABIS) {
                if (!a.startsWith("arm64")) arm64Only = false;
            }
            if (!node.isFile() || node.length() == 0) return "FAIL|libnode.so 缺失或空：" + node.getAbsolutePath();
            if (!cxx.isFile() || cxx.length() == 0) return "FAIL|libc++_shared.so 缺失或空：" + cxx.getAbsolutePath();
            if (!arm64Only) return "FAIL|非 arm64 唯一 ABI：" + abis;
            return null;
        }));

        // PRB-02 引擎加载（FR-101-PROBE-002）
        l.add(new Probe("PRB-02", "引擎加载（System.loadLibrary(\"node\")）", 60_000, () -> {
            try {
                System.loadLibrary("node");
                return null;
            } catch (Throwable t) {
                return "FAIL|System.loadLibrary(\"node\") 失败: " + t.getClass().getName() + ": " + t.getMessage();
            }
        }));

        // PRB-03 版本契约（FR-101-PROBE-003）
        l.add(new Probe("PRB-03", "版本契约（node-runtime.properties node.version/sha256 vs lock 26.13.0 + 实际文件哈希）", 150_000, () -> {
            java.util.Properties p = readNodeProperties();
            if (p == null) return "FAIL|META-INF/node-runtime.properties 不可得（classloader 与 assets 均无）";
            String v = p.getProperty("node.version");
            String sha = p.getProperty("sha256");
            if (!EXPECTED_NODE_VERSION.equals(v)) {
                return "FAIL|node.version=" + v + " ≠ " + EXPECTED_NODE_VERSION;
            }
            if (sha == null || !sha.equalsIgnoreCase(EXPECTED_NODE_SHA256)) {
                return "FAIL|properties.sha256=" + sha + " ≠ 锁值 " + EXPECTED_NODE_SHA256;
            }
            String fileSha = sha256File(new java.io.File(nd, "libnode.so"));
            if (fileSha == null) {
                return "PASS-FACT|版本/锁 sha 断言过；实际 libnode.so 文件 sha256 不可计算（记录性）";
            }
            if (!fileSha.equalsIgnoreCase(sha)) {
                return "FAIL|实际 libnode.so sha256=" + fileSha + " ≠ properties.sha256=" + sha;
            }
            return null;
        }));

        // PRB-04 引擎入口符号（FR-101-PROBE-004）
        l.add(new Probe("PRB-04", "引擎入口符号（dlsym _ZN4node5StartEiPPc 非 0）", 30_000, () -> {
            String err = ShimProbe.ensureLoaded();
            if (err != null) {
                return "FAIL|libprobe_shim.so 加载失败: " + err;
            }
            long addr = ShimProbe.dlsymNodeStart();
            if (addr == 0) {
                String dl = ShimProbe.getDlError();
                return "FAIL|dlsym(node::Start)==0；" + (dl == null ? "无 dlerror 文本" : dl);
            }
            return "PASS-FACT|addr=0x" + Long.toHexString(addr);
        }));

        // PRB-05 进程内执行（FR-101-PROBE-005，⬜ 后续批次，不硬造）
        l.add(new Probe("PRB-05", "进程内执行 node -e 回程 v26.13.0（⬜）", 5_000, () ->
                "BLOCKED:FR-101-PROBE-005 进程内执行（node -e 回程）本批次不实现——需 isolate 引导 + "
                        + "stdout 捕获 + 进程内安全隔离与超时，无法在本批次（无设备窗口）验证；⬜ 后续批次（不硬造）。"));

        return l;
    }

    // ── 主流程 ────────────────────────────────────────────────────────────────

    private void runAll() {
        if (!running.compareAndSet(false, true)) {
            log("跑批进行中，跳过");
            return;
        }
        new Thread(() -> {
            try {
                org.json.JSONArray report = new org.json.JSONArray();
                log("── RUN ALL PROBES ──");
                int pass = 0, fail = 0, blocked = 0;
                for (Probe p : probes()) {
                    long t0 = System.currentTimeMillis();
                    final String[] verdictBox = new String[1];
                    final java.util.concurrent.atomic.AtomicReference<Throwable> thrown =
                            new java.util.concurrent.atomic.AtomicReference<>();
                    Thread t = new Thread(() -> {
                        try {
                            verdictBox[0] = p.check.check();
                        } catch (Throwable x) {
                            thrown.set(x);
                        }
                    });
                    t.setDaemon(true);
                    t.start();
                    long timeoutMs = p.timeoutMs;
                    boolean timedOut = false;
                    try {
                        t.join(timeoutMs + 200);
                    } catch (InterruptedException ie) {
                        timedOut = true;
                    }
                    if (t.isAlive()) {
                        t.interrupt();
                        verdictBox[0] = "FAIL|超时(>" + timeoutMs + "ms): " + p.title;
                        timedOut = true;
                    } else if (thrown.get() != null) {
                        verdictBox[0] = "FAIL|探针异常: " + thrown.get();
                    }
                    long ms = System.currentTimeMillis() - t0;
                    String verdict = verdictBox[0];
                    String tag;
                    if (verdict == null) {
                        tag = "PASS";
                    } else if (verdict.startsWith("BLOCKED")) {
                        tag = "BLOCKED";
                    } else if (verdict.startsWith("PASS-")) {
                        tag = "PASS";
                    } else {
                        tag = "FAIL";
                    }
                    if ("PASS".equals(tag)) pass++;
                    else if ("BLOCKED".equals(tag)) blocked++;
                    else fail++;
                    try {
                        org.json.JSONObject j = new org.json.JSONObject();
                        j.put("id", p.id);
                        j.put("title", p.title);
                        j.put("verdict", tag);
                        j.put("detail", verdict == null ? "" : verdict);
                        j.put("timeoutMs", timeoutMs);
                        j.put("timedOut", timedOut);
                        j.put("ms", ms);
                        report.put(j);
                    } catch (org.json.JSONException ignored) {
                    }
                    log(String.format("%-8s %-8s %s", p.id, tag, p.title)
                            + (verdict == null ? "" : "  | " + head(verdict, 220)));
                }
                org.json.JSONObject out = new org.json.JSONObject();
                try {
                    out.put("runAt", new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", java.util.Locale.ROOT).format(new java.util.Date()));
                    out.put("device", deviceLine());
                    out.put("expected", new org.json.JSONObject()
                            .put("node", "v" + EXPECTED_NODE_VERSION)
                            .put("abi", "arm64-v8a")
                            .put("sha256", EXPECTED_NODE_SHA256));
                    out.put("summary", new org.json.JSONObject()
                            .put("pass", pass).put("fail", fail).put("blocked", blocked)
                            .put("total", pass + fail + blocked));
                    out.put("probes", report);
                } catch (org.json.JSONException ignored) {
                }
                java.io.File f = new java.io.File(getFilesDir(), "probe-report.json");
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(f)) {
                    fos.write(out.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                } catch (Exception e) {
                    log("报告写盘失败: " + e);
                }
                log(String.format("── 汇总：PASS=%d FAIL=%d BLOCKED=%d ──", pass, fail, blocked));
                log("报告：filesDir/probe-report.json（run-as org.fellow99.nodeprobe cat files/probe-report.json）");
            } catch (Throwable t) {
                log("FATAL " + t);
            } finally {
                running.set(false);
            }
        }).start();
    }

    private String deviceLine() {
        return android.os.Build.MODEL + " / " + android.os.Build.VERSION.RELEASE
                + " / API " + android.os.Build.VERSION.SDK_INT + " / " + abisLine();
    }

    private static String head(String s, int n) {
        if (s == null) return "";
        s = s.replace("\n", "\\n");
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }
}
