package com.kuronami.localinferenceapi;

import com.kuronami.localinferenceapi.api.LocalInference;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

@Mod(Constants.MOD_ID)
public final class LocalInferenceMod {
    public LocalInferenceMod(IEventBus eventBus) {
        LocalInference.initialize(FMLPaths.GAMEDIR.get());
        com.kuronami.localinferenceapi.internal.DeveloperProbe.runIfRequested();
        NeoForge.EVENT_BUS.addListener((ServerStartingEvent event) -> LocalInference.initialize(FMLPaths.GAMEDIR.get()));
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent event) -> LocalInference.close());
        Runtime.getRuntime().addShutdownHook(new Thread(LocalInference::close, "localinferenceapi-shutdown"));
    }
}
