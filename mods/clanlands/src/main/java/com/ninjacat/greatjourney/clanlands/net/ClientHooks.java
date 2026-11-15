package com.ninjacat.greatjourney.clanlands.net;

import java.util.function.Consumer;

/**
 * The client's handlers for server messages, installed by the client side at startup. Common code only ever calls
 * through these fields, so a dedicated server never loads a client class. On a server they stay as no-ops.
 */
public final class ClientHooks {
    private ClientHooks() {}

    public static volatile Consumer<ClanPayloads.OpenDialogue> openDialogue = p -> {};
    public static volatile Consumer<ClanPayloads.CloseDialogue> closeDialogue = p -> {};
    public static volatile Consumer<ClanPayloads.Subtitle> subtitle = p -> {};
    public static volatile Consumer<ClanPayloads.OpenJournal> openJournal = p -> {};
    public static volatile Consumer<ClanPayloads.JournalUpdate> journalUpdate = p -> {};
    public static volatile Consumer<ClanPayloads.MapData> mapData = p -> {};
    public static volatile Consumer<ClanPayloads.TourStart> tourStart = p -> {};
    public static volatile Consumer<ClanPayloads.TourGo> tourGo = p -> {};
    public static volatile Consumer<ClanPayloads.TourEnd> tourEnd = p -> {};
}
