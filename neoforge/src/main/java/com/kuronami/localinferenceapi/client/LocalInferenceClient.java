package com.kuronami.localinferenceapi.client;

import com.kuronami.localinferenceapi.Constants;
import com.kuronami.localinferenceapi.api.LocalInference;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/** Dist gate を通して登録し、dedicated server に client event を読み込ませない。 */
@EventBusSubscriber(modid = Constants.MOD_ID, value = Dist.CLIENT)
public final class LocalInferenceClient {
    private LocalInferenceClient() {}
    @SubscribeEvent public static void connected(ClientPlayerNetworkEvent.LoggingIn event) {
        LocalInference.initialize(FMLPaths.GAMEDIR.get());
    }
    @SubscribeEvent public static void disconnected(ClientPlayerNetworkEvent.LoggingOut event) {
        LocalInference.close();
    }
}
