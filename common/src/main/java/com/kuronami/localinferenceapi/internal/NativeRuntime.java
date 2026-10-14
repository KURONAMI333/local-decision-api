package com.kuronami.localinferenceapi.internal;

import com.kuronami.localinferenceapi.internal.som.Fetcher;
import com.kuronami.localinferenceapi.internal.som.HttpFetcher;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

/**
 * worker が読む ONNX Runtime と tokenizers の native library を用意する。
 *
 * 配布 JAR には native library を入れない。初回だけ Maven Central から
 * 公式の成果物 JAR を取得し、JAR 全体と取り出す各ファイルの SHA-256 が
 * 固定値と一致した時だけ cache へ公開する。2回目以降は cache の再照合だけで
 * 済み、ネットワークに触れない。照合できない時は推論できないことを示す
 * {@link Unavailable} で止まり、別の場所の library へ黙って切り替えない。
 *
 * JNA の jnidispatch は取得しない。worker では DJL が CUDA を探す時だけ使い、
 * 欠けた時は DJL が UnsatisfiedLinkError を捕捉して CPU 経路を選ぶ。
 *
 * 下の SHA-256 は Maven Central の公開成果物の checksum で、秘密情報ではない。
 */
public final class NativeRuntime {
    /** Maven Central の成果物1つ。JAR 全体の SHA-256 と、platform ごとに取り出す entry。 */
    record Artifact(String name, URI uri, long size, String sha256, Map<String, List<Entry>> entries) {}

    /** JAR 内の entry と、取り出したファイルの SHA-256。 */
    record Entry(String path, String sha256) {
        String fileName() { return path.substring(path.lastIndexOf('/') + 1); }
    }

    /** worker の起動に渡す場所。 */
    public record Prepared(Path onnxRuntimeDirectory, Path tokenizersLibrary) {}

    /** 取得・照合に失敗し、この環境では推論できない。 */
    public static final class Unavailable extends IOException {
        @java.io.Serial private static final long serialVersionUID = 1L;
        Unavailable(String message, Throwable cause) { super(message, cause); }
    }

    static final String MAVEN_CENTRAL_HOST = "repo1.maven.org";

    /** この時間 1 byte も受信が進まなければ取得を諦める。試験だけが短くする。 */
    static volatile Duration stallTimeout = Duration.ofSeconds(60);

    /** 少しずつでも届き続ける取得の上限。73 MB を極端に遅い回線で何時間も待たない。試験だけが短くする。 */
    static volatile Duration downloadLimit = Duration.ofMinutes(30);

    static final Artifact ONNX_RUNTIME = new Artifact("onnxruntime-1.30.0",
            URI.create("https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime/1.30.0/onnxruntime-1.30.0.jar"),
            55_572_031L, "57d8cedf6f0c8fd6d50ed65c17bf9939f55a4512f4a1f3e732023d258f262ac6", // gitleaks:allow
            Map.of(
                    "windows-amd64", List.of(
                            new Entry("ai/onnxruntime/native/win-x64/onnxruntime.dll", "22712d61401957fea4e153077ae5e4e5aa2f4e2a5c94d17983cf464db040653e"), // gitleaks:allow
                            new Entry("ai/onnxruntime/native/win-x64/onnxruntime4j_jni.dll", "a3193efb6c808c6b88aeb94090994a9641bd5aeb8bfa9d5404a43432bfcbb6e3"), // gitleaks:allow
                            new Entry("ai/onnxruntime/native/win-x64/onnxruntime_providers_shared.dll", "70faf06bce63e7f8ebd7dd09fdc8aafe8d0e4b4f4522c6ad82e942254b65e6dc")), // gitleaks:allow
                    "macos-arm64", List.of(
                            new Entry("ai/onnxruntime/native/osx-aarch64/libonnxruntime.dylib", "ab6d0f357b6e5ffa85a0d957417a6ddf45c845f537f2437244acff0482ba2f13"), // gitleaks:allow
                            new Entry("ai/onnxruntime/native/osx-aarch64/libonnxruntime4j_jni.dylib", "b7241095d589f12d484c40a2a5f1984ce7e73e73828d09cbd27a2fee13efc3ca")), // gitleaks:allow
                    "linux-amd64", List.of(
                            new Entry("ai/onnxruntime/native/linux-x64/libonnxruntime.so", "245a6f8c38127551057a1cd1ffd59f0a186a227ade4f3492dea2494eb565542e"), // gitleaks:allow
                            new Entry("ai/onnxruntime/native/linux-x64/libonnxruntime4j_jni.so", "b05aa3b05221722edad8c05824f76072dc15e82487e1dd465a74a5f8b4fe9dc9")), // gitleaks:allow
                    "linux-arm64", List.of(
                            new Entry("ai/onnxruntime/native/linux-aarch64/libonnxruntime.so", "64e903a43a041240fd6bcffe0ac6d4fea47ef87bf24b9d097801bd00a9612a4b"), // gitleaks:allow
                            new Entry("ai/onnxruntime/native/linux-aarch64/libonnxruntime4j_jni.so", "a57f656449b347ae7d567af55e4feb97770f672765f828f631f3a5bfc8a3d7e5")))); // gitleaks:allow

