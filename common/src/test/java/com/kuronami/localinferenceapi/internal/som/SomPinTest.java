package com.kuronami.localinferenceapi.internal.som;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 同梱 pin packet と SomPin の anchor の結合を検査する。
 * MANIFEST_SHA256 はコンパイル時定数であり、resource bytes と食い違えば
 * ここで全 test run が止まる — sidecar ではなく product code が anchor。
 */
class SomPinTest {

    @Test
    void embeddedManifestBytesMatchPinnedDigest() throws Exception {
        byte[] bytes = SomPin.embeddedManifestBytes();
        assertNotNull(bytes);
        assertEquals(SomPin.MANIFEST_SHA256, ManifestVerifier.sha256Hex(bytes),
                "同梱 manifest.json と SomPin.MANIFEST_SHA256 が一致すること");
    }

    @Test
    void embeddedManifestVerifiesAgainstPin() {
        var r = SomPackage.verifyEmbeddedManifest();
        assertTrue(r.passed(), () -> String.join("\n", r.failures()));
        assertNotNull(r.manifest());
        assertEquals(SomPin.MANIFEST_SHA256, r.manifest().manifestSha256());
        assertEquals(10, r.manifest().artifacts().size(),
                "1.0.0 pin packet は 10 artifact "
                        + "(runtime×4: macos-arm64/macos-x64/win-vulkan/win-cpu "
                        + "+ weights×1 + license×5)");
    }

    @Test
    void embeddedLicenseResourcesAllPresentAndMatchPin() throws Exception {
        var r = SomPackage.verifyEmbeddedManifest();
        assertTrue(r.passed(), () -> String.join("\n", r.failures()));
        int licenses = 0;
        for (ManifestVerifier.Artifact art : r.manifest().artifacts()) {
            if (!"license".equals(art.role())) continue;
            licenses++;
            try (InputStream in = SomPin.openEmbedded(art.path())) {
                assertNotNull(in, "license resource missing: " + art.path());
                byte[] bytes = in.readAllBytes();
                assertEquals(art.size(), bytes.length, art.path());
                assertEquals(art.sha256().toLowerCase(),
                        ManifestVerifier.sha256Hex(bytes), art.path());
            }
        }
        assertEquals(5, licenses);
    }

    @Test
    void everyUpstreamUrlHostIsAllowlisted() throws Exception {
        var r = SomPackage.verifyEmbeddedManifest();
        assertTrue(r.passed());
        TrustedPin pin = SomPin.pin();
        for (ManifestVerifier.Artifact art : r.manifest().artifacts()) {
            if (art.upstreamUrl() == null) continue; // packager 供給 file
            URI uri = new URI(art.upstreamUrl());
            assertEquals("https", uri.getScheme(), art.upstreamUrl());
            assertTrue(pin.hostAllowed(uri.getHost()),
                    () -> "host not covered by pin: " + uri.getHost());
        }
    }

    @Test
    void openEmbeddedRejectsUnsafeAndUnknownPaths() throws Exception {
        assertNull(SomPin.openEmbedded("../Constants.class"));
        assertNull(SomPin.openEmbedded("/etc/passwd"));
        assertNull(SomPin.openEmbedded("C:/windows/x"));
        assertNull(SomPin.openEmbedded("licenses/NOPE.txt"));
    }

    @Test
    void productionPinNeverPermitsPlainHttp() {
        assertFalse(SomPin.pin().allowPlainHttp());
    }
}
