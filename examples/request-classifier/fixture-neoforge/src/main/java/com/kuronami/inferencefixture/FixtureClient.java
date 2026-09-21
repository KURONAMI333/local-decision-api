package com.kuronami.inferencefixture;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

@EventBusSubscriber(modid = "inferencesharedfixture", value = Dist.CLIENT)
public final class FixtureClient {
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        ClientLifecycleProbe.tick(Minecraft.getInstance());
    }
}
