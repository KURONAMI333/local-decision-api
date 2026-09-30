package com.kuronami.localinferenceapi;

import com.kuronami.localinferenceapi.internal.DeveloperProbe;
import com.kuronami.localinferenceapi.internal.InferenceLifecycle;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;

public final class LocalInferenceMod implements ModInitializer {
    @Override public void onInitialize() {
        InferenceLifecycle.initialize(FabricLoader.getInstance().getGameDir());
        // dedicated server には client の world 読み込み遷移が無いので初期化時に発火する。
        // client 側は LocalInferenceClient の JOIN が join 後に1回だけ呼ぶ。
        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER) DeveloperProbe.runIfRequested();
        ServerLifecycleEvents.SERVER_STARTING.register(server -> InferenceLifecycle.initialize(FabricLoader.getInstance().getGameDir()));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> InferenceLifecycle.endSession());
        Runtime.getRuntime().addShutdownHook(new Thread(InferenceLifecycle::shutdown, "localinferenceapi-shutdown"));
    }
}
