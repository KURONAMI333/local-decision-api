package com.kuronami.inferenceexample;

import com.kuronami.localinferenceapi.api.LocalInference;
import com.mojang.logging.LogUtils;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/** 開発者が明示した場合だけ、実サーバーとワールドのある状態で公開APIを使う。 */
public final class DeveloperSmoke {
    private static ServerLevel capturedLevel;
    private static WorldSession.Lease lease;
    private static MinecraftServer capturedServer;
    private DeveloperSmoke() {}

    public static void started(MinecraftServer server) {
        if (!Boolean.getBoolean("inferenceexample.smoke")) return;
        System.clearProperty("inferenceexample.primaryComplete");
        System.clearProperty("inferenceexample.primaryFinishedNanos");
        System.setProperty("inferenceexample.primaryStartedNanos", Long.toString(System.nanoTime()));
        capturedLevel = server.overworld();
        capturedServer = server;
        lease = WorldSession.capture(server, capturedLevel);
        var first = LocalInference.decide(RequestClassifier.request("I am hungry. Can I have some bread?"));
        var second = LocalInference.decide(RequestClassifier.request("It is too dark. I need a torch."));
        CompletableFuture.allOf(first, second).whenComplete((ignored, failure) -> {
            long completedAt = System.nanoTime();
            server.execute(() -> {
            if (failure != null) {
                LogUtils.getLogger().error("INFERENCE_EXAMPLE_SMOKE_FAIL", failure);
                return;
            }
            if (server.overworld() != capturedLevel || !server.isSameThread()) {
                LogUtils.getLogger().error("INFERENCE_EXAMPLE_SMOKE_FAIL wrong world or thread");
                return;
            }
            System.setProperty("inferenceexample.primaryFinishedNanos", Long.toString(completedAt));
            System.setProperty("inferenceexample.primaryComplete", Integer.toHexString(System.identityHashCode(server)));
            // 選択内容は診断値。正解率を、この2例だけから保証しない。
            LogUtils.getLogger().info("INFERENCE_EXAMPLE_SMOKE_PASS twoRequests=true serverThread=true first={} second={}",
                    first.join().selected(), second.join().selected());
            });
        });
    }

    public static void stopped(MinecraftServer server) {
        if (server != capturedServer) return;
        // ワールド停止の実イベントを観測。停止後の返信はコマンド側の isCurrent が拒否する。
        if (lease.valid()) {
            LogUtils.getLogger().error("INFERENCE_EXAMPLE_WORLD_STOPPED_FAIL stale lease still valid");
        } else {
            LogUtils.getLogger().info("INFERENCE_EXAMPLE_WORLD_STOPPED_PASS staleLeaseRejected=true");
        }
        lease = null;
        capturedLevel = null;
        capturedServer = null;
    }
}
