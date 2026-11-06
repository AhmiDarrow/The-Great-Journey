package com.ninjacat.greatjourney.clanlands.life.events;

import com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan;
import com.ninjacat.greatjourney.clanlands.life.gathering.ClanNews;

/**
 * The one place Clan events hand a line of news on to the Gatherings ({@link ClanNews}), where the leaders tell the
 * other Clans what has happened since the last full moon. Each line is a whole sentence a leader can say aloud.
 */
public final class EventNews {
    private EventNews() {}

    public static void record(Clan clan, String text) {
        try {
            ClanNews.record(clan, text);
        } catch (RuntimeException e) {
            // news is a nicety: a broken record never stops the event that made it
        }
    }
}
