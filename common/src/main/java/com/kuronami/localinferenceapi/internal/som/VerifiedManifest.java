package com.kuronami.localinferenceapi.internal.som;

import java.util.List;

/**
 * manifest が caller 供給の TrustedPin に対する phase-1 検証を通ったことの
 * 不透明な証明。constructor は package-private — この package の外では
 * 偽造できないため、{@link Stager#stage} は検証済みでない metadata では
 * public API 経由で到達不能になる。
 */
public final class VerifiedManifest {

    private final List<ManifestVerifier.Artifact> artifacts;
    private final String sha256; // pin して検証した manifest bytes の digest

    VerifiedManifest(List<ManifestVerifier.Artifact> artifacts, String sha256) {
        this.artifacts = List.copyOf(artifacts);
        this.sha256 = sha256;
    }

    public List<ManifestVerifier.Artifact> artifacts() { return artifacts; }

    /**
     * platform 実行環境に関係する artifact だけを残す view — platform 非指定
     * の artifact(weights/license 等の共有物)と {@code platformKey} 一致分を
     * 残し、他 platform 専用の runtime を落とす。manifest digest(= pin
     * anchor)は元 manifest のまま変わらない — anchor は manifest bytes 全体を
     * 束ねており、view は stage/inspect が対象とする artifact 集合だけを
     * 狭める。{@code platformKey} が null の時は元のまま返す。
     */
    VerifiedManifest platformView(String platformKey) {
        if (platformKey == null) return this;
        List<ManifestVerifier.Artifact> kept = new java.util.ArrayList<>();
        for (ManifestVerifier.Artifact a : artifacts) {
            if (a.platform() == null || a.platform().equals(platformKey)) kept.add(a);
        }
        return new VerifiedManifest(kept, sha256);
    }

    /** pin して検証した manifest bytes の sha256。 */
    public String manifestSha256() { return sha256; }
}
