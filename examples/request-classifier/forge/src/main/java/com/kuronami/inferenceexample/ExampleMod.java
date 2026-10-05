package com.kuronami.inferenceexample;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.common.Mod;

@Mod("inferenceexample")
public final class ExampleMod {
    public ExampleMod() {
        MinecraftForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> RequestClassifier.register(event.getDispatcher()));
        MinecraftForge.EVENT_BUS.addListener((TickEvent.ServerTickEvent event) -> {
            if (event.phase == TickEvent.Phase.END) MpProbe.tick(event.getServer());
        });
        MinecraftForge.EVENT_BUS.addListener((ServerStartedEvent event) -> {
            WorldSession.started(event.getServer());
            DeveloperSmoke.started(event.getServer());
        });
        MinecraftForge.EVENT_BUS.addListener((ServerStoppingEvent event) -> WorldSession.stopping(event.getServer()));
        MinecraftForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> DeveloperSmoke.stopped(event.getServer()));
    }
}
