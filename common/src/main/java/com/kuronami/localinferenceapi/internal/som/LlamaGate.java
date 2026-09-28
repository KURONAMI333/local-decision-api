package com.kuronami.localinferenceapi.internal.som;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * pin 済み llama.cpp runtime を監督する {@link SomNativeGate}。起動経路:
 *
 *   verifyEmbeddedManifest(anchor) → stage(platform subset, fetch 含む)
 *   → inspect(commit marker + 全 staged bytes の sha 再照合)
 *   → Unpacker で runtime archive を展開(member 毎の sha 照合)
 *   → spawn 直前に entry executable・GGUF の real-path containment と
 *     bytes を再照合 → llama-server を 127.0.0.1 のみに bind して起動
 *   → /health が ok になるまで待つ
 *
 * 規則(いずれも ensureServing を呼ぶ router seq thread 上で完結):
 *  - ensureServing は絶対に例外を投げない・長時間ブロックしない — stage
 *    と spawn は専用 provision スレッドへ逃がし、進行中は NO_COMMIT を
 *    返して要求を CPU 経路へ即座に退避させる。
 *  - TAMPERED(manifest digest 不一致・containment escape・staged bytes や
 *    展開 member の hash 不一致・embedded license 不一致)はこのゲートの
 *    存続中 latch される — router 側の latch と二重に fail-closed。
 *  - spawn/health 失敗は cooldown 後にのみ再試行し、cooldown 中は
 *    NO_COMMIT を返す — 要求毎の crash-loop を打たない。
 *  - invalidateWorker(request 失敗)は即座に子プロセスを落とし、次の
 *    ensureServing で再 provision する — 再試行回数の上限は router の
 *    circuit breaker(連続 request 失敗)が束ねるため gate は回数を数えない。
 *  - idleTimeout の間 request/ensureServing が無ければ child を止める —
 *    VRAM/RAM を遊休時に占有しない。再起動は次の demand で。
 *  - child の stdout/stderr は排水して pipe 詰まりを防ぎ、末尾のみ
 *    startup 失敗の診断へ残す。
 */
final class LlamaGate implements SomNativeGate {

    static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(180);
    static final Duration IDLE_TIMEOUT = Duration.ofMinutes(5);
    static final Duration PROVISION_COOLDOWN = Duration.ofSeconds(30);
    static final Duration STOP_GRACE = Duration.ofSeconds(5);
    static final int CONTEXT_SIZE = 8192;

    /** 試験 seam — 実プロセス起動を差し替える。製品は ProcessBuilder。 */
    interface ProcessLauncher {
        Process start(List<String> argv, Path cwd, Path logSink) throws IOException;
    }

    private final Path gameDirectory;
    private final String platformKey;   // null → NO_RUNTIME_ARTIFACT
    private final Fetcher fetcher;
    private final EmbeddedSource embedded;
    private final ProcessLauncher launcher;
    private final Duration startupTimeout;
    private final Duration idleTimeout;
    private final Duration provisionCooldown;

    private final ExecutorService provisionExec;
    private final ScheduledExecutorService idleTimer;

    private CompletableFuture<Availability> provision;
    private Process child;
    private int port = -1;
    private String apiKey;
    private Path stagedRoot;            // provision が確定した staging root
    private boolean tampered;
    private boolean closed;
    private volatile Availability lastFailure = Availability.NO_COMMIT;
    private long nextProvisionAt;        // nanoTime — failure cooldown
    private ScheduledFuture<?> idleTask;

    LlamaGate(Path gameDirectory) {
        this(gameDirectory, SomPlatform.detect(),
                HttpFetcher.production(SomPin.ALLOWED_FETCH_HOSTS),
                SomPin::openEmbedded, ProcessBuilderLauncher.INSTANCE,
                STARTUP_TIMEOUT, IDLE_TIMEOUT, PROVISION_COOLDOWN);
    }

