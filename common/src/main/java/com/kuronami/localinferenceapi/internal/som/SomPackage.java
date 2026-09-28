package com.kuronami.localinferenceapi.internal.som;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * pin 済み SOM artifact package の入口。将来の native gate(S8b 以降)が
 * 起動前に呼ぶ想定の3操作を集約する:
 *
 *   verifyEmbeddedManifest() — JAR 同梱 manifest を SomPin の anchor で検証
 *   stage(gameDirectory)     — 未検証・未取得の artifact を .localinferenceapi/som
 *                              へ content-addressed で stage し commit する
 *   inspect(gameDirectory)   — commit marker + staged tree が現在の pin と
 *                              一致して完全かを読み取り専用で判定する
 *
 * 製品経路は生きている: {@link SomWiring} の productionGate は
 * {@link LlamaGate} を組み立て、LlamaGate が llama-server の spawn 前に
 * verifyEmbeddedManifest()・stage()・inspect()・root() を呼ぶ
 * (native route は既定 ON、-Dlocalinferenceapi.som.native=false で退避)。
 * この層自体は process を spawn しない — 起動・監視は LlamaGate の責務。
 *
 * offline 性: commit 済み tree の inspect と license artifact の stage は
 * network を要さない。network を使うのは runtime/weights artifact が
 * まだ staged でない時の stage のみで、host は SomPin の allowlist に
 * bounded で限られる。
 */
public final class SomPackage {

    /** game dir 配下の staging root(.localinferenceapi/som)。 */
    private static final String DIR_NAME = ".localinferenceapi";

    private SomPackage() {}

    /** staging root。WorkerClient と同じく launcher の symlink を避けるため
     *  game dir は real path で固定する。更に managed tail
     *  ({@code .localinferenceapi/som}) に置かれた pre-existing link は
     *  辿らず link 自体を外す — link 越しの dir へ staged bytes を流さない。
     *  POSIX symlink と Windows junction/mount-point(reparse point)の両方を
     *  拾う: isSymbolicLink は junction を見ないが toRealPath は透過して
     *  resolve するため、lexical との不一致として必ず現れる。unlink→
     *  検査の隙間の再植えは bounded retry で拾い、消えなければ失敗。
     *  (link を消す操作は対象へ触れず常に安全。inspect 経路でも同じ。)
     *  この検査と stage/inspect の使用の間の再植えは、Stager 側が同じ
     *  real game dir を containment base として受け取って、dir 作成前後・
     *  resolve 直後・artifact loop 各回頭・commit 公開後に実位置を再照合
     *  する — 検出した escape は失敗(commit 未成立)または TAMPERED に
     *  倒す。ただし name-based 検査は同時再植えを完全には塞げず、settle
     *  後の窓で外側 dir へ pin-authentic bytes が落ちうる残存リスクが
     *  残る(Stager の class javadoc 参照) — 絶対の囲い込みではない。 */
    public static Path root(Path gameDirectory) throws IOException {
        return managedTail(gameDirectory.toRealPath());
    }

    /**
     * 検査済みの managed tail を real game dir から作る。呼出しのたびに
     * root() と同じ bounded 修復を行う — stage/inspect 経路では呼出し側が
     * 同一の real gd を containment base として Stager へ渡し、ここでの
     * 検査から使用までの隙間に植えられた link/junction を Stager 側で
     * 拾わせる。
     */
    private static Path managedTail(Path realGameDir) throws IOException {
        Path tail = realGameDir.resolve(DIR_NAME).resolve("som");
        for (int i = 0; i < 3; i++) {
            Path bad = RelPaths.firstDivergingComponent(realGameDir, tail);
            if (bad == null) return tail;
            Files.delete(bad);
        }
        if (RelPaths.firstDivergingComponent(realGameDir, tail) != null) {
            throw new IOException("staging tail escapes game dir containment");
        }
        return tail;
    }

    /**
     * JAR 同梱 manifest を pin と照合して検証する。成功した Result.manifest()
     * が staging 層の唯一の入力になる。resource 欠落・digest 不一致・schema
     * 違反は全て失敗 fail-closed。
     */
    public static ManifestVerifier.Result verifyEmbeddedManifest() {
        byte[] bytes;
        try {
            bytes = SomPin.embeddedManifestBytes();
        } catch (IOException e) {
            return new ManifestVerifier.Result(
                    java.util.List.of("embedded manifest unreadable: " + e.getMessage()),
                    java.util.List.of(), null);
        }
        return ManifestVerifier.verifyManifest(bytes, SomPin.pin());
    }

    /**
     * 製品の stage 経路: embedded manifest を検証し、runtime/weights は
     * production fetcher(HTTPS・allowlist・redirect 再検証)、license は JAR
     * 同梱 resource から root へ stage する。manifest が検証を通らなければ
     * disk・network ともに触らない。{@code platformKey} が非 null なら
     * artifact 集合はその実行環境の分だけ(他 platform 専用 runtime を
     * 取得しない — commit marker には検証済み manifest 全体の digest が
     * 残る)。
     */
    public static Stager.Result stage(Path gameDirectory) throws IOException {
        return stage(gameDirectory, null);
    }

    public static Stager.Result stage(Path gameDirectory, String platformKey)
            throws IOException {
        return stage(gameDirectory, platformKey,
                HttpFetcher.production(SomPin.ALLOWED_FETCH_HOSTS),
                SomPin::openEmbedded);
    }

    /** 試験用 seam: fetcher と embedded source を差し替える。 */
    static Stager.Result stage(Path gameDirectory, Fetcher fetcher,
                               EmbeddedSource embedded) throws IOException {
        return stage(gameDirectory, null, fetcher, embedded);
    }

    static Stager.Result stage(Path gameDirectory, String platformKey,
                               Fetcher fetcher, EmbeddedSource embedded)
            throws IOException {
        ManifestVerifier.Result v = verifyEmbeddedManifest();
        if (!v.passed()) return new Stager.Result(v.failures(), null);
        // real 化した game dir を containment base として渡す — managedTail
        // の検査と Stager の使用の隙間に managed tail へ植えられた
        // link/junction を Stager 側の再検査で拾い、resolved した外側 dir を
        // staged bytes の基点にしない
        Path gd = gameDirectory.toRealPath();
        return Stager.stage(v.manifest().platformView(platformKey),
                managedTail(gd), gd, fetcher, embedded);
    }

    /**
     * staged package が現在の pin と一致して完全かを判定する。
     * COMMITTED の時だけ files に宣言 path → staged file が入る。
     * ABSENT(未 stage)と STALE(旧 pin の commit)は TAMPERED と区別する —
     * どちらも再 stage が正当な回復経路。manifest 自体が anchor に落ちる
     * 場合は TAMPERED。
     */
    public static Stager.Inspection inspect(Path gameDirectory) throws IOException {
        return inspect(gameDirectory, null);
    }

    /**
     * {@code platformKey} 指定時は stage と同じ artifact subset を検査する
     * — 他 platform 専用 artifact の不在は不完全ではない。
     */
    public static Stager.Inspection inspect(Path gameDirectory, String platformKey)
            throws IOException {
        ManifestVerifier.Result v = verifyEmbeddedManifest();
        if (!v.passed()) {
            return new Stager.Inspection(Stager.Inspection.Status.TAMPERED,
                    v.failures(), Map.of());
        }
        Path gd = gameDirectory.toRealPath();
        return Stager.inspect(managedTail(gd), gd,
                v.manifest().platformView(platformKey));
    }
}
