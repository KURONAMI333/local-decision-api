package com.kuronami.localinferenceapi;

import com.kuronami.localinferenceapi.api.LocalInference;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;

public final class LocalInferenceMod implements ModInitializer {
    @Override public void onInitialize() {
        LocalInference.initialize(FabricLoader.getInstance().getGameDir());
        com.kuronami.localinferenceapi.internal.DeveloperProbe.runIfRequested();
        ServerLifecycleEvents.SERVER_STARTING.register(server -> LocalInference.initialize(FabricLoader.getInstance().getGameDir()));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> LocalInference.close());
        Runtime.getRuntime().addShutdownHook(new Thread(LocalInference::close, "localinferenceapi-shutdown"));
    }
}