    /** 試験用 seam — platform・fetch・起動・timeout を注入する。 */
    LlamaGate(Path gameDirectory, String platformKey, Fetcher fetcher,
              EmbeddedSource embedded, ProcessLauncher launcher,
              Duration startupTimeout, Duration idleTimeout,
              Duration provisionCooldown) {
        this.gameDirectory = gameDirectory;
        this.platformKey = platformKey;
        this.fetcher = fetcher;
        this.embedded = embedded;
        this.launcher = launcher;
        this.startupTimeout = startupTimeout;
        this.idleTimeout = idleTimeout;
        this.provisionCooldown = provisionCooldown;
        this.provisionExec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "localinferenceapi-som-provision");
            t.setDaemon(true);
            return t;
        });
        this.idleTimer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "localinferenceapi-som-idle");
            t.setDaemon(true);
            return t;
        });
    }

    // ---- SomNativeGate ----

    @Override
    public synchronized Availability ensureServing() {
        if (closed) return Availability.NO_COMMIT;
        if (tampered) return Availability.TAMPERED;
        if (platformKey == null) return Availability.NO_RUNTIME_ARTIFACT;
        Process c = child;
        if (c != null) {
            if (c.isAlive()) {
                touch();
                return Availability.SERVING;
            }
            // 勝手に死んだ — request 失敗を待たず死亡を観測した時点で次の
            // demand に備える(cooldown は provision 失敗にのみ掛ける)。
            child = null;
            port = -1;
            apiKey = null;
        }
        if (provision != null) {
            if (!provision.isDone()) return Availability.NO_COMMIT;
            Availability a;
            try {
                a = provision.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                a = Availability.LAUNCH_FAILED;
            } catch (ExecutionException e) {
                a = Availability.LAUNCH_FAILED;
            } catch (java.util.concurrent.CancellationException e) {
                // close() が cancel(true) した provision — CPU へ逃がす
                a = Availability.NO_COMMIT;
            }
            provision = null;
            if (a == Availability.SERVING) {
                Process n = child;
                if (n != null && n.isAlive()) {
                    touch();
                    return Availability.SERVING;
                }
                // 完了済み future は consume まで残る — install 後に child が
                // invalidateWorker/killChild で畳まれたり自然死した場合、
                // SERVING を再生してはいけない(死んだ worker の port/-1 へ
                // request を向かわせる)。killChild 済み(child==null)は
                // provision 失敗ではないので cooldown 無しで再 provision へ。
                if (n != null) {
                    child = null;
                    port = -1;
                    apiKey = null;
                    a = Availability.CRASHED; // install 直後の自然死
                    lastFailure = a;
                    nextProvisionAt =
                            System.nanoTime() + provisionCooldown.toNanos();
                }
            } else if (a == Availability.TAMPERED) {
                tampered = true;
                return Availability.TAMPERED;
            } else {
                lastFailure = a;
                nextProvisionAt =
                        System.nanoTime() + provisionCooldown.toNanos();
            }
        }
        if (System.nanoTime() < nextProvisionAt) return lastFailure;
        provision = CompletableFuture.supplyAsync(this::provisionOnce, provisionExec);
        return Availability.NO_COMMIT; // この request は CPU で応答される
    }

    @Override
    public synchronized int port() {
        return port;
    }

    /** channel が今の worker の api key を読むための supplier 先。 */
    synchronized String apiKey() {
        return apiKey;
    }

    /** channel の request 活動 — idle unload を延期する。 */
    synchronized void touch() {
        if (closed || child == null) return;
        if (idleTask != null) idleTask.cancel(false);
        idleTask = idleTimer.schedule(this::idleUnload,
                idleTimeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void invalidateWorker() {
        killChild(); // 次の ensureServing が respawn を kick する
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (idleTask != null) idleTask.cancel(false);
        killChild();
        CompletableFuture<Availability> p = provision;
        if (p != null) p.cancel(true);
        provisionExec.shutdownNow();
        idleTimer.shutdownNow();
    }

    // ---- provision(provisionExec 上で実行) ----

    private synchronized void idleUnload() {
        if (closed || child == null) return;
        killChild();
    }

    private synchronized void killChild() {
        Process c = child;
        child = null;
        port = -1;
        apiKey = null;
        if (c == null) return;
        c.destroy();
        try {
            if (!c.waitFor(STOP_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
                c.destroyForcibly();
                c.waitFor(STOP_GRACE.toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            c.destroyForcibly();
        }
    }

    /**
     * stage → inspect → unpack → spawn → health を1回試す。呼出は1本に
     * 直列化(provisionExec)されている。child/port/apiKey の install は
     * synchronized セクションで行い ensureServing 側と一貫させる。
     */
    private Availability provisionOnce() {
        try {
            ManifestVerifier.Result v = SomPackage.verifyEmbeddedManifest();
            if (!v.passed()) return Availability.TAMPERED;

            Stager.Result staged =
                    SomPackage.stage(gameDirectory, platformKey, fetcher, embedded);
            if (!staged.committed()) {
                return isTrustFailure(staged.failures())
                        ? Availability.TAMPERED : Availability.NO_COMMIT;
            }

            Stager.Inspection ins =
                    SomPackage.inspect(gameDirectory, platformKey);
            switch (ins.status()) {
                case TAMPERED -> { return Availability.TAMPERED; }
                case COMMITTED -> {}
                default -> { return Availability.NO_COMMIT; } // ABSENT/STALE
            }

            Path root = SomPackage.root(gameDirectory).toRealPath();
            synchronized (this) { stagedRoot = root; }

            VerifiedManifest view = v.manifest().platformView(platformKey);
            List<ManifestVerifier.Artifact> runtimes = view.artifacts().stream()
                    .filter(a -> "runtime".equals(a.role())).toList();
            ManifestVerifier.Artifact weights = view.artifacts().stream()
                    .filter(a -> "weights".equals(a.role()))
                    .findFirst().orElse(null);
            if (runtimes.isEmpty() || weights == null) {
                return Availability.NO_RUNTIME_ARTIFACT;
            }
            Path gguf = verifiedFile(root, ins, weights);
            if (gguf == null) return Availability.TAMPERED;

            for (ManifestVerifier.Artifact rt : runtimes) {
                Availability a = tryRuntime(root, ins, rt, gguf);
                if (a == Availability.SERVING) return Availability.SERVING;
                if (a == Availability.TAMPERED) return Availability.TAMPERED;
                lastFailure = a; // 次の候補へ — manifest 順が優先度
            }
            return lastFailure;
        } catch (Throwable t) {
            if (t instanceof InterruptedException) Thread.currentThread().interrupt();
            return Availability.LAUNCH_FAILED;
        }
    }

    private static boolean isTrustFailure(List<String> failures) {
        for (String f : failures) {
            String l = f.toLowerCase(Locale.ROOT);
            if (l.contains("embedded license") || l.contains("escapes containment")
                    || l.contains("unsafe") || l.contains("marker")
                    || l.contains("swapped") || l.contains("manifest")) {
                return true;
            }
        }
        return false;
    }

    /**
     * staged file の spawn 前再照合(S8b 契約): name を real path へ resolve
     * して staging root 配下を確認し、bytes を manifest sha と再照合する。
     * どちらかが崩れれば null(fail-closed)。
     */
    private Path verifiedFile(Path root, Stager.Inspection ins,
                              ManifestVerifier.Artifact art) throws IOException {
        Path f = ins.files().get(art.path());
        if (f == null) return null;
        Path real = f.toRealPath();
        if (!real.startsWith(root) || !Files.isRegularFile(real)) return null;
        if (Files.size(real) != art.size()) return null;
        return art.sha256().equalsIgnoreCase(ManifestVerifier.sha256Hex(real))
                ? real : null;
    }

    /**
     * 1 runtime artifact を展開・検証・spawn・health まで試す。成功時は
     * child/port/apiKey を install して SERVING を返す。
     */
    private Availability tryRuntime(Path root, Stager.Inspection ins,
                                    ManifestVerifier.Artifact rt, Path gguf) {
        try {
            Path archive = verifiedFile(root, ins, rt);
            if (archive == null) return Availability.TAMPERED;
            Path runtimeDir = root.resolve("runtime");
            Files.createDirectories(runtimeDir);
            // 展開先の鎖に managed dir 配下の link が居るなら摘む
            if (RelPaths.firstDivergingComponent(root, runtimeDir) != null) {
                return Availability.TAMPERED;
            }
            Path dest = runtimeDir.resolve("rt-" + rt.sha256().substring(0, 16));
            if (Files.exists(dest, LinkOption.NOFOLLOW_LINKS)
                    && (!Files.isDirectory(dest, LinkOption.NOFOLLOW_LINKS)
                        || !dest.toRealPath().startsWith(root))) {
                return Availability.TAMPERED; // 展開先自体が link 越し/不正
            }
            Unpacker.unpack(archive, dest, rt);
            Unpacker.verifyExtracted(dest, rt);

            Path entry = dest.resolve(rt.entry());
            Path realEntry = entry.toRealPath();
            if (!realEntry.startsWith(dest.toRealPath())
                    || !Files.isRegularFile(realEntry)) {
                return Availability.TAMPERED;
            }
            String expected = rt.members().get(rt.entry());
            if (expected == null
                    || !expected.equalsIgnoreCase(ManifestVerifier.sha256Hex(realEntry))) {
                return Availability.TAMPERED;
            }
            if (!Files.isExecutable(realEntry)) {
                realEntry.toFile().setExecutable(true);
            }

            int p = pickPort();
            if (p < 0) return Availability.LAUNCH_FAILED;
            String key = UUID.randomUUID().toString().replace("-", "");
            Process proc = launcher.start(argv(realEntry, gguf, p, key), dest, dest);
            Availability ready = awaitHealth(proc, p, key);
            if (ready != Availability.SERVING) {
                destroy(proc);
                return ready;
            }
            synchronized (this) {
                if (closed) { destroy(proc); return Availability.NO_COMMIT; }
                child = proc;
                port = p;
                apiKey = key;
            }
            return Availability.SERVING;
        } catch (Throwable t) {
            if (t instanceof InterruptedException) Thread.currentThread().interrupt();
            return Availability.LAUNCH_FAILED;
        }
    }

    private static List<String> argv(Path entry, Path gguf, int port, String key) {
        List<String> a = new ArrayList<>();
        a.add(entry.toAbsolutePath().toString());
        a.add("-m"); a.add(gguf.toAbsolutePath().toString());
        a.add("-c"); a.add(String.valueOf(CONTEXT_SIZE));
        a.add("-ngl"); a.add("99");              // GPU あれば offload、無ければ CPU
        a.add("--host"); a.add("127.0.0.1");    // loopback 限定 — CLI 引数で固定
        a.add("--port"); a.add(String.valueOf(port));
        a.add("--api-key"); a.add(key);          // 同機別プロセスからの叩き込み抑止
        a.add("--no-webui");
        return a;
    }

    /** 空いている loopback ephemeral port を拾う(bind 競合は spawn 失敗で拾う)。 */
    private static int pickPort() {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return s.getLocalPort();
        } catch (IOException e) {
            return -1;
        }
    }

    private Availability awaitHealth(Process proc, int p, String key) {
        LlamaClient probe = new LlamaClient(p, () -> key,
                Duration.ofSeconds(5), Duration.ofMillis(500));
        long deadline = System.nanoTime() + startupTimeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (!proc.isAlive()) return Availability.CRASHED;
            if (probe.healthy()) return Availability.SERVING;
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Availability.LAUNCH_FAILED;
            }
        }
        return Availability.STARTUP_TIMEOUT;
    }

    private static void destroy(Process p) {
        p.destroy();
        try {
            if (!p.waitFor(STOP_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
        }
    }

    // ---- production launcher ----

    /** 試験が本物の spawn を使うため package-private に見せる。 */
    static final class ProcessBuilderLauncher implements ProcessLauncher {
        static final ProcessBuilderLauncher INSTANCE = new ProcessBuilderLauncher();

        @Override
        public Process start(List<String> argv, Path cwd, Path logSink) throws IOException {
            ProcessBuilder pb = new ProcessBuilder(argv);
            pb.directory(cwd.toFile());
            // 環境経由の差し込みを絞る: dynamic loader / llama.cpp の env
            // 上書き変数だけを落とし、他の env は継承する
            pb.environment().keySet().removeIf(k ->
                    k.startsWith("DYLD_") || k.startsWith("LD_")
                            || k.startsWith("LLAMA_ARG_"));
            pb.redirectErrorStream(true);
            Process p = pb.start();
            // stdout/stderr を排水 — 未消費だと pipe buffer で wedged になる。
            // 末尾 20 行だけ起動失敗の診断用に残す。
            Thread drain = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(
                        p.getInputStream(), StandardCharsets.UTF_8))) {
                    for (String line; (line = r.readLine()) != null;) {
                        synchronized (LlamaGate.class) {
                            TAIL.addLast(line);
                            while (TAIL.size() > 20) TAIL.removeFirst();
                        }
                    }
                } catch (IOException closed) {
                    // process 終了で stream が切れる通常経路
                }
            }, "localinferenceapi-som-llama-log");
            drain.setDaemon(true);
            drain.start();
            return p;
        }
    }

    private static final Deque<String> TAIL = new ArrayDeque<>();

    /** 診断用 — 直近の child 出力(行)。 */
    static synchronized List<String> recentChildOutput() {
        return List.copyOf(TAIL);
    }
}
