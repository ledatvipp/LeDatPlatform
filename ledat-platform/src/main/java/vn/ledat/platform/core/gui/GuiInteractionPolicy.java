package vn.ledat.platform.core.gui;

import java.util.Set;

final class GuiInteractionPolicy {
    private GuiInteractionPolicy() {
    }

    static boolean cancelDrag(boolean cancelBottomClicks, int topSize, Set<Integer> rawSlots) {
        if (cancelBottomClicks) {
            return true;
        }
        if (rawSlots == null || rawSlots.isEmpty()) {
            return false;
        }
        int safeTopSize = Math.max(0, topSize);
        return rawSlots.stream().anyMatch(slot -> slot != null && slot >= 0 && slot < safeTopSize);
    }
}
