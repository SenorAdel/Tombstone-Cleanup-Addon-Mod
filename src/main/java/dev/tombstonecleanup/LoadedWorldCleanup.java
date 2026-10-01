package dev.tombstonecleanup;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.item.VanillaContainerWrapper;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/** Scans online players and block/entity storage in already-loaded FULL chunks only. */
public final class LoadedWorldCleanup {
    private LoadedWorldCleanup() {}

    public static void run(MinecraftServer server, CleanupReport report, boolean execute) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            report.onlinePlayersProcessed++;
            String owner = "online player " + player.getUUID();
            cleanContainer(player.getInventory(), "Online player inventory", owner, report, execute);
            report.enderChestsProcessed++;
            cleanContainer(player.getEnderChestInventory(), "Ender Chests", owner, report, execute);
        }

        for (ServerLevel level : server.getAllLevels()) {
            report.dimensionsProcessed++;
            for (LevelChunk chunk : LoadedChunkTracker.snapshot(level)) {
                if (chunk.getLevel() != level) continue;
                report.loadedChunksProcessed++;
                for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                    if (blockEntity.isRemoved()) continue;
                    BlockPos pos = blockEntity.getBlockPos();
                    var blockId = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock());
                    if ("tombstone".equals(blockId.getNamespace())) {
                        report.tombstoneBlockEntitiesSkipped++;
                        continue;
                    }
                    String location = level.dimension().identifier() + " " + pos;
                    try {
                        if (blockEntity instanceof Container container) {
                            report.worldContainersProcessed++;
                            cleanContainer(container, "World containers", location, report, execute);
                        } else {
                            ResourceHandler<ItemResource> handler = level.getCapability(Capabilities.Item.BLOCK, pos, null);
                            if (handler != null) {
                                report.worldContainersProcessed++;
                                cleanHandler(handler, "World containers", location, report, execute);
                            }
                        }
                    } catch (Exception exception) {
                        report.failure("World inventory at " + location + " failed: " + shortMessage(exception));
                    }
                }
            }

            for (Entity entity : level.getAllEntities()) {
                if (!(entity instanceof ItemEntity dropped) || dropped.isRemoved()) continue;
                report.droppedStacksProcessed++;
                String location = level.dimension().identifier() + " dropped item entity " + dropped.getUUID();
                try {
                    ItemStack existing = dropped.getItem();
                    StackCleaner.Analysis analysis = StackCleaner.inspect(existing, execute);
                    report.recordAnalysis("Dropped items", analysis);
                    if (!execute || (!analysis.delete && !analysis.changed)) continue;
                    // ItemEntity#setItem is the authoritative vanilla mutation path for its current stack.
                    dropped.setItem(analysis.delete ? ItemStack.EMPTY : analysis.stack);
                    report.recordApplied("Dropped items", analysis);
                } catch (Exception exception) {
                    report.failure("Dropped item at " + location + " failed: " + shortMessage(exception));
                }
            }
        }
    }

    public static void cleanContainer(Container container, String category, String owner, CleanupReport report, boolean execute) {
        try {
            cleanHandler(VanillaContainerWrapper.of(container), category, owner, report, execute);
        } catch (Exception exception) {
            report.failure(category + " at " + owner + " could not be opened: " + shortMessage(exception));
        }
    }

    public static void cleanHandler(ResourceHandler<ItemResource> handler, String category, String owner,
            CleanupReport report, boolean execute) {
        int size;
        try {
            size = handler.size();
        } catch (Exception exception) {
            report.failure(category + " at " + owner + " could not report its slots: " + shortMessage(exception));
            return;
        }

        for (int slot = 0; slot < size; slot++) {
            String location = owner + " slot " + slot;
            try {
                ItemResource resource = handler.getResource(slot);
                long storedAmount = handler.getAmountAsLong(slot);
                if (resource == null || resource.isEmpty() || storedAmount <= 0) continue;
                if (storedAmount > Integer.MAX_VALUE) {
                    report.unsupported(category + " at " + location + " reports an item amount above Minecraft's stack limit.");
                    continue;
                }
                int amount = (int) storedAmount;
                ItemStack stack = resource.toStack(amount);
                StackCleaner.Analysis analysis = StackCleaner.inspect(stack, execute);
                report.recordAnalysis(category, analysis);
                if (!execute || (!analysis.delete && !analysis.changed)) continue;

                try (Transaction transaction = Transaction.openRoot()) {
                    ItemResource current = handler.getResource(slot);
                    long currentAmount = handler.getAmountAsLong(slot);
                    if (!resource.equals(current) || currentAmount != storedAmount) {
                        report.failure(category + " at " + location + " changed during cleanup; the stack was left untouched.");
                        continue;
                    }
                    int extracted = handler.extract(slot, resource, amount, transaction);
                    if (extracted != amount) {
                        report.failure(category + " at " + location + " did not allow an exact transactional extraction.");
                        continue;
                    }
                    if (!analysis.delete) {
                        int inserted = handler.insert(slot, ItemResource.of(analysis.stack), amount, transaction);
                        if (inserted != amount) {
                            report.failure(category + " at " + location + " rejected the cleaned stack; the transaction was aborted.");
                            continue;
                        }
                    }
                    transaction.commit();
                    report.recordApplied(category, analysis);
                }
            } catch (Exception exception) {
                report.failure(category + " at " + location + " failed: " + shortMessage(exception));
            }
        }
    }

    static String shortMessage(Throwable exception) {
        String message = exception.getMessage();
        return exception.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }
}
