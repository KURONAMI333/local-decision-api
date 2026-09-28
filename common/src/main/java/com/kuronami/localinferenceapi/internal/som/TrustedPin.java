package com.kuronami.localinferenceapi.internal.som;

import java.util.Locale;
import java.util.Set;

/**
 * manifest の外部 trust anchor。製品では {@link SomPin} が JAR へ埋め込んだ
 * 値を供給する。verifier は manifest の隣に置かれた sidecar から anchor を
 * 決して導出しない(sidecar は変更検知の補助であって認証ではない)。
 *
 * manifestSha256:    pin された manifest bytes の sha256。必須。
 * allowedHosts:      非空なら全 artifact の upstream_url host がこの許可表に
 *                    合致(完全一致または subdomain)しなければならない。空なら
 *                    host 検査を省略する(ダウンロードしない install 済み tree
 *                    の検査用)。
 * allowPlainHttp:    製品では常に false。単体試験が loopback http へ向くため
 *                    だけに存在し、{@link #production} からは得られない。
 * maxArtifactBytes:  1 artifact の pin 済み size の上限。これを超える宣言は
 *                    download の前に拒否する(bounded fetch の入口)。
 *
 * record 自体は package-private — package 外から canonical ctor で
 * allowPlainHttp=true を持つ pin を組み立てる経路を残さない。
 */
record TrustedPin(String manifestSha256, Set<String> allowedHosts,
                  boolean allowPlainHttp, long maxArtifactBytes) {

    TrustedPin {
        if (manifestSha256 == null || manifestSha256.isBlank()) {
            throw new IllegalArgumentException("trusted manifest sha256 is required");
        }
        allowedHosts = allowedHosts == null ? Set.of() : Set.copyOf(allowedHosts);
        if (maxArtifactBytes <= 0) {
            throw new IllegalArgumentException("maxArtifactBytes must be positive");
        }
    }

    static TrustedPin production(String manifestSha256, Set<String> allowedHosts,
                                 long maxArtifactBytes) {
        return new TrustedPin(manifestSha256, allowedHosts, false, maxArtifactBytes);
    }

    /** 試験専用 — http:// upstream URL を許す。製品経路へ配線しない。 */
    static TrustedPin insecureForTests(String manifestSha256, Set<String> allowedHosts) {
        return new TrustedPin(manifestSha256, allowedHosts, true, Long.MAX_VALUE);
    }

    boolean hostAllowed(String host) {
        if (allowedHosts.isEmpty()) return true;
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        for (String allowed : allowedHosts) {
            if (h.equals(allowed) || h.endsWith("." + allowed)) return true;
        }
        return false;
    }
}
