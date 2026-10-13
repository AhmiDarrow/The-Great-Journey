package com.ninjacat.greatjourney.clanlands.life;

import com.ninjacat.greatjourney.clanlands.layout.Landmark;
import com.ninjacat.greatjourney.clanlands.layout.Sample;
import com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan;
import com.ninjacat.greatjourney.clanlands.layout.Zone;
import com.ninjacat.greatjourney.clanlands.life.BookClans.Info;
import com.ninjacat.greatjourney.clanlands.life.Dialog.Choice;
import com.ninjacat.greatjourney.clanlands.life.Dialog.Line;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;

/**
 * The three ways to begin, chosen on waking from the StarClan dream: a kittypet in a Twoleg garden at the edge of
 * the ThunderClan woods (a patrol finds you), an apprentice of a Clan of your choice, or a loner by the lake.
 * Clanless cats who walk into a camp are offered a place in that Clan.
 */
public final class Starts {
    private Starts() {}

    public static void offerChoices(ServerPlayer p) {
        Journey.setStage(p, Journey.Stage.CHOOSING);
        Journey.stamp(p, "choose.asked", p.serverLevel().getServer().overworld().getGameTime());
        if (com.ninjacat.greatjourney.clanlands.life.campaign.Campaign.offerStarts(p)) return;      // the Campaign's own way
        Dialog.play(p,
                Line.narrate("The stars fade. You wake in the world of the Clans. How will your story begin?"),
                choiceLine("Choose one:"));
    }

    private static Line choiceLine(String text) {
        return Line.narrate(text).withChoices(
                Choice.of("A kittypet at the forest edge", "greatjourney start kittypet"),
                Choice.of("Join a Clan now", "greatjourney start clan"),
                Choice.of("A loner by the lake", "greatjourney start loner"));
    }

    public static void chooseClan(ServerPlayer p) {
        List<Choice> c = new ArrayList<>();
        for (Info i : Info.values()) c.add(new Choice(i.title, "greatjourney start clan " + i.key(), i.colour));
        Dialog.play(p, Line.narrate("Which Clan will you join?").withChoices(c.toArray(Choice[]::new)));
    }

    public static boolean begin(ServerPlayer p, Journey.Start start, Info clan) {
        ClanWorld w = ClanWorld.current();
        if (w == null || Journey.stage(p) != Journey.Stage.CHOOSING) return false;
        ServerLevel ow = p.serverLevel().getServer().overworld();
        if (Mode.of(ow) == Mode.CAMPAIGN) return com.ninjacat.greatjourney.clanlands.life.campaign.Campaign.beginStart(p, start, clan);
        Dialog.clear(p);
        switch (start) {
            case KITTYPET -> {
                Landmark home = w.lake.kittypetHome();
                arrive(p, ow, home.x() + 2, home.z() + 7, q -> Dialog.play(q,
                        Line.narrate("You are a kittypet, warm and well fed in your Twoleg garden."),
                        Line.narrate("Beyond the fence the woods rustle with strange scents. Wild cats live out there, they say..."),
                        Line.narrate("Climb the fence and explore when you are ready.")));
            }
            case CLAN -> {
                if (clan == null) return false;
                Landmark camp = w.lake.camp(clan.clan);
                Journey.setStart(p, start);
                Journey.setStage(p, Journey.Stage.PLAYING);
                arrive(p, ow, camp.x() + 1, camp.z() + 1, q -> Joining.join(q, clan));
                giveGuide(p);
                return true;
            }
            case LONER -> {
                double[] s = w.lake.lonerSpot();
                arrive(p, ow, s[0], s[1], q -> Dialog.play(q,
                        Line.narrate("You are a loner, belonging to no one. The great lake shines before you."),
                        Line.narrate("Four Clans live around these shores. Visit their camps if you wish to join one, or walk your own path.")));
            }
            default -> {
                return false;
            }
        }
        Journey.setStart(p, start);
        Journey.setStage(p, Journey.Stage.PLAYING);
        giveGuide(p);
        return true;
    }

    /** How long after arriving the dialogue waits, so the camp has drawn on the client when the window opens. */
    static final int ARRIVAL_HOLD = 40;

