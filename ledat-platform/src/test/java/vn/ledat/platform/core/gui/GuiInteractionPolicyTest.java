package vn.ledat.platform.core.gui;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuiInteractionPolicyTest {
    @Test
    void cancelBottomClicksMakesEveryDragInert() {
        assertTrue(GuiInteractionPolicy.cancelDrag(true, 54, Set.of(60, 61)));
    }

    @Test
    void permissiveBottomStillCancelsAnyDragTouchingTheTopInventory() {
        assertTrue(GuiInteractionPolicy.cancelDrag(false, 54, Set.of(53, 60)));
        assertFalse(GuiInteractionPolicy.cancelDrag(false, 54, Set.of(54, 60)));
        assertFalse(GuiInteractionPolicy.cancelDrag(false, 54, Set.of()));
    }
}
