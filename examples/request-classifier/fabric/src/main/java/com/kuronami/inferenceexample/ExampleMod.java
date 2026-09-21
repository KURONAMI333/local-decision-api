package com.kuronami.inferenceexample;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

public final class ExampleMod implements ModInitializer {
    @Override public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) -> RequestClassifier.register(dispatcher));
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            WorldSession.started(server);
            DeveloperSmoke.started(server);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(WorldSession::stopping);
        ServerLifecycleEvents.SERVER_STOPPED.register(DeveloperSmoke::stopped);
    }
}
