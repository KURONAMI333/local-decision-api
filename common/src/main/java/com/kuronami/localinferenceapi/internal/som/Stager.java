package com.kuronami.localinferenceapi.internal.som;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kuronami.localinferenceapi.internal.som.ManifestVerifier.Artifact;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 2: content-addressed staging layer。{@link VerifiedManifest} —
 * ManifestVerifier.verifyManifest の偽造不能な token — しか受け付けないため、
 * 未検証の metadata を public API 経由で stage することはできない。commit
 * marker は caller 供給の digest でなく vm.manifestSha256() を記録する。
 *
 * {@code <root>} 配下の layout:
 *   {@code staging/<sha256>.part}   — resume 可能な部分 download。消しても安全
 *   {@code artifacts/<sha256>}      — size+sha256 の完全一致後だけ公開される
 *   current.json            — commit marker。最後に atomic move で書く
 *
 * role による取得元:
 *   - "license" — JAR 同梱 resource(EmbeddedSource)からのみ。notices の正本は
 *     publisher が同梱した bytes であり、manifest の upstream_url は
 *     provenance 記録。resource 欠落や bytes 不一致は失敗。
 *   - "runtime" / "weights" — pin 済み upstream_url を Fetcher 経由で取得。
 *
 * 保証(POSIX で test 済み):
 *  - 部分 bytes は artifacts/ 下に出ない — 完全に hash された file のみ
 *  - current.json は全 set が検証された時だけ存在する
 *  - atomic 公開は必須: filesystem が atomic move を供給できなければ staging
 *    は失敗する(以前 commit された current.json は失敗時もそのまま残る)
 *  - artifact path は全 I/O の前に safe relpath として再検査される
 *  - artifacts/・staging/・current.json に link が残っていれば link 自体を
 *    外して本物の dir/file に作り直す — 検出した link は決して辿らない
 *    (敵対的な同時再植えへの完全な防御ではない — 末尾の残存リスクを参照)。
 *    POSIX symlink は isSymbolicLink で、Windows junction/mount-point は
 *    toRealPath の lexical 不一致で検出する(toRealPath は reparse point
 *    を透過する)。hardlink は同一 inode で link に見えないため、書込み前に
 *    pre-existing の .part が lone regular file かを unix:nlink で確かめ、
 *    判別不能な FS では partial を信用せず取り直す。
 *    管理 dir 配下の link の削除は対象物へ触れず常に安全で、読み取り専用の
 *    {@link #inspect} が残存 link を TAMPERED として報告する側に立つ
 *  - stale marker(異なる manifest digest を持つ旧 commit)は読み取り専用の
 *    inspect が STALE と判定する — stage はそれを消さず、新しい commit が
 *    成功した時だけ atomic に置き換える。途中失敗は旧 commit の記録を残す
 *  - fetch は宣言 size を1 byte も超えて書かず、resume は bounded
 *    (4 fetch iteration まで)
 *  - resume の .part 追記は lone 検査を通った inode を open した fd に対して
 *    行う — network 待ちの間に .part の name が差替えられても(植えられた
 *    hardlink や rename)書先は検証済み inode のまま変わらない。open 直後に
 *    name が同じ lone inode を指すか再照合し、外れていれば書込まずやり直す
 *  - caller が real 化済みの containment base(SomPackage は real game dir)
 *    を渡す経路では、root 末端への managed 成分鎖を dir 作成の前後で
 *    divergence 再検査し、toRealPath 直後にも resolved root が base 配下か
 *    再照合する — root() 通過後・検査→resolve の隙間に植えられた
 *    link/junction が toRealPath を透過しても、resolved した外側 dir を
 *    staged bytes の基点にしない。inspect 側も同じ窓の照合を持ち、
 *    resolve した外側 dir の内容を COMMITTED として返さない
 *  - 残存リスク(明示 — 絶対の囲い込みではない): settled root は inode
 *    ではなく resolve 済みの *name* であり、以後の各 syscall は name を
 *    再 traversal する。Java の name-based I/O には dirfd/openat
 *    (O_BENEATH)相当が無く、同一 uid が動かせる dir 上で managed tail
 *    を同時に再植えする敵への完全な防御は不可能。settle 後の replant
 *    は .part・publish・commit marker を外側 dir へ向け直せる(実証済み
 *    — R-1)。その縮小として artifact loop 各回の頭と commit 公開後の
 *    最終照合で実位置を再検査し、外側へ落ちた commit は「成功」と
 *    報告しない。最終照合は位置に加えて、内側に resolve した marker の
 *    manifest_sha256 が今回の manifest と一致することを要求する —
 *    marker move の窓だけ外側へ向け verify 前に戻す blink で今回の
 *    marker が外側へ落ちても、内側に残る前回 commit の stale marker
 *    を借りた偽の commit 成功にはならない(committed()==true は
 *    「この manifest の marker が内側に resolve した」を意味する)。
 *    ただし外側に落ちた pin-authentic bytes は残り(litter。同 uid が
 *    書ける場所なのでここで消さない)、照合→使用の窓や、照合中の各
 *    name traversal を往復する replant の検出不能な残存窓も残る。
 *    name-based containment は敵対的な同時再植えへの best-effort で
 *    あり、「link 越しの書込みは絶対にしない」という絶対保証ではない
 */
public final class Stager {

    private static final int MAX_RESUME_ATTEMPTS = 3;
    private static final int BUFFER = 128 * 1024;
    /** commit marker の sanity bound — marker は小さい JSON でしかない。 */
    private static final long MARKER_MAX_BYTES = 64L << 10;

    /** atomic 公開の seam — 試験が atomic-move 非対応の filesystem を再現する
     *  ために注入できる。製品は ATOMIC を使う。 */
    interface Mover { void move(Path src, Path dst) throws IOException; }

    private static final Mover ATOMIC = (src, dst) ->
            Files.move(src, dst,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);

    /** settle loop の divergence 検査→{@code toRealPath} の窓へ managed tail
     *  の replant を撃つ試験 seam — 窓の内側の link 差替えを deterministic
     *  に再現するために注入できる。製品は NONE。 */
    interface SettleProbe { void run() throws IOException; }

    private static final SettleProbe NONE = () -> {};

    /** inspect の divergence 検査→{@code toRealPath} の窓へ managed tail
     *  の replant を撃つ試験 seam — {@link SettleProbe} と対称の位置で
     *  窓の内側の link 差替えを deterministic に再現する。製品は
     *  NONE_INSPECT。 */
    interface InspectProbe { void run() throws IOException; }

    private static final InspectProbe NONE_INSPECT = () -> {};

    public record Result(List<String> failures, Path commitMarker) {
        public boolean committed() { return commitMarker != null; }
    }

    /**
     * 既に stage された package の検査結果。{@link Status#COMMITTED} の時だけ
     * files に宣言 path → staged file の写像が入る。files の key は検証済み
     * manifest の path であり、commit marker の role field は informational
     * として扱う — runtime artifact の特定は manifest.artifacts() の role
     * と files の写像で行うこと(S8b 契約)。
     *
     * S8b 契約(spawn/health gate 側): files() の path が返す実位置は
     * resolve 時点のもので、その後の managed tail 再植えを name-based
     * 検査では塞げない。name 経由の exec/spawn は使用時に path を再
     * traverse するため、consumer は artifact を使う直前に (a) 使う各
     * file を toRealPath で再 resolve して containment base 配下にある
     * ことを再照合し、(b) その file の bytes を manifest の sha256 と
     * 再照合(rehash)する — bytes の再照合だけでは場所の差替えを検出
     * できず、場所の照合だけでは bytes の差替えを検出できない。どちら
     * かが崩れれば spawn しない(fail-closed)。
     */
    public record Inspection(Status status, List<String> failures,
                             Map<String, Path> files) {
        public enum Status { COMMITTED, ABSENT, STALE, TAMPERED }
        public boolean usable() { return status == Status.COMMITTED; }
    }

    private Stager() {}

    public static Result stage(VerifiedManifest manifest, Path root,
                               Fetcher fetcher, EmbeddedSource embedded) {
        return stage(manifest, root, null, fetcher, embedded, ATOMIC);
    }

    /**
     * 製品経路(SomPackage)用: {@code containmentBase} = caller が real 化して
     * 保証する root の上位 dir(real game dir)。null なら root 自体を
     * caller-trusted と看做す(直接呼出の試験等 — root 鎖の管理は caller)。
     *
     * base 非 null の時、{@code SomPackage.root} の検査とここの使用の隙間に
     * managed tail へ植えられた link/junction を拾うため、dir 作成の前後で
     * {@link RelPaths#firstDivergingComponent} を再検査する。junction は
     * isSymbolicLink に出ないが toRealPath が透過するので real≠lexical の
     * 不一致として必ず現れる — resolved した外側 dir を containment の基点
     * にしない(F-B)。検査→resolve の隙間に植えられた link は、toRealPath
     * 直後に resolved root を base と再照合して拾う(N-1)。再植えが消えない
     * 競合は bounded retry の後失敗に倒す。
     */
    static Result stage(VerifiedManifest manifest, Path root, Path containmentBase,
                        Fetcher fetcher, EmbeddedSource embedded) {
        return stage(manifest, root, containmentBase, fetcher, embedded, ATOMIC);
    }

    /** 試験 seam 付き: settle loop の検査→resolve 窓へ replant を注入する。 */
    static Result stage(VerifiedManifest manifest, Path root, Path containmentBase,
                        Fetcher fetcher, EmbeddedSource embedded,
                        SettleProbe settleProbe) {
        return stage(manifest, root, containmentBase, fetcher, embedded, ATOMIC,
                settleProbe);
    }

    static Result stage(VerifiedManifest manifest, Path root, Fetcher fetcher,
                        EmbeddedSource embedded, Mover mover) {
        return stage(manifest, root, null, fetcher, embedded, mover);
    }

    static Result stage(VerifiedManifest manifest, Path root, Path containmentBase,
                        Fetcher fetcher, EmbeddedSource embedded, Mover mover) {
        return stage(manifest, root, containmentBase, fetcher, embedded, mover, NONE);
    }

    static Result stage(VerifiedManifest manifest, Path root, Path containmentBase,
                        Fetcher fetcher, EmbeddedSource embedded, Mover mover,
                        SettleProbe settleProbe) {
        List<String> failures = new ArrayList<>();
        Path base = null; // caller が real 化して保証する containment base
        try {
            base = containmentBase != null ? containmentBase.toRealPath() : null;
            if (base != null && !root.startsWith(base)) {
                // base 配下でない target には divergence 検査も削除もしない
                failures.add("staging root escapes containment base: " + root);
                return new Result(failures, null);
            }
            // root 自体が link なら辿らず消す — caller が link 越しの dir を
            // 指していても、staged bytes を別場所へ流さない。base 付き経路では
            // 中間成分の junction も divergence で拾う。unlink→create の隙間に
            // 再植えされた成分も同じ検査で拾う(bounded — 消えない競合は失敗に倒す)
            boolean settled = false;
            for (int i = 0; i < 3 && !settled; i++) {
                Path bad = base != null
                        ? RelPaths.firstDivergingComponent(base, root)
                        : (Files.isSymbolicLink(root) ? root : null);
                if (bad != null) { Files.delete(bad); continue; }
                Files.createDirectories(root);   // まず実在させる
                bad = base != null
                        ? RelPaths.firstDivergingComponent(base, root)
                        : (Files.isSymbolicLink(root) ? root : null);
                if (bad != null) { Files.delete(bad); continue; }
                settleProbe.run();
                Path resolved = root.toRealPath(); // 親鎖の link を実体へ固定
                if (base != null && !resolved.startsWith(base)) {
                    // 直上の検査と resolve の隙間に植えられた tail link が
                    // resolved root を base の外へ逃がす — 外側を staged
                    // bytes の基点にせず、lexical 側の diverging 成分を
                    // name だけ外して replant としてやり直す(bounded)
                    bad = RelPaths.firstDivergingComponent(base, root);
                    if (bad != null) Files.delete(bad);
                    continue;
                }
                root = resolved;
                settled = true;
            }
            if (!settled) {
                throw new IOException("staging root replanted as link repeatedly");
            }
        } catch (IOException e) {
            failures.add("cannot create staging root: " + e.getMessage());
            return new Result(failures, null);
        }

        Path artifactsDir = root.resolve("artifacts");
        Path stagingDir = root.resolve("staging");
        Path marker = root.resolve("current.json");

        // 管理 path に残る symlink を link 自体だけ外す(対象へは触れない)。
        try {
            for (Path managed : List.of(artifactsDir, stagingDir, marker)) {
                if (Files.isSymbolicLink(managed)) Files.delete(managed);
            }
            Files.createDirectories(artifactsDir);
            Files.createDirectories(stagingDir);
            // isSymbolicLink を潜る Windows junction/mount-point と、
            // unlink→create の隙間に再植えされた成分を拾う: toRealPath は
            // reparse point を透過して resolve するので、real path が
            // lexical と一致しない最初の成分(link/junction 自体)を name
            // だけ消して作り直す — 削除は参照先へ触れない。それでも diverge
            // するなら競合として失敗する。
            for (Path dir : List.of(artifactsDir, stagingDir)) {
                Path bad = RelPaths.firstDivergingComponent(root, dir);
                if (bad == null) continue;
                Files.delete(bad);
                Files.createDirectories(dir);
                if (RelPaths.firstDivergingComponent(root, dir) != null) {
                    failures.add("staging dir escapes containment: " + dir);
                    return new Result(failures, null);
                }
            }
        } catch (IOException e) {
            failures.add("cannot create staging dirs: " + e.getMessage());
            return new Result(failures, null);
        }

        // stale marker(別 manifest digest の旧 commit)は開始時に消さない:
        // 検査は常に inspect 側の STALE 判定に委ね、marker は新しい commit が
        // 成功した時にだけ atomic に置き換わる。stage 途中で失敗しても旧
        // commit の記録は残り、「以前 commit された package が現 pin と
        // 一致しない」という状態を失わない。

        List<Artifact> staged = new ArrayList<>();
        for (Artifact art : manifest.artifacts()) {
            if (base != null) {
                // R-1 narrowing: settled root は resolve 済みの *name* であり、
                // settle 後に managed tail が再植えされると以後の name
                // traversal は外側へ向く。次の artifact の書込みへ進む前に
                // root の実位置を再照合し、残る replant を早く検出して
                // fail-closed — 以後の write(.part・publish・marker)が
                // 外側へ出るのを防ぐ。単一 artifact 内の窓は残るため
                // commit 後の最終照合と併用する(窓の完全閉塞は不可能 —
                // Java には dirfd/openat 相当がない)
                try {
                    if (!root.toRealPath().startsWith(base)) {
                        failures.add("staging root escaped containment "
                                + "mid-stage: " + root);
                        return new Result(failures, null);
                    }
                } catch (IOException e) {
                    failures.add("staging root unresolvable mid-stage: "
                            + e.getMessage());
                    return new Result(failures, null);
                }
            }
            if (!RelPaths.isSafe(art.path())) {
                failures.add("UNSAFE path refused by stager: " + art.path());
                continue;
            }
            String sha = art.sha256().toLowerCase();
            Path published = artifactsDir.resolve(sha);
            try {
                if (Files.isSymbolicLink(published)) Files.delete(published); // link は実体と看做さない
                if (Files.isRegularFile(published, LinkOption.NOFOLLOW_LINKS)
                        // 別名を持つ hardlink は公開済みと看做さず atomic move
                        // で name を差し替える(victim inode へは触れない)。
                        // nlink を読めない FS(-1)は size/hash 検査に委ねる
                        && linkCount(published) <= 1
                        && Files.size(published) == art.size()
                        && sha.equals(ManifestVerifier.sha256Hex(published))) {
                    staged.add(art); // 検証済みの内容が既に公開済み
                    continue;
                }
            } catch (IOException e) {
                failures.add("unreadable published artifact " + sha + ": " + e.getMessage());
                continue;
            }
            String err = switch (art.role()) {
                case "license" -> stageEmbedded(art, published, stagingDir, embedded, mover);
                case "runtime", "weights" -> downloadOne(art, published, stagingDir, fetcher, mover);
                default -> "unknown role refused by stager: " + art.role();
            };
            if (err != null) failures.add(err);
            else staged.add(art);
        }

        if (!failures.isEmpty()) {
            return new Result(failures, null); // marker 無し — set が不完全
        }

        // commit marker は最後に atomic に書く: consumer はその存在を
        // 「artifacts/ 下の全 artifact が pin された manifest digest と一致」
        // と解釈する。書込み失敗は既 commit の marker を壊さない。
        // entry は {path, sha256, role} を持つ。role field は informational
        // であり認証ではない — consumer(S8b)は marker の role を信用せず、
        // 検証済み manifest.artifacts() の role と Inspection.files() の
        // 写像から runtime artifact を特定する。
        JsonObject commit = new JsonObject();
        commit.addProperty("manifest_sha256", manifest.manifestSha256());
        JsonArray arr = new JsonArray();
        for (Artifact art : staged) {
            JsonObject entry = new JsonObject();
            entry.addProperty("path", art.path());
            entry.addProperty("sha256", art.sha256().toLowerCase());
            entry.addProperty("role", art.role());
            arr.add(entry);
        }
        commit.add("artifacts", arr);
        try {
            atomicWrite(marker, commit.toString().getBytes(StandardCharsets.UTF_8), mover);
        } catch (IOException e) {
            failures.add("cannot publish commit marker: " + e.getMessage());
            return new Result(failures, null);
        }
        // R-1 narrowing(最終照合): settle 後に managed tail が再植えされると
        // .part・publish・marker が外側へ落ちうる — 実証済み。commit 後に
        // marker と全 staged file の実位置が base 配下かを照合し、外側へ
        // 落ちた commit を「成功」と報告しない(fail-closed な検出)。
        // 外側へ落ちた bytes/marker はここでは消さない — 同 uid が書ける
        // 場所への pin-authentic litter であり、参照先を不用意に触らない。
        // 本照合後の再植えと、照合までに窓を往復した replant は name-based
        // I/O では検出も防止もできない残存リスク(dirfd 相当が無い) —
        // 絶対の囲い込みではなく、commit 報告の真実性だけを守る。
        if (base != null) {
            try {
                Path realMarker = marker.toRealPath();
                if (!realMarker.startsWith(base)) {
                    failures.add("commit marker escaped containment: " + marker);
                    return new Result(failures, null);
                }
                // marker move の窓だけ外側へ向け verify 前に戻す blink で、
                // 今回の marker が外側へ落ち内側に前回 commit の stale
                // marker が残る場合、位置照合だけでは「内側に resolve する
                // marker」を通して committed()==true の偽成功になった
                // (実証済み — closeout review)。内側 marker の
                // manifest_sha256 が今回 stage した manifest と一致する
                // ことを要求する — marker digest は認証でないが、「この
                // manifest の commit marker が内側に在る」ことの確認には
                // なる。resolved した path で読み、resolve 済みの実位置と
                // 同じ file に対して照合する。
                if (!markerIdentifiesManifest(realMarker, manifest)) {
                    failures.add("commit marker inside containment does not "
                            + "match staged manifest: " + marker);
                    return new Result(failures, null);
                }
                for (Artifact art : staged) {
                    Path f = artifactsDir.resolve(art.sha256().toLowerCase());
                    if (!f.toRealPath().startsWith(base)) {
                        failures.add("staged artifact escaped containment: " + f);
                        return new Result(failures, null);
                    }
                }
            } catch (IOException e) {
                failures.add("post-commit containment verify failed: "
                        + e.getMessage());
                return new Result(failures, null);
            }
        }
        return new Result(failures, marker);
    }

    /**
     * staged package の検査 — 読み取り専用で何も修復しない。
     * marker 不在 → ABSENT。marker digest ≠ 現在の pin → STALE(再 stage が
     * 正当)。marker/file への symlink・欠落・size/hash 不一致・marker と
     * manifest の artifact 集合の不一致 → TAMPERED。全て通れば COMMITTED。
     *
     * marker digest は秘密ではない(manifest は公開 JAR に入る)ため、marker
     * 自体は認証にならない。実際の防壁は artifact sha → file bytes の
     * content-addressed 再検証であり、ここで必ず実行する。
     *
     * containmentBase 付き経路では、resolved root が base の外なら中身を
     * 検査せず TAMPERED — COMMITTED で返す files() は resolve 時点で
     * base 配下を指す(resolve 後の再植えは name-based 検査では塞げない
     * 残存リスク — class javadoc 参照)。
     */
    public static Inspection inspect(Path root, VerifiedManifest manifest) {
        return inspect(root, null, manifest);
    }

    /**
     * {@code containmentBase} 付き製品経路: stage と同じく、managed tail へ
     * 植えられた link/junction が残るなら resolved した外側 dir を検査せず
     * TAMPERED に倒す(inspect は修復しない — 報告に徹する)。検査→
     * toRealPath の隙間に植えられた link は、resolve 直後に realRoot を
     * base と再照合して拾う — 外側 dir の内容を COMMITTED として返さず、
     * files() は空のまま TAMPERED を返す。
     */
    static Inspection inspect(Path root, Path containmentBase,
                              VerifiedManifest manifest) {
        return inspect(root, containmentBase, manifest, NONE_INSPECT);
    }

    /** 試験 seam 付き: inspect の検査→resolve 窓へ replant を注入する。 */
    static Inspection inspect(Path root, Path containmentBase,
                              VerifiedManifest manifest, InspectProbe inspectProbe) {
        List<String> failures = new ArrayList<>();
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return new Inspection(Inspection.Status.ABSENT, failures, Map.of());
        }
        Path realRoot;
        try {
            Path base = null;
            if (containmentBase != null) {
                base = containmentBase.toRealPath();
                if (!root.startsWith(base)
                        || RelPaths.firstDivergingComponent(base, root) != null) {
                    failures.add("staging tail escapes containment: " + root);
                    return new Inspection(Inspection.Status.TAMPERED,
                            failures, Map.of());
                }
            }
            inspectProbe.run();
            realRoot = root.toRealPath(); // 親鎖の link を解いて実体へ固定
            if (base != null && !realRoot.startsWith(base)) {
                // stage と対称の窓: 直上の検査と resolve の隙間に植えられた
                // tail link が realRoot を base の外へ逃がす — 外側 dir の
                // 内容を検査結果として返さず TAMPERED + 空 files に倒す。
                // 修復はしない(読み取り専用) — link は残す
                failures.add("staging root resolves outside containment: "
                        + realRoot);
                return new Inspection(Inspection.Status.TAMPERED,
                        failures, Map.of());
            }
        } catch (IOException e) {
            failures.add("staging root unreadable: " + e.getMessage());
            return new Inspection(Inspection.Status.TAMPERED, failures, Map.of());
        }
        Path marker = realRoot.resolve("current.json");
        Path artifactsDir = realRoot.resolve("artifacts");

        if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
            return new Inspection(Inspection.Status.ABSENT, failures, Map.of());
        }
        if (Files.isSymbolicLink(marker) || !Files.isRegularFile(marker)) {
            failures.add("commit marker is not a regular file");
            return new Inspection(Inspection.Status.TAMPERED, failures, Map.of());
        }
        if (Files.isSymbolicLink(artifactsDir)) {
            failures.add("SYMLINK at artifacts dir: " + artifactsDir);
            return new Inspection(Inspection.Status.TAMPERED, failures, Map.of());
        }
        // junction/mount-point(reparse)は isSymbolicLink に出ない —
        // toRealPath は junction を透過するため、管理 dir の real path が
        // lexical と一致しなければ escape と判定する
        try {
            if (RelPaths.firstDivergingComponent(realRoot, artifactsDir) != null) {
                failures.add("artifacts dir escapes containment: " + artifactsDir);
                return new Inspection(Inspection.Status.TAMPERED, failures, Map.of());
            }
        } catch (IOException e) {
            failures.add("artifacts dir containment check failed: " + e.getMessage());
            return new Inspection(Inspection.Status.TAMPERED, failures, Map.of());
        }

        JsonObject commit;
        try {
            // marker は小さい JSON であるべき — 巨大 file の全読込みと、
            // 検証前の bytes で再帰 parser を落とす深い nesting を前置で遮る。
            // size 検査と全読込みの隙間に肥大化する swap でも bound を破れない
            // よう、cap+1 までしか読まない bounded read で持ち上げる
            byte[] markerBytes = ManifestVerifier.readBounded(marker, MARKER_MAX_BYTES);
            if (markerBytes.length > MARKER_MAX_BYTES) {
                failures.add("commit marker oversized: " + markerBytes.length + " bytes");
                return new Inspection(Inspection.Status.TAMPERED, failures, Map.of());
            }
            if (!ManifestVerifier.jsonDepthBounded(markerBytes)) {
                failures.add("commit marker malformed: JSON nesting exceeds depth bound");
                return new Inspection(Inspection.Status.TAMPERED, failures, Map.of());
            }
            JsonElement parsed = JsonParser.parseString(
                    new String(markerBytes, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) throw new IllegalStateException("not an object");
            commit = parsed.getAsJsonObject();
        } catch (IOException | RuntimeException bad) {
            failures.add("commit marker malformed: " + bad.getMessage());
            return new Inspection(Inspection.Status.TAMPERED, failures, Map.of());
        }
        JsonElement digestEl = commit.get("manifest_sha256");
        if (digestEl == null || !digestEl.isJsonPrimitive()
                || !digestEl.getAsJsonPrimitive().isString()) {
            failures.add("commit marker missing manifest_sha256");
            return new Inspection(Inspection.Status.TAMPERED, failures, Map.of());
        }
        if (!digestEl.getAsString().equalsIgnoreCase(manifest.manifestSha256())) {
            failures.add("commit marker is STALE: " + digestEl.getAsString()
                    + " != pinned " + manifest.manifestSha256());
            return new Inspection(Inspection.Status.STALE, failures, Map.of());
        }

        // marker が宣言する集合は検証済み manifest の集合と正確に一致する
        // 必要がある — digest を知っていれば書ける marker への追加・削除を
        // ここで検出する(digest は秘密ではないので集合一致が実質の鍵)。
        JsonElement arrEl = commit.get("artifacts");
        Map<String, String> declared = new LinkedHashMap<>();
        for (Artifact art : manifest.artifacts()) {
            declared.put(art.path(), art.sha256().toLowerCase());
        }
        Map<String, String> marked = new LinkedHashMap<>();
        if (arrEl != null && arrEl.isJsonArray()) {
            for (JsonElement e : arrEl.getAsJsonArray()) {
                if (!e.isJsonObject()) continue;
                JsonObject en = e.getAsJsonObject();
                JsonElement p = en.get("path");
                JsonElement s = en.get("sha256");
                if (p != null && p.isJsonPrimitive() && p.getAsJsonPrimitive().isString()
                        && s != null && s.isJsonPrimitive() && s.getAsJsonPrimitive().isString()) {
                    marked.put(p.getAsString(), s.getAsString().toLowerCase());
                }
            }
        }
        if (!marked.equals(declared)) {
            failures.add("commit marker artifact set does not match verified manifest");
            return new Inspection(Inspection.Status.TAMPERED, failures, Map.of());
        }

        Map<String, Path> files = new LinkedHashMap<>();
        for (Artifact art : manifest.artifacts()) {
            Path file = artifactsDir.resolve(art.sha256().toLowerCase());
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                failures.add("MISSING staged artifact " + art.path());
                continue;
            }
            // hardlink で別名を持つ staged file は commit 済み集合の排他性を
            // 破る — bytes は hash で確かめられるが、別名側からの書換えで
            // 検証後に変わり得る。nlink を読めない FS(-1)では検査しない
            long links = linkCount(file);
            if (links > 1) {
                failures.add("LINKED staged artifact " + art.path()
                        + " (nlink=" + links + ")");
                continue;
            }
            try {
                long size = Files.size(file);
                if (size != art.size()) {
                    failures.add("SIZE " + art.path() + ": " + size + " != " + art.size());
                    continue;
                }
                String digest = ManifestVerifier.sha256Hex(file);
                if (!digest.equalsIgnoreCase(art.sha256())) {
                    failures.add("HASH " + art.path() + ": " + digest + " != " + art.sha256());
                    continue;
                }
            } catch (IOException e) {
                failures.add("unreadable staged artifact " + art.path() + ": " + e.getMessage());
                continue;
            }
            files.put(art.path(), file);
        }

        if (!failures.isEmpty()) {
            return new Inspection(Inspection.Status.TAMPERED, failures, Map.of());
        }
        return new Inspection(Inspection.Status.COMMITTED, failures, files);
    }

    /**
     * commit 後照合用: base 内に resolve した marker が {@code manifest} の
     * commit marker かを bounded read + parse で確かめる。size/depth bound
     * は inspect の marker 検査と同じ前置を置き、malformed・oversized・
     * digest 不一致は false — 信用しない。読取り失敗は IOException を
     * 投げ、呼出し側の fail-closed な失敗に畳む。
     */
    private static boolean markerIdentifiesManifest(Path realMarker,
                                                    VerifiedManifest manifest)
            throws IOException {
        byte[] bytes = ManifestVerifier.readBounded(realMarker, MARKER_MAX_BYTES);
        if (bytes.length > MARKER_MAX_BYTES
                || !ManifestVerifier.jsonDepthBounded(bytes)) {
            return false;
        }
        try {
            JsonElement parsed = JsonParser.parseString(
                    new String(bytes, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) return false;
            JsonElement digest = parsed.getAsJsonObject().get("manifest_sha256");
            return digest != null && digest.isJsonPrimitive()
                    && digest.getAsJsonPrimitive().isString()
                    && digest.getAsString()
                            .equalsIgnoreCase(manifest.manifestSha256());
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    /** license artifact: JAR 同梱 resource から .part 経由で公開する。 */
    private static String stageEmbedded(Artifact art, Path published, Path stagingDir,
                                        EmbeddedSource embedded, Mover mover) {
        Path part = stagingDir.resolve(art.sha256().toLowerCase() + ".part");
        try (InputStream in = embedded.open(art.path())) {
            if (in == null) {
                return "embedded license resource missing: " + art.path();
            }
            // 植えられた link/hardlink 越しに書込まない — embedded bytes は
            // 即時再供給できるので resume を要さず、残存の part 名は unlink
            // して CREATE_NEW で作り直す。CREATE_NEW は「存在すれば失敗」を
            // atomic にするため、delete 後の再植え競合でも link 越しの
            // open にならない。name の削除は参照先 inode の内容へ触れない。
            Files.deleteIfExists(part);
            byte[] buf = new byte[BUFFER];
            long total = 0;
            try (OutputStream out = Files.newOutputStream(part,
                    StandardOpenOption.CREATE_NEW)) {
                for (int n; total < art.size() && (n = in.read(buf)) != -1;) {
                    int writable = (int) Math.min(n, art.size() - total);
                    out.write(buf, 0, writable); // 宣言 size を超える byte は書かない
                    total += writable;
                }
            }
            if (total != art.size() || !verifyPart(part, art)) {
                Files.deleteIfExists(part);
                return "embedded license bytes do not match pin: " + art.path();
            }
            return publish(part, published, art, mover);
        } catch (IOException e) {
            return "embedded license stage failed for " + art.path() + ": " + e.getMessage();
        }
    }

    /** runtime/weights artifact の download + 検証 + 公開。null = 成功。 */
    private static String downloadOne(Artifact art, Path published, Path stagingDir,
                                      Fetcher fetcher, Mover mover) {
        URI uri;
        try {
            if (art.upstreamUrl() == null) return "no upstream_url for " + art.path();
            uri = new URI(art.upstreamUrl());
        } catch (URISyntaxException e) {
            return "bad upstream_url for " + art.path() + ": " + e.getMessage();
        }

        Path part = stagingDir.resolve(art.sha256().toLowerCase() + ".part");
        try {
            // F-A: 追記は open した fd に対して行い、name 経由では open しない。
            // fd は open 時点の inode を束ねるため、その後の .part name 差替え
            // (植えられた hardlink / rename)で書先を別 inode へ変えられない —
            // 実証された「lone 検査 → APPEND open の間の network 待ちで name を
            // victim へ差替え、追記が victim inode を書き換える」攻撃を閉じる。
            FileChannel channel = null; // pin した inode への書込み口
            Object pinnedKey = null;    // pin した inode の fileKey
            long have = 0;              // pinned inode に既に入っている byte 数
            try {
                for (int attempt = 0; attempt <= MAX_RESUME_ATTEMPTS; attempt++) {
                    if (channel == null) {
                        // 植えられた link 越しに書込んではいけない — symlink は
                        // 本体を消す。hardlink は同一 inode を共有するだけで
                        // link としては見えないため、pre-existing の part が
                        // lone regular file でなければ name だけ消して取り直す
                        // (name の削除は victim inode の内容へ触れない)。nlink
                        // を読めない FS では partial を信用せず最初から取り直す
                        // (安全側)。
                        if (Files.isSymbolicLink(part)) Files.delete(part);
                        if (Files.exists(part, LinkOption.NOFOLLOW_LINKS)
                                && !isLoneRegularFile(part)) {
                            Files.delete(part);
                        }
                        have = Files.exists(part) ? Files.size(part) : 0;
                        if (have > art.size()) {
                            Files.delete(part); // 超過した古い partial — 信用しない
                            have = 0;
                        }
                        if (have == art.size()) {
                            // download 済みで未公開 = 未検証。いま検証する
                            if (verifyPart(part, art)) {
                                return publish(part, published, art, mover);
                            }
                            Files.delete(part);
                            have = 0;
                        }
                        try {
                            if (have > 0) {
                                // resume: lone 検査を通った inode を network
                                // 待ちの前に open で pin する。検査→open の隙に
                                // name が差替えられていれば open 直後の照合で
                                // 検出し、書込まずやり直す
                                pinnedKey = fileKey(part);
                                channel = FileChannel.open(part,
                                        StandardOpenOption.WRITE,
                                        StandardOpenOption.APPEND);
                                if (pinnedKey == null
                                        || !pinnedKey.equals(fileKey(part))
                                        || !isLoneRegularFile(part)) {
                                    // 判別不能な fileKey 差異を信用しない
                                    Files.deleteIfExists(part);
                                    channel.close();
                                    channel = null;
                                    pinnedKey = null;
                                    continue;
                                }
                            } else {
                                // fresh: 「存在すれば失敗」が atomic なので
                                // 再植え競合でも link 越しの open にならない
                                channel = FileChannel.open(part,
                                        StandardOpenOption.CREATE_NEW,
                                        StandardOpenOption.WRITE);
                                pinnedKey = fileKey(part);
                            }
                        } catch (FileAlreadyExistsException replanted) {
                            // CREATE_NEW の競合 — name だけ外してやり直す
                            Files.deleteIfExists(part);
                            continue;
                        } catch (NoSuchFileException gone) {
                            // size 計測と open の隙間に name が消えた — やり直す
                            continue;
                        } catch (IOException openFailure) {
                            return "cannot open staging file for " + art.path()
                                    + ": " + openFailure.getMessage();
                        }
                    }
                    // ここに来た時 channel は必ず pin 済み inode を指す
                    try (Fetcher.Response res = fetcher.open(uri, have)) {
                        if (res.status() == 404) return "HTTP 404 for " + art.path();
                        if (res.status() == 416 && have == art.size()) {
                            if (verifyPart(part, art)) {
                                channel.close();
                                channel = null;
                                return publish(part, published, art, mover);
                            }
                            Files.deleteIfExists(part);
                            channel.close();
                            channel = null;
                            pinnedKey = null;
                            continue;
                        }
                        boolean append = res.status() == 206 && have > 0;
                        if (!append && res.status() != 200) {
                            return "HTTP " + res.status() + " for " + art.path();
                        }
                        if (!append) {
                            // server が Range を無視(200) — pinned inode を
                            // truncate して先頭から書き直す(name 差替えは
                            // pinned fd に影響しない)
                            channel.truncate(0);
                            channel.position(0);
                            have = 0;
                        }
                        ByteBuffer buf = ByteBuffer.allocate(BUFFER);
                        long total = have;
                        try (InputStream in = res.body()) {
                            // 宣言 size を1 byte も超えて書かない — 余剰は捨てる
                            for (int n; total < art.size()
                                    && (n = in.read(buf.array(), 0,
                                            (int) Math.min(buf.capacity(),
                                                    art.size() - total))) != -1;) {
                                buf.clear();
                                buf.limit(n);
                                while (buf.hasRemaining()) channel.write(buf);
                                total += n;
                            }
                        } catch (IOException midStream) {
                            // connection reset 等 — pinned inode の bytes を
                            // 残し Range で retry
                            have = total;
                            continue;
                        }
                        have = total;
                    }
                    // pin した inode を name が指し続けるか — 外れているなら
                    // name だけ外して pinned inode を破棄する(書込み済み
                    // bytes は name を失った inode にあるため publish 不能)
                    if (pinnedKey != null && !pinnedKey.equals(fileKey(part))) {
                        Files.deleteIfExists(part);
                        channel.close();
                        channel = null;
                        pinnedKey = null;
                        have = 0;
                        continue;
                    }
                    if (have == art.size()) {
                        if (verifyPart(part, art)) {
                            // publish 前に fd を閉じる — open file を rename
                            // できない filesystem への配慮でもある
                            channel.close();
                            channel = null;
                            return publish(part, published, art, mover);
                        }
                        Files.deleteIfExists(part);
                        channel.close();
                        channel = null;
                        pinnedKey = null;
                        have = 0;
                        continue;
                    }
                    // have < size: partial — loop が Range で retry する
                }
                return "download incomplete after " + (MAX_RESUME_ATTEMPTS + 1)
                        + " attempts: " + art.path();
            } finally {
                if (channel != null) {
                    try {
                        channel.close();
                    } catch (IOException ignored) {
                        // best-effort close — pin は棄却経路でも破棄に過ぎない
                    }
                }
            }
        } catch (Fetcher.FetchRejected rejected) {
            return "fetch rejected for " + art.path() + ": " + rejected.getMessage();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return "download failed for " + art.path() + ": " + e.getMessage();
        }
    }

    private static boolean verifyPart(Path part, Artifact art) throws IOException {
        return Files.size(part) == art.size()
                && ManifestVerifier.sha256Hex(part).equals(art.sha256().toLowerCase());
    }

    /**
     * unix:nlink を読める FS では file の link 数を返す。非対応 FS
     * (Windows 等)や読取り失敗では -1(判別不能) — 書込み側は lone でない
     * 扱いで安全側に取り直し、検査側は「検査不能」として失敗にしない。
     */
    private static long linkCount(Path p) {
        try {
            Object v = Files.getAttribute(p, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
            return v instanceof Number n ? n.longValue() : -1;
        } catch (IOException | RuntimeException unsupported) {
            return -1;
        }
    }

    /**
     * pre-existing の staging file が「我々だけが持つ regular file」かの
     * 検査。hardlink で植えられた .part へ TRUNCATE/APPEND すると同一
     * inode の victim file まで書き換えるため、書込み前に必ず通す。
     * link 数を読めない FS では false = 信用せず取り直す。
     */
    private static boolean isLoneRegularFile(Path p) {
        return Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) && linkCount(p) == 1;
    }

    /**
     * name が束ねる inode の識別子 — basic view の fileKey(POSIX では
     * (dev,ino)、NTFS でも file index を返す必須属性で、hardlink alias は
     * 同一 key を持つ)。読めない FS や読取り失敗では null(判別不能) —
     * resume の pin 検証に使う経路では null を信用せず取り直す側に倒す。
     */
    private static Object fileKey(Path p) {
        try {
            return Files.getAttribute(p, "basic:fileKey", LinkOption.NOFOLLOW_LINKS);
        } catch (IOException | RuntimeException unsupported) {
            return null;
        }
    }

    private static String publish(Path part, Path published, Artifact art, Mover mover) {
        try {
            mover.move(part, published);
        } catch (AtomicMoveNotSupportedException unsupported) {
            return "atomic publish unsupported on this filesystem";
        } catch (IOException e) {
            return "publish failed: " + e.getMessage();
        }
        // move は name の付け替え — verifyPart→move の隙間に part 名が
        // hardlink へ入替えられていると、published は未検証 inode を指す。
        // 実際に着地した bytes を再検証して TOCTOU を閉じる
        try {
            if (!verifyPart(published, art)) {
                Files.deleteIfExists(published);
                return "published bytes swapped before move: " + art.path();
            }
        } catch (IOException e) {
            return "post-publish verify failed for " + art.path() + ": " + e.getMessage();
        }
        return null;
    }

    /** atomic な marker 書込み: temp file → mover.move。atomic move を
     *  供給できない filesystem では失敗する — 非 atomic な commit marker
     *  書込みは crash 後に切り詰められた "ready" signal を残し得るため。
     *  既存の marker は成功時にのみ置き換えられるため、失敗した publish は
     *  古い commit を保存する。 */
    private static void atomicWrite(Path target, byte[] content, Mover mover) throws IOException {
        Path tmp = Files.createTempFile(target.getParent(), "current-", ".tmp");
        try {
            Files.write(tmp, content);
            mover.move(tmp, target);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
