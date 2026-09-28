package com.kuronami.localinferenceapi.internal.som;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ManifestVerifier の fail-closed matrix。verify_manifest.py の release mode と
 * sandbox VerifierTest の parity + S8a の追加契約(role 白名単・license 必須・
 * size 上限・manifest_version)をカバーする。
 */
class ManifestVerifierTest {

    private static final String SHA = "0".repeat(64);

    private static Path write(Path dir, String rel, byte[] content) throws IOException {
        Path p = dir.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.write(p, content);
        return p;
    }

    private static String sha(String s) {
        return ManifestVerifier.sha256Hex(s.getBytes(StandardCharsets.UTF_8));
    }

    private static ManifestVerifier.Result phase1(String manifestJson) {
        byte[] bytes = manifestJson.getBytes(StandardCharsets.UTF_8);
        return ManifestVerifier.verifyManifest(bytes,
                TrustedPin.insecureForTests(ManifestVerifier.sha256Hex(bytes), Set.of()));
    }

    /** 最小の合法 manifest: runtime artifact + license file。 */
    private static String validManifest() {
        return """
            {"manifest_version":1,"artifacts":[
              {"path":"bin/tool","role":"runtime","sha256":"%s","size":10,
               "upstream_url":"https://example.test/tool","license_files":["licenses/NOTICE.txt"]},
              {"path":"licenses/NOTICE.txt","role":"license","sha256":"%s","size":6}
            ]}""".formatted(sha("tool-bytes"), sha("notice"));
    }

    private static Path buildValidPackage(Path root) throws IOException {
        write(root, "bin/tool", "tool-bytes".getBytes(StandardCharsets.UTF_8));
        write(root, "licenses/NOTICE.txt", "notice".getBytes(StandardCharsets.UTF_8));
        write(root, "manifest.json", validManifest().getBytes(StandardCharsets.UTF_8));
        return root.resolve("manifest.json");
    }

    // ---- trust anchor ----

    @Test
    void validManifestPasses() {
        var r = phase1(validManifest());
        assertTrue(r.passed(), () -> String.join("\n", r.failures()));
        assertEquals(2, r.manifest().artifacts().size());
    }

    @Test
    void tamperedManifestRejectedBeforeParse() {
        byte[] bytes = validManifest().getBytes(StandardCharsets.UTF_8);
        var r = ManifestVerifier.verifyManifest(bytes,
                TrustedPin.insecureForTests(SHA, Set.of()));
        assertFalse(r.passed());
        assertTrue(r.failures().stream().anyMatch(f -> f.contains("TAMPERED")));
        assertNull(r.manifest(), "anchor 失敗時は token を発行しない");
    }

    @Test
    void missingAnchorFails() {
        byte[] bytes = validManifest().getBytes(StandardCharsets.UTF_8);
        var r = ManifestVerifier.verifyManifest(bytes, null);
        assertFalse(r.passed());
        assertTrue(r.failures().stream().anyMatch(f -> f.contains("no trusted manifest digest")));
    }

    @Test
    void emptyAndMalformedManifestsFail() {
        byte[] bytes = validManifest().getBytes(StandardCharsets.UTF_8);
        TrustedPin pin = TrustedPin.insecureForTests(
                ManifestVerifier.sha256Hex(bytes), Set.of());
        assertFalse(ManifestVerifier.verifyManifest(new byte[0], pin).passed());

        byte[] broken = "{not json".getBytes(StandardCharsets.UTF_8);
        var r = ManifestVerifier.verifyManifest(broken,
                TrustedPin.insecureForTests(ManifestVerifier.sha256Hex(broken), Set.of()));
        assertTrue(r.failures().stream().anyMatch(f -> f.contains("malformed")));

        byte[] noList = "{\"manifest_version\":1}".getBytes(StandardCharsets.UTF_8);
        var r2 = ManifestVerifier.verifyManifest(noList,
                TrustedPin.insecureForTests(ManifestVerifier.sha256Hex(noList), Set.of()));
        assertTrue(r2.failures().stream().anyMatch(f -> f.contains("artifacts")));
    }

