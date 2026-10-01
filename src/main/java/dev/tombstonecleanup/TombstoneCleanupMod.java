package dev.tombstonecleanup;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

@Mod(TombstoneCleanupMod.MOD_ID)
public final class TombstoneCleanupMod {
    public static final String MOD_ID = "tombstonecleanup";

    public TombstoneCleanupMod() {
        NeoForge.EVENT_BUS.register(this);
        NeoForge.EVENT_BUS.addListener(LoadedChunkTracker::onLoad);
        NeoForge.EVENT_BUS.addListener(LoadedChunkTracker::onUnload);
    }

    @SubscribeEvent
    public void registerCommands(RegisterCommandsEvent event) {
        CleanupCommands.register(event);
    }
}
