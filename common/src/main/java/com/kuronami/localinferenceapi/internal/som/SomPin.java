package com.kuronami.localinferenceapi.internal.som;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

/**
 * SOM native artifact package の trust anchor。publisher(=この MOD JAR)が
 * 同梱する manifest bytes の sha256 と fetch policy を product code に
 * コンパイル時定数として固定する — disk 上の sidecar や編集可能な file は
 * anchor にならない。
 *
 * 同梱 resource(全て common/src/main/resources/localinferenceapi/som/ 由来、
 * 両 loader JAR へ packaged):
 *   localinferenceapi/som/manifest.json  — pin された manifest(bytes 固定、
 *     git 側は .gitattributes の -text で改行変換を抑止)
 *   localinferenceapi/som/licenses/*     — license/notice の正本 bytes
 *
 * MANIFEST_SHA256 を更新する時は同梱 manifest.json と必ず同じ commit で
 * 変える。両者の一致は SomPinTest が全 test run で検証する。
 *
 * manifest digest は秘密ではない(公開 JAR 由来)。真正性の防壁は pin が
 * manifest を、manifest が各 artifact の sha256 を、sha256 が staged file
 * bytes を束ねる連鎖であり、最終的には JAR 自体の配布経路が anchor を運ぶ。
 */
public final class SomPin {

    /** pin packet の classpath prefix。 */
    public static final String RESOURCE_PREFIX = "localinferenceapi/som/";
    /** JAR 内 manifest の resource path。 */
    public static final String MANIFEST_RESOURCE = RESOURCE_PREFIX + "manifest.json";

    /**
     * 同梱 manifest.json bytes の sha256。
     * `shasum -a 256 common/src/main/resources/localinferenceapi/som/manifest.json`
     * で再計算できる(som-pin/manifest.sha256 の dev sidecar と同じ値)。
     */
    public static final String MANIFEST_SHA256 =
            "3744d73d6a1d35a802f737d09e7e856723c5f9cb6555c88b4ebe82f03f78c59c";

    /**
     * artifact fetch が触れてよい host。各 entry は完全一致か subdomain に
     * 合致する(suffix match)。
     *   github.com / githubusercontent.com — llama.cpp release asset とその CDN hop
     *   huggingface.co / hf.co             — JevK5-GGUF repo resolve と LFS CDN hop
     *   apache.org                         — Apache-2.0 canonical text の provenance
     * license artifact は embedded resource からしか stage しないため、実際に
     * network へ出るのは runtime/weights の github/hf 系 host のみ。
     */
    public static final Set<String> ALLOWED_FETCH_HOSTS = Set.of(
            "github.com", "githubusercontent.com",
            "huggingface.co", "hf.co", "apache.org");

    /**
     * 1 artifact の pin 済み size の上限(manifest fetch.max_artifact_bytes)。
     * JevK5-4B v0.3 Q5_K_M は 3,074,986,400 B なので 4 GiB で束ねる。
     */
    public static final long MAX_ARTIFACT_BYTES = 4_294_967_296L; // 4 GiB
    /** manifest bytes の sanity bound。 */
    public static final long MAX_MANIFEST_BYTES = 4L << 20; // 4 MiB

    private SomPin() {}

    /** 製品の trust anchor。 */
    public static TrustedPin pin() {
        return TrustedPin.production(MANIFEST_SHA256, ALLOWED_FETCH_HOSTS, MAX_ARTIFACT_BYTES);
    }

    /**
     * 同梱 manifest bytes を返す。resource 欠落・読取り失敗・bound 超過は
     * 全て null でなく IOException — anchor 失敗を黙らせない。
     */
    public static byte[] embeddedManifestBytes() throws IOException {
        try (InputStream in = openEmbedded("manifest.json")) {
            if (in == null) throw new IOException("bundled SOM manifest is missing");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            for (int n; (n = in.read(buf)) != -1;) {
                out.write(buf, 0, n);
                if (out.size() > MAX_MANIFEST_BYTES) {
                    throw new IOException("bundled SOM manifest exceeds size bound");
                }
            }
            return out.toByteArray();
        }
    }

    /**
     * artifact path の同梱 bytes を開く({@link EmbeddedSource})。unsafe な
     * path や resource 欠落は null。path は phase-1 で検証済みのはずだが、
     * classpath の任意 resource へ逃げる網代わりの検査をここでも行う。
     */
    public static InputStream openEmbedded(String artifactPath) throws IOException {
        if (!RelPaths.isSafe(artifactPath)) return null;
        return SomPin.class.getResourceAsStream("/" + RESOURCE_PREFIX + artifactPath);
    }
}
