package com.ninjacat.greatjourney.qa.client;

import net.minecraft.client.Minecraft;

/** TEST ONLY. One step of a playtest script: ticked every client tick until it says it is done (or times out). */
public interface Step {
    /** Short label for the log and the report. */
    String name();

    /** Called each client tick with the ticks spent on this step so far; true when the step is finished. */
    boolean tick(Minecraft mc, int age);

    /** Ticks before the step is failed and skipped. */
    default int timeout() {
        return 20 * 90;
    }

    /** A step whose failure makes the rest of its scenario pointless (the harness skips to the next scenario). */
    default boolean critical() {
        return false;
    }
}
