package com.kuronami.localinferenceapi.internal.som;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * pin 済み runtime archive(tar.gz / zip)の安全な展開。staging root 配下の
 * {@code dest} に「manifest の members map で pin された bytes だけ」を
 * 置く — member 名・sha256 は検証済み manifest 由来であり、archive bytes
 * 自体は stage 時に sha256 照合済み。
 *
 * 規則:
 *  - member path は {@link RelPaths#isSafe} の後、manifest の members map
 *    に登録された名前と一致しなければ拒否する(未定義の余剰 file も拒否 —
 *    members map が upstream bytes の完全な写像であることが前提)。
 *  - tar の symlink/hardlink entry は link を作らず、参照先 member の
 *    verified bytes を link 名へ copy する(link 越しの escape 面を持たない)。
 *  - 展開は dest 隣の一時 dir へ行い、全 member の hash 照合後に atomic
 *    move で公開する。失敗時は一時 dir だけを消し、既存の良い展開物は
 *    そのまま残る。
 *  - POSIX では mode の exec bit を引き継ぐ(link の copy 元の mode を使う)。
 */
final class Unpacker {

    private Unpacker() {}

    /** 展開済み dir の ready marker — 中身は archive sha256。 */
    static final String OK_MARKER = "unpack.ok";

    /**
     * archive を dest へ展開し member bytes を検証する。既に同一 archive
     * sha256 の完了 marker がある場合は再展開しない(直前の spawn 検証が
     * member の再 hash を担う)。失敗時は例外、dest は作らない。
     */
    static void unpack(Path archive, Path dest, ManifestVerifier.Artifact art)
            throws IOException {
        if (art.members().isEmpty()) {
            throw new IOException("runtime artifact declares no member pins: " + art.path());
        }
        if (Files.isDirectory(dest) && Files.isRegularFile(dest.resolve(OK_MARKER))) {
            String ok = Files.readString(dest.resolve(OK_MARKER), StandardCharsets.UTF_8).trim();
            if (ok.equalsIgnoreCase(art.sha256())) return; // 同じ archive の完了済み展開
        }
        Path tmp = Files.createTempDirectory(dest.getParent(), "unpack-");
        boolean success = false;
        try {
            java.util.Set<String> landed;
            if ("tar.gz".equals(art.archive())) {
                landed = extractTarGz(archive, tmp);
            } else if ("zip".equals(art.archive())) {
                landed = extractZip(archive, tmp);
            } else {
                throw new IOException("runtime artifact has no archive format: " + art.path());
            }
            // members map は upstream bytes の完全な写像 — 未定義の余剰 file
            // は書き出さない方針に従い、着地した名前集合と pin 集合の完全一致
            // を要求する(多い・少ないどちらも拒否)。
            if (!landed.equals(art.members().keySet())) {
                throw new IOException("archive members do not match pin: "
                        + "extra=" + diff(landed, art.members().keySet())
                        + " missing=" + diff(art.members().keySet(), landed));
            }
            verifyMembers(tmp, art.members());
            Files.writeString(tmp.resolve(OK_MARKER),
                    art.sha256() + "\n", StandardCharsets.UTF_8);
            deleteTree(dest);
            try {
                Files.move(tmp, dest,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, dest);
            }
            success = true;
        } finally {
            if (!success) deleteTree(tmp);
        }
    }

    /**
     * 展開後の全 member を pin map と再照合する — spawn 直前の fail-closed
     * 検査。archive の hash は bytes の真正性を、ここは展開後 file の現時点
     * の改変を見る。
     */
    static void verifyExtracted(Path dest, ManifestVerifier.Artifact art)
            throws IOException {
        if (!Files.isDirectory(dest)) throw new IOException("runtime dir missing: " + dest);
        verifyMembers(dest, art.members());
    }

    private static void verifyMembers(Path dir, Map<String, String> members)
            throws IOException {
        for (Map.Entry<String, String> pin : members.entrySet()) {
            Path f = dir.resolve(pin.getKey());
            if (!Files.isRegularFile(f, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    || !f.toRealPath().startsWith(dir.toRealPath())) {
                throw new IOException("extracted member missing or unsafe: " + pin.getKey());
            }
            String digest = ManifestVerifier.sha256Hex(f);
            if (!digest.equalsIgnoreCase(pin.getValue())) {
                throw new IOException("extracted member hash mismatch: " + pin.getKey());
            }
        }
    }

    private static java.util.Set<String> diff(java.util.Set<String> a,
                                              java.util.Set<String> b) {
        java.util.Set<String> d = new java.util.TreeSet<>(a);
        d.removeAll(b);
        return d.size() > 8 ? d.stream().limit(8).collect(
                java.util.stream.Collectors.toCollection(java.util.TreeSet::new)) : d;
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted((a, b) -> b.compareTo(a)).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    // ---------- zip ----------

    private static java.util.Set<String> extractZip(Path archive, Path dest)
            throws IOException {
        Map<String, byte[]> written = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (e.isDirectory()) continue;
                String name = e.getName();
                // zip64 等で size が負のこともある — 実 bytes を読んでから判定
                try (InputStream in = zip.getInputStream(e)) {
                    byte[] bytes = in.readAllBytes();
                    if (bytes.length > 128L << 20) {
                        throw new IOException("zip member too large: " + name);
                    }
                    written.put(name, bytes);
                }
            }
        }
        writeAll(dest, written, Map.of(), List.of());
        return written.keySet();
    }

    // ---------- tar.gz ----------

    private static final int TAR_BLOCK = 512;

    private static java.util.Set<String> extractTarGz(Path archive, Path dest)
            throws IOException {
        // member name → (bytes, executable)
        Map<String, byte[]> files = new LinkedHashMap<>();
        Map<String, Boolean> exec = new LinkedHashMap<>();
        List<String[]> links = new ArrayList<>(); // [name, target]
        try (InputStream raw = new GZIPInputStream(Files.newInputStream(archive))) {
            String pendingName = null;
            while (true) {
                byte[] header = raw.readNBytes(TAR_BLOCK);
                if (header.length == 0) break;
                if (header.length != TAR_BLOCK) {
                    throw new EOFException("truncated tar header in " + archive);
                }
                if (isZeroBlock(header)) break;
                String name = pendingName != null ? pendingName : tarName(header);
                long size = tarOctal(header, 124, 12);
                int mode = (int) tarOctal(header, 100, 8);
                char type = (char) header[156];
                String linkname = tarString(header, 157, 100);
                if (size > 128L << 20) {
                    throw new IOException("tar member too large: " + name);
                }
                long dataBlocks = (size + TAR_BLOCK - 1) / TAR_BLOCK;
                byte[] data = raw.readNBytes((int) (dataBlocks * TAR_BLOCK));
                if (data.length != dataBlocks * TAR_BLOCK) {
                    throw new EOFException("truncated tar data in " + archive);
                }
                switch (type) {
                    case 'L' -> { // GNU longname — 次の entry の name
                        pendingName = new String(data, 0, (int) size, StandardCharsets.UTF_8)
                                .replace("\0", "");
                    }
                    case 'x', 'g' -> { // pax extended header — path override を拾う
                        String pax = new String(data, 0, (int) size, StandardCharsets.UTF_8);
                        String p = paxPath(pax);
                        if (p != null) pendingName = p;
                    }
                    case '0', '\0', '7' -> { // regular file
                        if (files.size() > 512) throw new IOException("tar too many members");
                        files.put(name, java.util.Arrays.copyOf(data, (int) size));
                        exec.put(name, (mode & 0111) != 0);
                    }
                    case '2', '1' -> links.add(new String[]{name, linkname}); // sym/hard link
                    case '5' -> {} // directory entry — 実 file の mkdir で足りる
                    default -> {} // その他の typeflag は skip(内容物としては使わない)
                }
            }
        }
        // すべての tar member は共通の top dir(例 llama-b10964/)を剥がす
        String top = null;
        for (String n : files.keySet()) {
            String t = n.contains("/") ? n.substring(0, n.indexOf('/')) : null;
            if (t == null) { top = null; break; }
            if (top == null) top = t;
            else if (!top.equals(t)) { top = null; break; }
        }
        Map<String, byte[]> stripped = new LinkedHashMap<>();
        Map<String, Boolean> strippedExec = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            String n = top != null ? e.getKey().substring(top.length() + 1) : e.getKey();
            stripped.put(n, e.getValue());
            strippedExec.put(n, exec.getOrDefault(e.getKey(), false));
        }
        List<String[]> strippedLinks = new ArrayList<>();
        for (String[] l : links) {
            String n = top != null && l[0].startsWith(top + "/")
                    ? l[0].substring(top.length() + 1) : l[0];
            strippedLinks.add(new String[]{n, l[1]});
        }
        writeAll(dest, stripped, strippedExec, strippedLinks);
        java.util.Set<String> landed = new java.util.LinkedHashSet<>(stripped.keySet());
        for (String[] l : strippedLinks) landed.add(l[0]);
        return landed;
    }

    private static boolean isZeroBlock(byte[] header) {
        for (byte b : header) if (b != 0) return false;
        return true;
    }

    private static String tarName(byte[] header) {
        String name = tarString(header, 0, 100);
        String prefix = tarString(header, 345, 155);
        return prefix.isEmpty() ? name : prefix + "/" + name;
    }

    private static String tarString(byte[] b, int off, int len) {
        int end = off;
        int max = off + len;
        while (end < max && b[end] != 0) end++;
        return new String(b, off, end - off, StandardCharsets.UTF_8);
    }

    private static long tarOctal(byte[] b, int off, int len) {
        long v = 0;
        int end = off + len;
        while (off < end) {
            byte c = b[off++];
            if (c == ' ' || c == 0) {
                if (v != 0) break;
                continue;
            }
            if (c < '0' || c > '7') break;
            v = (v << 3) + (c - '0');
        }
        return v;
    }

    /** pax extended header の `path` record を拾う(他の key は無視)。 */
    private static String paxPath(String pax) {
        for (String rec : pax.split("\n")) {
            int sp = rec.indexOf(' ');
            if (sp < 0) continue;
            String kv = rec.substring(sp + 1);
            if (kv.startsWith("path=")) return kv.substring(5);
        }
        return null;
    }

    // ---------- materialize ----------

    private static void writeAll(Path dest, Map<String, byte[]> files,
                                 Map<String, Boolean> exec, List<String[]> links)
            throws IOException {
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            if (!RelPaths.isSafe(e.getKey())) {
                throw new IOException("unsafe archive member: " + e.getKey());
            }
            Path out = dest.resolve(e.getKey());
            Files.createDirectories(out.getParent());
            Files.write(out, e.getValue());
            if (Boolean.TRUE.equals(exec.get(e.getKey()))) {
                setExec(out);
            }
        }
        // symlink/hardlink は「参照先 member bytes の copy」として実体化する。
        // link 名は safe、参照先は本 archive の member に限る → escape 面なし。
        // link → link の多段(libllama.dylib→libllama.0→libllama.0.4.1)は
        // 参照先が regular file に辿り着くまで辿る。
        Map<String, String> linkMap = new LinkedHashMap<>();
        for (String[] l : links) linkMap.put(l[0], l[1]);
        for (String[] link : links) {
            String name = link[0], target = link[1];
            if (!RelPaths.isSafe(name)) {
                throw new IOException("unsafe link member: " + name);
            }
            // linkname は「link を含む dir からの相対」が tar の規則。
            // 裸の target("libx.so.0" 等)は link の親 dir へ前置して member
            // 名へ写す — 上流 archive は link と target を同 dir に置く前提。
            // "../"・"\\"・絶対 target は引き続き拒否し、解決後が本 archive の
            // member(bytes pin 済み)に限るため escape 面は生じない。
            int slash = name.lastIndexOf('/');
            String resolved = (slash < 0 ? "" : name.substring(0, slash + 1))
                    + target;
            String raw = target;
            for (int hop = 0; hop < 8; hop++) {
                // 各 hop の生 linkname ごとに拒否する — 多段の途中に
                // ".." が入る経路を残さない。
                if (raw.contains("..") || raw.contains("\\")
                        || raw.startsWith("/")) {
                    throw new IOException("unsafe link target: " + name + " -> " + target);
                }
                String next = linkMap.get(resolved);
                if (next == null) break;
                int s2 = resolved.lastIndexOf('/');
                resolved = (s2 < 0 ? "" : resolved.substring(0, s2 + 1)) + next;
                raw = next;
            }
            byte[] bytes = files.get(resolved);
            if (bytes == null) {
                throw new IOException("link target not in archive: " + name + " -> " + target);
            }
            Path out = dest.resolve(name);
            Files.createDirectories(out.getParent());
            Files.write(out, bytes);
            Boolean x = exec.get(resolved);
            if (Boolean.TRUE.equals(x)) setExec(out);
        }
    }

    private static void setExec(Path f) throws IOException {
        try {
            Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rwxr-xr-x");
            Files.setPosixFilePermissions(f, perms);
        } catch (UnsupportedOperationException notPosix) {
            // Windows では exec bit は不要 — exe の存在だけで起動できる
        }
    }
}
