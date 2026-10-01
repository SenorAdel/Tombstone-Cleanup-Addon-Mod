package dev.tombstonecleanup;

import appeng.api.networking.GridHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.api.config.Actionable;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/** Optional integration that mutates ME inventories only through AE2's public MEStorage API. */
public final class Ae2StorageCleanup {
    private Ae2StorageCleanup() {}

    public static void run(MinecraftServer server, CleanupReport report, boolean execute) {
        report.ae2Available = true;
        Map<IGrid, Boolean> grids = new IdentityHashMap<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (net.minecraft.world.level.chunk.LevelChunk chunk : LoadedChunkTracker.snapshot(level)) {
                chunk.getBlockEntities().values().forEach(blockEntity -> {
                    if (blockEntity.isRemoved()) return;
                    try {
                        IInWorldGridNodeHost host = GridHelper.getNodeHost(level, blockEntity.getBlockPos());
                        if (host == null) return;
                        IGridNode node = host.getGridNode(null);
                        if (node == null || !node.isOnline()) return;
                        IGrid grid = node.getGrid();
                        if (grid != null) grids.put(grid, Boolean.TRUE);
                    } catch (RuntimeException exception) {
                        report.failure("AE2 grid discovery at " + level.dimension().identifier() + " "
                                + blockEntity.getBlockPos() + " failed: " + LoadedWorldCleanup.shortMessage(exception));
                    }
                });
            }
        }

