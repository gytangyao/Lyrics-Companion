package com.zuoqirun.lyricscompanion;

import android.content.SharedPreferences;

/**
 * Implemented by anything that owns one screen's configuration: the overlay window of an extra
 * display, and every settings page opened for such a display.
 *
 * <p>Until now the app had exactly two screens — the main overlay and one secondary — and their
 * settings lived in one preference file, separated only by the {@code _main} / {@code _secondary}
 * key suffix. Extra screens each get their own file instead, so a HUD and a driving display can
 * both show lyrics with independent parameters. {@link AppPreferences} asks this interface which
 * store a display-scoped read or write belongs to, which is what keeps the choice out of the
 * hundreds of call sites that only ever name a preference.
 */
interface DisplaySlotHost {
    /**
     * The preference store for the screen this host belongs to. The main overlay and the legacy
     * secondary slot return the shared store, which is where their keys have always been.
     */
    SharedPreferences displaySlotPreferences();
}
