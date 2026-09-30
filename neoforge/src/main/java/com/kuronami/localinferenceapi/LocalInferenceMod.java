package com.kuronami.localinferenceapi;

import com.kuronami.localinferenceapi.internal.DeveloperProbe;
import com.kuronami.localinferenceapi.internal.InferenceLifecycle;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

@Mod(Constants.MOD_ID)
public final class LocalInferenceMod {
    public LocalInferenceMod(IEventBus eventBus) {
        InferenceLifecycle.initialize(FMLPaths.GAMEDIR.get());
        // dedicated server には client の world 読み込み遷移が無いので初期化時に発火する。
        // client 側は LocalInferenceClient.connected(LoggingIn) が join 後に1回だけ呼ぶ。
        if (FMLEnvironment.dist == Dist.DEDICATED_SERVER) DeveloperProbe.runIfRequested();
        NeoForge.EVENT_BUS.addListener((ServerStartingEvent event) -> InferenceLifecycle.initialize(FMLPaths.GAMEDIR.get()));
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent event) -> InferenceLifecycle.endSession());
        Runtime.getRuntime().addShutdownHook(new Thread(InferenceLifecycle::shutdown, "localinferenceapi-shutdown"));
    }
}
