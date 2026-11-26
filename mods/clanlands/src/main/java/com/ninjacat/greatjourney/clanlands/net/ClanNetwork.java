package com.ninjacat.greatjourney.clanlands.net;

import com.ninjacat.greatjourney.clanlands.life.Dialog;
import com.ninjacat.greatjourney.clanlands.life.rpg.Journals;
import com.ninjacat.greatjourney.clanlands.life.tour.Tour;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Registers the dialogue and journal messages. Client-bound handlers go through {@link ClientHooks} so this class
 * is safe on a dedicated server; server-bound handlers run on the server thread, and never trust the client: ids out
 * of range are dropped, a stale scene's answer is ignored by {@link Dialog}, and a client flooding the server with
 * answers or journal requests is throttled.
 */
public final class ClanNetwork {
    private ClanNetwork() {}

    public static final String VERSION = "3";

    public static void register(IEventBus modBus) {
        modBus.addListener(ClanNetwork::payloads);
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> LAST.remove(e.getEntity().getUUID()));
    }

    /** Most choices a scene can offer; an index beyond it is a forged packet. */
    static final int MAX_CHOICES = 64;
    /** Least gap, in ticks, between two journal requests from one player; most dialogue answers in one second. */
    static final int JOURNAL_GAP = 10, ANSWERS_PER_SECOND = 10;

    /** Per player: the tick of their last journal request, and the start and count of their current answer second. */
    private static final Map<UUID, int[]> LAST = new HashMap<>();

    private static int[] last(ServerPlayer sp) {
        return LAST.computeIfAbsent(sp.getUUID(), k -> new int[] {Integer.MIN_VALUE / 2, Integer.MIN_VALUE / 2, 0, Integer.MIN_VALUE / 2});
    }

    /** Whether this journal request comes long enough after the player's last one. */
    static boolean allowJournal(ServerPlayer sp) {
        int now = sp.server.getTickCount();
        int[] l = last(sp);
        if (now - l[0] < JOURNAL_GAP) return false;
        l[0] = now;
        return true;
    }

    /** Whether this map request comes long enough after the player's last one (maps refresh twice a second). */
    static boolean allowMap(ServerPlayer sp) {
        int now = sp.server.getTickCount();
        int[] l = last(sp);
        if (now - l[3] < 8) return false;
        l[3] = now;
        return true;
    }

    /** Whether this dialogue answer is within the player's allowance (a real player never comes near it). */
    static boolean allowAnswer(ServerPlayer sp) {
        int now = sp.server.getTickCount();
        int[] l = last(sp);
        if (now - l[1] >= 20) {
            l[1] = now;
            l[2] = 0;
        }
        return ++l[2] <= ANSWERS_PER_SECOND;
    }

    private static void payloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar r = event.registrar(VERSION);
        r.playToClient(ClanPayloads.OpenDialogue.TYPE, ClanPayloads.OpenDialogue.STREAM_CODEC,
                (p, ctx) -> ctx.enqueueWork(() -> ClientHooks.openDialogue.accept(p)));
        r.playToClient(ClanPayloads.CloseDialogue.TYPE, ClanPayloads.CloseDialogue.STREAM_CODEC,
                (p, ctx) -> ctx.enqueueWork(() -> ClientHooks.closeDialogue.accept(p)));
        r.playToClient(ClanPayloads.Subtitle.TYPE, ClanPayloads.Subtitle.STREAM_CODEC,
                (p, ctx) -> ctx.enqueueWork(() -> ClientHooks.subtitle.accept(p)));
        r.playToClient(ClanPayloads.OpenJournal.TYPE, ClanPayloads.OpenJournal.STREAM_CODEC,
                (p, ctx) -> ctx.enqueueWork(() -> ClientHooks.openJournal.accept(p)));
        r.playToClient(ClanPayloads.JournalUpdate.TYPE, ClanPayloads.JournalUpdate.STREAM_CODEC,
                (p, ctx) -> ctx.enqueueWork(() -> ClientHooks.journalUpdate.accept(p)));
        r.playToServer(ClanPayloads.DialogueChoice.TYPE, ClanPayloads.DialogueChoice.STREAM_CODEC,
                (p, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer sp && p.sceneId() > 0 && p.index() >= 0 && p.index() < MAX_CHOICES
                            && allowAnswer(sp)) {
                        Dialog.onChoice(sp, p.sceneId(), p.index());
                    }
                }));
        r.playToServer(ClanPayloads.DialogueClosed.TYPE, ClanPayloads.DialogueClosed.STREAM_CODEC,
                (p, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer sp && p.sceneId() > 0) Dialog.onClosed(sp, p.sceneId());
                }));
        r.playToServer(ClanPayloads.RequestJournal.TYPE, ClanPayloads.RequestJournal.STREAM_CODEC,
                (p, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer sp && allowJournal(sp)) Journals.open(sp);
                }));
        r.playToClient(ClanPayloads.MapData.TYPE, ClanPayloads.MapData.STREAM_CODEC,
                (p, ctx) -> ctx.enqueueWork(() -> ClientHooks.mapData.accept(p)));
        r.playToServer(ClanPayloads.RequestMap.TYPE, ClanPayloads.RequestMap.STREAM_CODEC,
                (p, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer sp && p.region() >= -1 && p.region() <= 2 && allowMap(sp)) {
                        var data = com.ninjacat.greatjourney.clanlands.life.rpg.ClanMaps.view(sp, p.region());
                        if (data != null && sp.connection.hasChannel(ClanPayloads.MapData.TYPE)) {
                            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sp, data);
                        }
                    }
                }));
        r.playToClient(ClanPayloads.TourStart.TYPE, ClanPayloads.TourStart.STREAM_CODEC,
                (p, ctx) -> ctx.enqueueWork(() -> ClientHooks.tourStart.accept(p)));
        r.playToClient(ClanPayloads.TourGo.TYPE, ClanPayloads.TourGo.STREAM_CODEC,
                (p, ctx) -> ctx.enqueueWork(() -> ClientHooks.tourGo.accept(p)));
        r.playToClient(ClanPayloads.TourEnd.TYPE, ClanPayloads.TourEnd.STREAM_CODEC,
                (p, ctx) -> ctx.enqueueWork(() -> ClientHooks.tourEnd.accept(p)));
        r.playToServer(ClanPayloads.TourNext.TYPE, ClanPayloads.TourNext.STREAM_CODEC,
                (p, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer sp) Tour.onNext(sp, p.index());
                }));
        r.playToServer(ClanPayloads.TourDone.TYPE, ClanPayloads.TourDone.STREAM_CODEC,
                (p, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer sp) Tour.onDone(sp);
                }));
    }
}
