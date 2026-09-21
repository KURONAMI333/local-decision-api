package com.kuronami.somcore;

import com.kuronami.somcore.api.SomCore;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

@Mod(Constants.MOD_ID)
public final class Somcore {
    public Somcore(IEventBus eventBus) {
        SomCore.initialize(FMLPaths.GAMEDIR.get());
        com.kuronami.somcore.internal.DeveloperProbe.runIfRequested();
        NeoForge.EVENT_BUS.addListener((ServerStartingEvent event) -> SomCore.initialize(FMLPaths.GAMEDIR.get()));
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent event) -> SomCore.close());
        Runtime.getRuntime().addShutdownHook(new Thread(SomCore::close, "somcore-shutdown"));
    }
}
