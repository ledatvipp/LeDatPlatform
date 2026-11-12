package vn.ledat.platform.api.item;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CustomItemKeyTest {
    @Test
    void parsesProviderAndNamespacedIds() {
        assertEquals("itemsadder:weapons:flame_sword", CustomItemKey.parse("ItemsAdder:Weapons:Flame_Sword").canonical());
        assertEquals("oraxen:ruby", CustomItemKey.parse("oraxen:ruby").canonical());
        assertEquals("SWORD:flame_blade".toLowerCase(), CustomItemKey.parse("mmoitems:SWORD:FLAME_BLADE").externalId());
    }

    @Test
    void rejectsUnqualifiedIds() {
        assertThrows(IllegalArgumentException.class, () -> CustomItemKey.parse("diamond_sword"));
    }
}
