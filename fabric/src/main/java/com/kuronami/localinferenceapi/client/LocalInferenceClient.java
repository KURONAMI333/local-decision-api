package com.kuronami.localinferenceapi.client;

import com.kuronami.localinferenceapi.internal.DeveloperProbe;
import com.kuronami.localinferenceapi.internal.InferenceLifecycle;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;

/** client entrypoint に隔離し、dedicated server は client class を解決しない。 */
public final class LocalInferenceClient implements ClientModInitializer {
    @Override public void onInitializeClient() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            InferenceLifecycle.initialize(FabricLoader.getInstance().getGameDir());
            // join 後に発火させる。MOD 初期化時の要求は world 読み込み冒頭の
            // disconnect (正規 endSession) と確定的に競合する。起動1回の制限は probe 側が持つ。
            DeveloperProbe.runIfRequested();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> InferenceLifecycle.endSession());
    }
}