    @Test
    void manifestVersionRequiredAndMustBeOne() {
        String noVersion = "{\"artifacts\":[]}";
        assertFalse(phase1(noVersion).passed());
        String v2 = "{\"manifest_version\":2,\"artifacts\":[]}";
        assertFalse(phase1(v2).passed());
        String vFloat = "{\"manifest_version\":1.5,\"artifacts\":[]}";
        assertFalse(phase1(vFloat).passed());
        String vStr = "{\"manifest_version\":\"1\",\"artifacts\":[]}";
        assertFalse(phase1(vStr).passed());
    }

    // ---- path safety ----

    @Test
    void unsafePathsRejected() {
        // gson で組み立てて JSON escape を正しく通す(backslash を含む経路)
        for (String p : new String[]{"../escape", "a/../b", "/abs/x",
                "C:/win/x", "C:x", "//server/share", "a\\b", "a\\\\b", "", " "}) {
            com.google.gson.JsonObject m = new com.google.gson.JsonObject();
            m.addProperty("manifest_version", 1);
            com.google.gson.JsonObject a = new com.google.gson.JsonObject();
            a.addProperty("path", p);
            a.addProperty("role", "runtime");
            a.addProperty("sha256", SHA);
            a.addProperty("size", 1);
            a.addProperty("upstream_url", "https://example.test/x");
            a.add("license_files", new com.google.gson.JsonArray());
            com.google.gson.JsonArray arts = new com.google.gson.JsonArray();
            arts.add(a);
            m.add("artifacts", arts);
            var r = phase1(m.toString());
            assertFalse(r.passed(), () -> "path not rejected: " + p);
            assertTrue(r.failures().stream().anyMatch(
                    f -> f.contains("UNSAFE") || f.contains("license_files required")),
                    () -> p + " -> " + r.failures());
        }
    }

    @Test
    void duplicatePathRejected() {
        // 2件目も合法な runtime artifact でなければ parse が先に落ちて
        // duplicate 検査へ届かない — licenses/N は別途宣言して他の失敗を潰す
        var r = phase1("""
            {"manifest_version":1,"artifacts":[
              {"path":"a/b","role":"runtime","sha256":"%1$s","size":1,
               "upstream_url":"https://example.test/a","license_files":["licenses/N"]},
              {"path":"a/b","role":"runtime","sha256":"%1$s","size":1,
               "upstream_url":"https://example.test/b","license_files":["licenses/N"]},
              {"path":"licenses/N","role":"license","sha256":"%1$s","size":1}
            ]}""".formatted(SHA));
        assertTrue(r.failures().stream().anyMatch(f -> f.contains("duplicate")),
                () -> String.join("\n", r.failures()));
        assertNull(r.manifest());
    }

    // ---- field types ----

    @Test
    void malformedFieldTypesFailClosed() {
        var r = phase1("""
            {"manifest_version":1,"artifacts":[
              {"path":123,"role":"runtime","sha256":"%1$s","size":1},
              {"path":"a","role":"runtime","sha256":null,"size":1},
              {"path":"b","role":"runtime","sha256":"%1$s","size":"big"},
              {"path":"c","role":"runtime","sha256":"%1$s","size":true},
              {"path":"d","role":"runtime","sha256":"%1$s","size":1.5},
              {"path":"e","role":"runtime","sha256":"xyz","size":1},
              {"path":"f","role":42,"sha256":"%1$s","size":1},
              {"path":"g","role":"party","sha256":"%1$s","size":1},
              {"path":"h","role":"runtime","sha256":"%1$s","size":0},
              {"path":"i","role":"runtime","sha256":"%1$s","size":-3},
              {"path":"j","role":"runtime","sha256":"%1$s","size":1,
               "upstream_url":7,"license_files":["licenses/N"]},
              {"path":"k","role":"runtime","sha256":"%1$s","size":1,
               "upstream_url":"https://example.test/k","license_files":"no"},
              {"path":"l","role":"runtime","sha256":"%1$s","size":1,
               "upstream_url":"https://example.test/l","license_files":["../evil"]}
            ]}""".formatted(SHA));
        assertEquals(13, r.failures().size(), () -> String.join("\n", r.failures()));
        assertNull(r.manifest());
    }

