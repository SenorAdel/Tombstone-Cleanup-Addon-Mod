package dev.tombstonecleanup;

import java.util.LinkedHashMap;
import java.util.Map;

/** Registry-identifier policy shared by the runtime cleaner and its unit tests. */
public final class StackPolicy {
    public static final String TOMBSTONE_NAMESPACE = "tombstone";
    public static final String GRAVE_KEY = "tombstone:grave_key";

    private StackPolicy() {}

    public static boolean isGraveKey(String itemId) {
        return GRAVE_KEY.equals(itemId);
    }

    public static boolean isTombstoneItem(String itemId) {
        return namespace(itemId).equals(TOMBSTONE_NAMESPACE);
    }

    public static boolean isForbiddenItem(String itemId) {
        return isTombstoneItem(itemId) && !isGraveKey(itemId);
    }

    public static boolean isTombstoneEnchantment(String enchantmentId) {
        return namespace(enchantmentId).equals(TOMBSTONE_NAMESPACE);
    }

    public static Map<String, Integer> withoutTombstoneEnchantments(Map<String, Integer> enchantments) {
        Map<String, Integer> kept = new LinkedHashMap<>();
        enchantments.forEach((id, level) -> {
            if (!isTombstoneEnchantment(id)) {
                kept.put(id, level);
            }
        });
        return Map.copyOf(kept);
    }

    private static String namespace(String identifier) {
        int separator = identifier.indexOf(':');
        return separator < 0 ? "minecraft" : identifier.substring(0, separator);
    }
}
