package com.kuronami.somcore;

import com.kuronami.somcore.api.SomCore;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;

public final class Somcore implements ModInitializer {
    @Override public void onInitialize() {
        SomCore.initialize(FabricLoader.getInstance().getGameDir());
        com.kuronami.somcore.internal.DeveloperProbe.runIfRequested();
        ServerLifecycleEvents.SERVER_STARTING.register(server -> SomCore.initialize(FabricLoader.getInstance().getGameDir()));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> SomCore.close());
        Runtime.getRuntime().addShutdownHook(new Thread(SomCore::close, "somcore-shutdown"));
    }
}
