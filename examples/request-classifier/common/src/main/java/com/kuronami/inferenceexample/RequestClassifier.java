package com.kuronami.inferenceexample;

import com.kuronami.localinferenceapi.api.DecisionRequest;
import com.kuronami.localinferenceapi.api.LocalInference;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;
import java.util.List;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** モデルへ渡すのは文字列のみ。完了後にサーバースレッドで所属を再確認する利用例。 */
public final class RequestClassifier {
    private static final List<String> CHOICES = List.of(
            "Asking for food", "Asking for light or torches", "Asking for directions");
    private static final List<String> KEYS = List.of("food", "light", "directions");

    private RequestClassifier() {}

    public static DecisionRequest request(String text) {
        if (text.length() > 500) throw new IllegalArgumentException("Request too long");
        return new DecisionRequest(text, "What does this player need?", CHOICES);
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("inferenceexample")
                .then(Commands.argument("text", StringArgumentType.greedyString()).executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    DecisionRequest request;
                    try {
                        request = request(StringArgumentType.getString(context, "text"));
                    } catch (IllegalArgumentException invalid) {
                        player.sendSystemMessage(Component.translatable("inferenceexample.invalid"));
                        return 0;
                    }
                    MinecraftServer server = context.getSource().getServer();
                    ServerLevel level = player.serverLevel();
                    WorldSession.Lease lease = WorldSession.capture(server, level);
                    UUID id = player.getUUID();
                    // 再ログインした別の ServerPlayer に以前の結果を渡さない。
                    ServerPlayer originalPlayer = player;
                    player.sendSystemMessage(Component.translatable("inferenceexample.queued"));
                    LocalInference.decide(request).whenComplete((result, failure) -> {
                        if (server.isStopped()) return;
                        server.execute(() -> {
                            ServerPlayer current = server.getPlayerList().getPlayer(id);
                            if (!lease.valid() || !isCurrent(server, level, originalPlayer, current)) return;
                            if (failure != null) {
                                current.sendSystemMessage(Component.translatable("inferenceexample.failed"));
                                LogUtils.getLogger().debug("Example classification failed", failure);
                            } else if (result.selected() == null) {
                                current.sendSystemMessage(Component.translatable("inferenceexample.abstained"));
                            } else {
                                current.sendSystemMessage(Component.translatable("inferenceexample.result",
                                        Component.translatable("inferenceexample." + KEYS.get(result.selected()))));
                            }
                        });
                    });
                    return 1;
                })));
    }

    private static boolean isCurrent(MinecraftServer server, ServerLevel level,
                                     ServerPlayer original, ServerPlayer current) {
        return !server.isStopped() && current == original && current != null
                && current.serverLevel() == level && server.getLevel(level.dimension()) == level;
    }
}
