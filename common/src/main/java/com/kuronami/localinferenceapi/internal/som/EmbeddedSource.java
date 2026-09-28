package com.kuronami.localinferenceapi.internal.som;

import java.io.IOException;
import java.io.InputStream;

/**
 * JAR 内(pin packet)の bytes を manifest の artifact path で引く seam。
 * role=license の artifact は network へ出ず常にこの経路から stage する
 * — notice 文面は publisher が同梱した bytes が唯一の正本で、上流 URL は
 * provenance の記録であって取得先ではない。製品は {@link SomPin#openEmbedded}
 * (classpath resource)、試験は map を差し込む。
 */
@FunctionalInterface
public interface EmbeddedSource {

    /** artifact path の同梱 bytes を開く。resource が無ければ null。 */
    InputStream open(String artifactPath) throws IOException;
}
