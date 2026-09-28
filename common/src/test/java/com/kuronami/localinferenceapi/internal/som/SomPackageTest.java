package com.kuronami.localinferenceapi.internal.som;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * SomPackage の結合試験 — 製品が使う embedded manifest + SomPin anchor を
 * 通って stage/inspect へ進む経路だけを見る。
 *
 * fullStageThenTamperDetectedWithLocalMapAssets は JevK5 pin の実 artifact
 * (local_map.json が指す host 上の file、計 ~3.1GB)を loopback HTTP で供給し、
 * 同梱 manifest そのままの検証→staging→commit→inspect を通す。assets が
 * 存在しない環境では skip される — 試験の合否は外部 CDN に依存しない。
 */
class SomPackageTest {

    /** 検証済みの artifact 群を指す map(work 外向け)。
     *  machine 固有 path は tracked file に書かない — env
     *  {@code SOM_TEST_LOCAL_MAP} または system property
     *  {@code som.test.localMap} で供給する。未供給・欠落なら該当試験は skip。 */
    private static final Path LOCAL_MAP = Path.of(
            System.getProperty("som.test.localMap",
                    System.getenv().getOrDefault("SOM_TEST_LOCAL_MAP",
                            "som-s7-pin/local_map.json")));

    private HttpServer server;

    @AfterEach void stop() { if (server != null) server.stop(0); }

    private static Map<String, Path> readLocalMap() throws IOException {
        if (!Files.isRegularFile(LOCAL_MAP)) return Map.of();
        JsonObject obj = JsonParser.parseString(
                Files.readString(LOCAL_MAP, StandardCharsets.UTF_8)).getAsJsonObject();
        Map<String, Path> out = new LinkedHashMap<>();
        for (String k : obj.keySet()) {
            Path p = Path.of(obj.get(k).getAsString());
            if (!Files.isRegularFile(p)) return Map.of(); // 一部欠落なら skip
            out.put(k, p);
        }
        return out;
    }

