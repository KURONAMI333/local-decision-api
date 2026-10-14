package com.kuronami.localinferenceapi.internal;

import com.kuronami.localinferenceapi.internal.som.Fetcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class NativeRuntimeTest {
    private static final byte[] ORT_LIB = "ort-native".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ORT_JNI = "ort-jni".getBytes(StandardCharsets.UTF_8);
    private static final byte[] TOKENIZERS_LIB = "tokenizers-native".getBytes(StandardCharsets.UTF_8);
    private static final URI ORT_URI = URI.create("https://repo1.maven.org/ort.jar");
    private static final URI TOKENIZERS_URI = URI.create("https://repo1.maven.org/tokenizers.jar");

    private static final byte[] ORT_JAR = jar(Map.of("native/p/libort.so", ORT_LIB, "native/p/libjni.so", ORT_JNI, "Other.class", new byte[8]));
    private static final byte[] TOKENIZERS_JAR = jar(Map.of("lib/p/libtokenizers.so", TOKENIZERS_LIB));

    private static NativeRuntime.Artifact ort(byte[] jarBytes, byte[] pinnedLib) {
        return new NativeRuntime.Artifact("ort-test", ORT_URI, jarBytes.length, sha(jarBytes), Map.of("test-platform", List.of(
                new NativeRuntime.Entry("native/p/libort.so", sha(pinnedLib)),
                new NativeRuntime.Entry("native/p/libjni.so", sha(ORT_JNI)))));
    }

    private static NativeRuntime.Artifact tokenizers() {
        return new NativeRuntime.Artifact("tokenizers-test", TOKENIZERS_URI, TOKENIZERS_JAR.length, sha(TOKENIZERS_JAR),
                Map.of("test-platform", List.of(new NativeRuntime.Entry("lib/p/libtokenizers.so", sha(TOKENIZERS_LIB)))));
    }

    /** Maven Central の代わりに、URI ごとの bytes を返す。呼出回数を数える。 */
    private static Fetcher serving(AtomicInteger calls, Map<URI, byte[]> bodies) {
        return (uri, resumeFrom) -> {
            calls.incrementAndGet();
            byte[] body = bodies.get(uri);
            if (body == null) return new Fetcher.Response(404, new ByteArrayInputStream(new byte[0]));
            return new Fetcher.Response(200, new ByteArrayInputStream(body));
        };
    }

    private static final Fetcher OFFLINE = (uri, resumeFrom) -> { throw new ConnectException("network is unreachable"); };

    @Test
    void firstStartDownloadsVerifiesAndPublishesOnlyPlatformFiles(@TempDir Path tmp) throws IOException {
        AtomicInteger calls = new AtomicInteger();
        var prepared = NativeRuntime.prepare(tmp, serving(calls, Map.of(ORT_URI, ORT_JAR, TOKENIZERS_URI, TOKENIZERS_JAR)),
                "test-platform", ort(ORT_JAR, ORT_LIB), tokenizers());
        assertEquals(2, calls.get());
        assertArrayEquals(ORT_LIB, Files.readAllBytes(prepared.onnxRuntimeDirectory().resolve("libort.so")));
        assertArrayEquals(ORT_JNI, Files.readAllBytes(prepared.onnxRuntimeDirectory().resolve("libjni.so")));
        assertEquals("libtokenizers.so", prepared.tokenizersLibrary().getFileName().toString());
        assertArrayEquals(TOKENIZERS_LIB, Files.readAllBytes(prepared.tokenizersLibrary()));
        assertFalse(Files.exists(prepared.onnxRuntimeDirectory().resolve("Other.class")));
        assertNoPartials(tmp);
    }

    @Test
    void secondStartWorksOfflineFromVerifiedCache(@TempDir Path tmp) throws IOException {
        var first = NativeRuntime.prepare(tmp, serving(new AtomicInteger(), Map.of(ORT_URI, ORT_JAR, TOKENIZERS_URI, TOKENIZERS_JAR)),
                "test-platform", ort(ORT_JAR, ORT_LIB), tokenizers());
        var second = NativeRuntime.prepare(tmp, OFFLINE, "test-platform", ort(ORT_JAR, ORT_LIB), tokenizers());
        assertEquals(first, second);
    }

    @Test
    void offlineFirstStartIsUnavailableAndPublishesNothing(@TempDir Path tmp) {
        var failure = assertThrows(NativeRuntime.Unavailable.class,
                () -> NativeRuntime.prepare(tmp, OFFLINE, "test-platform", ort(ORT_JAR, ORT_LIB), tokenizers()));
        assertTrue(failure.getMessage().contains("Maven Central"), failure.getMessage());
        assertTrue(failure.getMessage().contains("internet connection"), failure.getMessage());
        assertFalse(Files.exists(tmp.resolve("ort-test/test-platform/libort.so")));
        assertNoPartials(tmp);
    }

    @Test
    void tamperedArtifactIsDiscarded(@TempDir Path tmp) {
        byte[] tampered = jar(Map.of("native/p/libort.so", "evil".getBytes(StandardCharsets.UTF_8), "native/p/libjni.so", ORT_JNI));
        // JAR 全体の SHA-256 が違えば、中身を1つも展開しない。
        var pinned = ort(ORT_JAR, ORT_LIB);
        var failure = assertThrows(NativeRuntime.Unavailable.class, () -> NativeRuntime.prepare(tmp,
                serving(new AtomicInteger(), Map.of(ORT_URI, tampered, TOKENIZERS_URI, TOKENIZERS_JAR)),
                "test-platform", pinned, tokenizers()));
        assertTrue(failure.getMessage().contains("SHA-256"), failure.getMessage());
        assertFalse(Files.exists(tmp.resolve("ort-test/test-platform/libort.so")));
        assertNoPartials(tmp);
    }

    @Test
    void entryHashMismatchIsDiscardedEvenWhenJarMatches(@TempDir Path tmp) {
        var wrongEntryPin = ort(ORT_JAR, "something-else".getBytes(StandardCharsets.UTF_8));
        assertThrows(NativeRuntime.Unavailable.class, () -> NativeRuntime.prepare(tmp,
                serving(new AtomicInteger(), Map.of(ORT_URI, ORT_JAR, TOKENIZERS_URI, TOKENIZERS_JAR)),
                "test-platform", wrongEntryPin, tokenizers()));
        assertFalse(Files.exists(tmp.resolve("ort-test/test-platform/libort.so")));
        assertNoPartials(tmp);
    }

    @Test
    void oversizedBodyStopsAtPinnedSize(@TempDir Path tmp) {
        byte[] padded = new byte[ORT_JAR.length + 1024];
        System.arraycopy(ORT_JAR, 0, padded, 0, ORT_JAR.length);
        var failure = assertThrows(NativeRuntime.Unavailable.class, () -> NativeRuntime.prepare(tmp,
                serving(new AtomicInteger(), Map.of(ORT_URI, padded, TOKENIZERS_URI, TOKENIZERS_JAR)),
                "test-platform", ort(ORT_JAR, ORT_LIB), tokenizers()));
        assertTrue(failure.getMessage().contains("larger"), failure.getMessage());
        assertNoPartials(tmp);
    }

    @Test
    void httpErrorIsUnavailable(@TempDir Path tmp) {
        var failure = assertThrows(NativeRuntime.Unavailable.class, () -> NativeRuntime.prepare(tmp,
                serving(new AtomicInteger(), Map.of()), "test-platform", ort(ORT_JAR, ORT_LIB), tokenizers()));
        assertTrue(failure.getMessage().contains("HTTP 404"), failure.getMessage());
    }

    @Test
    void corruptedCacheIsReplacedFromVerifiedDownload(@TempDir Path tmp) throws IOException {
        AtomicInteger calls = new AtomicInteger();
        Fetcher fetcher = serving(calls, Map.of(ORT_URI, ORT_JAR, TOKENIZERS_URI, TOKENIZERS_JAR));
        var prepared = NativeRuntime.prepare(tmp, fetcher, "test-platform", ort(ORT_JAR, ORT_LIB), tokenizers());
        Files.write(prepared.onnxRuntimeDirectory().resolve("libort.so"), "corrupt".getBytes(StandardCharsets.UTF_8));
        NativeRuntime.prepare(tmp, fetcher, "test-platform", ort(ORT_JAR, ORT_LIB), tokenizers());
        assertEquals(3, calls.get()); // tokenizers の cache は有効なので取り直さない
        assertArrayEquals(ORT_LIB, Files.readAllBytes(prepared.onnxRuntimeDirectory().resolve("libort.so")));
    }

    @Test
    void unsupportedPlatformIsUnavailableWithoutNetwork(@TempDir Path tmp) {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(NativeRuntime.Unavailable.class, () -> NativeRuntime.prepare(tmp,
                serving(calls, Map.of()), null, ort(ORT_JAR, ORT_LIB), tokenizers()));
        assertEquals(0, calls.get());
    }

    @Test
    void platformKeysMatchSupportedRuntimes() {
        assertEquals("windows-amd64", NativeRuntime.platform("Windows 11", "amd64"));
        assertEquals("macos-arm64", NativeRuntime.platform("Mac OS X", "aarch64"));
        assertEquals("linux-amd64", NativeRuntime.platform("Linux", "x86_64"));
        assertEquals("linux-arm64", NativeRuntime.platform("Linux", "aarch64"));
        assertNull(NativeRuntime.platform("Linux", "ppc64le"));
        assertNull(NativeRuntime.platform("FreeBSD", "amd64"));
        for (String key : List.of("windows-amd64", "macos-arm64", "linux-amd64", "linux-arm64")) {
            assertTrue(NativeRuntime.ONNX_RUNTIME.entries().containsKey(key), key);
            assertTrue(NativeRuntime.TOKENIZERS.entries().containsKey(key), key);
        }
        // Intel Mac と Windows on ARM は ONNX Runtime 1.30.0 の公式 JAR に native が無い。
        assertFalse(NativeRuntime.ONNX_RUNTIME.entries().containsKey("macos-amd64"));
        assertFalse(NativeRuntime.ONNX_RUNTIME.entries().containsKey("windows-arm64"));
    }

    @Test
    void productionPinsPointOnlyAtMavenCentral() {
        for (var artifact : List.of(NativeRuntime.ONNX_RUNTIME, NativeRuntime.TOKENIZERS)) {
            assertEquals("https", artifact.uri().getScheme());
            assertEquals(NativeRuntime.MAVEN_CENTRAL_HOST, artifact.uri().getHost());
            assertTrue(artifact.sha256().matches("[0-9a-f]{64}"));
            artifact.entries().values().forEach(list -> list.forEach(e -> assertTrue(e.sha256().matches("[0-9a-f]{64}"), e.path())));
        }
    }

    private static void assertNoPartials(Path root) {
        try (Stream<Path> files = Files.walk(root)) {
            assertEquals(List.of(), files.filter(p -> p.getFileName().toString().endsWith(".part")).toList());
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static byte[] jar(Map<String, byte[]> entries) {
        try {
            var buffer = new ByteArrayOutputStream();
            try (var zip = new ZipOutputStream(buffer)) {
                for (var entry : new java.util.TreeMap<>(entries).entrySet()) {
                    zip.putNextEntry(new ZipEntry(entry.getKey()));
                    zip.write(entry.getValue());
                    zip.closeEntry();
                }
            }
            return buffer.toByteArray();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static String sha(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
