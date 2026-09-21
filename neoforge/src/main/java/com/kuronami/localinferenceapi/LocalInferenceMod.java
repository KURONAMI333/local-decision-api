package com.kuronami.localinferenceapi;

import com.kuronami.localinferenceapi.internal.InferenceLifecycle;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

@Mod(Constants.MOD_ID)
public final class LocalInferenceMod {
    public LocalInferenceMod(IEventBus eventBus) {
        InferenceLifecycle.initialize(FMLPaths.GAMEDIR.get());
        com.kuronami.localinferenceapi.internal.DeveloperProbe.runIfRequested();
        NeoForge.EVENT_BUS.addListener((ServerStartingEvent event) -> InferenceLifecycle.initialize(FMLPaths.GAMEDIR.get()));
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent event) -> InferenceLifecycle.endSession());
        Runtime.getRuntime().addShutdownHook(new Thread(InferenceLifecycle::shutdown, "localinferenceapi-shutdown"));
    }
}
