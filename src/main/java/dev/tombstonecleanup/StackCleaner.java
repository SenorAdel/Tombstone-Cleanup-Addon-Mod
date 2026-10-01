package dev.tombstonecleanup;

import java.util.List;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.item.component.ItemContainerContents;

/** Cleans item stacks through Minecraft's current data-component APIs. */
public final class StackCleaner {
    private static final int MAX_NESTING_DEPTH = 16;

    private StackCleaner() {}

    public static Analysis inspect(ItemStack input, boolean prepareChanges) {
        return inspect(input, prepareChanges, 0);
    }

    private static Analysis inspect(ItemStack input, boolean prepareChanges, int depth) {
        if (input.isEmpty()) return Analysis.empty(input);
        if (depth > MAX_NESTING_DEPTH) {
            Analysis result = Analysis.empty(input.copy());
            result.unsupported++;
            result.protectedContents = true;
            return result;
        }

        Identifier itemId = BuiltInRegistries.ITEM.getKey(input.getItem());
        if (StackPolicy.isGraveKey(itemId.toString())) {
            Analysis result = Analysis.empty(input.copy());
            result.graveKeys = input.getCount();
            result.protectedContents = true;
            return result;
        }

        boolean forbidden = StackPolicy.isForbiddenItem(itemId.toString());
        Analysis result = Analysis.empty(input.copy());
        if (forbidden) {
            result.forbiddenStacks = 1;
            result.forbiddenItems = input.getCount();
        }

        boolean hasProtectedChild = false;
        boolean childChanged = false;
        boolean changedHere = false;

        ItemContainerContents containerContents = result.stack.get(DataComponents.CONTAINER);
        if (containerContents != null && containerContents.getSlots() > 0) {
            NonNullList<ItemStack> childStacks = NonNullList.withSize(containerContents.getSlots(), ItemStack.EMPTY);
            containerContents.copyInto(childStacks);
            boolean containerChanged = false;
            for (int slot = 0; slot < childStacks.size(); slot++) {
                ItemStack child = childStacks.get(slot);
                if (child.isEmpty()) continue;
                Analysis childResult = inspect(child, prepareChanges, depth + 1);
                result.merge(childResult);
                hasProtectedChild |= isProtectedChild(child, childResult);
                if (prepareChanges && (childResult.delete || childResult.changed)) {
                    childStacks.set(slot, childResult.delete ? ItemStack.EMPTY : childResult.stack);
                    containerChanged = true;
                }
            }
            if (prepareChanges && containerChanged) {
                result.stack.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(childStacks));
                result.changed = true;
                childChanged = true;
                changedHere = true;
            }
        }

        ChargedProjectiles projectiles = result.stack.get(DataComponents.CHARGED_PROJECTILES);
        if (projectiles != null && !projectiles.isEmpty()) {
            List<ItemStack> childStacks = projectiles.itemCopies();
            boolean projectileChanged = false;
            for (int i = 0; i < childStacks.size(); i++) {
                ItemStack child = childStacks.get(i);
                Analysis childResult = inspect(child, prepareChanges, depth + 1);
                result.merge(childResult);
                hasProtectedChild |= isProtectedChild(child, childResult);
                if (prepareChanges && (childResult.delete || childResult.changed)) {
                    childStacks.set(i, childResult.delete ? ItemStack.EMPTY : childResult.stack);
                    projectileChanged = true;
                }
            }
            if (prepareChanges && projectileChanged) {
                childStacks.removeIf(ItemStack::isEmpty);
                result.stack.set(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.ofNonEmpty(childStacks));
                result.changed = true;
                childChanged = true;
                changedHere = true;
            }
        }

        BundleContents bundle = result.stack.get(DataComponents.BUNDLE_CONTENTS);
        if (bundle != null && !bundle.isEmpty()) {
            boolean hasTarget = false;
            for (ItemStack child : bundle.itemCopyStream().toList()) {
                Analysis childResult = inspect(child, false, depth + 1);
                result.mergeFindings(childResult);
                hasProtectedChild |= isProtectedChild(child, childResult);
                hasTarget |= childResult.forbiddenStacks > 0 || childResult.enchantmentsFound > 0;
            }
            // The current bundle component does not expose a lossless public reconstruction API for its selected item.
            if (hasTarget) result.unsupported++;
        }