    // ---- license/notice enforcement (fail-closed before launch) ----

    @Test
    void undeclaredLicenseReferenceFails() {
        var r = phase1("""
            {"manifest_version":1,"artifacts":[{"path":"bin/x","role":"runtime",
             "sha256":"%s","size":1,"upstream_url":"https://example.test/x",
             "license_files":["licenses/GONE.txt"]}]}""".formatted(SHA));
        assertTrue(r.failures().stream().anyMatch(f -> f.contains("not declared")));
    }

    @Test
    void runtimeAndWeightsRequireUpstreamUrlAndLicenseFiles() {
        var r = phase1("""
            {"manifest_version":1,"artifacts":[
              {"path":"bin/x","role":"runtime","sha256":"%1$s","size":1,
               "license_files":["licenses/N"]},
              {"path":"bin/y","role":"weights","sha256":"%1$s","size":1,
               "upstream_url":"https://example.test/y"},
              {"path":"licenses/N","role":"license","sha256":"%1$s","size":1}
            ]}""".formatted(SHA));
        assertTrue(r.failures().stream().anyMatch(f -> f.contains("upstream_url required")),
                () -> String.join("\n", r.failures()));
        assertTrue(r.failures().stream().anyMatch(f -> f.contains("license_files required")),
                () -> String.join("\n", r.failures()));
    }

    @Test
    void licenseArtifactMustLiveUnderLicensesDir() {
        var r = phase1("""
            {"manifest_version":1,"artifacts":[{"path":"docs/NOTICE.txt","role":"license",
             "sha256":"%s","size":1}]}""".formatted(SHA));
        assertFalse(r.passed());
        assertTrue(r.failures().stream().anyMatch(f -> f.contains("licenses/")));
    }

    // ---- fetch policy ----

    @Test
    void httpSchemeRejectedUnderProductionPin() {
        String m = """
            {"manifest_version":1,"artifacts":[{"path":"bin/x","role":"runtime",
             "sha256":"%s","size":1,"upstream_url":"http://127.0.0.1:1/x",
             "license_files":["licenses/N"]}]}""".formatted(SHA);
        byte[] bytes = m.getBytes(StandardCharsets.UTF_8);
        var r = ManifestVerifier.verifyManifest(bytes,
                TrustedPin.production(ManifestVerifier.sha256Hex(bytes),
                        Set.of("127.0.0.1"), Long.MAX_VALUE));
        assertTrue(r.failures().stream().anyMatch(f -> f.contains("scheme rejected")));
    }

    @Test
    void disallowedHostRejected() {
        String m = """
            {"manifest_version":1,"artifacts":[{"path":"bin/x","role":"runtime",
             "sha256":"%s","size":1,"upstream_url":"https://evil.example/x",
             "license_files":["licenses/N"]}]}""".formatted(SHA);
        byte[] bytes = m.getBytes(StandardCharsets.UTF_8);
        var r = ManifestVerifier.verifyManifest(bytes,
                TrustedPin.production(ManifestVerifier.sha256Hex(bytes),
                        Set.of("github.com"), Long.MAX_VALUE));
        assertTrue(r.failures().stream().anyMatch(f -> f.contains("allowlist")));
    }

