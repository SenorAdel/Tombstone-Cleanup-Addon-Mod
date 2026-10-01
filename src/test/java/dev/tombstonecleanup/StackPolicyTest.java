package dev.tombstonecleanup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class StackPolicyTest {
    @Test
    void minecraftDiamondIsUnchanged() {
        assertFalse(StackPolicy.isForbiddenItem("minecraft:diamond"));
    }

    @Test
    void graveKeyIsPreserved() {
        assertTrue(StackPolicy.isGraveKey("tombstone:grave_key"));
        assertFalse(StackPolicy.isForbiddenItem("tombstone:grave_key"));
    }

    @Test
    void otherTombstoneItemsAreDeletedByPolicy() {
        assertTrue(StackPolicy.isForbiddenItem("tombstone:scroll_of_magic"));
        assertTrue(StackPolicy.isForbiddenItem("tombstone:future_item"));
    }

    @Test
    void vanillaArmorEnchantmentsRemainUnchanged() {
        Map<String, Integer> enchantments = Map.of("minecraft:protection", 4, "minecraft:unbreaking", 3);
        assertEquals(enchantments, StackPolicy.withoutTombstoneEnchantments(enchantments));
    }

    @Test
    void vanillaEnchantmentsRemainWhenTombstoneEnchantmentIsRemoved() {
        Map<String, Integer> enchantments = Map.of(
                "minecraft:protection", 4,
                "minecraft:unbreaking", 3,
                "tombstone:purification", 2);
        assertEquals(Map.of("minecraft:protection", 4, "minecraft:unbreaking", 3),
                StackPolicy.withoutTombstoneEnchantments(enchantments));
    }

    @Test
    void allTombstoneEnchantmentsAreRemoved() {
        Map<String, Integer> enchantments = Map.of("tombstone:purification", 2, "tombstone:reaping", 1);
        assertTrue(StackPolicy.withoutTombstoneEnchantments(enchantments).isEmpty());
    }

    @Test
    void enchantmentsFromOtherModsRemain() {
        Map<String, Integer> enchantments = Map.of("examplemod:warding", 3);
        assertEquals(enchantments, StackPolicy.withoutTombstoneEnchantments(enchantments));
    }

    @Test
    void cleanupPolicyIsIdempotent() {
        Map<String, Integer> initial = Map.of("minecraft:unbreaking", 3, "tombstone:purification", 2);
        Map<String, Integer> cleaned = StackPolicy.withoutTombstoneEnchantments(initial);
        assertEquals(cleaned, StackPolicy.withoutTombstoneEnchantments(cleaned));
    }
}