    static final Artifact TOKENIZERS = new Artifact("tokenizers-0.38.0",
            URI.create("https://repo1.maven.org/maven2/ai/djl/huggingface/tokenizers/0.38.0/tokenizers-0.38.0.jar"),
            18_128_284L, "2ad145a5ae6bb12b9ddc3e394a68d876f36959732f0e6ed6ee0c118054cd7061", // gitleaks:allow
            Map.of(
                    // DJL は Windows で依存 DLL 3つを tokenizers.dll より先に同じ場所から読む。
                    // tokenizers 本体を各リストの最後に置く(prepare がそれを指す)。
                    "windows-amd64", List.of(
                            new Entry("native/lib/win-x86_64/cpu/libwinpthread-1.dll", "567ca7ae7ecdfb8799b28c41ac3d1a2cc46f4ee2e72c511f027c44250498e893"), // gitleaks:allow
                            new Entry("native/lib/win-x86_64/cpu/libgcc_s_seh-1.dll", "32ce10abc802edc83c36d62e0e9f9389bab0c4bfeacf01cc98726da81094b80a"), // gitleaks:allow
                            new Entry("native/lib/win-x86_64/cpu/libstdc++-6.dll", "a0b8f623f0da655a30873ef7d6b6be0e6942c34535abcaecce142fa0ab1cef4f"), // gitleaks:allow
                            new Entry("native/lib/win-x86_64/cpu/tokenizers.dll", "3e0084560d5b8f509634c582c9b7bdb268e93d570c85c825198a8deb772ff0ae")), // gitleaks:allow
                    "macos-arm64", List.of(
                            new Entry("native/lib/osx-aarch64/cpu/libtokenizers.dylib", "cca1c15bcbfa47b1c942e9d395ea8353161e5ed709f74826ad5faebcd05544ab")), // gitleaks:allow
                    "linux-amd64", List.of(
                            new Entry("native/lib/linux-x86_64/cpu/libtokenizers.so", "edf58c92155e0fe4fae5231e782a1f02deeb5881de7bbcd1195f6e25009da90a")), // gitleaks:allow
                    "linux-arm64", List.of(
                            new Entry("native/lib/linux-aarch64/cpu/libtokenizers.so", "2561672dc37df2704e29eab4049bcfceee5332799bc3b9d85f7d70045b99a610")))); // gitleaks:allow

    private NativeRuntime() {}

    /** 製品経路: 実行中の platform 用に Maven Central から用意する。 */
    public static Prepared prepare(Path cacheRoot) throws IOException {
        return prepare(cacheRoot, HttpFetcher.production(Set.of(MAVEN_CENTRAL_HOST)),
                platform(System.getProperty("os.name", ""), System.getProperty("os.arch", "")),
                ONNX_RUNTIME, TOKENIZERS);
    }