    @Test
    void subdomainSuffixMatchApplies() {
        String m = """
            {"manifest_version":1,"artifacts":[
              {"path":"a","role":"runtime","sha256":"%1$s","size":1,
               "upstream_url":"https://objects.githubusercontent.com/x",
               "license_files":["licenses/N"]},
              {"path":"b","role":"runtime","sha256":"%1$s","size":1,
               "upstream_url":"https://notgithub.com.evil.test/y",
               "license_files":["licenses/N"]}
            ]}""".formatted(SHA);
        byte[] bytes = m.getBytes(StandardCharsets.UTF_8);
        var r = ManifestVerifier.verifyManifest(bytes,
                TrustedPin.production(ManifestVerifier.sha256Hex(bytes),
                        Set.of("githubusercontent.com"), Long.MAX_VALUE));
        // a は subdomain match で通り、b は suffix 見掛け倒しを拒否する
        assertEquals(1, r.failures().stream()
                .filter(f -> f.contains("allowlist")).count());
    }

    @Test
    void oversizeArtifactRejectedAgainstCap() {
        String m = """
            {"manifest_version":1,"artifacts":[{"path":"bin/x","role":"runtime",
             "sha256":"%s","size":2000000000,"upstream_url":"https://example.test/x",
             "license_files":["licenses/N"]}]}""".formatted(SHA);
        byte[] bytes = m.getBytes(StandardCharsets.UTF_8);
        var r = ManifestVerifier.verifyManifest(bytes,
                TrustedPin.production(ManifestVerifier.sha256Hex(bytes),
                        Set.of("example.test"), 1_000_000_000L));
        assertTrue(r.failures().stream().anyMatch(f -> f.contains("cap")));
    }

    @Test
    void localReferenceRejected() {
        var r = phase1("""
            {"manifest_version":1,"artifacts":[{"path":"bin/x","role":"runtime",
             "sha256":"%s","size":1,"upstream_url":"https://example.test/x",
             "license_files":["licenses/N"],
             "local_reference":"/opt/local/some/file"}]}""".formatted(SHA));
        assertTrue(r.failures().stream().anyMatch(f -> f.contains("local_reference")));
    }

    @Test
    void missingFilesOnDiskStillPassPhase1() {
        // phase 1 は metadata のみ — 未取得 file への manifest 検証が
        // first-use downloader を可能にする
        assertTrue(phase1(validManifest()).passed());
    }

    // ---- verifyPackageTree (declared-path tree, Python parity) ----

    @Test
    void validPackageTreePasses(@TempDir Path tmp) throws IOException {
        Path manifest = buildValidPackage(tmp);
        var r = ManifestVerifier.verifyPackageTree(tmp, manifest,
                ManifestVerifier.sha256Hex(manifest));
        assertTrue(r.passed(), () -> String.join("\n", r.failures()));
    }

    @Test
    void packageTreeRequiresAnchor(@TempDir Path tmp) throws IOException {
        Path manifest = buildValidPackage(tmp);
        var r = ManifestVerifier.verifyPackageTree(tmp, manifest, null);
        assertFalse(r.passed());
        assertTrue(r.failures().stream().anyMatch(f -> f.contains("no trusted manifest digest")));
        assertNull(r.manifest(),
                "unanchored diagnostics は failure のみを返し token を発行しない");
    }

    @Test
    void emptyArtifactSetRejected() {
        // 空の artifacts 集合は vacuous COMMITTED を生み得る — 拒否する
        var r = phase1("{\"manifest_version\":1,\"artifacts\":[]}");
        assertFalse(r.passed());
        assertTrue(r.failures().stream().anyMatch(f -> f.contains("no artifacts")),
                () -> String.join("\n", r.failures()));
        assertNull(r.manifest());
    }

