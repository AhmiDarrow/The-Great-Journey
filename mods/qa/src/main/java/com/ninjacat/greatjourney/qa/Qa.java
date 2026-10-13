package com.ninjacat.greatjourney.qa;

/**
 * TEST ONLY. Switches for the playtest harness, all read from JVM system properties that tools/playtest.py sets:
 * {@code greatjourney.qa.suite} (the scenarios this client plays), {@code greatjourney.qa.background} (true: never
 * show a window, take focus or grab the mouse). Without a suite nothing in this mod does anything to the game.
 */
public final class Qa {
    private Qa() {}

    public static final String SUITE = System.getProperty("greatjourney.qa.suite", "");
    public static final boolean BACKGROUND = Boolean.parseBoolean(System.getProperty("greatjourney.qa.background", "true"));
    /** Multiplayer: the server this client joins ("host:port"), or empty for singleplayer worlds. */
    public static final String SERVER = System.getProperty("greatjourney.qa.server", "");
    public static final String TAG = "[QA]";

    public static boolean driving() {
        return !SUITE.isEmpty();
    }
}
