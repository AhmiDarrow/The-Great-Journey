package com.ninjacat.greatjourney.clanlands.life;

import com.ninjacat.greatjourney.clanlands.net.ClanPayloads;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Cats talking to one player, RPG style. Lines queued for a player are gathered into a scene and shown in the
 * client's dialogue window (name plate, portrait, typewriter text, choice buttons). The scene's choices stay on the
 * server: the client reports which one was picked, and the server runs that choice's /greatjourney command as the
 * player. The next queued scene opens when the player finishes or dismisses the current one.
 *
 * <p>Each player has their own queue and window, so in multiplayer everyone follows their own story. A player
 * without the client half of the mod (which should not happen) gets the lines in chat instead.
 */
public final class Dialog {
    private Dialog() {}

    /**
     * One line: who speaks, what they say, the choices offered after it (empty for none), how long it stays when
     * shown in chat, and the speaking entity's id for a live portrait (-1 for none).
     */
    public record Line(String speaker, int colour, String text, List<Choice> choices, int pauseTicks, int entityId) {
        public Line(String speaker, int colour, String text, List<Choice> choices, int pauseTicks) {
            this(speaker, colour, text, choices, pauseTicks, -1);
        }

        public static Line say(String speaker, int colour, String text) {
            return new Line(speaker, colour, text, List.of(), pauseFor(text));
        }

        public static Line narrate(String text) {
            return new Line(null, 0xAAAAAA, text, List.of(), pauseFor(text));
        }

        public Line withChoices(Choice... c) {
            return new Line(speaker, colour, text, List.of(c), 0, entityId);
        }

        /** Shows this entity (usually the speaking cat) in the dialogue window's portrait frame. */
        public Line speakerEntity(Entity e) {
            return new Line(speaker, colour, text, choices, pauseTicks, e == null ? -1 : e.getId());
        }

        ClanPayloads.DialogueLine wire() {
            return new ClanPayloads.DialogueLine(speaker == null ? "" : speaker, colour, text, entityId, speaker == null);
        }
    }

    /** A choice: the label on its button and the command it runs (without the leading slash). */
    public record Choice(String label, String command, int colour) {
        public static Choice of(String label, String command) {
            return new Choice(label, command, 0x55FF55);
        }
    }

    /** A scene open in a player's window, and the commands behind its choices. */
    private record Scene(int id, List<Choice> choices, int sentTick) {}

    private static final class State {
        final Deque<Line> pending = new ArrayDeque<>();
        Scene open;
        int chatWait;
        /** No new scene opens before this server tick (see {@link #hold}). */
        int holdUntil;
    }

    private static final Map<UUID, State> STATES = new HashMap<>();
    private static final AtomicInteger NEXT_SCENE = new AtomicInteger(1);
    /** A scene the client never answers is let go after five minutes. */
    private static final int SCENE_TIMEOUT_TICKS = 20 * 60 * 5;

    /** Reading time in chat: about 1.5 s plus 0.06 s per character, capped. */
    static int pauseFor(String text) {
        return Math.min(140, 30 + text.length() * 6 / 5);
    }

    private static State state(ServerPlayer player) {
        return STATES.computeIfAbsent(player.getUUID(), k -> new State());
    }

    public static void play(ServerPlayer player, List<Line> lines) {
        state(player).pending.addAll(lines);
    }

    public static void play(ServerPlayer player, Line... lines) {
        play(player, List.of(lines));
    }

    /**
     * Keeps the player's next scene from opening for {@code ticks} (and, once that time is up, until they stand on the
     * ground, for at most two seconds more): after a teleport the client needs a moment to receive and draw the land
     * and the cats, or the window opens over plain sky with the Clan medallion for a portrait.
     */
    public static void hold(ServerPlayer player, int ticks) {
        State s = state(player);
        s.holdUntil = Math.max(s.holdUntil, player.server.getTickCount() + ticks);
    }

    /** Ambient lines (Clan news, a passing remark): a small box above the hotbar, no window, nothing to answer. */
    public static void subtitle(ServerPlayer player, List<Line> lines) {
        if (lines.isEmpty()) return;
        if (hasClient(player)) {
            List<ClanPayloads.DialogueLine> wire = new ArrayList<>();
            for (Line l : lines) wire.add(l.wire());
            PacketDistributor.sendToPlayer(player, new ClanPayloads.Subtitle(wire));
        } else {
            for (Line l : lines) player.sendSystemMessage(render(l));
        }
    }

    public static void subtitle(ServerPlayer player, Line... lines) {
        subtitle(player, List.of(lines));
    }

    /** Plays the same lines to several players, each in their own window. */
    public static void playTo(Collection<ServerPlayer> players, List<Line> lines) {
        for (ServerPlayer p : players) play(p, lines);
    }

    /** Subtitles for every player within {@code radius} blocks of {@code at} in that level. */
    public static void subtitleNear(ServerLevel level, Vec3 at, double radius, List<Line> lines) {
        for (ServerPlayer p : level.players()) {
            if (p.position().distanceToSqr(at) <= radius * radius) subtitle(p, lines);
        }
    }

    /** Drops anything still queued for the player and closes their window (a new scene interrupts the old one). */
    public static void clear(ServerPlayer player) {
        State s = STATES.get(player.getUUID());
        if (s == null) return;
        s.pending.clear();
        s.chatWait = 0;
        if (s.open != null) {
            if (hasClient(player)) PacketDistributor.sendToPlayer(player, new ClanPayloads.CloseDialogue(s.open.id()));
            s.open = null;
        }
    }

    /** Forgets a player who left: their window is gone, and queued lines would greet them out of place next time. */
    public static void forget(ServerPlayer player) {
        STATES.remove(player.getUUID());
    }

