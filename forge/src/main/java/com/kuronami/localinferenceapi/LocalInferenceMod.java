package com.kuronami.localinferenceapi;

import com.kuronami.localinferenceapi.internal.DeveloperProbe;
import com.kuronami.localinferenceapi.internal.InferenceLifecycle;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;

@Mod(Constants.MOD_ID)
public final class LocalInferenceMod {
    public LocalInferenceMod() {
        InferenceLifecycle.initialize(FMLPaths.GAMEDIR.get());
        // dedicated server には client の world 読み込み遷移が無いので初期化時に発火する。
        // client 側は LocalInferenceClient.connected(LoggingIn) が join 後に1回だけ呼ぶ。
        if (FMLEnvironment.dist == Dist.DEDICATED_SERVER) DeveloperProbe.runIfRequested();
        MinecraftForge.EVENT_BUS.addListener((ServerStartingEvent event) -> InferenceLifecycle.initialize(FMLPaths.GAMEDIR.get()));
        MinecraftForge.EVENT_BUS.addListener((ServerStoppingEvent event) -> InferenceLifecycle.endSession());
        Runtime.getRuntime().addShutdownHook(new Thread(InferenceLifecycle::shutdown, "localinferenceapi-shutdown"));
    }
}
