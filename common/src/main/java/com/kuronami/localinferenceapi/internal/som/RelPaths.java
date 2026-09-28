package com.kuronami.localinferenceapi.internal.som;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * package 相対 path の安全性。verify_manifest.py の is_safe_relpath /
 * first_symlink_component / resolves_inside の移植。
 *
 * parity メモ: PurePosixPath は空 segment を潰すので "a//b" は Python 側でも
 * safe。ここでも同じ(空 segment は ".." 検査の前に除く)。先頭の "/"・"\\"、
 * 任意の backslash、drive letter は引き続き拒否する。
 */
final class RelPaths {
    private static final Pattern DRIVE = Pattern.compile("^[A-Za-z]:");

    private RelPaths() {}

    static boolean isSafe(String rel) {
        if (rel == null || rel.isBlank()) return false;
        if (DRIVE.matcher(rel).find() || rel.startsWith("/") || rel.startsWith("\\")
                || rel.indexOf('\\') >= 0) {
            return false;
        }
        for (String part : rel.split("/", -1)) {
            if (part.equals("..")) return false;
        }
        return true;
    }

    /** rel の path 成分から空 segment を除いたもの(PurePosixPath parity)。 */
    static List<String> components(String rel) {
        List<String> parts = new ArrayList<>();
        for (String part : rel.split("/", -1)) {
            if (!part.isEmpty()) parts.add(part);
        }
        return parts;
    }

    /**
     * root/rel の成分鎖(中間 directory と leaf を含む)で最初の symlink。
     * 無ければ null。存在確認は Python の Path.is_symlink()/exists() と同じ
     * 意味で、存在しない最初の成分で止まる。
     */
    static Path firstSymlinkComponent(Path root, String rel) {
        Path cur = root;
        for (String part : components(rel)) {
            cur = cur.resolve(part);
            if (Files.isSymbolicLink(cur)) return cur;
            if (!Files.exists(cur)) break;
        }
        return null;
    }

    /**
     * realBase(real path 化済み)から target までの成分鎖で、実位置が
     * lexical path と一致しない最初の成分を返す。POSIX symlink と Windows
     * の junction / mount-point(reparse point)の両方を拾う:
     * {@link Files#isSymbolicLink} は junction(IO_REPARSE_TAG_MOUNT_POINT)
     * を見ないが、{@link Path#toRealPath} は junction を透過して resolve
     * するため、reparse point は必ず real≠lexical の不一致として現れる。
     * 削除は link 名だけに作用し参照先へ触れないので、呼出側は返された
     * 成分を {@link Files#delete} して作り直してよい。存在しない成分で
     * 止まる(それより深い成分は存在し得ない)。target は realBase 配下の
     * 管理 path 前提 — それ以外では relativize が意味を持たない。
     */
    static Path firstDivergingComponent(Path realBase, Path target) throws IOException {
        Path rel = realBase.relativize(target);
        // 呼出側は返された成分を name だけ消す — base 外を指す target から
        // ".." が混ざった path を delete 候補として返してはいけない
        for (Path seg : rel) {
            if (seg.toString().equals("..")) {
                throw new IOException("target escapes base: " + target);
            }
        }
        Path cur = realBase;
        for (Path seg : rel) {
            cur = cur.resolve(seg);
            if (Files.isSymbolicLink(cur)) return cur; // dangling も含む
            if (!Files.exists(cur, LinkOption.NOFOLLOW_LINKS)) return null;
            if (!cur.toRealPath().equals(cur)) return cur;
        }
        return null;
    }

    /**
     * 未作成かもしれない target の canonical containment: 存在する最も深い
     * 祖先を resolve し、残りを連結する。release 側の呼出は先に
     * firstSymlinkComponent を通すこと — この検査だけでは最深の実在成分より
     * 下の link を見ない。 */
    static boolean resolvesInsideLenient(Path root, Path target) throws IOException {
        Path realRoot = root.toRealPath();
        Path abs = target.toAbsolutePath().normalize();
        Path cursor = abs;
        List<String> tail = new ArrayList<>();
        while (cursor != null && !Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
            tail.add(0, cursor.getFileName().toString());
            cursor = cursor.getParent();
        }
        if (cursor == null) return false;
        Path resolved = cursor.toRealPath();
        for (String part : tail) resolved = resolved.resolve(part);
        return resolved.normalize().startsWith(realRoot);
    }
}
