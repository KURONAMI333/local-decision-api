package com.kuronami.localinferenceapi.internal.som;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unpacker の単体試験 — tar.gz / zip を test 内で生成し、member pin の
 * sha256 照合・member 集合の完全一致・symlink/hardlink の bytes copy 化・
 * unsafe name 拒否・ok marker の再展開 skip を検査する。
 */
class UnpackerTest {

    private static ManifestVerifier.Artifact artifact(String archive,
                                                      Map<String, String> members) {
        byte[] dummy = new byte[0]; // archive sha は ok-marker 比較にだけ使う
        return new ManifestVerifier.Artifact("bin/rt." + archive, sha256(dummy),
                0L, "runtime", null, List.of("licenses/X.txt"), "rt-id", null,
                archive, "bin/tool", members);
    }

    private static String sha256(byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder s = new StringBuilder(64);
            for (byte b : d) s.append(Character.forDigit((b >> 4) & 0xf, 16))
                    .append(Character.forDigit(b & 0xf, 16));
            return s.toString();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path writeZip(Path dir, Map<String, byte[]> members)
            throws IOException {
        Path zip = dir.resolve("a.zip");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (Map.Entry<String, byte[]> e : members.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue());
                z.closeEntry();
            }
        }
        return zip;
    }

    // ---------- 手組み tar ----------

    private record TarEntry(String name, byte[] data, int mode,
                            char type, String linkname) {}

    private static byte[] tarBytes(List<TarEntry> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (TarEntry e : entries) {
            byte[] name = e.name().getBytes(StandardCharsets.UTF_8);
            byte[] link = e.linkname() == null ? new byte[0]
                    : e.linkname().getBytes(StandardCharsets.UTF_8);
            assertTrue(name.length <= 100 && link.length <= 100);
            byte[] h = new byte[512];
            System.arraycopy(name, 0, h, 0, name.length);
            writeOctal(h, 100, 8, e.mode());
            writeOctal(h, 108, 8, 0); // uid
            writeOctal(h, 116, 8, 0); // gid
            writeOctal(h, 124, 12, e.data().length);
            writeOctal(h, 136, 12, 0); // mtime
            for (int i = 148; i < 156; i++) h[i] = ' ';
            h[156] = (byte) e.type();
            if (link.length > 0) System.arraycopy(link, 0, h, 157, link.length);
            byte[] magic = "ustar\0".getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(magic, 0, h, 257, magic.length);
            long sum = 0;
            for (byte b : h) sum += b & 0xff;
            writeOctal(h, 148, 7, sum);
            h[155] = ' ';
            out.write(h);
            out.write(e.data());
            int pad = (512 - e.data().length % 512) % 512;
            out.write(new byte[pad]);
        }
        out.write(new byte[1024]); // end marker
        return out.toByteArray();
    }

    private static void writeOctal(byte[] h, int off, int len, long v) {
        String s = Long.toOctalString(v);
        int pad = len - 1 - s.length();
        for (int i = 0; i < pad; i++) h[off + i] = '0';
        for (int i = 0; i < s.length(); i++) h[off + pad + i] = (byte) s.charAt(i);
        h[off + len - 1] = 0;
    }

    private static Path writeTarGz(Path dir, List<TarEntry> entries)
            throws IOException {
        Path t = dir.resolve("a.tar.gz");
        try (GZIPOutputStream g =
                     new GZIPOutputStream(Files.newOutputStream(t))) {
            g.write(tarBytes(entries));
        }
        return t;
    }

    // ---------- zip ----------

    @Test void zipMembersLandAndVerify(@TempDir Path dir) throws IOException {
        byte[] tool = "#!/bin/sh\necho hi\n".getBytes(StandardCharsets.UTF_8);
        byte[] lib = "libbytes".getBytes(StandardCharsets.UTF_8);
        Path zip = writeZip(dir, Map.of(
                "bin/tool.sh", tool, "lib/x.dylib", lib));
        Map<String, String> pins = new LinkedHashMap<>();
        pins.put("bin/tool.sh", sha256(tool));
        pins.put("lib/x.dylib", sha256(lib));
        Path dest = dir.resolve("rt");

        Unpacker.unpack(zip, dest, artifact("zip", pins));
        assertArrayEquals(tool, Files.readAllBytes(dest.resolve("bin/tool.sh")));
        assertArrayEquals(lib, Files.readAllBytes(dest.resolve("lib/x.dylib")));
        Unpacker.verifyExtracted(dest, artifact("zip", pins)); // no throw
    }

    @Test void zipMemberHashMismatchFails(@TempDir Path dir) throws IOException {
        Path zip = writeZip(dir, Map.of("bin/tool", "real".getBytes()));
        Map<String, String> pins = Map.of("bin/tool", sha256("forged".getBytes()));
        Path dest = dir.resolve("rt");
        assertThrows(IOException.class,
                () -> Unpacker.unpack(zip, dest, artifact("zip", pins)));
        assertFalse(Files.isDirectory(dest), "失敗時は dest を残さない");
    }

    @Test void zipExtraOrMissingMemberFails(@TempDir Path dir) throws IOException {
        byte[] a = "a".getBytes(), b = "b".getBytes();
        Path zip = writeZip(dir, Map.of("bin/a", a, "bin/b", b));
        Map<String, String> pins = Map.of("bin/a", sha256(a)); // b は pin に無い
        assertThrows(IOException.class, () -> Unpacker.unpack(
                zip, dir.resolve("rt"), artifact("zip", pins)));
    }

    @Test void zipUnsafeMemberRejected(@TempDir Path dir) throws IOException {
        Path zip = writeZip(dir, Map.of("../evil", "x".getBytes()));
        Map<String, String> pins = Map.of("../evil", sha256("x".getBytes()));
        // member 集合は一致するが isSafe が落とす
        assertThrows(IOException.class, () -> Unpacker.unpack(
                zip, dir.resolve("rt"), artifact("zip", pins)));
        assertFalse(Files.exists(dir.resolve("evil")));
    }

    @Test void unpackIsIdempotentViaOkMarker(@TempDir Path dir) throws IOException {
        byte[] tool = "x".getBytes();
        Path zip = writeZip(dir, Map.of("bin/tool", tool));
        ManifestVerifier.Artifact art =
                artifact("zip", Map.of("bin/tool", sha256(tool)));
        Path dest = dir.resolve("rt");
        Unpacker.unpack(zip, dest, art);
        // archive を壊しても ok marker 済みなら再展開しない(エラーにならない)
        Files.writeString(zip, "corrupt");
        Unpacker.unpack(zip, dest, art);
        // 中身を壊すと verifyExtracted は落ちる
        Files.writeString(dest.resolve("bin/tool"), "tampered");
        assertThrows(IOException.class, () -> Unpacker.verifyExtracted(dest, art));
    }

    @Test void unpackRejectsEmptyMemberPins(@TempDir Path dir) throws IOException {
        Path zip = writeZip(dir, Map.of("bin/a", "x".getBytes()));
        assertThrows(IOException.class, () -> Unpacker.unpack(
                zip, dir.resolve("rt"), artifact("zip", Map.of())));
    }

    // ---------- tar.gz ----------

    @Test void tarExtractsFilesWithExecBit(@TempDir Path dir) throws IOException {
        byte[] tool = "tool".getBytes();
        byte[] lib = "lib".getBytes();
        Path tar = writeTarGz(dir, List.of(
                new TarEntry("rt/bin/tool", tool, 0755, '0', null),
                new TarEntry("rt/lib/x.so", lib, 0644, '0', null)));
        Map<String, String> pins = new LinkedHashMap<>();
        pins.put("bin/tool", sha256(tool));
        pins.put("lib/x.so", sha256(lib));
        Path dest = dir.resolve("out");

        Unpacker.unpack(tar, dest, artifact("tar.gz", pins));
        assertArrayEquals(tool, Files.readAllBytes(dest.resolve("bin/tool")));
        assertTrue(Files.isExecutable(dest.resolve("bin/tool")),
                "mode の exec bit を引き継ぐ");
        assertFalse(Files.isExecutable(dest.resolve("lib/x.so")));
    }

    @Test void tarSymlinkMaterializesAsVerifiedCopy(@TempDir Path dir)
            throws IOException {
        byte[] lib = "real-lib".getBytes();
        // libx.so → libx.so.0 → libx.so.0.1 の多段 link — 最終 bytes を
        // link 名へ copy する(link として残さない)。
        Path tar = writeTarGz(dir, List.of(
                new TarEntry("rt/lib/libx.so.0.1", lib, 0644, '0', null),
                new TarEntry("rt/lib/libx.so.0", new byte[0], 0644, '2',
                        "libx.so.0.1"),
                new TarEntry("rt/lib/libx.so", new byte[0], 0644, '2',
                        "libx.so.0")));
        Map<String, String> pins = new LinkedHashMap<>();
        pins.put("lib/libx.so.0.1", sha256(lib));
        pins.put("lib/libx.so.0", sha256(lib));   // copy 化 → 同じ sha
        pins.put("lib/libx.so", sha256(lib));
        Path dest = dir.resolve("out");

        Unpacker.unpack(tar, dest, artifact("tar.gz", pins));
        Path link = dest.resolve("lib/libx.so");
        assertFalse(Files.isSymbolicLink(link), "link は file として実体化");
        assertArrayEquals(lib, Files.readAllBytes(link));
        Unpacker.verifyExtracted(dest, artifact("tar.gz", pins));
    }

    @Test void tarEscapingLinkTargetRejected(@TempDir Path dir) throws IOException {
        byte[] lib = "x".getBytes();
        Path tar = writeTarGz(dir, List.of(
                new TarEntry("rt/lib/real.so", lib, 0644, '0', null),
                new TarEntry("rt/lib/evil", new byte[0], 0644, '2',
                        "../outside")));
        Map<String, String> pins = Map.of(
                "lib/real.so", sha256(lib),
                "lib/evil", sha256(lib));
        assertThrows(IOException.class, () -> Unpacker.unpack(
                tar, dir.resolve("out"), artifact("tar.gz", pins)));
    }

    @Test void tarLinkToNonMemberRejected(@TempDir Path dir) throws IOException {
        Path tar = writeTarGz(dir, List.of(
                new TarEntry("rt/a", "x".getBytes(), 0644, '0', null),
                new TarEntry("rt/l", new byte[0], 0644, '2', "ghost")));
        Map<String, String> pins = Map.of(
                "a", sha256("x".getBytes()),
                "l", sha256("x".getBytes()));
        assertThrows(IOException.class, () -> Unpacker.unpack(
                tar, dir.resolve("out"), artifact("tar.gz", pins)));
    }
}