    static Prepared prepare(Path cacheRoot, Fetcher fetcher, String platform,
                            Artifact onnxRuntime, Artifact tokenizers) throws IOException {
        if (platform == null || !onnxRuntime.entries().containsKey(platform) || !tokenizers.entries().containsKey(platform)) {
            throw new Unavailable("Local Decision API has no native runtime for this platform ("
                    + System.getProperty("os.name") + " " + System.getProperty("os.arch") + ")", null);
        }
        Files.createDirectories(cacheRoot);
        removeAbandonedParts(cacheRoot);
        Path onnxDirectory = install(cacheRoot, fetcher, onnxRuntime, platform);
        Path tokenizersDirectory = install(cacheRoot, fetcher, tokenizers, platform);
        List<Entry> tokenizerFiles = tokenizers.entries().get(platform);
        // DJL は RUST_LIBRARY_PATH がこのファイル名を指す時、その親ディレクトリから読む。
        Path library = tokenizersDirectory.resolve(tokenizerFiles.get(tokenizerFiles.size() - 1).fileName());
        return new Prepared(onnxDirectory, library);
    }

    /** os.name / os.arch を取得対象の platform キーへ写す。対象外は null。 */
    static String platform(String osName, String osArch) {
        String os = osName.toLowerCase(Locale.ROOT);
        String arch = switch (osArch.toLowerCase(Locale.ROOT)) {
            case "aarch64", "arm64" -> "arm64";
            case "x86_64", "amd64", "x64" -> "amd64";
            default -> null;
        };
        if (arch == null) return null;
        if (os.startsWith("windows")) return "windows-" + arch;
        if (os.contains("mac") || os.contains("darwin")) return "macos-" + arch;
        if (os.contains("linux")) return "linux-" + arch;
        return null;
    }

    private static Path install(Path cacheRoot, Fetcher fetcher, Artifact artifact, String platform) throws IOException {
        List<Entry> wanted = artifact.entries().get(platform);
        Path directory = cacheRoot.resolve(artifact.name()).resolve(platform);
        if (installed(directory, wanted)) return directory;
        Files.createDirectories(directory);
        Path download = Files.createTempFile(cacheRoot, artifact.name() + "-", ".jar.part");
        try {
            fetch(fetcher, artifact, download);
            try (ZipFile jar = new ZipFile(download.toFile())) {
                for (Entry entry : wanted) extract(jar, entry, directory);
            }
        } finally {
            Files.deleteIfExists(download);
        }
        return directory;
    }

