package com.ninjacat.greatjourney.clanlands.life.gathering;

import com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * What has happened in each Clan since the last Gathering, for its leader to tell the others at the next full moon:
 * kits born, new apprentices and warriors, cats gone to StarClan, a raid on the fresh-kill pile, sickness in camp.
 * The Clan events record a line here as each thing happens; the Gathering reads the lines that have not been told
 * yet and marks them told. A Clan with nothing new gets a line from the books instead ("Prey is running well").
 *
 * <p>Each line is something the leader can say aloud to the other Clans, in plain words and without gore, about the
 * Clan's cats by name: "Mosspaw has earned her warrior name: she is Mossheart now." The newest {@link #KEPT} lines a
 * Clan has are kept, and saved with the world.
 */
public final class ClanNews extends SavedData {
    private static final String ID = "greatjourney_clan_news";
    /** Lines kept per Clan; the oldest go first. */
    public static final int KEPT = 24;

    /** One piece of news: the Clan, the leader's words, and the game time it happened. */
    public record Item(Clan clan, String text, long gameTime) {}

    private final Map<Clan, List<Item>> items = new EnumMap<>(Clan.class);
    /** The game time of the last Gathering where each Clan's news was told. */
    private final Map<Clan, Long> told = new EnumMap<>(Clan.class);

    /**
     * Records a piece of the Clan's news, to be told at the next Gathering. Safe to call from anywhere on the server
     * (off the server thread it is handed over to it); does nothing without a running server, a Clan or words.
     */
    public static void record(Clan clan, String text) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) record(server.overworld(), clan, text);
    }

    /** The same, for a given level (its server's overworld keeps the news). */
    public static void record(ServerLevel level, Clan clan, String text) {
        if (level == null || clan == null || text == null || text.isBlank()) return;
        MinecraftServer server = level.getServer();
        if (!server.isSameThread()) {
            server.execute(() -> record(level, clan, text));
            return;
        }
        ServerLevel ow = server.overworld();
        get(ow).add(clan, text.trim(), ow.getGameTime());
    }

    /** The Clan's news not told at a Gathering yet, oldest first. */
    public static List<Item> untold(ServerLevel level, Clan clan) {
        ClanNews d = get(level.getServer().overworld());
        long since = d.told.getOrDefault(clan, Long.MIN_VALUE);
        List<Item> out = new ArrayList<>();
        for (Item i : d.items.getOrDefault(clan, List.of())) if (i.gameTime() > since) out.add(i);
        return out;
    }

    /** The Clan's last {@code n} lines, told or not, oldest first. */
    public static List<Item> recent(ServerLevel level, Clan clan, int n) {
        List<Item> all = get(level.getServer().overworld()).items.getOrDefault(clan, List.of());
        return List.copyOf(all.subList(Math.max(0, all.size() - n), all.size()));
    }

    /** When the Clan's news was last told at a Gathering (Long.MIN_VALUE if never). */
    public static long lastTold(ServerLevel level, Clan clan) {
        return get(level.getServer().overworld()).told.getOrDefault(clan, Long.MIN_VALUE);
    }

    /** Every Clan's news so far has been told (a Gathering has been held). */
    static void markTold(ServerLevel level, long gameTime) {
        ClanNews d = get(level.getServer().overworld());
        for (Clan c : Clan.values()) d.told.put(c, gameTime);
        d.setDirty();
    }

    private void add(Clan clan, String text, long now) {
        List<Item> list = items.computeIfAbsent(clan, k -> new ArrayList<>());
        long since = told.getOrDefault(clan, Long.MIN_VALUE);
        // the same words twice before a Gathering (two systems telling one happening) are said once
        for (Item i : list) if (i.gameTime() > since && i.text().equals(text)) return;
        list.add(new Item(clan, text, now));
        while (list.size() > KEPT) list.remove(0);
        setDirty();
    }

    static ClanNews get(ServerLevel overworld) {
        return overworld.getDataStorage().computeIfAbsent(new Factory<>(ClanNews::new, ClanNews::load), ID);
    }

    private static ClanNews load(CompoundTag tag, HolderLookup.Provider registries) {
        ClanNews d = new ClanNews();
        for (Clan c : Clan.values()) {
            CompoundTag ct = tag.getCompound(c.name());
            if (ct.contains("told")) d.told.put(c, ct.getLong("told"));
            List<Item> list = new ArrayList<>();
            for (Tag t : ct.getList("items", Tag.TAG_COMPOUND)) {
                CompoundTag it = (CompoundTag) t;
                list.add(new Item(c, it.getString("text"), it.getLong("time")));
            }
            if (!list.isEmpty()) d.items.put(c, list);
        }
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        for (Clan c : Clan.values()) {
            CompoundTag ct = new CompoundTag();
            if (told.containsKey(c)) ct.putLong("told", told.get(c));
            ListTag list = new ListTag();
            for (Item i : items.getOrDefault(c, List.of())) {
                CompoundTag it = new CompoundTag();
                it.putString("text", i.text());
                it.putLong("time", i.gameTime());
                list.add(it);
            }
            ct.put("items", list);
            tag.put(c.name(), ct);
        }
        return tag;
    }
}