    /**
     * Takes the player to x, z (on the ground, with their respawn point there) once the land there has loaded, then
     * runs {@code then} with them. The land loads without stalling the server (see {@link ChunkWait}); a player who
     * logged out meanwhile is looked up again by id, or skipped.
     */
    public static void arrive(ServerPlayer p, ServerLevel ow, double x, double z, java.util.function.Consumer<ServerPlayer> then) {
        UUID id = p.getUUID();
        // a far start can take a while to load; say something, so the choice does not seem to have done nothing
        if (Math.hypot(p.getX() - x, p.getZ() - z) > 64)
            Dialog.subtitle(p, Line.narrate("You set off through the trees toward your new home..."));
        ChunkWait.whenLoaded(ow, x, z, 24, () -> {
            ServerPlayer q = ow.getServer().getPlayerList().getPlayer(id);
            if (q == null) return;
            BlockPos at = BookClans.safeSpot(ow, (int) Math.floor(x), (int) Math.floor(z), 6);   // firm, open ground: never in a rock or a wall
            q.fallDistance = 0;
            q.teleportTo(ow, at.getX() + 0.5, at.getY() + 0.1, at.getZ() + 0.5, q.getYRot(), 0);
            q.setRespawnPosition(Level.OVERWORLD, at, 0, true, false);
            Dialog.hold(q, ARRIVAL_HOLD);               // the client draws the land and the cats before any window opens
            if (then != null) then.accept(q);
        });
    }

    /** StarClan's Guidance and the Clan journal, once per player. */
    public static void giveGuide(ServerPlayer p) {
        if (!Journey.once(p, "guide.given")) return;
        var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(ResourceLocation.parse("patchouli:guide_book"));
        if (item == net.minecraft.world.item.Items.AIR) item = net.minecraft.world.item.Items.BOOK;
        ItemStack book = new ItemStack(item);
        var type = net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_TYPE.get(ResourceLocation.parse("patchouli:book"));
        if (type != null) setComponent(book, type, ResourceLocation.fromNamespaceAndPath("clanlands", "starclans_guidance"));
        book.set(DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("StarClan's Guidance"));
        if (!p.getInventory().add(book)) p.drop(book, false);
        var journal = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("clanlands", "journal"));
        if (journal != net.minecraft.world.item.Items.AIR) {
            ItemStack j = new ItemStack(journal);
            if (!p.getInventory().add(j)) p.drop(j, false);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> void setComponent(ItemStack stack, net.minecraft.core.component.DataComponentType<T> type, Object value) {
        stack.set(type, (T) value);
    }

    /** A dismissed or lost choice (window closed, logged out) is asked again after this long. */
    private static final long ASK_AGAIN = 20L * 30;
    /** A camp invites the same clanless cat again after a day (they may have said "Not yet"). */
    static final long INVITE_AGAIN = 24000L;

    /** Once a second: the start choice until it is made, patrols that find kittypets, camps that invite visitors. */
    public static void tick(MinecraftServer server) {
        ClanWorld w = ClanWorld.current();
        if (w == null) return;
        long now = server.overworld().getGameTime();
        boolean campaign = Mode.of(server) == Mode.CAMPAIGN;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            Journey.Stage stage = Journey.stage(p);
            if (stage == Journey.Stage.CHOOSING) {
                if (!Dialog.busy(p) && now - Journey.stamped(p, "choose.asked") > ASK_AGAIN) {
                    Journey.stamp(p, "choose.asked", now);
                    Dialog.play(p, campaign ? com.ninjacat.greatjourney.clanlands.life.campaign.Campaign.startLine(p, "Your story is waiting. How will it begin?")
                            : choiceLine("Your story is waiting. How will it begin?"));
                }
                continue;
            }
            if (campaign) continue;                     // the Campaign finds its kittypets and loners its own way
            if (p.level().dimension() != Level.OVERWORLD || stage != Journey.Stage.PLAYING) continue;
            if (Joining.clanOf(p) != null || Dialog.busy(p)) continue;
            boolean invited = false;
            for (Clan c : Clan.values()) {
                Landmark camp = w.lake.camp(c);
                String key = "visit." + c.name();
                long last = Journey.stamped(p, key);
                if (Math.hypot(p.getX() - camp.x(), p.getZ() - camp.z()) < camp.radius() + 6 && (last == 0 || now - last > INVITE_AGAIN)) {
                    Journey.stamp(p, key, now);
                    visitCamp(p, Info.of(c), last != 0);
                    invited = true;
                    break;
                }
            }
            if (invited) continue;
            if (Journey.start(p) == Journey.Start.KITTYPET) {
                Sample s = w.sample(p.getX(), p.getZ());
                // the garden itself backs onto ThunderClan's woods: the patrol waits until the kittypet has climbed
                // the fence and gone exploring (the playtest harness saw it pounce the moment the kittypet arrived)
                Landmark home = w.lake.kittypetHome();
                // and only once they have been home at all: the start is chosen where the dream left them, and while the
                // garden loads they are still standing out there (often in ThunderClan's woods), far from any garden
                boolean away = Math.hypot(p.getX() - home.x(), p.getZ() - home.z()) > 48;
                if (!away && Journey.stamped(p, "kittypet.home") == 0) Journey.stamp(p, "kittypet.home", now);
                boolean exploring = away && Journey.stamped(p, "kittypet.home") != 0;
                if (exploring && s.zone() == Zone.THUNDERCLAN_FOREST && s.weight() > 0.9 && Journey.once(p, "patrol.thunder")) {
                    patrolFinds(p, Info.THUNDER);
                }
            }
        }
    }

