package com.kuronami.localinferenceapi.internal.som;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Stager を loopback HTTP server + 合成 bytes で検査する。小さな fixture のみ。
 * manifest は常に verifyManifest 経由で入る — Stager.stage は
 * VerifiedManifest token しか受け付けないので、これらの試験は実際の trust
 * 経路を通る。network 依存は loopback に閉じ、外部 CDN へは出ない。
 */
class StagerTest {

    private static final byte[] NOTICE = "notice".getBytes(StandardCharsets.UTF_8);
    private static final String NOTICE_PATH = "licenses/NOTICE.txt";

    private HttpServer server;
    private final Map<String, byte[]> files = new ConcurrentHashMap<>();
    private final Map<String, String> redirects = new ConcurrentHashMap<>();
    private final AtomicInteger truncateAt = new AtomicInteger(-1);
    private final AtomicInteger truncateEveryTime = new AtomicInteger(-1);
    private final AtomicInteger rangeRequests = new AtomicInteger();

    private void start(boolean honorRange) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/files/", ex -> {
            String name = ex.getRequestURI().getPath().substring("/files/".length());
            byte[] body = files.get(name);
            String range = ex.getRequestHeaders().getFirst("Range");
            try {
                if (body == null) {
                    ex.sendResponseHeaders(404, -1);
                } else if (honorRange && range != null && range.startsWith("bytes=")) {
                    rangeRequests.incrementAndGet();
                    long from = Long.parseLong(range.substring(6).split("-")[0]);
                    if (from >= body.length) {
                        ex.sendResponseHeaders(416, -1);
                    } else {
                        byte[] slice = java.util.Arrays.copyOfRange(
                                body, (int) from, body.length);
                        ex.getResponseHeaders().set("Content-Range",
                                "bytes " + from + "-" + (body.length - 1) + "/" + body.length);
                        ex.sendResponseHeaders(206, slice.length);
                        ex.getResponseBody().write(slice);
                    }
                } else {
                    int n = truncateAt.getAndSet(-1); // 一回だけの断続 truncation
                    int every = truncateEveryTime.get();
                    if (every >= 0) n = every;     // 永続 truncation
                    if (n >= 0 && n < body.length) {
                        ex.sendResponseHeaders(200, body.length);
                        ex.getResponseBody().write(body, 0, n);
                    } else {
                        ex.sendResponseHeaders(200, body.length);
                        ex.getResponseBody().write(body);
                    }
                }
            } finally {
                ex.getResponseBody().close();
                ex.close();
            }
        });
        server.createContext("/redir", ex -> {
            String target = redirects.get(ex.getRequestURI().getQuery());
            if (target == null) { ex.sendResponseHeaders(404, -1); }
            else {
                ex.getResponseHeaders().set("Location", target);
                ex.sendResponseHeaders(302, -1);
            }
            ex.close();
        });
        server.start();
    }

    private String fileUrl(String name) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/files/" + name;
    }

    private Fetcher testFetcher() {
        return HttpFetcher.insecureForTests(Set.of("127.0.0.1", "localhost"));
    }

    /** path → bytes の embedded source(製品の classpath の代役)。 */
    private EmbeddedSource embedded(Map<String, byte[]> byPath) {
        return path -> {
            byte[] b = byPath.get(path);
            return b == null ? null : new ByteArrayInputStream(b);
        };
    }

    /** NOTICE.txt だけを供給する embedded source。 */
    private EmbeddedSource notices() {
        return embedded(Map.of(NOTICE_PATH, NOTICE));
    }

    private static final EmbeddedSource NO_EMBEDDED = path -> null;

    /**
     * path→upstream URL・path→payload・path→role から manifest JSON を組む。
     * runtime/weights artifact は license_files に NOTICE.txt を必須とする
     * 契約のため、license artifact が未宣言なら NOTICE.txt を自動で宣言する
     * (payload は pathToPayload または既定 NOTICE)。
     */
    private byte[] manifestBytes(Map<String, String> pathToUrl,
                                 Map<String, byte[]> pathToPayload,
                                 Map<String, String> pathToRole) {
        Map<String, String> urls = new LinkedHashMap<>(pathToUrl);
        Map<String, String> roles = new LinkedHashMap<>(pathToRole);
        boolean needsNotice = urls.keySet().stream()
                .anyMatch(p -> !"license".equals(roles.getOrDefault(p, "runtime")));
        if (needsNotice && !urls.containsKey(NOTICE_PATH)) {
            urls.put(NOTICE_PATH, null);
            roles.put(NOTICE_PATH, "license");
        }
        JsonObject m = new JsonObject();
        m.addProperty("manifest_version", 1);
        JsonArray arts = new JsonArray();
        for (var e : urls.entrySet()) {
            String path = e.getKey();
            String role = roles.getOrDefault(path, "runtime");
            byte[] payload = pathToPayload.get(path);
            if ("license".equals(role) && payload == null) payload = NOTICE;
            JsonObject a = new JsonObject();
            a.addProperty("path", path);
            a.addProperty("role", role);
            a.addProperty("sha256", payload != null
                    ? ManifestVerifier.sha256Hex(payload) : "0".repeat(64));
            a.addProperty("size", payload != null ? payload.length : 1);
            if (e.getValue() != null) a.addProperty("upstream_url", e.getValue());
            JsonArray lfs = new JsonArray();
            if (!"license".equals(role)) lfs.add(NOTICE_PATH);
            a.add("license_files", lfs);
            arts.add(a);
        }
        m.add("artifacts", arts);
        return m.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** license artifact 1 件だけの単純な manifest。 */
    private byte[] licenseManifest(String licPath, byte[] notice) {
        return manifestBytes(linkedMapOf(licPath, null),
                Map.of(licPath, notice), Map.of(licPath, "license"));
    }

    private static <K, V> Map<K, V> linkedMapOf(K k, V v) {
        Map<K, V> m = new LinkedHashMap<>();
        m.put(k, v);
        return m;
    }

    private TrustedPin testPin(byte[] manifestBytes) {
        return TrustedPin.insecureForTests(
                ManifestVerifier.sha256Hex(manifestBytes),
                Set.of("127.0.0.1", "localhost"));
    }

    private VerifiedManifest verify(byte[] manifestBytes) {
        var r = ManifestVerifier.verifyManifest(manifestBytes, testPin(manifestBytes));
        assertTrue(r.passed(), () -> String.join("\n", r.failures()));
        return r.manifest();
    }

    @AfterEach void stop() { if (server != null) server.stop(0); }

    /** unix:nlink を読める FS か — hardlink 検査経路の試験ゲート。 */
    private static boolean nlinkReadable(Path dir) {
        try {
            return Files.getAttribute(dir, "unix:nlink",
                    LinkOption.NOFOLLOW_LINKS) instanceof Number;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static long nlink(Path p) throws IOException {
        return ((Number) Files.getAttribute(p, "unix:nlink",
                LinkOption.NOFOLLOW_LINKS)).longValue();
    }

    // ---- accepted bundle ----

    @Test
    void downloadsAndCommitsWithEmbeddedLicense(@TempDir Path tmp) throws IOException {
        files.put("a.bin", "hello".getBytes(StandardCharsets.UTF_8));
        files.put("b.dat", "world-data".getBytes(StandardCharsets.UTF_8));
        start(true);
        // phase 1 は disk 上に何も無くても成功する — file 存在は phase 2 の仕事
        byte[] m = manifestBytes(
                Map.of("bin/a.bin", fileUrl("a.bin"), "bin/b.dat", fileUrl("b.dat")),
                Map.of("bin/a.bin", files.get("a.bin"),
                        "bin/b.dat", files.get("b.dat")),
                Map.of());
        VerifiedManifest vm = verify(m);
        var r = Stager.stage(vm, tmp, testFetcher(), notices());
        assertTrue(r.committed(), () -> String.join("\n", r.failures()));
        for (var e : Map.of("bin/a.bin", files.get("a.bin"),
                "bin/b.dat", files.get("b.dat"),
                NOTICE_PATH, NOTICE).entrySet()) {
            assertArrayEquals(e.getValue(), Files.readAllBytes(tmp.resolve(
                    "artifacts/" + ManifestVerifier.sha256Hex(e.getValue()))), e.getKey());
        }
        JsonObject marker = JsonParser.parseString(
                Files.readString(tmp.resolve("current.json"))).getAsJsonObject();
        assertEquals(vm.manifestSha256(), marker.get("manifest_sha256").getAsString());
        assertEquals(3, marker.getAsJsonArray("artifacts").size());
        // inspect は commit 直後に COMMITTED を返さなければならない
        var ins = Stager.inspect(tmp, vm);
        assertTrue(ins.usable(), () -> String.join("\n", ins.failures()));
        assertEquals(3, ins.files().size());
    }

    @Test
    void forgedManifestRejectedBeforeNetwork(@TempDir Path tmp) throws IOException {
        files.put("a.bin", "x".getBytes(StandardCharsets.UTF_8));
        start(true);
        AtomicInteger calls = new AtomicInteger();
        Fetcher counting = (uri, off) -> {
            calls.incrementAndGet();
            return testFetcher().open(uri, off);
        };
        byte[] forged = manifestBytes(
                Map.of("bin/evil.bin", fileUrl("a.bin")),
                Map.of("bin/evil.bin", files.get("a.bin")), Map.of());
        var r = ManifestVerifier.verifyManifest(forged,
                TrustedPin.insecureForTests("0".repeat(64), Set.of("127.0.0.1")));
        assertFalse(r.passed());
        assertNull(r.manifest(), "tampered manifest は VerifiedManifest を生まない");
        assertEquals(0, calls.get(), "trust anchor 通過前に network へ出ない");
    }

    @Test
    void stagerRejectsRawManifestBySignature() {
        // public API は未検証 metadata を stage する経路を持たない:
        // stage() は VerifiedManifest しか取らず、それには public ctor が無い
        for (var ctor : VerifiedManifest.class.getDeclaredConstructors()) {
            assertFalse(java.lang.reflect.Modifier.isPublic(ctor.getModifiers()),
                    "VerifiedManifest ctor must not be public");
        }
        boolean tokenOnly = java.util.Arrays.stream(Stager.class.getDeclaredMethods())
                .filter(mm -> mm.getName().equals("stage")
                        && java.lang.reflect.Modifier.isPublic(mm.getModifiers()))
                .allMatch(mm -> java.util.List.of(mm.getParameterTypes())
                        .contains(VerifiedManifest.class));
        assertTrue(tokenOnly);
    }

    // ---- altered bytes/size ----

    @Test
    void hashMismatchNeverPublishes(@TempDir Path tmp) throws IOException {
        files.put("a.bin", "tampered".getBytes(StandardCharsets.UTF_8));
        start(true);
        byte[] m = manifestBytes(Map.of("bin/a.bin", fileUrl("a.bin")),
                Map.of("bin/a.bin", "declared".getBytes(StandardCharsets.UTF_8)),
                Map.of());
        // served bytes と同じ size にして hash 不一致だけを分離する
        JsonObject bad = JsonParser.parseString(new String(m)).getAsJsonObject();
        for (var el : bad.getAsJsonArray("artifacts")) {
            JsonObject a = el.getAsJsonObject();
            if (a.get("path").getAsString().equals("bin/a.bin")) {
                a.addProperty("size", files.get("a.bin").length);
            }
        }
        m = bad.toString().getBytes(StandardCharsets.UTF_8);
        var r = Stager.stage(verify(m), tmp, testFetcher(), notices());
        assertFalse(r.committed());
        assertFalse(Files.exists(tmp.resolve("current.json")));
        assertFalse(Files.exists(tmp.resolve("artifacts/"
                + ManifestVerifier.sha256Hex(files.get("a.bin")))),
                "不一致 bytes は公開されない");
    }

    @Test
    void oversizeBodyStopsAtDeclaredSize(@TempDir Path tmp) throws IOException {
        // server が宣言より多く送る — stager は宣言 size で書込みを止める
        files.put("a.bin", "0123456789EXTRA".getBytes(StandardCharsets.UTF_8));
        start(true);
        byte[] declared = "AAAAAAAAAA".getBytes(StandardCharsets.UTF_8); // 10B 別内容
        byte[] m = manifestBytes(Map.of("bin/a.bin", fileUrl("a.bin")),
                Map.of("bin/a.bin", declared), Map.of());
        VerifiedManifest vm = verify(m);
        var r = Stager.stage(vm, tmp, testFetcher(), notices());
        assertFalse(r.committed(), "prefix が一致しない bytes は公開されない");
        assertFalse(Files.exists(tmp.resolve("artifacts/"
                + ManifestVerifier.sha256Hex(declared))));
        Path part = tmp.resolve("staging/"
                + ManifestVerifier.sha256Hex(declared) + ".part");
        if (Files.exists(part)) {
            assertEquals(declared.length, Files.size(part),
                    "宣言 size を1 byte も超えて書かない");
        }
    }

    // ---- interrupted download / atomicity ----

    @Test
    void truncatedThenRangeResumes(@TempDir Path tmp) throws IOException {
        byte[] payload = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        files.put("a.bin", payload);
        truncateAt.set(6);
        start(true);
        byte[] m = manifestBytes(Map.of("bin/a.bin", fileUrl("a.bin")),
                Map.of("bin/a.bin", payload), Map.of());
        var r = Stager.stage(verify(m), tmp, testFetcher(), notices());
        assertTrue(r.committed(), () -> String.join("\n", r.failures()));
        assertTrue(rangeRequests.get() >= 1, "Range resume が走ること");
    }

    @Test
    void truncatedWithRangeIgnoredRestarts(@TempDir Path tmp) throws IOException {
        byte[] payload = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        files.put("a.bin", payload);
        truncateAt.set(6);
        start(false);
        byte[] m = manifestBytes(Map.of("bin/a.bin", fileUrl("a.bin")),
                Map.of("bin/a.bin", payload), Map.of());
        var r = Stager.stage(verify(m), tmp, testFetcher(), notices());
        assertTrue(r.committed(), () -> String.join("\n", r.failures()));
        assertEquals(0, rangeRequests.get());
    }

    @Test
    void persistentTruncationFailsWithoutCommit(@TempDir Path tmp) throws IOException {
        byte[] payload = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        files.put("a.bin", payload);
        truncateEveryTime.set(6);
        start(false); // Range 非対応 — 毎回 200 で 6B に打ち切るので resume 不可
        byte[] m = manifestBytes(Map.of("bin/a.bin", fileUrl("a.bin")),
                Map.of("bin/a.bin", payload), Map.of());
        var r = Stager.stage(verify(m), tmp, testFetcher(), notices());
        assertFalse(r.committed());
        assertFalse(Files.exists(tmp.resolve("current.json")),
                "中断した download は commit しない");
        assertFalse(Files.exists(tmp.resolve("artifacts/"
                + ManifestVerifier.sha256Hex(payload))),
                "未検証 bytes は artifacts/ へ出さない");
        assertTrue(Files.exists(tmp.resolve("staging")),
                ".part は resume 用に残してよい");
    }

    @Test
    void incompleteSetNoMarkerButPublishedKept(@TempDir Path tmp) throws IOException {
        start(true);
        files.put("a.bin", "present".getBytes(StandardCharsets.UTF_8));
        byte[] m = manifestBytes(
                Map.of("bin/a.bin", fileUrl("a.bin"), "bin/b.bin", fileUrl("b.bin")),
                Map.of("bin/a.bin", files.get("a.bin"),
                        "bin/b.bin", "absent".getBytes(StandardCharsets.UTF_8)),
                Map.of());
        var r = Stager.stage(verify(m), tmp, testFetcher(), notices());
        assertFalse(r.committed());
        assertFalse(Files.exists(tmp.resolve("current.json")), "不完全 set は commit しない");
        assertTrue(Files.exists(tmp.resolve("artifacts/"
                + ManifestVerifier.sha256Hex(files.get("a.bin")))),
                "成功分は content-addressed で残る");
    }

    @Test
    void markerPublishFailurePreservesOldCommit(@TempDir Path tmp) throws IOException {
        files.put("a.bin", "v1".getBytes(StandardCharsets.UTF_8));
        files.put("b.dat", "v2".getBytes(StandardCharsets.UTF_8));
        start(true);
        byte[] m1 = manifestBytes(Map.of("bin/a.bin", fileUrl("a.bin")),
                Map.of("bin/a.bin", files.get("a.bin")), Map.of());
        var r1 = Stager.stage(verify(m1), tmp, testFetcher(), notices());
        assertTrue(r1.committed());
        byte[] oldMarker = Files.readAllBytes(tmp.resolve("current.json"));

        // commit marker にだけ atomic move が失敗する filesystem を再現
        byte[] m2 = manifestBytes(Map.of("bin/b.dat", fileUrl("b.dat")),
                Map.of("bin/b.dat", files.get("b.dat")), Map.of());
        Stager.Mover failOnMarker = (src, dst) -> {
            if (dst.getFileName().toString().equals("current.json")) {
                throw new AtomicMoveNotSupportedException(src.toString(), dst.toString(), "simulated");
            }
            Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        };
        var r2 = Stager.stage(verify(m2), tmp, testFetcher(), notices(), failOnMarker);
        assertFalse(r2.committed());
        assertTrue(r2.failures().stream().anyMatch(f -> f.contains("commit marker")));
        assertArrayEquals(oldMarker, Files.readAllBytes(tmp.resolve("current.json")),
                "失敗した publish は古い commit を残す");
    }

    @Test
    void nonAtomicFilesystemFailsWithoutMarker(@TempDir Path tmp) throws IOException {
        files.put("a.bin", "x".getBytes(StandardCharsets.UTF_8));
        start(true);
        byte[] m = manifestBytes(Map.of("bin/a.bin", fileUrl("a.bin")),
                Map.of("bin/a.bin", files.get("a.bin")), Map.of());
        Stager.Mover noAtomic = (src, dst) -> {
            throw new AtomicMoveNotSupportedException(src.toString(), dst.toString(), "no atomic");
        };
        var r = Stager.stage(verify(m), tmp, testFetcher(), notices(), noAtomic);
        assertFalse(r.committed());
        assertFalse(Files.exists(tmp.resolve("current.json")),
                "atomic move を供給できない filesystem では commit しない");
    }

    @Test
    void restageIsIdempotentAndSkipsVerified(@TempDir Path tmp) throws IOException {
        files.put("a.bin", "same".getBytes(StandardCharsets.UTF_8));
        start(true);
        AtomicInteger calls = new AtomicInteger();
        Fetcher counting = (uri, off) -> {
            calls.incrementAndGet();
            return testFetcher().open(uri, off);
        };
        byte[] m = manifestBytes(Map.of("bin/a.bin", fileUrl("a.bin")),
                Map.of("bin/a.bin", files.get("a.bin")), Map.of());
        VerifiedManifest vm = verify(m);
        assertTrue(Stager.stage(vm, tmp, counting, notices()).committed());
        int firstRunCalls = calls.get();
        assertTrue(firstRunCalls >= 1);
        assertTrue(Stager.stage(vm, tmp, counting, notices()).committed(),
                "同一 manifest の再 stage は冪等");
        assertEquals(firstRunCalls, calls.get(),
                "検証済み artifact を再 fetch しない");
    }

    // ---- redirect / host policy ----

    @Test
    void redirectToAllowedHostFollows(@TempDir Path tmp) throws IOException {
        files.put("a.bin", "ok".getBytes(StandardCharsets.UTF_8));
        start(true);
        redirects.put("to-a", "/files/a.bin");
        byte[] m = manifestBytes(Map.of("bin/a.bin",
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/redir?to-a"),
                Map.of("bin/a.bin", files.get("a.bin")), Map.of());
        var r = Stager.stage(verify(m), tmp, testFetcher(), notices());
        assertTrue(r.committed(), () -> String.join("\n", r.failures()));
    }

    @Test
    void redirectToDisallowedHostRejectedBeforeConnect(@TempDir Path tmp) throws IOException {
        start(true);
        redirects.put("evil", "http://definitely-not-allowed.invalid/x");
        byte[] m = manifestBytes(Map.of("bin/a.bin",
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/redir?evil"),
                Map.of("bin/a.bin", "x".getBytes(StandardCharsets.UTF_8)), Map.of());
        var r = Stager.stage(verify(m), tmp, testFetcher(), notices());
        assertFalse(r.committed());
        assertTrue(r.failures().stream()
                        .anyMatch(f -> f.contains("rejected") || f.contains("allowlist")),
                () -> String.join("\n", r.failures()));
    }

    @Test
    void redirectLoopDepthIsBounded(@TempDir Path tmp) throws IOException {
        start(true);
        // /redir?loop への応答は自分自身へ戻る — 深さ上限で打ち切られること
        server.removeContext("/redir");
        server.createContext("/redir", ex -> {
            ex.getResponseHeaders().set("Location", "/redir?loop");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        byte[] m = manifestBytes(Map.of("bin/a.bin",
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/redir?loop"),
                Map.of("bin/a.bin", "x".getBytes(StandardCharsets.UTF_8)), Map.of());
        var r = Stager.stage(verify(m), tmp, testFetcher(), notices());
        assertFalse(r.committed());
        assertTrue(r.failures().stream().anyMatch(f -> f.contains("redirect")),
                () -> String.join("\n", r.failures()));
    }

    @Test
    void disallowedHostRejectedAtPhase1() {
        byte[] m = manifestBytes(Map.of("bin/a.bin", "https://evil.example/x"),
                Map.of("bin/a.bin", "x".getBytes(StandardCharsets.UTF_8)), Map.of());
        var r = ManifestVerifier.verifyManifest(m,
                TrustedPin.production(ManifestVerifier.sha256Hex(m),
                        Set.of("github.com"), Long.MAX_VALUE));
        assertFalse(r.passed());
        assertTrue(r.failures().stream().anyMatch(f -> f.contains("allowlist")),
                () -> String.join("\n", r.failures()));
    }

    @Test
    void pathEscapeRefusedBeforeNetwork(@TempDir Path tmp) {
        // '../' は phase 1 が拒否し fetcher へ届かない
        String m = """
            {"manifest_version":1,"artifacts":[{"path":"../evil","role":"runtime",
             "sha256":"%s","size":1,"upstream_url":"http://127.0.0.1:1/x",
             "license_files":["licenses/N"]}]}""".formatted("0".repeat(64));
        var r = ManifestVerifier.verifyManifest(m.getBytes(StandardCharsets.UTF_8),
                TrustedPin.insecureForTests(
                        ManifestVerifier.sha256Hex(m.getBytes(StandardCharsets.UTF_8)),
                        Set.of("127.0.0.1")));
        assertFalse(r.passed());
        assertTrue(r.failures().stream().anyMatch(f -> f.contains("UNSAFE")));
    }

    // ---- embedded license staging ----

    @Test
    void licenseStagesFromEmbeddedBytesNotNetwork(@TempDir Path tmp) throws IOException {
        byte[] notice = "packaged notice".getBytes(StandardCharsets.UTF_8);
        byte[] m = licenseManifest(NOTICE_PATH, notice);
        AtomicInteger calls = new AtomicInteger();
        Fetcher counting = (uri, off) -> {
            calls.incrementAndGet();
            throw new AssertionError("license は fetch しない");
        };
        var r = Stager.stage(verify(m), tmp, counting,
                embedded(Map.of(NOTICE_PATH, notice)));
        assertTrue(r.committed(), () -> String.join("\n", r.failures()));
        assertEquals(0, calls.get());
        assertArrayEquals(notice, Files.readAllBytes(tmp.resolve(
                "artifacts/" + ManifestVerifier.sha256Hex(notice))));
    }

    @Test
    void missingEmbeddedNoticeFailsWithoutCommit(@TempDir Path tmp) throws IOException {
        byte[] m = licenseManifest(NOTICE_PATH, NOTICE);
        var r = Stager.stage(verify(m), tmp, testFetcher(), NO_EMBEDDED);
        assertFalse(r.committed());
        assertTrue(r.failures().stream().anyMatch(f -> f.contains("embedded")),
                () -> String.join("\n", r.failures()));
        assertFalse(Files.exists(tmp.resolve("current.json")));
    }

    @Test
    void wrongEmbeddedBytesRejected(@TempDir Path tmp) throws IOException {
        byte[] m = licenseManifest(NOTICE_PATH, NOTICE);
        var r = Stager.stage(verify(m), tmp, testFetcher(),
                embedded(Map.of(NOTICE_PATH,
                        "forged".getBytes(StandardCharsets.UTF_8))));
        assertFalse(r.committed());
        assertTrue(r.failures().stream().anyMatch(f -> f.contains("do not match")),
                () -> String.join("\n", r.failures()));
    }

    // ---- stale marker / symlinks in managed area ----

    @Test
    void staleMarkerIsDetectedAndReplacedOnCommit(@TempDir Path tmp) throws IOException {
        files.put("a.bin", "v1".getBytes(StandardCharsets.UTF_8));
        files.put("b.dat", "v2".getBytes(StandardCharsets.UTF_8));
        start(true);
        byte[] m1 = manifestBytes(Map.of("bin/a.bin", fileUrl("a.bin")),
                Map.of("bin/a.bin", files.get("a.bin")), Map.of());
        var r1 = Stager.stage(verify(m1), tmp, testFetcher(), notices());
        assertTrue(r1.committed());
        String oldDigest = JsonParser.parseString(
                Files.readString(tmp.resolve("current.json")))
                .getAsJsonObject().get("manifest_sha256").getAsString();

        byte[] m2 = manifestBytes(Map.of("bin/b.dat", fileUrl("b.dat")),
                Map.of("bin/b.dat", files.get("b.dat")), Map.of());
        VerifiedManifest vm2 = verify(m2);
        // stage 開始前の inspect は STALE を返す
        assertEquals(Stager.Inspection.Status.STALE, Stager.inspect(tmp, vm2).status());
        var r2 = Stager.stage(vm2, tmp, testFetcher(), notices());
        assertTrue(r2.committed(), () -> String.join("\n", r2.failures()));
        String newDigest = JsonParser.parseString(
                Files.readString(tmp.resolve("current.json")))
                .getAsJsonObject().get("manifest_sha256").getAsString();
        assertNotEquals(oldDigest, newDigest);
        assertEquals(vm2.manifestSha256(), newDigest);
        assertTrue(Stager.inspect(tmp, vm2).usable());
    }

    @Test
    void symlinkedArtifactsDirIsNotFollowed(@TempDir Path tmp) throws IOException {
        files.put("a.bin", "data".getBytes(StandardCharsets.UTF_8));
        start(true);
        byte[] m = manifestBytes(Map.of("bin/a.bin", fileUrl("a.bin")),
                Map.of("bin/a.bin", files.get("a.bin")), Map.of());
        VerifiedManifest vm = verify(m);
        // artifacts/ が外側 dir への symlink として置かれた状態でも、
        // stager は link を外して本物の dir を作り、外側へは書かない
        Path outside = Files.createTempDirectory("som-outside");
        Files.createSymbolicLink(tmp.resolve("artifacts"), outside);
        var r = Stager.stage(vm, tmp, testFetcher(), notices());
        assertTrue(r.committed(), () -> String.join("\n", r.failures()));
        assertTrue(Files.isDirectory(tmp.resolve("artifacts")));
        assertFalse(Files.isSymbolicLink(tmp.resolve("artifacts")));
        assertEquals(0, Files.list(outside).count(), "外側 dir は無傷");
        assertTrue(Stager.inspect(tmp, vm).usable());
    }

    @Test
    void symlinkedLeafIsNotCountedAsStaged(@TempDir Path tmp) throws IOException {
        byte[] payload = "real".getBytes(StandardCharsets.UTF_8);
        files.put("a.bin", payload);
        start(true);
        byte[] m = manifestBytes(Map.of("bin/a.bin", fileUrl("a.bin")),
                Map.of("bin/a.bin", payload), Map.of());
        VerifiedManifest vm = verify(m);
        Files.createDirectories(tmp.resolve("artifacts"));
        // artifacts/<sha> が外部 file への symlink でも「公開済み」と看做さない
        Path outside = Files.createTempDirectory("som-outside");
        Path decoy = outside.resolve("decoy");
        Files.write(decoy, payload); // 内容は一致 — link という形だけが不正
        String sha = ManifestVerifier.sha256Hex(payload);
        Files.createSymbolicLink(tmp.resolve("artifacts").resolve(sha), decoy);
        var r = Stager.stage(vm, tmp, testFetcher(), notices());
        assertTrue(r.committed(), () -> String.join("\n", r.failures()));
        Path published = tmp.resolve("artifacts").resolve(sha);
        assertFalse(Files.isSymbolicLink(published), "link のまま残してはいけない");
        assertArrayEquals(payload, Files.readAllBytes(published));
        assertTrue(Stager.inspect(tmp, vm).usable());
    }

    @Test
    void symlinkedStagingRootIsNotFollowed(@TempDir Path parent) throws IOException {
        byte[] payload = "x".getBytes(StandardCharsets.UTF_8);
        files.put("a.bin", payload);
        start(true);
        byte[] m = manifestBytes(Map.of("bin/a.bin", fileUrl("a.bin")),
                Map.of("bin/a.bin", payload), Map.of());
        VerifiedManifest vm = verify(m);
        // root そのものが link でも link 越しへ書かない
        Path outside = Files.createTempDirectory("som-outside");
        Path root = parent.resolve("somroot");
        Files.createSymbolicLink(root, outside);
        var r = Stager.stage(vm, root, testFetcher(), notices());
        assertTrue(r.committed(), () -> String.join("\n", r.failures()));
        assertFalse(Files.isSymbolicLink(root), "link のまま残してはいけない");
        assertTrue(Files.isDirectory(root));
        assertTrue(Files.exists(root.resolve("current.json")));
        assertTrue(Files.list(outside).findAny().isEmpty(), "外側 dir は無傷");
    }

    @Test
    void symlinkedMarkerIsReplacedNotFollowed(@TempDir Path tmp) throws IOException {
        byte[] payload = "x".getBytes(StandardCharsets.UTF_8);
        files.put("a.bin", payload);
        start(true);
        byte[] m = manifestBytes(Map.of("bin/a.bin", fileUrl("a.bin")),
                Map.of("bin/a.bin", payload), Map.of());
        VerifiedManifest vm = verify(m);
        Path outside = Files.createTempDirectory("som-outside");
        Path target = outside.resolve("injected.json");
        Files.writeString(target, "{\"manifest_sha256\":\"injected\"}");
        Files.createSymbolicLink(tmp.resolve("current.json"), target);
        var r = Stager.stage(vm, tmp, testFetcher(), notices());
        assertTrue(r.committed(), () -> String.join("\n", r.failures()));
        assertFalse(Files.isSymbolicLink(tmp.resolve("current.json")));
        // link 先の file 自体は書き換えない
        assertEquals("{\"manifest_sha256\":\"injected\"}",
                Files.readString(target));
    }

    // ---- hardlink 植え(F1) — unix:nlink を読める FS でのみ実証 ----

    @Test
    void hardlinkedLicensePartIsUnlinkedVictimUntouched(@TempDir Path tmp) throws IOException {
        // staging/<sha>.part が victim file への hardlink として置かれた場合、
        // TRUNCATE/CREATE が victim inode を書き換えてはいけない
        byte[] notice = "pinned notice bytes".getBytes(StandardCharsets.UTF_8);
        VerifiedManifest vm = verify(licenseManifest(NOTICE_PATH, notice));
        Path stagingDir = Files.createDirectories(tmp.resolve("staging"));
        Path victim = Files.createTempDirectory("som-victim").resolve("victim.bin");
        byte[] victimBytes = "VICTIM DATA".getBytes(StandardCharsets.UTF_8);
        Files.write(victim, victimBytes);
        try {
            Files.createLink(stagingDir.resolve(
                    ManifestVerifier.sha256Hex(notice) + ".part"), victim);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "hardlink unsupported on this filesystem");
        }
        var r = Stager.stage(vm, tmp, testFetcher(),
                embedded(Map.of(NOTICE_PATH, notice)));
        assertTrue(r.committed(), () -> String.join("\n", r.failures()));
        assertArrayEquals(victimBytes, Files.readAllBytes(victim),
                "hardlink 植えの .part 経由で victim が書き換わってはいけない");
        assertArrayEquals(notice, Files.readAllBytes(tmp.resolve(
                "artifacts/" + ManifestVerifier.sha256Hex(notice))));
        assertTrue(Stager.inspect(tmp, vm).usable());
    }

    @Test
    void hardlinkedDownloadPartIsUnlinkedVictimUntouched(@TempDir Path tmp) throws IOException {
        // 0 < size < declared の hardlink .part — Range resume の APPEND が
        // victim inode へ及ぶ経路を塞ぐ
        byte[] payload = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        files.put("a.bin", payload);
        start(true); // Range 対応 — have>0 → 206 → APPEND へ進む形を作る
        byte[] m = manifestBytes(Map.of("bin/a.bin", fileUrl("a.bin")),
                Map.of("bin/a.bin", payload), Map.of());
        VerifiedManifest vm = verify(m);
        Path stagingDir = Files.createDirectories(tmp.resolve("staging"));
        Path victim = Files.createTempDirectory("som-victim").resolve("victim.bin");
        byte[] victimBytes = "OWN".getBytes(StandardCharsets.UTF_8); // 3B < 16B
        Files.write(victim, victimBytes);
        try {
            Files.createLink(stagingDir.resolve(
                    ManifestVerifier.sha256Hex(payload) + ".part"), victim);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "hardlink unsupported on this filesystem");
        }
        var r = Stager.stage(vm, tmp, testFetcher(), notices());
        assertTrue(r.committed(), () -> String.join("\n", r.failures()));
        assertArrayEquals(victimBytes, Files.readAllBytes(victim),
                "resume APPEND が victim inode へ及ばない");
        assertArrayEquals(payload, Files.readAllBytes(
                tmp.resolve("artifacts/" + ManifestVerifier.sha256Hex(payload))));
    }

    @Test
    void resumeNameSwapDuringFetchCannotReachVictim(@TempDir Path tmp) throws IOException {
        // F-A 回帰: lone 検査を通った .part の name が fetcher.open の
        // network 待ち中に victim file への hardlink へ差替えられても、
        // 追記は open 済み fd が束ねる検証済み inode へしか行かない。
        // HEAD では APPEND open が差替え後の name を辿り victim inode へ
        // 追記した(実証済み — victim 末尾へ download tail が書き込まれた)。
        // swap は fetcher.open の内側で撃つ — network 待ちと同じ位置で
        // deterministic に窓を再現する。
        byte[] payload = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        byte[] victimBytes = "VICTIM-ORIGINAL".getBytes(StandardCharsets.UTF_8);
        files.put("a.bin", payload);
        start(true); // Range 対応 — have>0 の 206 → 追記経路を通す
        byte[] m = manifestBytes(Map.of("bin/a.bin", fileUrl("a.bin")),
                Map.of("bin/a.bin", payload), Map.of());
        VerifiedManifest vm = verify(m);

        Path stagingDir = Files.createDirectories(tmp.resolve("staging"));
        String sha = ManifestVerifier.sha256Hex(payload);
        Path part = stagingDir.resolve(sha + ".part");
        Files.write(part, java.util.Arrays.copyOf(payload, 7)); // 正当な prefix の partial
        Path victim = Files.createTempDirectory("som-victim").resolve("victim.bin");
        Files.write(victim, victimBytes);
        try {
            Path probe = stagingDir.resolve("probe");
            Files.createLink(probe, part);
            Files.delete(probe);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "hardlink unsupported on this filesystem");
        }

        AtomicBoolean swapped = new AtomicBoolean();
        Fetcher swapping = (uri, off) -> {
            if (off > 0 && swapped.compareAndSet(false, true)) {
                // 旧実装の脆弱な窓と同じ位置: lone 検査を通り network 応答を
                // 待つ間に .part name を victim inode の hardlink へ差替える
                Files.delete(part);
                Files.createLink(part, victim);
            }
            return testFetcher().open(uri, off);
        };
        var r = Stager.stage(vm, tmp, swapping, notices());
        assertTrue(swapped.get(), "swap seam は必ず走る — 撃てなければ試験失敗");
        assertTrue(r.committed(), () -> String.join("\n", r.failures()));
        assertArrayEquals(victimBytes, Files.readAllBytes(victim),
                "resume の追記は pin した inode へ行き victim へ及ばない");
        assertArrayEquals(payload, Files.readAllBytes(
                tmp.resolve("artifacts/" + sha)));
        assertTrue(Stager.inspect(tmp, vm).usable());
    }

    @Test
    void nonRegularPartIsReplacedNotOpened(@TempDir Path tmp) throws IOException {
        // .part が directory 等の非 regular file — 書込みへ進まず取り直す
        byte[] payload = "payload".getBytes(StandardCharsets.UTF_8);
        files.put("a.bin", payload);
        start(true);
        byte[] m = manifestBytes(Map.of("bin/a.bin", fileUrl("a.bin")),
                Map.of("bin/a.bin", payload), Map.of());
        VerifiedManifest vm = verify(m);
        Path stagingDir = Files.createDirectories(tmp.resolve("staging"));
        Files.createDirectory(stagingDir.resolve(
                ManifestVerifier.sha256Hex(payload) + ".part"));
        var r = Stager.stage(vm, tmp, testFetcher(), notices());
        assertTrue(r.committed(), () -> String.join("\n", r.failures()));
        assertArrayEquals(payload, Files.readAllBytes(
                tmp.resolve("artifacts/" + ManifestVerifier.sha256Hex(payload))));
    }

    @Test
    void hardlinkedPublishedArtifactIsReplacedNotShared(@TempDir Path tmp) throws IOException {
        assumeTrue(nlinkReadable(tmp), "unix:nlink unsupported");
        // artifacts/<sha> が同内容の外部 file への hardlink — bytes は正しいが
        // 排他でないため lone でないと看做さず、atomic move で name を差し替える
        byte[] payload = "shareable".getBytes(StandardCharsets.UTF_8);
        files.put("a.bin", payload);
        start(true);
        byte[] m = manifestBytes(Map.of("bin/a.bin", fileUrl("a.bin")),
                Map.of("bin/a.bin", payload), Map.of());
        VerifiedManifest vm = verify(m);
        String sha = ManifestVerifier.sha256Hex(payload);
        Path victim = Files.createTempDirectory("som-victim").resolve("aliased.bin");
        Files.write(victim, payload); // bytes は一致 — link 関係だけが不正
        Files.createDirectories(tmp.resolve("artifacts"));
        Path published = tmp.resolve("artifacts").resolve(sha);
        Files.createLink(published, victim);
        var r = Stager.stage(vm, tmp, testFetcher(), notices());
        assertTrue(r.committed(), () -> String.join("\n", r.failures()));
        assertArrayEquals(payload, Files.readAllBytes(victim),
                "name の差替えは victim inode の内容へ触れない");
        assertEquals(1L, nlink(published),
                "published は lone inode — 別名共有を残さない");
        assertTrue(Stager.inspect(tmp, vm).usable());
    }

    @Test
    void hardlinkedCommittedArtifactIsTamperedAndHealed(@TempDir Path tmp) throws IOException {
        assumeTrue(nlinkReadable(tmp), "unix:nlink unsupported");
        // commit 済み artifact へ別名 hardlink が足されると排他性を破る —
        // inspect は TAMPERED、再 stage で lone inode へ治癒する
        byte[] payload = "committed".getBytes(StandardCharsets.UTF_8);
        files.put("a.bin", payload);
        start(true);
        byte[] m = manifestBytes(Map.of("bin/a.bin", fileUrl("a.bin")),
                Map.of("bin/a.bin", payload), Map.of());
        VerifiedManifest vm = verify(m);
        assertTrue(Stager.stage(vm, tmp, testFetcher(), notices()).committed());
        Path art = tmp.resolve("artifacts")
                .resolve(ManifestVerifier.sha256Hex(payload));
        Files.createLink(Files.createTempDirectory("som-alias").resolve("alias"), art);
        var tampered = Stager.inspect(tmp, vm);
        assertEquals(Stager.Inspection.Status.TAMPERED, tampered.status());
        assertTrue(tampered.failures().stream().anyMatch(f -> f.contains("LINKED")),
                () -> String.join("\n", tampered.failures()));
        assertTrue(Stager.stage(vm, tmp, testFetcher(), notices()).committed(),
                "再 stage で hardlink の name を差し替えて治癒");
        assertTrue(Stager.inspect(tmp, vm).usable());
    }

    @Test
    void symlinkedStagingDirIsUnlinkedNotFollowed(@TempDir Path tmp) throws IOException {
        // staging/ が外側 dir への symlink — 外して本物の dir を作る
        // (junction 検出の divergence 経路は Windows のみ — POSIX では
        //  symlink が同じ修復経路を通ることを確認する)
        byte[] payload = "x".getBytes(StandardCharsets.UTF_8);
        files.put("a.bin", payload);
        start(true);
        byte[] m = manifestBytes(Map.of("bin/a.bin", fileUrl("a.bin")),
                Map.of("bin/a.bin", payload), Map.of());
        VerifiedManifest vm = verify(m);
        Path outside = Files.createTempDirectory("som-outside");
        Files.createSymbolicLink(tmp.resolve("staging"), outside);
        var r = Stager.stage(vm, tmp, testFetcher(), notices());
        assertTrue(r.committed(), () -> String.join("\n", r.failures()));
        assertFalse(Files.isSymbolicLink(tmp.resolve("staging")));
        assertTrue(Files.list(outside).findAny().isEmpty(), "外側 dir は無傷");
        assertTrue(Stager.inspect(tmp, vm).usable());
    }

    // ---- managed tail の post-check replant(F-B) — containmentBase 経路 ----

    @Test
    void replantedIntermediateTailIsContainedAndRepaired(@TempDir Path tmp)
            throws IOException {
        // F-B: caller 側の managed-tail 検査(SomPackage.root 相当)を通った
        // 後、Stager 呼出し時点で中間成分へ植えられた link。containmentBase
        // 付き経路は resolved した外側 dir を containment の基点にせず、
        // link を外して real base 内へ収める。POSIX symlink は junction と
        // 同じ divergence 検出経路(isSymbolicLink が見なくても real≠lexical
        // で必ず現れる)を通る — NTFS junction 自体の挙動は Windows 実機で
        // 未検証。
        byte[] payload = "x".getBytes(StandardCharsets.UTF_8);
        files.put("a.bin", payload);
        start(true);
        byte[] m = manifestBytes(Map.of("bin/a.bin", fileUrl("a.bin")),
                Map.of("bin/a.bin", payload), Map.of());
        VerifiedManifest vm = verify(m);

        Path realTmp = tmp.toRealPath();
        Path tail = realTmp.resolve(".localinferenceapi").resolve("som");
        Path outside = Files.createTempDirectory("som-outside");
        Files.createSymbolicLink(realTmp.resolve(".localinferenceapi"), outside);

        var r = Stager.stage(vm, tail, realTmp, testFetcher(), notices());
        assertTrue(r.committed(), () -> String.join("\n", r.failures()));
        assertFalse(Files.isSymbolicLink(realTmp.resolve(".localinferenceapi")),
                "植えられた link は外される");
        assertTrue(Files.exists(tail.resolve("current.json")),
                "commit marker は managed tail の内側へ降りる");
        assertTrue(Files.list(outside).findAny().isEmpty(), "外側 dir は無傷");
        assertTrue(Stager.inspect(tail, realTmp, vm).usable());
    }

    @Test
    void replantedLeafTailIsContainedAndRepaired(@TempDir Path tmp) throws IOException {
        // leaf(som)だけが link の形 — junction は leaf に置かれても
        // isSymbolicLink に出ないが、lexical≠real の divergence として
        // 同じ経路で拾われる(POSIX symlink で修復経路を実証)
        byte[] payload = "x".getBytes(StandardCharsets.UTF_8);
        files.put("a.bin", payload);
        start(true);
        byte[] m = manifestBytes(Map.of("bin/a.bin", fileUrl("a.bin")),
                Map.of("bin/a.bin", payload), Map.of());
        VerifiedManifest vm = verify(m);

        Path realTmp = tmp.toRealPath();
        Files.createDirectories(realTmp.resolve(".localinferenceapi"));
        Path tail = realTmp.resolve(".localinferenceapi").resolve("som");
        Path outside = Files.createTempDirectory("som-outside");
        Files.createSymbolicLink(tail, outside);

        var r = Stager.stage(vm, tail, realTmp, testFetcher(), notices());
        assertTrue(r.committed(), () -> String.join("\n", r.failures()));
        assertFalse(Files.isSymbolicLink(tail), "leaf の link は外される");
        assertTrue(Files.exists(tail.resolve("current.json")));
        assertTrue(Files.list(outside).findAny().isEmpty(), "外側 dir は無傷");
    }

    @Test
    void inspectSeesReplantedTailAsTamperedNotOutside(@TempDir Path tmp)
            throws IOException {
        // containmentBase 付き inspect は managed tail に植えられた link を
        // 辿らない — resolved 先に見える内容を検査せず TAMPERED に倒し、
        // link は修復せず残す(報告に徹する)
        byte[] payload = "x".getBytes(StandardCharsets.UTF_8);
        files.put("a.bin", payload);
        start(true);
        byte[] m = manifestBytes(Map.of("bin/a.bin", fileUrl("a.bin")),
                Map.of("bin/a.bin", payload), Map.of());
        VerifiedManifest vm = verify(m);

        Path realTmp = tmp.toRealPath();
        Path tail = realTmp.resolve(".localinferenceapi").resolve("som");
        Path outside = Files.createTempDirectory("som-outside");
        Files.createDirectories(outside.resolve("som")); // link 先が存在する形に
        Files.createSymbolicLink(realTmp.resolve(".localinferenceapi"), outside);

        var ins = Stager.inspect(tail, realTmp, vm);
        assertEquals(Stager.Inspection.Status.TAMPERED, ins.status());
        assertTrue(ins.failures().stream().anyMatch(f -> f.contains("containment")),
                () -> String.join("\n", ins.failures()));
        assertTrue(Files.isSymbolicLink(realTmp.resolve(".localinferenceapi")),
                "inspect は修復しない — link は残す");
        assertTrue(Files.list(outside.resolve("som")).findAny().isEmpty(),
                "外側へは書き込みも削除もしない");
    }

    @Test
    void tailReplantAtResolveWindowIsContainedAndRepaired(@TempDir Path tmp)
            throws IOException {
        // N-1 回帰: post-createDirectories の divergence 検査と
        // root.toRealPath() の隙間に植えられた tail link は、resolved root
        // を containment base の外へ逃がす。SettleProbe seam が窓の内側で
        // deterministic に実在 dir → link の差替えを撃つ — resolved が
        // base 外なら staged bytes の基点にせず、link を外してやり直す。
        byte[] payload = "x".getBytes(StandardCharsets.UTF_8);
        files.put("a.bin", payload);
        start(true);
        byte[] m = manifestBytes(Map.of("bin/a.bin", fileUrl("a.bin")),
                Map.of("bin/a.bin", payload), Map.of());
        VerifiedManifest vm = verify(m);

        Path realTmp = tmp.toRealPath();
        Files.createDirectories(realTmp.resolve(".localinferenceapi"));
        Path tail = realTmp.resolve(".localinferenceapi").resolve("som");
        Path outside = Files.createTempDirectory("som-outside");

        AtomicBoolean planted = new AtomicBoolean();
        Stager.SettleProbe replant = () -> {
            if (planted.compareAndSet(false, true)) {
                // 検査→resolve の窓と同じ位置: 実在 leaf dir を link へ差替える
                Files.delete(tail);
                Files.createSymbolicLink(tail, outside);
            }
        };
        var r = Stager.stage(vm, tail, realTmp, testFetcher(), notices(), replant);
        assertTrue(planted.get(), "replant seam は必ず走る — 撃てなければ試験失敗");
        assertTrue(r.committed(), () -> String.join("\n", r.failures()));
        assertFalse(Files.isSymbolicLink(tail), "植えられた link は外される");
        assertTrue(Files.exists(tail.resolve("current.json")),
                "commit marker は managed tail の内側へ降りる");
        assertTrue(Files.list(outside).findAny().isEmpty(),
                "escape した resolved root 配下へは何も書かない");
        assertTrue(Stager.inspect(tail, realTmp, vm).usable());
    }

    @Test
    void persistentTailReplantAtResolveWindowFailsClosed(@TempDir Path tmp)
            throws IOException {
        // 窓の内側で毎回 replant される競合は bounded retry を尽きて
        // fail-closed — 外側 dir には artifact も marker も落ちない
        byte[] payload = "x".getBytes(StandardCharsets.UTF_8);
        files.put("a.bin", payload);
        start(true);
        byte[] m = manifestBytes(Map.of("bin/a.bin", fileUrl("a.bin")),
                Map.of("bin/a.bin", payload), Map.of());
        VerifiedManifest vm = verify(m);

        Path realTmp = tmp.toRealPath();
        Files.createDirectories(realTmp.resolve(".localinferenceapi"));
        Path tail = realTmp.resolve(".localinferenceapi").resolve("som");
        Path outside = Files.createTempDirectory("som-outside");

        AtomicInteger plants = new AtomicInteger();
        Stager.SettleProbe replantEveryTime = () -> {
            plants.incrementAndGet();
            if (Files.exists(tail, LinkOption.NOFOLLOW_LINKS)) Files.delete(tail);
            Files.createSymbolicLink(tail, outside);
        };
        var r = Stager.stage(vm, tail, realTmp, testFetcher(), notices(),
                replantEveryTime);
        assertFalse(r.committed(), "消えない replant は commit しない");
        assertTrue(r.failures().stream().anyMatch(f -> f.contains("replanted")),
                () -> String.join("\n", r.failures()));
        assertEquals(3, plants.get(), "settle loop は 3 回で尽きる");
        assertTrue(Files.list(outside).findAny().isEmpty(),
                "外側 dir は無傷 — 失敗しても外へ書かない");
        assertFalse(Files.isSymbolicLink(tail), "残存 link を残さない");
    }

    @Test
    void inspectTailReplantAtResolveWindowIsTamperedNotCommitted(@TempDir Path tmp)
            throws IOException {
        // R-2 回帰: inspect の divergence 検査と toRealPath の隙間に植えられた
        // tail link は resolved root を containment base の外へ逃がし、外側の
        // attacker-populated tree を COMMITTED + 外側 files() として返した
        // (marker digest と artifact bytes は公開情報で複製可能 — 秘密は不要)。
        // InspectProbe seam が窓の内側で deterministic に実在 tree → link の
        // 差替えを撃つ — 外側を COMMITTED と判定せず TAMPERED + 空 files に
        // 倒す。inspect は修復しないので link は残る(読み取り専用)。
        byte[] notice = "pinned notice".getBytes(StandardCharsets.UTF_8);
        VerifiedManifest vm = verify(licenseManifest(NOTICE_PATH, notice));

        Path realTmp = tmp.toRealPath();
        Path tail = realTmp.resolve(".localinferenceapi").resolve("som");
        assertTrue(Stager.stage(vm, tail, realTmp, testFetcher(),
                        embedded(Map.of(NOTICE_PATH, notice))).committed(),
                "前提: 本物の commit が内側に存在する");

        // 窓で差替える先は「検査を全部通る外側 tree」— marker と artifact を
        // そのまま複製する(digest は公開情報なので複製に秘密は要らない)
        Path outside = Files.createTempDirectory("som-outside");
        Path hold = realTmp.resolve(".localinferenceapi").resolve("som.HOLD");
        Files.createDirectories(outside.resolve("artifacts"));
        Files.copy(tail.resolve("current.json"), outside.resolve("current.json"));
        try (var arts = Files.list(tail.resolve("artifacts"))) {
            for (Path f : arts.toList()) {
                Files.copy(f, outside.resolve("artifacts")
                        .resolve(f.getFileName().toString()));
            }
        }

        AtomicBoolean planted = new AtomicBoolean();
        Stager.InspectProbe replant = () -> {
            if (planted.compareAndSet(false, true)) {
                // 検査→resolve の窓と同じ位置: 本物 tree を横へ置き、
                // name を外側の複製 tree へ向ける
                Files.move(tail, hold);
                Files.createSymbolicLink(tail, outside);
            }
        };
        var ins = Stager.inspect(tail, realTmp, vm, replant);
        assertTrue(planted.get(), "replant seam は必ず走る — 撃てなければ試験失敗");
        assertEquals(Stager.Inspection.Status.TAMPERED, ins.status(),
                "resolve した外側 dir を COMMITTED として返してはいけない");
        assertTrue(ins.failures().stream().anyMatch(f -> f.contains("containment")),
                () -> String.join("\n", ins.failures()));
        assertTrue(ins.files().isEmpty(),
                "files() は空 — 外側 path を COMMITTED 相当として返さない");
        assertTrue(Files.isSymbolicLink(tail),
                "inspect は修復しない — link は残す(読み取り専用)");
        // 本物 tree を戻せば内側の commit は変わらず COMMITTED — 外側複製が
        // COMMITTED を出せたことが「検査を通る本物相当の tree」である証明になる
        Files.delete(tail);
        Files.move(hold, tail);
        assertTrue(Stager.inspect(tail, realTmp, vm).usable());
    }

    @Test
    void postSettleTailReplantIsDetectedNotCommitted(@TempDir Path tmp)
            throws IOException {
        // R-1 narrowing: settled root は resolve 済みの *name* であり、settle
        // 後の tail 再植えは以後の name traversal を外側へ向ける — .part・
        // publish・marker が外側に落ちても commit 成功を報告してはいけない。
        // EmbeddedSource.open は artifact loop 内側の正当な seam — そこで
        // tail を差替えて deterministic に再現する(外側へ落ちた marker は
        // 残る = documented residual litter。検出して止めるのが筋で、
        // 参照先を消さない)。
        byte[] notice = "pinned notice".getBytes(StandardCharsets.UTF_8);
        VerifiedManifest vm = verify(licenseManifest(NOTICE_PATH, notice));

        Path realTmp = tmp.toRealPath();
        Path tail = realTmp.resolve(".localinferenceapi").resolve("som");
        Path outside = Files.createTempDirectory("som-outside");
        Path hold = realTmp.resolve(".localinferenceapi").resolve("som.HOLD");

        AtomicBoolean swapped = new AtomicBoolean();
        EmbeddedSource swapping = path -> {
            if (swapped.compareAndSet(false, true)) {
                // settle 済みの実 tree を横へ置き、name を外側へ向ける —
                // downstream の create/open は link を辿り外側へ着く
                Files.move(tail, hold);
                Files.createDirectories(outside.resolve("staging"));
                Files.createDirectories(outside.resolve("artifacts"));
                Files.createSymbolicLink(tail, outside);
            }
            return NOTICE_PATH.equals(path)
                    ? new ByteArrayInputStream(notice) : null;
        };
        var r = Stager.stage(vm, tail, realTmp, testFetcher(), swapping);
        assertTrue(swapped.get(), "swap seam は必ず走る — 撃てなければ試験失敗");
        assertFalse(r.committed(),
                "外側へ落ちた commit を成功と報告してはいけない");
        assertTrue(r.failures().stream().anyMatch(f -> f.contains("containment")),
                () -> String.join("\n", r.failures()));
        assertTrue(Files.exists(outside.resolve("current.json")),
                "外側へ落ちた marker は残る — 検出はするが外側削除はしない");
        // 内側 tree には commit が降りていない — link を戻しても ABSENT
        Files.delete(tail);
        Files.move(hold, tail);
        assertEquals(Stager.Inspection.Status.ABSENT,
                Stager.inspect(tail, realTmp, vm).status());
    }

    @Test
    void midStageTailReplantAbortsBeforeMarkerWrite(@TempDir Path tmp)
            throws IOException {
        // R-1 narrowing(artifact loop 頭の再照合): 複数 artifact の途中で
        // tail が再植えされて残るなら、次の artifact の書込みに進む前に
        // fail-closed — marker も含めて以後の write は外側へ出ない。
        byte[] nA = "notice A".getBytes(StandardCharsets.UTF_8);
        byte[] nB = "notice B".getBytes(StandardCharsets.UTF_8);
        Map<String, String> urls = new LinkedHashMap<>();
        urls.put("licenses/A.txt", null); // license artifact は network を使わない
        urls.put("licenses/B.txt", null);
        byte[] m = manifestBytes(urls,
                Map.of("licenses/A.txt", nA, "licenses/B.txt", nB),
                Map.of("licenses/A.txt", "license", "licenses/B.txt", "license"));
        VerifiedManifest vm = verify(m);

        Path realTmp = tmp.toRealPath();
        Path tail = realTmp.resolve(".localinferenceapi").resolve("som");
        Path outside = Files.createTempDirectory("som-outside");
        Path hold = realTmp.resolve(".localinferenceapi").resolve("som.HOLD");
        Map<String, byte[]> byPath = Map.of(
                "licenses/A.txt", nA, "licenses/B.txt", nB);

        AtomicBoolean swapped = new AtomicBoolean();
        EmbeddedSource swapping = path -> {
            if ("licenses/A.txt".equals(path) && swapped.compareAndSet(false, true)) {
                Files.move(tail, hold);
                Files.createDirectories(outside.resolve("staging"));
                Files.createDirectories(outside.resolve("artifacts"));
                Files.createSymbolicLink(tail, outside);
            }
            byte[] b = byPath.get(path);
            return b == null ? null : new ByteArrayInputStream(b);
        };
        var r = Stager.stage(vm, tail, realTmp, testFetcher(), swapping);
        assertTrue(swapped.get(), "swap seam は必ず走る — 撃てなければ試験失敗");
        assertFalse(r.committed(), "途中で外側へ逃げた stage は commit しない");
        assertTrue(r.failures().stream().anyMatch(f -> f.contains("containment")),
                () -> String.join("\n", r.failures()));
        assertFalse(Files.exists(outside.resolve("current.json")),
                "次 iteration 頭の再照合で止まる — marker は外側へ降りない");
    }

    @Test
    void blinkedMarkerMoveWithPriorCommitIsNotCommitted(@TempDir Path tmp)
            throws IOException {
        // post-commit 照合の偽成功(実証済み — 独立 closeout の
        // ProbeBlinkMarker variant B): marker move の内側だけ managed tail
        // を外側へ向け、verify の前に戻す blink で今回の marker は外側へ
        // 落ちる。内側に前回 commit の stale marker が残っていると、位置
        // 照合だけではそれを「内側に resolve する marker」として通し
        // committed()==true を返していた。最終照合は内側 marker の
        // manifest_sha256 が今回 stage した manifest と一致することまで
        // 要求し、旧 marker を借りた成功を認めない。
        byte[] n1 = "notice v1".getBytes(StandardCharsets.UTF_8);
        byte[] n2 = "notice v2".getBytes(StandardCharsets.UTF_8);
        VerifiedManifest vm1 = verify(licenseManifest(NOTICE_PATH, n1));
        VerifiedManifest vm2 = verify(licenseManifest(NOTICE_PATH, n2));

        Path realTmp = tmp.toRealPath();
        Path tail = realTmp.resolve(".localinferenceapi").resolve("som");
        Path hold = realTmp.resolve(".localinferenceapi").resolve("som.HOLD");
        Path outside = Files.createTempDirectory("som-outside");

        assertTrue(Stager.stage(vm1, tail, realTmp, testFetcher(),
                embedded(Map.of(NOTICE_PATH, n1))).committed(),
                "前提: 前回 commit の marker が内側に存在する");

        AtomicBoolean blinked = new AtomicBoolean();
        Stager.Mover blink = (src, dst) -> {
            if (!dst.getFileName().toString().equals("current.json")) {
                Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                return;
            }
            blinked.set(true);
            // marker move の窓だけ tail を外側へ向ける — tmp は real tree
            // 側にあるので外側へ移してから move を通し、verify の前に戻す
            Files.move(tail, hold);
            Files.createSymbolicLink(tail, outside);
            Files.move(hold.resolve(src.getFileName().toString()),
                    outside.resolve(src.getFileName().toString()));
            Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            Files.delete(tail);
            Files.move(hold, tail);
        };

        var r = Stager.stage(vm2, tail, realTmp, testFetcher(),
                embedded(Map.of(NOTICE_PATH, n2)), blink);
        assertTrue(blinked.get(), "blink seam は必ず走る — 撃てなければ試験失敗");
        assertFalse(r.committed(),
                "内側の stale marker を借りた commit 成功を報告してはいけない");
        assertTrue(r.failures().stream().anyMatch(f -> f.contains("marker")),
                () -> String.join("\n", r.failures()));
        assertTrue(Files.exists(outside.resolve("current.json")),
                "外側へ落ちた marker は残る — 検出はするが外側削除はしない");
        assertEquals(Stager.Inspection.Status.STALE,
                Stager.inspect(tail, realTmp, vm2).status(),
                "内側は旧 commit のまま — inspect は STALE を報告する");
        assertTrue(Stager.inspect(tail, realTmp, vm1).usable(),
                "前回 commit 側は変わらず COMMITTED");
    }

    // ---- commit marker の bounds(F5) ----

    @Test
    void oversizedMarkerIsTampered(@TempDir Path tmp) throws IOException {
        byte[] payload = "x".getBytes(StandardCharsets.UTF_8);
        files.put("a.bin", payload);
        start(true);
        byte[] m = manifestBytes(Map.of("bin/a.bin", fileUrl("a.bin")),
                Map.of("bin/a.bin", payload), Map.of());
        VerifiedManifest vm = verify(m);
        assertTrue(Stager.stage(vm, tmp, testFetcher(), notices()).committed());
        Files.write(tmp.resolve("current.json"), new byte[128 * 1024]);
        assertEquals(Stager.Inspection.Status.TAMPERED,
                Stager.inspect(tmp, vm).status());
    }

    @Test
    void deeplyNestedMarkerIsTamperedNotCrash(@TempDir Path tmp) throws IOException {
        // size bound 未満でも再帰 parser を落とせる深さ — depth bound が
        // 前置で遮り TAMPERED に収まる(StackOverflowError にしない)
        byte[] payload = "x".getBytes(StandardCharsets.UTF_8);
        files.put("a.bin", payload);
        start(true);
        byte[] m = manifestBytes(Map.of("bin/a.bin", fileUrl("a.bin")),
                Map.of("bin/a.bin", payload), Map.of());
        VerifiedManifest vm = verify(m);
        assertTrue(Stager.stage(vm, tmp, testFetcher(), notices()).committed());
        Files.writeString(tmp.resolve("current.json"), "[".repeat(50_000));
        var ins = Stager.inspect(tmp, vm);
        assertEquals(Stager.Inspection.Status.TAMPERED, ins.status());
        assertTrue(ins.failures().stream().anyMatch(f -> f.contains("nesting")),
                () -> String.join("\n", ins.failures()));
    }

    // ---- malformed redirect Location(F4) ----

    @Test
    void malformedRedirectLocationIsRejectedNotThrown(@TempDir Path tmp) throws IOException {
        // server 制御の Location が URI 構文違反でも、stage() は
        // RuntimeException を漏らさず Result.failures へ畳む
        start(true);
        server.createContext("/badloc", ex -> {
            ex.getResponseHeaders().set("Location", "{malformed uri}");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        byte[] m = manifestBytes(Map.of("bin/a.bin",
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/badloc"),
                Map.of("bin/a.bin", "x".getBytes(StandardCharsets.UTF_8)), Map.of());
        var r = Stager.stage(verify(m), tmp, testFetcher(), notices());
        assertFalse(r.committed());
        assertTrue(r.failures().stream().anyMatch(f -> f.contains("Location")
                        || f.contains("rejected")),
                () -> String.join("\n", r.failures()));
        assertFalse(Files.exists(tmp.resolve("current.json")));
    }
}
