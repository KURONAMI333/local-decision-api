package com.kuronami.localinferenceapi.client;

import com.kuronami.localinferenceapi.Constants;
import com.kuronami.localinferenceapi.internal.DeveloperProbe;
import com.kuronami.localinferenceapi.internal.InferenceLifecycle;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;

/** Dist gate を通して登録し、dedicated server に client event を読み込ませない。 */
@Mod.EventBusSubscriber(modid = Constants.MOD_ID, value = Dist.CLIENT)
public final class LocalInferenceClient {
    private LocalInferenceClient() {}
    @net.minecraftforge.eventbus.api.SubscribeEvent public static void connected(ClientPlayerNetworkEvent.LoggingIn event) {
        InferenceLifecycle.initialize(FMLPaths.GAMEDIR.get());
        // join 後に発火させる。MOD 初期化時の要求は doWorldLoad 冒頭の disconnect
        // (正規 endSession) と確定的に競合する。起動1回の制限は probe 側が持つ。
        DeveloperProbe.runIfRequested();
    }
    @net.minecraftforge.eventbus.api.SubscribeEvent public static void disconnected(ClientPlayerNetworkEvent.LoggingOut event) {
        InferenceLifecycle.endSession();
    }
}
