package dev.tombstonecleanup;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CleanupReport {
    public final Instant createdAt = Instant.now();
    public final String mode;
    public final Map<String, CategoryStats> categories = new LinkedHashMap<>();
    public final List<String> failures = new ArrayList<>();
    public final List<String> unsupported = new ArrayList<>();
    public final List<String> notes = new ArrayList<>();

    public int onlinePlayersProcessed;
    public int offlinePlayersProcessed;
    public int offlinePlayersSkippedOnline;
    public int enderChestsProcessed;
    public int worldContainersProcessed;
    public int tombstoneBlockEntitiesSkipped;
    public int droppedStacksProcessed;
    public int loadedChunksProcessed;
    public int dimensionsProcessed;
    public int ae2NetworksProcessed;
    public int ae2Operations;
    public boolean ae2Available;

    public long forbiddenStacksFound;
    public long forbiddenItemsFound;
    public long enchantmentsFound;
    public long enchantedStacksFound;
    public long graveKeysPreserved;
    public long deletedStacks;
    public long deletedItems;
    public long enchantmentsStripped;
    public long transformedStacks;

    public CleanupReport(String mode) {
        this.mode = mode;
    }

    public void recordAnalysis(String category, StackCleaner.Analysis analysis) {
        CategoryStats stats = category(category);
        stats.stacksInspected++;
        stats.forbiddenStacksFound += analysis.forbiddenStacks;
        stats.forbiddenItemsFound += analysis.forbiddenItems;
        stats.enchantmentsFound += analysis.enchantmentsFound;
        stats.enchantedStacksFound += analysis.enchantedStacksFound;
        stats.graveKeysPreserved += analysis.graveKeys;
        forbiddenStacksFound += analysis.forbiddenStacks;
        forbiddenItemsFound += analysis.forbiddenItems;
        enchantmentsFound += analysis.enchantmentsFound;
        enchantedStacksFound += analysis.enchantedStacksFound;
        graveKeysPreserved += analysis.graveKeys;
        if (analysis.unsupported > 0) {
            unsupported(category + ": one or more nested inventories could not be safely rewritten.");
        }
        if (analysis.blockedDeletion) {
            unsupported(category + ": a forbidden container holds other items or Grave's Keys and was preserved to avoid losing them.");
        }
    }

    public void recordApplied(String category, StackCleaner.Analysis analysis) {
        CategoryStats stats = category(category);
        stats.deletedStacks += analysis.deletedStacks;
        stats.deletedItems += analysis.deletedItems;
        stats.enchantmentsStripped += analysis.enchantmentsStripped;
        stats.transformedStacks += analysis.transformedStacks;
        deletedStacks += analysis.deletedStacks;
        deletedItems += analysis.deletedItems;
        enchantmentsStripped += analysis.enchantmentsStripped;
        transformedStacks += analysis.transformedStacks;
    }

    public void failure(String message) {
        failures.add(message);
    }

    public void unsupported(String message) {
        if (!unsupported.contains(message)) unsupported.add(message);
    }

    private CategoryStats category(String name) {
        return categories.computeIfAbsent(name, ignored -> new CategoryStats());
    }

    public String summary() {
        StringBuilder out = new StringBuilder();
        out.append("Tombstone Cleanup ").append(mode).append('\n');
        out.append("Forbidden Tombstone items found: ").append(forbiddenStacksFound)
                .append(" stacks / ").append(forbiddenItemsFound).append(" items\n");
        out.append("Tombstone enchantments found: ").append(enchantmentsFound)
                .append(" across ").append(enchantedStacksFound).append(" stacks\n");
        out.append("Online players processed: ").append(onlinePlayersProcessed).append('\n');
        out.append("Offline players processed: ").append(offlinePlayersProcessed)
                .append(" (skipped while online: ").append(offlinePlayersSkippedOnline).append(")\n");
        out.append("Ender Chests processed: ").append(enderChestsProcessed).append('\n');
        out.append("World containers processed: ").append(worldContainersProcessed).append('\n');
        out.append("Tombstone block entities skipped to preserve graves: ").append(tombstoneBlockEntitiesSkipped).append('\n');
        out.append("Dropped item stacks processed: ").append(droppedStacksProcessed).append('\n');
        out.append("Loaded chunks processed: ").append(loadedChunksProcessed)
                .append(" in ").append(dimensionsProcessed).append(" dimensions\n");
        out.append("AE2: ").append(ae2Available ? ae2NetworksProcessed + " networks / " + ae2Operations + " operations" : "not loaded; skipped")
                .append('\n');
        out.append("Grave Keys preserved: ").append(graveKeysPreserved).append('\n');
        if (mode.equals("Scan")) {
            out.append("No changes were made.\n");
        } else {
            out.append("Deleted stacks: ").append(deletedStacks).append('\n');
            out.append("Deleted individual items: ").append(deletedItems).append('\n');
            out.append("Enchantments stripped: ").append(enchantmentsStripped).append('\n');
            out.append("Transformed stacks: ").append(transformedStacks).append('\n');
        }
        out.append("Failures: ").append(failures.size()).append('\n');
        out.append("Skipped/unsupported storage: ").append(unsupported.size()).append('\n');
        return out.toString();
    }

    public String detailedText() {
        StringBuilder out = new StringBuilder(summary());
        out.append("Report time (UTC): ").append(createdAt).append('\n');
        out.append("\nCategory details\n");
        categories.forEach((name, stats) -> out.append("- ").append(name).append(": inspected ")
                .append(stats.stacksInspected).append(" stacks, found ").append(stats.forbiddenStacksFound)
                .append(" forbidden stacks / ").append(stats.forbiddenItemsFound).append(" items, ")
                .append(stats.enchantmentsFound).append(" Tombstone enchantments, preserved ")
                .append(stats.graveKeysPreserved).append(" Grave Keys; deleted ")
                .append(stats.deletedStacks).append(" stacks / ").append(stats.deletedItems)
                .append(" items, stripped ").append(stats.enchantmentsStripped).append(" enchantments, transformed ")
                .append(stats.transformedStacks).append(" stacks.\n"));
        if (!failures.isEmpty()) {
            out.append("\nFailures\n");
            failures.forEach(message -> out.append("- ").append(message).append('\n'));
        }
        if (!unsupported.isEmpty()) {
            out.append("\nSkipped or unsupported storage\n");
            unsupported.forEach(message -> out.append("- ").append(message).append('\n'));
        }
        if (!notes.isEmpty()) {
            out.append("\nNotes\n");
            notes.forEach(message -> out.append("- ").append(message).append('\n'));
        }
        return out.toString();
    }

    private static final class CategoryStats {
        long stacksInspected;
        long forbiddenStacksFound;
        long forbiddenItemsFound;
        long enchantmentsFound;
        long enchantedStacksFound;
        long graveKeysPreserved;
        long deletedStacks;
        long deletedItems;
        long enchantmentsStripped;
        long transformedStacks;
    }
}