    @Test
    void deeplyNestedManifestFailsBeforeRecursiveParse() {
        // F7: Gson 2.10.1 の再帰 parser は depth 制限を持たない —
        // digest 一致後・parse 前の depth bound で StackOverflow を防ぐ
        byte[] deep = ("{\"manifest_version\":1,\"artifacts\":"
                + "[".repeat(20_000) + "]}").getBytes(StandardCharsets.UTF_8);
        var r = ManifestVerifier.verifyManifest(deep,
                TrustedPin.insecureForTests(
                        ManifestVerifier.sha256Hex(deep), Set.of()));
        assertFalse(r.passed());
        assertTrue(r.failures().stream().anyMatch(f -> f.contains("nesting")),
                () -> String.join("\n", r.failures()));
        assertNull(r.manifest());
    }

    @Test
    void packageTreeUnanchoredDeepManifestDoesNotOverflow(@TempDir Path tmp) throws IOException {
        // self-anchor diagnostics 経路は attacker-controlled bytes を parse
        // する — depth 爆弾でも StackOverflow でなく failure に収まる
        Path manifest = write(tmp, "manifest.json",
                ("{\"manifest_version\":1,\"artifacts\":" + "[".repeat(50_000))
                        .getBytes(StandardCharsets.UTF_8));
        var r = ManifestVerifier.verifyPackageTree(tmp, manifest, null);
        assertFalse(r.passed());
        assertNull(r.manifest());
    }

    @Test
    void blankPathRejectedAsUnsafe() {
        // " "(空白のみ)は空でないが safe path でない — license_files 完備の
        // fixture で UNSAFE に落ちることを分離して確かめる
        var r = phase1("""
            {"manifest_version":1,"artifacts":[
              {"path":" ","role":"runtime","sha256":"%1$s","size":1,
               "upstream_url":"https://example.test/x","license_files":["licenses/N"]},
              {"path":"licenses/N","role":"license","sha256":"%1$s","size":1}
            ]}""".formatted(SHA));
        assertFalse(r.passed());
        assertTrue(r.failures().stream().anyMatch(f -> f.contains("UNSAFE")),
                () -> String.join("\n", r.failures()));
    }

    @Test
    void trustedPinNotConstructibleOutsidePackage() {
        // F8: canonical ctor が package 外から allowPlainHttp=true の pin を
        // 組み立てる経路を残さない — record 自体を package-private にする
        assertFalse(java.lang.reflect.Modifier.isPublic(
                TrustedPin.class.getModifiers()),
                "TrustedPin must not be public");
    }

    @Test
    void verifyPackageTreeIsNotPublicApi() {
        // F-D: caller 任意の trustedSha256 で VerifiedManifest token を mint
        // できる diagnostics を public API にしない — 製品経路で stage へ
        // 渡せる token の発行は verifyManifest(embedded bytes + pin)に限る
        for (var m : ManifestVerifier.class.getDeclaredMethods()) {
            if (m.getName().equals("verifyPackageTree")) {
                assertFalse(java.lang.reflect.Modifier.isPublic(m.getModifiers()),
                        "verifyPackageTree must not be public");
            }
        }
    }

    @Test
    void packageTreeTamperedDigestFails(@TempDir Path tmp) throws IOException {
        Path manifest = buildValidPackage(tmp);
        var r = ManifestVerifier.verifyPackageTree(tmp, manifest, SHA);
        assertTrue(r.failures().stream().anyMatch(f -> f.contains("TAMPERED")));
    }

    @Test
    void packageTreeMissingAndAlteredFilesFail(@TempDir Path tmp) throws IOException {
        Path manifest = buildValidPackage(tmp);
        String digest = ManifestVerifier.sha256Hex(manifest);
        // altered content (hash+size 両方変わる)
        Files.writeString(tmp.resolve("bin/tool"), "changed!");
        var r1 = ManifestVerifier.verifyPackageTree(tmp, manifest, digest);
        assertTrue(r1.failures().stream().anyMatch(f -> f.contains("SIZE") || f.contains("HASH")));

        // missing
        Files.delete(tmp.resolve("bin/tool"));
        var r2 = ManifestVerifier.verifyPackageTree(tmp, manifest, digest);
        assertTrue(r2.failures().stream().anyMatch(f -> f.contains("MISSING")));
    }

