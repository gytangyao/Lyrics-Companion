package com.zuoqirun.lyricscompanion;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * The context an extra display's overlay window is built with: everything it draws is scoped to
 * that one slot.
 *
 * <p>The window itself behaves like the secondary one — it is on a non-default display and uses
 * the secondary half of every preference — but its display-scoped values come from the slot's own
 * file, so changing the HUD never moves the lyrics on the driving display.
 */
final class DisplaySlotContext extends ContextWrapper implements DisplaySlotHost {
    /** Which screen a settings page edits; absent means the page was opened for main/secondary. */
    static final String EXTRA_SLOT = "display_slot";

    private final int slot;

    DisplaySlotContext(Context base, int slot) {
        super(base);
        this.slot = normalizeSlot(slot);
    }

    @Override public SharedPreferences displaySlotPreferences() {
        return preferencesFor(this, slot);
    }

    /**
     * The slot a settings page was opened for. Pages opened from older entry points only carry
     * the boolean secondary extra, which still maps to the main and secondary slots.
     */
    static int slotFrom(Intent intent, boolean secondary) {
        int slot = intent == null ? -1 : intent.getIntExtra(EXTRA_SLOT, -1);
        if (slot >= 0) return slot;
        return secondary ? DisplaySlotRegistry.SECONDARY_SLOT : DisplaySlotRegistry.MAIN_SLOT;
    }

    /** Slots 0 (main) and 1 (the legacy secondary) live in the shared preference file. */
    static SharedPreferences preferencesFor(Context context, int slot) {
        return slot < DisplaySlotRegistry.FIRST_EXTRA_SLOT ? AppPreferences.get(context)
                : context.getSharedPreferences(fileName(slot), Context.MODE_PRIVATE);
    }

    static String fileName(int slot) {
        return AppPreferences.FILE + "_display" + normalizeSlot(slot);
    }

    static int normalizeSlot(int slot) {
        return Math.max(2, slot);
    }
}
