package com.kuronami.inferenceexample;

import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

@Mod("inferenceexample")
public final class ExampleMod {
    public ExampleMod() {
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> RequestClassifier.register(event.getDispatcher()));
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) -> {
            WorldSession.started(event.getServer());
            DeveloperSmoke.started(event.getServer());
        });
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent event) -> WorldSession.stopping(event.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> DeveloperSmoke.stopped(event.getServer()));
    }
}
