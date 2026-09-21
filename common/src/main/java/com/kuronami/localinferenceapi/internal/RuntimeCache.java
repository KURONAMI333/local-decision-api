package com.kuronami.localinferenceapi.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 配布 JAR の中身だけを使う。ダウンロードやユーザーの Python 環境への依存はない。 */
final class RuntimeCache {
    @FunctionalInterface interface Resource { InputStream open() throws IOException; }

    static Path extract(Path directory, Resource resource) throws IOException {
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, "runtime-", ".part");
        try {
            MessageDigest digest = digest();
            try (InputStream input = resource.open(); var output = Files.newOutputStream(temporary)) {
                if (input == null) throw new IOException("Bundled runtime is missing");
                byte[] buffer = new byte[128 * 1024];
                for (int read; (read = input.read(buffer)) != -1;) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("Runtime extraction interrupted");
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
            }
            String hash = HexFormat.of().formatHex(digest.digest());
            Path target = directory.resolve("runtime-" + hash + ".jar");
            if (!Files.isRegularFile(target) || !hash.equals(hash(target))) {
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    // 部分書込みを完成品として扱わないため、atomic move 不可の場所では失敗する。
                    throw new IOException("Runtime cache does not support atomic publication", unsupported);
                }
            }
            return target;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String hash(Path path) throws IOException {
        MessageDigest digest = digest();
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[128 * 1024];
            for (int read; (read = input.read(buffer)) != -1;) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("Runtime verification interrupted");
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