    /** manifest artifact path → local file を /f?i=N で stream する server。 */
    private void startServing(List<Path> blobs) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/f", ex -> {
            String q = ex.getRequestURI().getQuery();
            int idx;
            try { idx = Integer.parseInt(q.substring(2)); }
            catch (RuntimeException bad) { ex.sendResponseHeaders(400, -1); ex.close(); return; }
            Path file = blobs.get(idx);
            try {
                long size = Files.size(file);
                String range = ex.getRequestHeaders().getFirst("Range");
                long from = 0;
                if (range != null && range.startsWith("bytes=")) {
                    from = Long.parseLong(range.substring(6).split("-")[0]);
                }
                try (SeekableByteChannel ch = Files.newByteChannel(file)) {
                    ch.position(from);
                    long remaining = size - from;
                    if (remaining <= 0) {
                        ex.sendResponseHeaders(416, -1);
                    } else {
                        if (from > 0) {
                            ex.getResponseHeaders().set("Content-Range",
                                    "bytes " + from + "-" + (size - 1) + "/" + size);
                            ex.sendResponseHeaders(206, remaining);
                        } else {
                            ex.sendResponseHeaders(200, remaining);
                        }
                        ByteBuffer buf = ByteBuffer.allocate(1 << 20);
                        while (remaining > 0) {
                            buf.clear();
                            buf.limit((int) Math.min(buf.capacity(), remaining));
                            int n = ch.read(buf);
                            if (n < 0) break;
                            buf.flip();
                            ex.getResponseBody().write(buf.array(), 0, n);
                            remaining -= n;
                        }
                    }
                }
            } finally {
                ex.getResponseBody().close();
                ex.close();
            }
        });
        server.start();
    }

    /**
     * pin 済み upstream_url → loopback URL へ差し替える fetcher を組み立てる。
     * 検証する manifest 自体は手付かず(https + github/hf host のまま) —
     * transport の差替えだけが Fetcher seam の中で起きる。
     * blobs は index 順に local file を並べて渡す(server 側の供給表)。
     */
    private Fetcher rewritingFetcher(Map<String, Integer> urlToIndex) {
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/f?i=";
        Fetcher inner = HttpFetcher.insecureForTests(Set.of("127.0.0.1"));
        return (uri, off) -> {
            Integer i = urlToIndex.get(uri.toString());
            if (i == null) throw new Fetcher.FetchRejected("no local-map entry for " + uri);
            return inner.open(URI.create(base + i), off);
        };
    }

    /** vm の fetch 対象 artifact について upstream_url → blob index を張る。 */
    private static Map<String, Integer> mapUrlsToBlobs(VerifiedManifest vm,
                                                       Map<String, Path> localMap,
                                                       List<Path> blobs) {
        Map<String, Integer> urlToIndex = new LinkedHashMap<>();
        for (ManifestVerifier.Artifact art : vm.artifacts()) {
            Path local = localMap.get(art.path());
            if (art.upstreamUrl() != null && local != null) {
                blobs.add(local);
                urlToIndex.put(art.upstreamUrl(), blobs.size() - 1);
            }
        }
        return urlToIndex;
    }

    // ---- package-level behavior ----

    @Test
    void inspectAbsentOnEmptyGameDir(@TempDir Path gameDir) throws IOException {
        var ins = SomPackage.inspect(gameDir);
        assertEquals(Stager.Inspection.Status.ABSENT, ins.status());
        assertFalse(ins.usable());
    }

    @Test
    void somTailSymlinkIsUnlinkedNotFollowed(@TempDir Path gameDir) throws IOException {
        Path outside = Files.createTempDirectory("som-outside");
        Files.createSymbolicLink(gameDir.resolve(".localinferenceapi"), outside);
        // root() は managed tail の link を外し、外側へ解決しない path を返す
        Path root = SomPackage.root(gameDir);
        assertFalse(Files.isSymbolicLink(gameDir.resolve(".localinferenceapi")));
        assertTrue(root.startsWith(gameDir.toRealPath()),
                "staging root は real game dir の中に留まる");
        assertTrue(Files.list(outside).findAny().isEmpty(), "外側 dir は無傷");
    }

    @Test
    void somTailIntermediateLinkIsUnlinked(@TempDir Path gameDir) throws IOException {
        // .localinferenceapi が本物の dir でその中の som だけが link —
        // 深い成分の link だけを外し、実 dir を残す。junction 相当の
        // divergence 検査と同じ修復経路(Windows junction の実証は別途要)
        Path outside = Files.createTempDirectory("som-outside");
        Files.createDirectories(gameDir.resolve(".localinferenceapi"));
        Files.createSymbolicLink(
                gameDir.resolve(".localinferenceapi").resolve("som"), outside);
        Path root = SomPackage.root(gameDir);
        assertTrue(Files.isDirectory(gameDir.resolve(".localinferenceapi")),
                "本物の中間 dir は残す");
        assertFalse(Files.isSymbolicLink(
                gameDir.resolve(".localinferenceapi").resolve("som")));
        assertTrue(root.startsWith(gameDir.toRealPath()),
                "staging root は real game dir の中に留まる");
        assertTrue(Files.list(outside).findAny().isEmpty(), "外側 dir は無傷");
    }

    @Test
    void stageRepairsReplantedTailInsideGameDir(@TempDir Path gameDir) throws IOException {
        // F-B 製品経路: managed tail の中間成分が link として置かれた状態で
        // stage へ入ると、SomPackage が渡す real game dir を containment
        // base として managedTail/Stager 双方が検査し、resolved した外側
        // dir へ bytes を流さず本物の dir を game dir 内に作る。
        // post-root() 窓の再現は Stager 側再検査の単体試験
        // (StagerTest.replantedIntermediateTailIsContainedAndRepaired)が
        // 担当し、ここは製品経路の配線(base が real game dir)を検査する。
        // NTFS junction 自体の挙動は Windows 実機で未検証。
        Path outside = Files.createTempDirectory("som-outside");
        Files.createSymbolicLink(gameDir.resolve(".localinferenceapi"), outside);
        Fetcher offline = (uri, off) -> {
            throw new Fetcher.FetchRejected("test: transport down");
        };
        var r = SomPackage.stage(gameDir, offline, SomPin::openEmbedded);
        assertFalse(r.committed(), "offline では commit しない");
        Path gd = gameDir.toRealPath();
        assertFalse(Files.isSymbolicLink(gd.resolve(".localinferenceapi")),
                "植えられた link は外される");
        assertTrue(Files.isDirectory(
                gd.resolve(".localinferenceapi").resolve("som").resolve("staging")),
                "staging dir は managed tail の内側に作られる");
        assertTrue(Files.list(outside).findAny().isEmpty(), "外側 dir は無傷");
        assertEquals(Stager.Inspection.Status.ABSENT,
                SomPackage.inspect(gameDir).status());
    }

    @Test
    void stageDoesNotCommitWhenFetcherDeclines(@TempDir Path gameDir) throws IOException {
        Fetcher offline = (uri, off) -> {
            throw new Fetcher.FetchRejected("test: transport down");
        };
        var r = SomPackage.stage(gameDir, offline, SomPin::openEmbedded);
        assertFalse(r.committed());
        assertFalse(Files.exists(
                SomPackage.root(gameDir).resolve("current.json")));
        var ins = SomPackage.inspect(gameDir);
        assertEquals(Stager.Inspection.Status.ABSENT, ins.status());
    }

    /**
     * 本物の embedded manifest + local-map assets で stage 全体を通し、
     * commit marker / inspect の COMMITTED・commit 後 tamper の TAMPERED・
     * marker 削除の ABSENT を1回の staging(~3.1GB)で検査する — disk 容量の
     * 都合で2回分の staging はしない。assets もしくは空き容量が無ければ skip。
     */
    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void fullStageThenTamperDetectedWithLocalMapAssets(@TempDir Path gameDir) throws Exception {
        Map<String, Path> localMap = readLocalMap();
        assumeTrue(localMap.size() == 5,
                "JevK5 local-map assets absent — end-to-end stage skipped");
        long needed = localMap.values().stream()
                .mapToLong(p -> {
                    try { return Files.size(p); } catch (IOException e) { return 0; }
                }).sum();
        long usable = Files.getFileStore(gameDir).getUsableSpace();
        assumeTrue(usable > needed + (256L << 20),
                "insufficient free space for end-to-end stage: usable="
                        + usable + " need=" + needed);

        var verified = SomPackage.verifyEmbeddedManifest();
        assertTrue(verified.passed(), () -> String.join("\n", verified.failures()));
        VerifiedManifest vm = verified.manifest();

        // license artifact は embedded resource から来る — local map に無い
        List<Path> blobs = new ArrayList<>();
        Map<String, Integer> urlToIndex = mapUrlsToBlobs(vm, localMap, blobs);
        assertEquals(5, blobs.size(), "取得系 artifact は 5 件(runtime×4+weights×1)");
        startServing(blobs);

        var staged = SomPackage.stage(gameDir,
                rewritingFetcher(urlToIndex), SomPin::openEmbedded);
        assertTrue(staged.committed(), () -> String.join("\n", staged.failures()));

        Path root = SomPackage.root(gameDir);
        JsonObject marker = JsonParser.parseString(Files.readString(
                root.resolve("current.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(SomPin.MANIFEST_SHA256,
                marker.get("manifest_sha256").getAsString());
        assertEquals(10, marker.getAsJsonArray("artifacts").size());

        var ins = SomPackage.inspect(gameDir);
        assertEquals(Stager.Inspection.Status.COMMITTED, ins.status(),
                () -> String.join("\n", ins.failures()));
        assertEquals(10, ins.files().size());
        // 全 artifact が manifest 宣言 path に写像される
        for (ManifestVerifier.Artifact art : vm.artifacts()) {
            assertTrue(ins.files().containsKey(art.path()), art.path());
        }

        // commit 後に小さい artifact(license file)の1 byte を反転
        Path small = ins.files().get("licenses/LLAMA-CPP-LICENSE.txt");
        assertNotNull(small);
        byte[] bytes = Files.readAllBytes(small);
        bytes[0] ^= 0x01;
        Files.write(small, bytes);

        var ins2 = SomPackage.inspect(gameDir);
        assertEquals(Stager.Inspection.Status.TAMPERED, ins2.status());
        assertFalse(ins2.usable());

        // marker 自体の削除は TAMPERED でなく ABSENT(再 stage が正当)
        Files.delete(root.resolve("current.json"));
        assertEquals(Stager.Inspection.Status.ABSENT,
                SomPackage.inspect(gameDir).status());
    }
}