    private static void visitCamp(ServerPlayer p, Info clan, boolean again) {
        String host = speakerIn(p, clan, WCatEntity.Rank.DEPUTY);
        Joining.offer(p, clan, host, again
                ? List.of(Line.say(host, clan.colour, "You again. You keep finding your way back to our camp, don't you?"),
                        Line.say(host, clan.colour, "The offer still stands. " + clan.title + " never turns away a cat with a brave heart."))
                : List.of(Line.say(host, clan.colour, "A stranger in our camp? Easy, everyone. I can smell no harm on this one."),
                        Line.say(host, clan.colour, clan.title + " could use another pair of sharp claws.")));
    }

    /** A ThunderClan patrol catches a kittypet in the woods, the way the old stories begin. */
    private static void patrolFinds(ServerPlayer p, Info clan) {
        ServerLevel ow = p.serverLevel();
        LifeData life = LifeData.get(ow);
        UUID id = life.clanId(clan.clan);
        if (id == null) return;
        RandomSource r = p.getRandom();
        ClanNames names = new ClanNames(ow.getSeed() ^ p.getUUID().getMostSignificantBits());
        ClanWorld w = ClanWorld.current();
        Landmark camp = w.lake.camp(clan.clan);
        BlockPos centre = BookClans.surface(ow, (int) camp.x(), (int) camp.z());
        List<String> who = new ArrayList<>();
        List<WCatEntity> cats = new ArrayList<>();
        double ahead = Math.toRadians(p.getYRot() + 90);
        for (int i = 0; i < 3; i++) {
            ClanNames.Name n = i == 2 ? names.apprentice() : names.warrior();
            int x = (int) (p.getX() + Math.cos(ahead) * 8 + (i - 1) * 2), z = (int) (p.getZ() + Math.sin(ahead) * 8);
            BlockPos at = BookClans.surface(ow, x, z);
            cats.add(BookClans.spawnCat(ow, clan.clan, id, i == 2 ? BookClans.Role.APPRENTICE : BookClans.Role.WARRIOR, n,
                    r.nextInt(2), at, centre, r));
            who.add(n.full());
        }
        Joining.offer(p, clan, who.get(0), List.of(
                Line.narrate("Three cats step out of the bracken, fur bristling."),
                Line.say(who.get(0), clan.colour, "Halt! What is a kittypet doing in " + clan.title + " territory?").speakerEntity(cats.get(0)),
                Line.say(who.get(2), clan.colour, "It's not even scared of us. Look how it stands its ground!").speakerEntity(cats.get(2)),
                Line.say(who.get(1), clan.colour, "Brave, for a kittypet. Our leader might like that.").speakerEntity(cats.get(1)),
                Line.say(who.get(0), clan.colour, "Leave your Twoleg nest behind, and live as a warrior with us.").speakerEntity(cats.get(0))));
    }

    /** The name of a cat of the given rank in the Clan's camp, for the Clan to speak through. */
    private static String speakerIn(ServerPlayer p, Info clan, WCatEntity.Rank rank) {
        ClanWorld w = ClanWorld.current();
        LifeData life = LifeData.get(p.serverLevel());
        UUID id = life.clanId(clan.clan);
        Landmark camp = w.lake.camp(clan.clan);
        var cats = p.serverLevel().getEntitiesOfClass(WCatEntity.class,
                new AABB(camp.x() - 60, -64, camp.z() - 60, camp.x() + 60, 320, camp.z() + 60),
                c -> id != null && id.equals(c.getClanUUID()) && c.getRank() == rank && c.getCustomName() != null);
        if (cats.isEmpty()) return life.leader(clan.clan);
        return cats.get(0).getCustomName().getString().replace(" ♂", "").replace(" ♀", "");
    }
}
