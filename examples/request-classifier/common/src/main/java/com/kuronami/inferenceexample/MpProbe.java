package com.kuronami.inferenceexample;

import com.kuronami.localinferenceapi.api.LocalInference;
import com.kuronami.localinferenceapi.api.NoulRequest;
import com.kuronami.localinferenceapi.api.ScoreLevel;
import com.kuronami.localinferenceapi.api.ScoreRequest;
import com.mojang.logging.LogUtils;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.MinecraftServer;

/** mp検証用。明示した時だけ周期的に公開APIを呼び、接続中のplayer名と一緒に記録する。 */
public final class MpProbe {
    private static final int INTERVAL_TICKS = 600;
    private MpProbe() {}

    public static void tick(MinecraftServer server) {
        if (!Boolean.getBoolean("inferenceexample.mpProbe")) return;
        if (server.getTickCount() == 0 || server.getTickCount() % INTERVAL_TICKS != 0) return;
        List<String> names = server.getPlayerList().getPlayers().stream()
                .map(p -> p.getGameProfile().getName()).toList();
        var decide = LocalInference.decide(RequestClassifier.request("It is too dark. I need a torch."));
        var score = LocalInference.score(new ScoreRequest(
                "The player is in a dark cave and asks for torches.",
                "How useful is a torch for this player?",
                List.of(new ScoreLevel("Not useful", 0), new ScoreLevel("Somewhat useful", 1),
                        new ScoreLevel("Very useful", 2))));
        var noul = LocalInference.noul(new NoulRequest(
                "The player is in a dark cave and asks for torches.",
                "The player needs a source of light."));
        CompletableFuture.allOf(decide, score, noul).whenComplete((ignored, failure) -> server.execute(() -> {
            if (server.isStopped()) return;
            if (failure != null) {
                LogUtils.getLogger().error("INFERENCE_MP_PERIODIC_FAIL", failure);
                return;
            }
            LogUtils.getLogger().info("INFERENCE_MP_PERIODIC_PASS tick={} players={} selected={} score={} trueProbability={}",
                    server.getTickCount(), names, decide.join().selected(), score.join().score(),
                    noul.join().trueProbability());
        }));
    }
}
