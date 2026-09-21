package com.kuronami.inferenceexample;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/** 同じサーバーインスタンスでも停止をまたぐ古い結果を適用しない。 */
public final class WorldSession {
    private static final Map<MinecraftServer, Object> ACTIVE = new ConcurrentHashMap<>();
    private WorldSession() {}
    public static void started(MinecraftServer server) { ACTIVE.put(server, new Object()); }
    public static void stopping(MinecraftServer server) { ACTIVE.remove(server); }
    public static Lease capture(MinecraftServer server, ServerLevel level) {
        return new Lease(server, level, ACTIVE.get(server));
    }
    public record Lease(MinecraftServer server, ServerLevel level, Object generation) {
        public boolean valid() {
            return generation != null && ACTIVE.get(server) == generation && !server.isStopped()
                    && server.getLevel(level.dimension()) == level;
        }
    }
}
