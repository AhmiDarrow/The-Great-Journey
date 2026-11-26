package com.ninjacat.greatjourney.clanlands.net;

import com.ninjacat.greatjourney.clanlands.life.rpg.JournalView;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Everything the dialogue window, the HUD subtitles and the journal send between server and client. The server
 * decides what is said and what each choice does; the client only shows it and reports which choice was picked.
 */
public final class ClanPayloads {
    private ClanPayloads() {}

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> id(String path) {
        return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("clanlands", path));
    }

    /** One spoken or narrated line. {@code speaker} is "" for narration; {@code entityId} is -1 when no portrait. */
    public record DialogueLine(String speaker, int colour, String text, int entityId, boolean narration) {
        static void write(FriendlyByteBuf b, DialogueLine l) {
            b.writeUtf(l.speaker);
            b.writeInt(l.colour);
            b.writeUtf(l.text, 2048);
            b.writeVarInt(l.entityId + 1);
            b.writeBoolean(l.narration);
        }

        static DialogueLine read(FriendlyByteBuf b) {
            return new DialogueLine(b.readUtf(), b.readInt(), b.readUtf(2048), b.readVarInt() - 1, b.readBoolean());
        }
    }

    /** A choice as the client sees it: its label and colour. What it does stays on the server. */
    public record ChoiceView(String label, int colour) {}

    private static void writeLines(FriendlyByteBuf b, List<DialogueLine> lines) {
        b.writeVarInt(lines.size());
        for (DialogueLine l : lines) DialogueLine.write(b, l);
    }

    private static List<DialogueLine> readLines(FriendlyByteBuf b) {
        int n = b.readVarInt();
        List<DialogueLine> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(DialogueLine.read(b));
        return out;
    }

    /** S2C: open (or replace) the dialogue window with a scene. */
    public record OpenDialogue(int sceneId, List<DialogueLine> lines, List<ChoiceView> choices) implements CustomPacketPayload {
        public static final Type<OpenDialogue> TYPE = id("open_dialogue");
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenDialogue> STREAM_CODEC = StreamCodec.of((b, p) -> {
            b.writeVarInt(p.sceneId);
            writeLines(b, p.lines);
            b.writeVarInt(p.choices.size());
            for (ChoiceView c : p.choices) {
                b.writeUtf(c.label);
                b.writeInt(c.colour);
            }
        }, b -> {
            int id = b.readVarInt();
            List<DialogueLine> lines = readLines(b);
            int n = b.readVarInt();
            List<ChoiceView> choices = new ArrayList<>(n);
            for (int i = 0; i < n; i++) choices.add(new ChoiceView(b.readUtf(), b.readInt()));
            return new OpenDialogue(id, lines, choices);
        });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** S2C: close the dialogue window if it still shows this scene (the server moved on). */
    public record CloseDialogue(int sceneId) implements CustomPacketPayload {
        public static final Type<CloseDialogue> TYPE = id("close_dialogue");
        public static final StreamCodec<RegistryFriendlyByteBuf, CloseDialogue> STREAM_CODEC =
                StreamCodec.of((b, p) -> b.writeVarInt(p.sceneId), b -> new CloseDialogue(b.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** S2C: ambient lines (Clan news) shown one after another in a small box above the hotbar. */
    public record Subtitle(List<DialogueLine> lines) implements CustomPacketPayload {
        public static final Type<Subtitle> TYPE = id("subtitle");
        public static final StreamCodec<RegistryFriendlyByteBuf, Subtitle> STREAM_CODEC =
                StreamCodec.of((b, p) -> writeLines(b, p.lines), b -> new Subtitle(readLines(b)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** S2C: open the journal on this view. */
    public record OpenJournal(JournalView view) implements CustomPacketPayload {
        public static final Type<OpenJournal> TYPE = id("open_journal");
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenJournal> STREAM_CODEC =
                JournalView.STREAM_CODEC.map(OpenJournal::new, OpenJournal::view);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** S2C: the journal's contents changed; an open journal redraws, a closed one ignores it. */
    public record JournalUpdate(JournalView view) implements CustomPacketPayload {
        public static final Type<JournalUpdate> TYPE = id("journal_update");
        public static final StreamCodec<RegistryFriendlyByteBuf, JournalUpdate> STREAM_CODEC =
                JournalView.STREAM_CODEC.map(JournalUpdate::new, JournalUpdate::view);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S: the player picked choice {@code index} in scene {@code sceneId}. */
    public record DialogueChoice(int sceneId, int index) implements CustomPacketPayload {
        public static final Type<DialogueChoice> TYPE = id("dialogue_choice");
        public static final StreamCodec<RegistryFriendlyByteBuf, DialogueChoice> STREAM_CODEC = StreamCodec.of(
                (b, p) -> {
                    b.writeVarInt(p.sceneId);
                    b.writeVarInt(p.index);
                }, b -> new DialogueChoice(b.readVarInt(), b.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S: the player finished or dismissed scene {@code sceneId} without choosing. */
    public record DialogueClosed(int sceneId) implements CustomPacketPayload {
        public static final Type<DialogueClosed> TYPE = id("dialogue_closed");
        public static final StreamCodec<RegistryFriendlyByteBuf, DialogueClosed> STREAM_CODEC =
                StreamCodec.of((b, p) -> b.writeVarInt(p.sceneId), b -> new DialogueClosed(b.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S: please send my journal. */
    public record RequestJournal() implements CustomPacketPayload {
        public static final Type<RequestJournal> TYPE = id("request_journal");
        public static final StreamCodec<RegistryFriendlyByteBuf, RequestJournal> STREAM_CODEC =
                StreamCodec.of((b, p) -> {}, b -> new RequestJournal());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // the Clan map ----------------------------------------------------------------------------------------------

    /** One entry of a map's legend: a name and the colour its ground is drawn in. */
    public record MapLegend(String name, int colour) {}

    /** A named spot on the map: a camp, a place from the books, or a big area's label (no marker). */
    public record MapPoi(String name, int kind, int colour, double x, double z) {
        public static final int PLACE = 0, CAMP = 1, AREA = 2;
    }

    /**
     * A quest's place on the map: which quest (the journal's id for it), what to do there, its number among that
     * quest's places (0: only one), and whether it belongs to the quest the player follows (the gold star).
     */
    public record MapMark(String quest, String label, double x, double z, int number, boolean followed) {
        public static MapMark at(String quest, String label, double x, double z) {
            return new MapMark(quest, label, x, z, 0, false);
        }

        public MapMark follow(boolean on) {
            return new MapMark(quest, label, x, z, number, on);
        }
    }

    /**
     * S2C: one region's map. {@code cells} holds a legend index per cell (row by row, {@code size} x {@code size},
     * each {@code cell} blocks wide from {@code minX}, {@code minZ}); {@code shade} the relief, 0-255.
     */
    public record MapData(int region, List<String> regions, int minX, int minZ, int cell, int size, byte[] cells,
                          byte[] shade, List<MapLegend> legend, List<MapPoi> pois, boolean here, double px, double pz,
                          float yaw, List<MapMark> marks) implements CustomPacketPayload {
        public static final Type<MapData> TYPE = id("map_data");
        /** Largest map the client accepts (cells per side). */
        public static final int MAX_SIZE = 256;
        public static final StreamCodec<RegistryFriendlyByteBuf, MapData> STREAM_CODEC = StreamCodec.of((b, p) -> {
            b.writeVarInt(p.region);
            b.writeVarInt(p.regions.size());
            for (String s : p.regions) b.writeUtf(s, 64);
            b.writeInt(p.minX);
            b.writeInt(p.minZ);
            b.writeVarInt(p.cell);
            b.writeVarInt(p.size);
            b.writeByteArray(p.cells);
            b.writeByteArray(p.shade);
            b.writeVarInt(p.legend.size());
            for (MapLegend l : p.legend) {
                b.writeUtf(l.name, 64);
                b.writeInt(l.colour);
            }
            b.writeVarInt(p.pois.size());
            for (MapPoi q : p.pois) {
                b.writeUtf(q.name, 96);
                b.writeVarInt(q.kind);
                b.writeInt(q.colour);
                b.writeDouble(q.x);
                b.writeDouble(q.z);
            }
            b.writeBoolean(p.here);
            b.writeDouble(p.px);
            b.writeDouble(p.pz);
            b.writeFloat(p.yaw);
            b.writeVarInt(p.marks.size());
            for (MapMark m : p.marks) {
                b.writeUtf(m.quest, 96);
                b.writeUtf(clip(m.label, 256), 256);
                b.writeDouble(m.x);
                b.writeDouble(m.z);
                b.writeVarInt(m.number);
                b.writeBoolean(m.followed);
            }
        }, b -> {
            int region = b.readVarInt();
            int nr = Math.min(b.readVarInt(), 8);
            List<String> regions = new ArrayList<>(nr);
            for (int i = 0; i < nr; i++) regions.add(b.readUtf(64));
            int minX = b.readInt(), minZ = b.readInt(), cell = b.readVarInt(), size = Math.min(b.readVarInt(), MAX_SIZE);
            byte[] cells = b.readByteArray(MAX_SIZE * MAX_SIZE), shade = b.readByteArray(MAX_SIZE * MAX_SIZE);
            int nl = Math.min(b.readVarInt(), 64);
            List<MapLegend> legend = new ArrayList<>(nl);
            for (int i = 0; i < nl; i++) legend.add(new MapLegend(b.readUtf(64), b.readInt()));
            int np = Math.min(b.readVarInt(), 512);
            List<MapPoi> pois = new ArrayList<>(np);
            for (int i = 0; i < np; i++) pois.add(new MapPoi(b.readUtf(96), b.readVarInt(), b.readInt(), b.readDouble(), b.readDouble()));
            boolean here = b.readBoolean();
            double px = b.readDouble(), pz = b.readDouble();
            float yaw = b.readFloat();
            int nm = Math.min(b.readVarInt(), 128);
            List<MapMark> marks = new ArrayList<>(nm);
            for (int i = 0; i < nm; i++) marks.add(new MapMark(b.readUtf(96), b.readUtf(256), b.readDouble(), b.readDouble(), b.readVarInt(), b.readBoolean()));
            return new MapData(region, regions, minX, minZ, cell, size, cells, shade, legend, pois, here, px, pz, yaw, marks);
        });

        private static String clip(String s, int max) {
            return s.length() <= max ? s : s.substring(0, max - 1) + "…";
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S: please send the map of {@code region} (-1: the region I am in). */
    public record RequestMap(int region) implements CustomPacketPayload {
        public static final Type<RequestMap> TYPE = id("request_map");
        public static final StreamCodec<RegistryFriendlyByteBuf, RequestMap> STREAM_CODEC =
                StreamCodec.of((b, p) -> b.writeVarInt(p.region + 1), b -> new RequestMap(b.readVarInt() - 1));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // the guided tour -------------------------------------------------------------------------------------------

    /**
     * One stop of the guided tour: what to call it, what to say, and how the camera moves there. The camera's path
     * is worked out on both sides from these numbers ({@code life.tour.TourCamera}); only the heights come from each
     * side's own terrain. {@code y} is the target height, or NaN for the ground there plus {@code lift}. {@code path}
     * holds x, z pairs along a road ({@link #SWEEP}) or x, y, z triples through a tunnel ({@link #TRACK}).
     */
    public record TourStop(String title, String caption, int style, double x, double y, double z, double lift,
                           double radius, double heading, int seconds, double[] path) {
        /** Circle slowly round a place, looking in. */
        public static final int ORBIT = 0;
        /** Drift in toward a place (a waterfall, a cave mouth), from far and high to near and low. */
        public static final int PUSH = 1;
        /** Glide beside a road, looking along it. */
        public static final int SWEEP = 2;
        /** Follow a line of points exactly (through a tunnel), looking ahead. */
        public static final int TRACK = 3;
        /** Stand at a lookout and pan slowly across the view. */
        public static final int VISTA = 4;
        /** Hang above a hollow's rim and look down into it, circling a little. */
        public static final int DOWN = 5;

        static void write(FriendlyByteBuf b, TourStop s) {
            b.writeUtf(s.title, 256);
            b.writeUtf(s.caption, 1024);
            b.writeVarInt(s.style);
            b.writeDouble(s.x);
            b.writeDouble(s.y);
            b.writeDouble(s.z);
            b.writeDouble(s.lift);
            b.writeDouble(s.radius);
            b.writeDouble(s.heading);
            b.writeVarInt(s.seconds);
            b.writeVarInt(s.path.length);
            for (double d : s.path) b.writeDouble(d);
        }

        static TourStop read(FriendlyByteBuf b) {
            String title = b.readUtf(256), caption = b.readUtf(1024);
            int style = b.readVarInt();
            double x = b.readDouble(), y = b.readDouble(), z = b.readDouble(), lift = b.readDouble(), radius = b.readDouble(),
                    heading = b.readDouble();
            int seconds = b.readVarInt();
            int n = b.readVarInt();
            if (n < 0 || n > 4096) throw new IllegalArgumentException("tour path too long: " + n);
            double[] path = new double[n];
            for (int i = 0; i < n; i++) path[i] = b.readDouble();
            return new TourStop(title, caption, style, x, y, z, lift, radius, heading, seconds, path);
        }
    }

    /** S2C: a guided tour begins; these are its stops. The camera waits, black, for the first {@link TourGo}. */
    public record TourStart(List<TourStop> stops) implements CustomPacketPayload {
        public static final Type<TourStart> TYPE = id("tour_start");
        public static final StreamCodec<RegistryFriendlyByteBuf, TourStart> STREAM_CODEC = StreamCodec.of((b, p) -> {
            b.writeVarInt(p.stops.size());
            for (TourStop s : p.stops) TourStop.write(b, s);
        }, b -> {
            int n = b.readVarInt();
            if (n < 0 || n > 64) throw new IllegalArgumentException("too many tour stops: " + n);
            List<TourStop> stops = new ArrayList<>(n);
            for (int i = 0; i < n; i++) stops.add(TourStop.read(b));
            return new TourStart(stops);
        });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** S2C: the player has been moved to stop {@code index}; show it once the land around it has arrived. */
    public record TourGo(int index) implements CustomPacketPayload {
        public static final Type<TourGo> TYPE = id("tour_go");
        public static final StreamCodec<RegistryFriendlyByteBuf, TourGo> STREAM_CODEC =
                StreamCodec.of((b, p) -> b.writeVarInt(p.index), b -> new TourGo(b.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** S2C: the tour is over and the player is back where they started. */
    public record TourEnd() implements CustomPacketPayload {
        public static final Type<TourEnd> TYPE = id("tour_end");
        public static final StreamCodec<RegistryFriendlyByteBuf, TourEnd> STREAM_CODEC = StreamCodec.of((b, p) -> {}, b -> new TourEnd());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S: stop {@code index} has been shown (or skipped); please move on. */
    public record TourNext(int index) implements CustomPacketPayload {
        public static final Type<TourNext> TYPE = id("tour_next");
        public static final StreamCodec<RegistryFriendlyByteBuf, TourNext> STREAM_CODEC =
                StreamCodec.of((b, p) -> b.writeVarInt(p.index), b -> new TourNext(b.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S: the closing card has been shown; put me back. */
    public record TourDone() implements CustomPacketPayload {
        public static final Type<TourDone> TYPE = id("tour_done");
        public static final StreamCodec<RegistryFriendlyByteBuf, TourDone> STREAM_CODEC = StreamCodec.of((b, p) -> {}, b -> new TourDone());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
