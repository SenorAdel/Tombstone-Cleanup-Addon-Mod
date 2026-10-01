package dev.tombstonecleanup;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.event.level.ChunkEvent;

/** Tracks chunks loaded during the server lifetime so scans include non-ticking FULL chunks too. */
public final class LoadedChunkTracker {
    private static final Map<ServerLevel, Set<ChunkPos>> LOADED = new WeakHashMap<>();

    private LoadedChunkTracker() {}

    public static synchronized void onLoad(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level) {
            LOADED.computeIfAbsent(level, ignored -> new HashSet<>()).add(event.getChunk().getPos());
        }
    }

    public static synchronized void onUnload(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            Set<ChunkPos> chunks = LOADED.get(level);
            if (chunks != null) {
                chunks.remove(event.getChunk().getPos());
                if (chunks.isEmpty()) LOADED.remove(level);
            }
        }
    }

    public static synchronized List<LevelChunk> snapshot(ServerLevel level) {
        Set<ChunkPos> chunks = LOADED.get(level);
        if (chunks == null) return List.of();
        List<LevelChunk> snapshot = new ArrayList<>(chunks.size());
        for (ChunkPos pos : chunks) {
            LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x(), pos.z());
            if (chunk != null) snapshot.add(chunk);
        }
        return snapshot;
    }
}
