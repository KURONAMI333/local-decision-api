package com.kuronami.inferencefixture;

import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@Mod("inferencesharedfixture")
public final class FixtureMod {
    public FixtureMod() {
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) -> SharedFailureProbe.started(event.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent event) -> SharedFailureProbe.stopping(event.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> SharedFailureProbe.tick(event.getServer()));
    }
}
