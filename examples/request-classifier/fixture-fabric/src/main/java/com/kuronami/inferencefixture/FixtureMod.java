package com.kuronami.inferencefixture;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

public final class FixtureMod implements ModInitializer {
    @Override public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(SharedFailureProbe::started);
        ServerLifecycleEvents.SERVER_STOPPING.register(SharedFailureProbe::stopping);
        ServerTickEvents.END_SERVER_TICK.register(SharedFailureProbe::tick);
    }
}