    /**
     * Forgets everyone (the server stopped). Singleplayer keeps this class loaded between worlds: a scene left open in
     * one world, timed by that server's tick count, would otherwise block the same player's dialogue in the next.
     */
    public static void reset() {
        STATES.clear();
    }

    /** Whether the player has lines waiting or a scene open. */
    public static boolean busy(ServerPlayer player) {
        State s = STATES.get(player.getUUID());
        return s != null && (!s.pending.isEmpty() || s.open != null);
    }

    /** The client picked choice {@code index} of scene {@code sceneId}: run that choice's command as the player. */
    public static void onChoice(ServerPlayer player, int sceneId, int index) {
        State s = STATES.get(player.getUUID());
        if (s == null || s.open == null || s.open.id() != sceneId) return;
        List<Choice> choices = s.open.choices();
        if (index < 0 || index >= choices.size()) return;     // an impossible answer leaves the question open
        s.open = null;
        String command = choices.get(index).command();
        MinecraftServer server = player.getServer();
        if (server != null) server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), command);
    }

    /** The client finished or dismissed scene {@code sceneId}. */
    public static void onClosed(ServerPlayer player, int sceneId) {
        State s = STATES.get(player.getUUID());
        if (s != null && s.open != null && s.open.id() == sceneId) s.open = null;
    }

    /** Called every server tick: opens the next scene for each player whose window is free. */
    public static void tick(MinecraftServer server) {
        int now = server.getTickCount();
        var it = STATES.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            State s = e.getValue();
            if (p == null) {
                it.remove();
                continue;
            }
            if (s.open != null) {
                if (now - s.open.sentTick() < SCENE_TIMEOUT_TICKS) continue;
                if (hasClient(p)) PacketDistributor.sendToPlayer(p, new ClanPayloads.CloseDialogue(s.open.id()));
                s.open = null;
            }
            if (s.pending.isEmpty()) continue;
            if (com.ninjacat.greatjourney.clanlands.life.tour.Tour.touring(p)) continue;   // held until the tour ends
            if (now < s.holdUntil || (s.holdUntil > 0 && now < s.holdUntil + 40 && !p.onGround())) continue;   // held while the camp draws
            if (hasClient(p)) {
                sendScene(p, s, now);
            } else {
                chatTick(p, s);
            }
        }
    }

    /**
     * A line that names its speaker but no entity gets one, if a creature of that name stands within 32 blocks of
     * the player (Clan cats in camp, the StarClan cats in the dream, Midnight, the Tribe): their portrait shows.
     */
    private static Line withPortrait(ServerPlayer p, Line l) {
        if (l.entityId() >= 0 || l.speaker() == null || l.speaker().isBlank()) return l;
        String want = l.speaker().trim();
        var found = p.serverLevel().getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, p.getBoundingBox().inflate(32),
                e -> e != p && e.getCustomName() != null && e.getCustomName().getString().replace("♂", "").replace("♀", "").trim().equals(want));
        if (found.isEmpty()) return l;
        found.sort(java.util.Comparator.comparingDouble(e -> e.distanceToSqr(p)));
        return l.speakerEntity(found.get(0));
    }

    /** Gathers queued lines up to (and including) the first line with choices into one scene and sends it. */
    private static void sendScene(ServerPlayer p, State s, int now) {
        org.slf4j.LoggerFactory.getLogger(Dialog.class).debug("Dialogue: sending a scene to {} ({} lines waiting)", p.getGameProfile().getName(), s.pending.size());
        List<ClanPayloads.DialogueLine> lines = new ArrayList<>();
        List<Choice> choices = List.of();
        while (!s.pending.isEmpty()) {
            Line l = s.pending.poll();
            lines.add(withPortrait(p, l).wire());
            if (!l.choices().isEmpty()) {
                choices = l.choices();
                break;
            }
        }
        int id = NEXT_SCENE.getAndIncrement();
        List<ClanPayloads.ChoiceView> views = new ArrayList<>();
        for (Choice c : choices) views.add(new ClanPayloads.ChoiceView(c.label(), c.colour()));
        s.open = new Scene(id, choices, now);
        PacketDistributor.sendToPlayer(p, new ClanPayloads.OpenDialogue(id, lines, views));
    }

    /** Chat fallback: one line every few seconds, choices as clickable text. */
    private static void chatTick(ServerPlayer p, State s) {
        if (s.chatWait > 0) {
            s.chatWait--;
            return;
        }
        Line line = s.pending.poll();
        if (line == null) return;
        p.sendSystemMessage(render(line));
        s.chatWait = line.pauseTicks();
    }

    private static boolean hasClient(ServerPlayer p) {
        return p.connection != null && p.connection.hasChannel(ClanPayloads.OpenDialogue.TYPE);
    }

    /** A line as chat text (the fallback when a player has no dialogue window). */
    public static Component render(Line line) {
        MutableComponent out = Component.empty();
        if (line.speaker() != null) {
            out.append(Component.literal(line.speaker()).withStyle(Style.EMPTY.withColor(line.colour()).withBold(true)));
            out.append(Component.literal(": ").withStyle(ChatFormatting.GRAY));
            out.append(Component.literal(line.text()).withStyle(ChatFormatting.WHITE));
        } else {
            out.append(Component.literal(line.text()).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
        }
        for (Choice c : line.choices()) {
            out.append(Component.literal("  "));
            out.append(Component.literal("[" + c.label() + "]").withStyle(Style.EMPTY.withColor(c.colour()).withBold(true)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/" + c.command()))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(c.label())))));
        }
        return out;
    }
}