        int networkIndex = 0;
        for (IGrid grid : grids.keySet()) {
            String network = "AE2 storage network " + (++networkIndex);
            try {
                MEStorage storage = grid.getService(IStorageService.class).getInventory();
                cleanNetwork(storage, network, report, execute);
                report.ae2NetworksProcessed++;
            } catch (RuntimeException exception) {
                report.failure(network + " could not be scanned: " + LoadedWorldCleanup.shortMessage(exception));
            }
        }
    }

    private static void cleanNetwork(MEStorage storage, String network, CleanupReport report, boolean execute) {
        KeyCounter current = storage.getAvailableStacks();
        List<Map.Entry<AEItemKey, Long>> candidates = new ArrayList<>();
        for (AEKey key : current.keySet()) {
            if (!(key instanceof AEItemKey itemKey)) continue;
            long amount = current.get(key);
            if (amount > 0) candidates.add(Map.entry(itemKey, amount));
        }

        IActionSource source = IActionSource.empty();
        for (Map.Entry<AEItemKey, Long> candidate : candidates) {
            AEItemKey originalKey = candidate.getKey();
            long amount = candidate.getValue();
            ItemStack original = originalKey.toStack();
            StackCleaner.Analysis analysis;
            try {
                analysis = StackCleaner.inspect(original, execute);
                scaleForNetwork(analysis, amount, original.getMaxStackSize());
                report.recordAnalysis("AE2 storage", analysis);
            } catch (RuntimeException exception) {
                report.failure(network + " item storage analysis failed: " + LoadedWorldCleanup.shortMessage(exception));
                continue;
            }

            if (!execute || (!analysis.delete && !analysis.changed)) continue;
            report.ae2Operations++;
            long originalBefore = available(storage, originalKey);
            AEItemKey cleanedKey = analysis.delete ? null : AEItemKey.of(analysis.stack);
            long cleanedBefore = cleanedKey == null ? 0 : available(storage, cleanedKey);
            if (originalBefore != amount) {
                report.failure(network + " changed since the storage snapshot; the affected item was left untouched.");
                continue;
            }
            boolean modificationStarted = false;
            boolean operationCompleted = false;
            try {
                long simulatedExtraction = storage.extract(originalKey, amount, Actionable.SIMULATE, source);
                if (simulatedExtraction != amount) {
                    report.failure(network + " could not preflight an exact extraction; the item was left untouched.");
                    continue;
                }

                modificationStarted = true;
                long extracted = storage.extract(originalKey, amount, Actionable.MODULATE, source);
                if (extracted != amount) {
                    long restored = storage.insert(originalKey, extracted, Actionable.MODULATE, source);
                    if (restored != extracted) {
                        boolean rollbackVerified = rollback(storage, originalKey, originalBefore, cleanedKey, cleanedBefore, source);
                        report.failure(network + " extracted a partial amount and could not restore it completely; rollback "
                                + (rollbackVerified ? "was verified." : "could not be verified; inspect the network immediately."));
                    } else {
                        report.failure(network + " returned a partial extraction; the original amount was restored.");
                    }
                    continue;
                }

                if (analysis.delete) {
                    operationCompleted = true;
                } else {
                    if (originalKey.equals(cleanedKey)) {
                        long simulatedRestore = storage.insert(originalKey, amount, Actionable.SIMULATE, source);
                        if (simulatedRestore != amount) {
                            boolean rollbackVerified = rollback(storage, originalKey, originalBefore, cleanedKey, cleanedBefore, source);
                            report.failure(network + " could not preflight restoring an unrepresentable transformed stack; rollback "
                                    + (rollbackVerified ? "was verified." : "could not be verified; inspect the network immediately."));
                            operationCompleted = true;
                            continue;
                        }
                        long restored = storage.insert(originalKey, amount, Actionable.MODULATE, source);
                        if (restored != amount || available(storage, originalKey) != originalBefore) {
                            boolean rollbackVerified = rollback(storage, originalKey, originalBefore, cleanedKey, cleanedBefore, source);
                            report.failure(network + " could not restore the original after AE2 reported an unchanged storage key; rollback "
                                    + (rollbackVerified ? "was verified." : "could not be verified; inspect the network immediately."));
                        } else {
                            report.failure(network + " produced the same AE2 storage key for a transformed stack; the original was restored.");
                        }
                        operationCompleted = true;
                        continue;
                    }

                    long simulatedInsertion = storage.insert(cleanedKey, amount, Actionable.SIMULATE, source);
                    if (simulatedInsertion != amount) {
                        long restored = storage.insert(originalKey, amount, Actionable.MODULATE, source);
                        if (restored != amount) {
                            boolean rollbackVerified = rollback(storage, originalKey, originalBefore, cleanedKey, cleanedBefore, source);
                            report.failure(network + " could not preflight cleaned-stack insertion or restore the original amount; rollback "
                                    + (rollbackVerified ? "was verified." : "could not be verified; inspect the network immediately."));
                        } else {
                            report.failure(network + " could not preflight cleaned-stack insertion; the original amount was restored.");
                        }
                        operationCompleted = true;
                        continue;
                    }

                    long inserted = storage.insert(cleanedKey, amount, Actionable.MODULATE, source);
                    if (inserted != amount) {
                        long cleanedRemoved = storage.extract(cleanedKey, inserted, Actionable.MODULATE, source);
                        long cleanedRemaining = inserted - cleanedRemoved;
                        long originalToRestore = amount - cleanedRemaining;
                        long restored = storage.insert(originalKey, originalToRestore, Actionable.MODULATE, source);
                        if (cleanedRemoved != inserted || restored != originalToRestore) {
                            boolean rollbackVerified = rollback(storage, originalKey, originalBefore, cleanedKey, cleanedBefore, source);
                            report.failure(network + " could not complete an enchantment transformation; rollback "
                                    + (rollbackVerified ? "was verified." : "could not be verified; inspect the network immediately."));
                        } else {
                            report.failure(network + " rejected the cleaned stack; the original amount was restored.");
                        }
                        operationCompleted = true;
                        continue;
                    }
                    operationCompleted = true;
                }

                report.recordApplied("AE2 storage", analysis);
            } catch (RuntimeException exception) {
                boolean restored = !modificationStarted || operationCompleted
                        || rollback(storage, originalKey, originalBefore, cleanedKey, cleanedBefore, source);
                report.failure(network + " storage operation failed: " + LoadedWorldCleanup.shortMessage(exception)
                        + (restored ? "; rollback verified." : "; rollback could not be verified; inspect the network immediately."));
            }
        }
    }

    private static long available(MEStorage storage, AEKey key) {
        return key == null ? 0 : storage.getAvailableStacks().get(key);
    }

    /** Attempts to restore the exact key counts captured immediately before a failed transformation. */
    private static boolean rollback(MEStorage storage, AEItemKey originalKey, long originalBefore,
            AEItemKey cleanedKey, long cleanedBefore, IActionSource source) {
        try {
            if (cleanedKey != null && !cleanedKey.equals(originalKey)) {
                long currentCleaned = available(storage, cleanedKey);
                long newlyAdded = Math.max(0, currentCleaned - cleanedBefore);
                if (newlyAdded > 0 && storage.extract(cleanedKey, newlyAdded, Actionable.MODULATE, source) != newlyAdded) return false;
            }
            long currentOriginal = available(storage, originalKey);
            long missingOriginal = Math.max(0, originalBefore - currentOriginal);
            if (missingOriginal > 0 && storage.insert(originalKey, missingOriginal, Actionable.MODULATE, source) != missingOriginal) return false;
            return available(storage, originalKey) == originalBefore
                    && (cleanedKey == null || cleanedKey.equals(originalKey) || available(storage, cleanedKey) == cleanedBefore);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static void scaleForNetwork(StackCleaner.Analysis analysis, long amount, int maxStackSize) {
        long stackCount = amount / Math.max(1, maxStackSize) + (amount % Math.max(1, maxStackSize) == 0 ? 0 : 1);
        analysis.forbiddenStacks = saturatingMultiply(analysis.forbiddenStacks, stackCount);
        analysis.forbiddenItems = saturatingMultiply(analysis.forbiddenItems, amount);
        analysis.enchantmentsFound = saturatingMultiply(analysis.enchantmentsFound, stackCount);
        analysis.enchantedStacksFound = saturatingMultiply(analysis.enchantedStacksFound, stackCount);
        analysis.graveKeys = saturatingMultiply(analysis.graveKeys, amount);
        analysis.deletedStacks = saturatingMultiply(analysis.deletedStacks, stackCount);
        analysis.deletedItems = saturatingMultiply(analysis.deletedItems, amount);
        analysis.enchantmentsStripped = saturatingMultiply(analysis.enchantmentsStripped, stackCount);
        analysis.transformedStacks = saturatingMultiply(analysis.transformedStacks, stackCount);
    }

    private static long saturatingMultiply(long value, long factor) {
        if (value == 0 || factor == 0) return 0;
        if (value > Long.MAX_VALUE / factor) return Long.MAX_VALUE;
        return value * factor;
    }
}
