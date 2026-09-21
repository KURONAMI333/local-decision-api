package com.kuronami.inferencefixture;

import com.kuronami.localinferenceapi.api.DecisionRequest;
import com.kuronami.localinferenceapi.api.DecisionResult;
import com.kuronami.localinferenceapi.api.LocalInference;
import com.mojang.logging.LogUtils;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/** 明示した開発起動だけで、実integrated serverを2回使う。キー入力やマウス操作はしない。 */
public final class ClientLifecycleProbe {
    private enum State { BOOT, CREATING_FIRST, FIRST_INFERENCE, DISCONNECT_FIRST, BETWEEN, TITLE_INFERENCE, CREATE_SECOND,
        CREATING_SECOND, SECOND_INFERENCE, DISCONNECT_SECOND, FINISH, DONE }
    private static State state = State.BOOT;
    private static int startupTicks;
    private static long deadline;
    private static IntegratedServer firstServer;
    private static IntegratedServer secondServer;
    private static CompletableFuture<DecisionResult> oldFuture;
    private static final String RUN = "lia-client-probe-" + UUID.randomUUID();
    private ClientLifecycleProbe() {}

    public static void tick(Minecraft client) {
        if (!Boolean.getBoolean("inferenceexample.clientSmoke") || state == State.DONE) return;
        if (deadline == 0) deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(8);
        if (System.nanoTime() > deadline) { fail(client, "timeout in " + state); return; }
        try {
            switch (state) {
                case BOOT -> {
                    if (client.level != null || client.getSingleplayerServer() != null) {
                        fail(client, "refusing to replace an existing world");
                        return;
                    }
                    if (++startupTicks < 40 || client.getOverlay() != null) return;
                    state = State.CREATING_FIRST;
                    create(client, "first");
                }
                case CREATING_FIRST -> {
                    if (!ready(client)) return;
                    firstServer = client.getSingleplayerServer();
                    state = State.FIRST_INFERENCE;
                    infer(client, true);
                }
                case DISCONNECT_FIRST -> {
                    // 直前の要求は完了を待たずに実切断へ渡す。closeはloader hookのみが呼ぶ。
                    oldFuture = LocalInference.decide(request());
                    state = State.BETWEEN; // disconnectは内部runTickを回すため先に状態を進める。
                    client.level.disconnect();
                    client.disconnect(new TitleScreen());
                }
                case BETWEEN -> {
                    if (client.level != null || client.getSingleplayerServer() != null || !firstServer.isShutdown()) return;
                    if (oldFuture == null || !oldFuture.isDone()) return;
                    LogUtils.getLogger().info("CLIENT_LIFECYCLE_FIRST_STOPPED oldFutureDone=true oldFutureFailed={}",
                            oldFuture.isCompletedExceptionally());
                    state = State.TITLE_INFERENCE;
                    LocalInference.decide(request()).whenComplete((result, failure) -> client.execute(() -> {
                        if (state != State.TITLE_INFERENCE) return;
                        if (failure != null || !client.isSameThread() || client.level != null || client.getSingleplayerServer() != null) {
                            fail(client, "title-screen inference failed or session changed: " + failure);
                            return;
                        }
                        LogUtils.getLogger().info("CLIENT_LIFECYCLE_TITLE_INFERENCE_PASS selected={} clientThread=true", result.selected());
                        state = State.CREATE_SECOND;
                    }));
                }
                case CREATE_SECOND -> {
                    state = State.CREATING_SECOND;
                    create(client, "second");
                }
                case CREATING_SECOND -> {
                    if (!ready(client)) return;
                    secondServer = client.getSingleplayerServer();
                    if (secondServer == firstServer || !firstServer.isShutdown() || !oldFuture.isDone()) {
                        fail(client, "old session remained active");
                        return;
                    }
                    state = State.SECOND_INFERENCE;
                    infer(client, false);
                }
                case DISCONNECT_SECOND -> {
                    state = State.FINISH;
                    client.level.disconnect();
                    client.disconnect(new TitleScreen());
                }
                case FINISH -> {
                    if (client.level != null || client.getSingleplayerServer() != null || !secondServer.isShutdown()) return;
                    state = State.DONE;
                    LogUtils.getLogger().info("CLIENT_LIFECYCLE_PASS worlds=2 titleInference=true sameJvm=true clientThread=true oldFutureSettled=true serversStopped=true");
                    client.stop();
                }
                default -> { }
            }
        } catch (Exception failure) { fail(client, failure.toString()); }
    }

    private static boolean ready(Minecraft client) {
        return client.level != null && client.player != null && client.getSingleplayerServer() != null
                && client.getOverlay() == null;
    }
    private static void create(Minecraft client, String suffix) {
        GameRules rules = new GameRules();
        rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
        var settings = new LevelSettings(RUN + '-' + suffix, GameType.CREATIVE, false,
                Difficulty.PEACEFUL, true, rules, WorldDataConfiguration.DEFAULT);
        LogUtils.getLogger().info("CLIENT_LIFECYCLE_CREATE {}", suffix);
        client.createWorldOpenFlows().createFreshLevel(RUN + '-' + suffix, settings,
                new WorldOptions(12345L, false, false),
                registry -> registry.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT)
                        .value().createWorldDimensions(), new TitleScreen());
    }
    private static void infer(Minecraft client, boolean first) {
        ClientLevel expectedLevel = client.level;
        IntegratedServer expectedServer = client.getSingleplayerServer();
        State expectedState = first ? State.FIRST_INFERENCE : State.SECOND_INFERENCE;
        LocalInference.decide(request()).whenComplete((result, failure) -> client.execute(() -> {
            if (state != expectedState) return;
            if (!client.isSameThread() || client.level != expectedLevel || client.getSingleplayerServer() != expectedServer) {
                fail(client, "callback delivered to a different client session");
                return;
            }
            if (failure != null) { fail(client, "inference failed: " + failure); return; }
            LogUtils.getLogger().info("CLIENT_LIFECYCLE_INFERENCE_PASS session={} selected={} clientThread=true",
                    first ? 1 : 2, result.selected());
            state = first ? State.DISCONNECT_FIRST : State.DISCONNECT_SECOND;
        }));
    }
    private static DecisionRequest request() {
        return new DecisionRequest("I cannot remember my password. Please reset it.", "What does this person need?",
                List.of("Reporting a lost bank card", "Resetting a password", "Requesting a loan"));
    }
    private static void fail(Minecraft client, String reason) {
        state = State.DONE;
        LogUtils.getLogger().error("CLIENT_LIFECYCLE_FAIL {}", reason);
        client.stop();
    }
}