        int enchantmentsFoundHere = countTombstoneEnchantments(result.stack, DataComponents.ENCHANTMENTS)
                + countTombstoneEnchantments(result.stack, DataComponents.STORED_ENCHANTMENTS);
        if (enchantmentsFoundHere > 0) {
            result.enchantmentsFound += enchantmentsFoundHere;
            result.enchantedStacksFound++;
            if (prepareChanges && !forbidden) {
                int removed = stripTombstoneEnchantments(result.stack, DataComponents.ENCHANTMENTS)
                        + stripTombstoneEnchantments(result.stack, DataComponents.STORED_ENCHANTMENTS);
                if (removed > 0) {
                    result.enchantmentsStripped += removed;
                    result.changed = true;
                    changedHere = true;
                }
            }
        }

        if (forbidden) {
            if (hasProtectedChild) {
                result.blockedDeletion = true;
                result.protectedContents = true;
                result.unsupported++;
            } else {
                result.stack = input.copy();
                result.changed = false;
                result.enchantmentsStripped = 0;
                result.transformedStacks = 0;
                result.delete = true;
                result.deletedStacks++;
                result.deletedItems += input.getCount();
                // A forbidden container may contain other forbidden items; their content is removed with the outer stack.
                result.deletedStacks += result.nestedDeletedStacks;
                result.deletedItems += result.nestedDeletedItems;
            }
        } else {
            result.deletedStacks += result.nestedDeletedStacks;
            result.deletedItems += result.nestedDeletedItems;
        }

        result.protectedContents |= hasProtectedChild;
        result.changed |= childChanged;
        if (!result.delete && changedHere) result.transformedStacks++;
        if (result.changed && result.transformedStacks == 0) result.transformedStacks = 1;
        return result;
    }

    private static boolean isProtectedChild(ItemStack child, Analysis analysis) {
        if (child.isEmpty()) return false;
        Identifier id = BuiltInRegistries.ITEM.getKey(child.getItem());
        return !StackPolicy.isForbiddenItem(id.toString()) || analysis.protectedContents || analysis.blockedDeletion;
    }

    private static int countTombstoneEnchantments(ItemStack stack, net.minecraft.core.component.DataComponentType<ItemEnchantments> type) {
        ItemEnchantments enchantments = stack.get(type);
        if (enchantments == null) return 0;
        int count = 0;
        for (Holder<Enchantment> holder : enchantments.keySet()) {
            Optional<ResourceKey<Enchantment>> key = holder.unwrapKey();
            if (key.isPresent() && StackPolicy.isTombstoneEnchantment(key.get().identifier().toString())) count++;
        }
        return count;
    }

    private static int stripTombstoneEnchantments(ItemStack stack, net.minecraft.core.component.DataComponentType<ItemEnchantments> type) {
        ItemEnchantments enchantments = stack.get(type);
        if (enchantments == null || enchantments.isEmpty()) return 0;
        int previousSize = enchantments.size();
        ItemEnchantments.Mutable mutable = new ItemEnchantments.Mutable(enchantments);
        mutable.removeIf(holder -> holder.unwrapKey()
                .map(key -> StackPolicy.isTombstoneEnchantment(key.identifier().toString()))
                .orElse(false));
        int removed = previousSize - mutable.keySet().size();
        if (removed > 0) {
            stack.set(type, mutable.toImmutable());
        }
        return removed;
    }

    public static final class Analysis {
        public ItemStack stack;
        public boolean delete;
        public boolean blockedDeletion;
        public boolean changed;
        public boolean protectedContents;
        public long forbiddenStacks;
        public long forbiddenItems;
        public long enchantmentsFound;
        public long enchantedStacksFound;
        public long graveKeys;
        public long deletedStacks;
        public long deletedItems;
        public long enchantmentsStripped;
        public long transformedStacks;
        public int unsupported;
        private long nestedDeletedStacks;
        private long nestedDeletedItems;

        private Analysis(ItemStack stack) {
            this.stack = stack;
        }

        private static Analysis empty(ItemStack stack) {
            return new Analysis(stack);
        }

        private void merge(Analysis child) {
            forbiddenStacks += child.forbiddenStacks;
            forbiddenItems += child.forbiddenItems;
            enchantmentsFound += child.enchantmentsFound;
            enchantedStacksFound += child.enchantedStacksFound;
            graveKeys += child.graveKeys;
            enchantmentsStripped += child.enchantmentsStripped;
            transformedStacks += child.transformedStacks;
            unsupported += child.unsupported;
            nestedDeletedStacks += child.deletedStacks;
            nestedDeletedItems += child.deletedItems;
            protectedContents |= child.protectedContents;
        }

        private void mergeFindings(Analysis child) {
            forbiddenStacks += child.forbiddenStacks;
            forbiddenItems += child.forbiddenItems;
            enchantmentsFound += child.enchantmentsFound;
            enchantedStacksFound += child.enchantedStacksFound;
            graveKeys += child.graveKeys;
            unsupported += child.unsupported;
            protectedContents |= child.protectedContents;
        }
    }
}