    /** 強制終了で残った古い一時ファイルを消す。進行中の別インスタンスの分は時刻で避ける。 */
    private static void removeAbandonedParts(Path cacheRoot) {
        long cutoff = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1);
        try (Stream<Path> files = Files.walk(cacheRoot, 3)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                if (!file.getFileName().toString().endsWith(".part")) continue;
                try {
                    if (Files.getLastModifiedTime(file).toMillis() < cutoff) Files.deleteIfExists(file);
                } catch (IOException ignored) { /* 掃除は best effort。取得自体は続ける */ }
            }
        } catch (IOException | UncheckedIOException ignored) { /* 同上 */ }
    }

    private static boolean installed(Path directory, List<Entry> wanted) throws IOException {
        for (Entry entry : wanted) {
            Path file = directory.resolve(entry.fileName());
            if (!Files.isRegularFile(file) || !entry.sha256().equals(hash(file))) return false;
        }
        return true;
    }

    private static void fetch(Fetcher fetcher, Artifact artifact, Path target) throws IOException {
        MessageDigest digest = digest();
        long total = 0;
        // 起動 timeout の外で走るので、受信が止まったまま待ち続けないよう自前で見張る。
        // 一定時間 1 byte も進まなければ本文を閉じて割り込む。閉じた本文が例外でなく
        // EOF を返す実装もあるので、ループを抜けた後にも stalled を見る。
        Thread downloader = Thread.currentThread();
        AtomicLong lastProgress = new AtomicLong(System.nanoTime());
        AtomicBoolean stalled = new AtomicBoolean();
        Object guard = new Object();
        boolean[] finished = {false};
        AtomicReference<InputStream> body = new AtomicReference<>();
        long limit = stallTimeout.toNanos();
        long deadline = System.nanoTime() + downloadLimit.toNanos();
        long period = Math.max(limit / 4, TimeUnit.MILLISECONDS.toNanos(10));
        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "localinferenceapi-download-watchdog");
            thread.setDaemon(true);
            return thread;
        });
        watchdog.scheduleAtFixedRate(() -> {
            long now = System.nanoTime();
            if (now - lastProgress.get() < limit && now - deadline < 0) return;
            synchronized (guard) {
                // fetch を抜けた後は割り込まない(後続の処理に割り込みを漏らさない)。
                if (finished[0] || !stalled.compareAndSet(false, true)) return;
                InputStream open = body.get();
                if (open != null) {
                    try { open.close(); } catch (IOException ignored) { /* read 側が失敗として受け取る */ }
                }
                downloader.interrupt();
            }
        }, period, period, TimeUnit.NANOSECONDS);
        try (Fetcher.Response response = fetcher.open(artifact.uri(), 0)) {
            body.set(response.body());
            lastProgress.set(System.nanoTime());
            if (response.status() != 200) {
                throw new Unavailable("Maven Central returned HTTP " + response.status() + " for " + artifact.uri(), null);
            }
            try (OutputStream output = Files.newOutputStream(target)) {
                InputStream input = response.body();
                byte[] buffer = new byte[128 * 1024];
                for (int read; (read = input.read(buffer)) != -1;) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    lastProgress.set(System.nanoTime());
                    total += read;
                    if (total > artifact.size()) {
                        throw new Unavailable("Downloaded " + artifact.name() + " is larger than the pinned artifact; it was discarded", null);
                    }
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
            }
            if (stalled.get()) throw new IOException("Download stalled");
        } catch (Unavailable unavailable) {
            throw unavailable;
        } catch (InterruptedException | IOException failure) {
            if (stalled.get()) {
                Thread.interrupted(); // 見張りの割り込みであって停止要求ではない
                throw new Unavailable("Download of " + artifact.name() + " from Maven Central stopped receiving data for "
                        + stallTimeout.toSeconds() + " seconds or did not finish within " + downloadLimit.toMinutes()
                        + " minutes (" + artifact.uri() + ")", failure);
            }
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                throw new IOException("Native runtime download interrupted", failure);
            }
            if (Thread.currentThread().isInterrupted()) throw (IOException) failure;
            if (failure instanceof Fetcher.FetchRejected) {
                throw new Unavailable("Download of " + artifact.name() + " was redirected away from Maven Central or rejected by the download policy ("
                        + failure.getMessage() + "); a proxy or captive portal may be intercepting it", failure);
            }
            throw new Unavailable("Could not download " + artifact.name() + " from Maven Central (" + artifact.uri()
                    + "); the first inference request needs an internet connection", failure);
        } finally {
            synchronized (guard) {
                finished[0] = true;
                if (stalled.get()) Thread.interrupted(); // 見張りの割り込みであって停止要求ではない
            }
            watchdog.shutdownNow();
        }
        String actual = HexFormat.of().formatHex(digest.digest());
        if (total != artifact.size() || !artifact.sha256().equals(actual)) {
            throw new Unavailable("Downloaded " + artifact.name() + " does not match its pinned SHA-256 (got " + actual
                    + ", " + total + " bytes); it was discarded", null);
        }
    }

    private static void extract(ZipFile jar, Entry entry, Path directory) throws IOException {
        ZipEntry zipEntry = jar.getEntry(entry.path());
        if (zipEntry == null) throw new Unavailable("Pinned artifact is missing " + entry.path(), null);
        Path temporary = Files.createTempFile(directory, entry.fileName() + "-", ".part");
        try {
            MessageDigest digest = digest();
            try (InputStream input = jar.getInputStream(zipEntry); OutputStream output = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[128 * 1024];
                for (int read; (read = input.read(buffer)) != -1;) {
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
            }
            String actual = HexFormat.of().formatHex(digest.digest());
            if (!entry.sha256().equals(actual)) {
                throw new Unavailable(entry.path() + " does not match its pinned SHA-256; it was discarded", null);
            }
            Path published = directory.resolve(entry.fileName());
            // 同じゲームディレクトリの別インスタンスが先に置いた同一ファイルは置き換えない
            // (Windows では読込済み DLL への上書きが失敗する)。
            if (Files.isRegularFile(published) && entry.sha256().equals(hash(published))) return;
            try {
                Files.move(temporary, published, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                // 部分書込みを完成品として扱わないため、atomic move 不可の場所では失敗する。
                throw new IOException("Native runtime cache does not support atomic publication", unsupported);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String hash(Path path) throws IOException {
        MessageDigest digest = digest();
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[128 * 1024];
            for (int read; (read = input.read(buffer)) != -1;) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
