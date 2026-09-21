package com.kuronami.localinferenceapi.client;

import com.kuronami.localinferenceapi.api.LocalInference;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;

/** client entrypoint に隔離し、dedicated server は client class を解決しない。 */
public final class LocalInferenceClient implements ClientModInitializer {
    @Override public void onInitializeClient() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> LocalInference.initialize(FabricLoader.getInstance().getGameDir()));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> LocalInference.close());
    }
}