    @Test
    void packageTreeLeafSymlinkEscapeFails(@TempDir Path tmp) throws IOException {
        Path outside = Files.createTempDirectory("som-outside");
        Files.writeString(outside.resolve("real.txt"), "x");
        buildValidPackage(tmp);
        Files.createSymbolicLink(tmp.resolve("bin/evil"), outside.resolve("real.txt"));
        String m = """
            {"manifest_version":1,"artifacts":[{"path":"bin/evil","role":"runtime",
             "sha256":"%s","size":1,"upstream_url":"https://example.test/x",
             "license_files":["licenses/NOTICE.txt"]},
             {"path":"licenses/NOTICE.txt","role":"license","sha256":"%s","size":6}]}
            """.formatted(ManifestVerifier.sha256Hex(outside.resolve("real.txt")), sha("notice"));
        Path manifest = write(tmp, "m4.json", m.getBytes(StandardCharsets.UTF_8));
        var r = ManifestVerifier.verifyPackageTree(tmp, manifest,
                ManifestVerifier.sha256Hex(manifest));
        assertTrue(r.failures().stream().anyMatch(f -> f.contains("SYMLINK")),
                () -> String.join("\n", r.failures()));
    }

    /** 中間 dir が symlink、leaf は通常 file — intermediate escape。 */
    @Test
    void packageTreeIntermediateSymlinkFails(@TempDir Path tmp) throws IOException {
        Path outside = Files.createTempDirectory("som-outside");
        Files.writeString(outside.resolve("laya-cli"), "x");
        buildValidPackage(tmp);
        Files.createSymbolicLink(tmp.resolve("bin2"), outside);
        String m = """
            {"manifest_version":1,"artifacts":[{"path":"bin2/laya-cli","role":"runtime",
             "sha256":"%s","size":1,"upstream_url":"https://example.test/x",
             "license_files":["licenses/NOTICE.txt"]},
             {"path":"licenses/NOTICE.txt","role":"license","sha256":"%s","size":6}]}
            """.formatted(ManifestVerifier.sha256Hex(outside.resolve("laya-cli")), sha("notice"));
        Path manifest = write(tmp, "m5.json", m.getBytes(StandardCharsets.UTF_8));
        var r = ManifestVerifier.verifyPackageTree(tmp, manifest,
                ManifestVerifier.sha256Hex(manifest));
        assertTrue(r.failures().stream().anyMatch(f -> f.contains("SYMLINK")),
                () -> String.join("\n", r.failures()));
    }

    @Test
    void packageTreeMissingLicenseFails(@TempDir Path tmp) throws IOException {
        Path manifest = buildValidPackage(tmp);
        Files.delete(tmp.resolve("licenses/NOTICE.txt"));
        var r = ManifestVerifier.verifyPackageTree(tmp, manifest,
                ManifestVerifier.sha256Hex(manifest));
        assertTrue(r.failures().stream().anyMatch(
                f -> f.contains("LICENSE") || f.contains("MISSING")),
                () -> String.join("\n", r.failures()));
    }

    @Test
    void packageTreeLicenseSymlinkFails(@TempDir Path tmp) throws IOException {
        Path outside = Files.createTempDirectory("som-outside");
        Files.writeString(outside.resolve("NOTICE.txt"), "notice");
        Path manifest = buildValidPackage(tmp);
        Files.delete(tmp.resolve("licenses/NOTICE.txt"));
        Files.createSymbolicLink(tmp.resolve("licenses/NOTICE.txt"),
                outside.resolve("NOTICE.txt"));
        var r = ManifestVerifier.verifyPackageTree(tmp, manifest,
                ManifestVerifier.sha256Hex(manifest));
        assertTrue(r.failures().stream().anyMatch(f -> f.contains("SYMLINK")),
                () -> String.join("\n", r.failures()));
    }
}
