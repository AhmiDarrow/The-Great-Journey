package com.ninjacat.greatjourney.clanlands.life.quests;

import com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan;
import com.ninjacat.greatjourney.clanlands.life.BookClans;
import com.ninjacat.greatjourney.clanlands.life.BookClans.Info;
import com.ninjacat.greatjourney.clanlands.life.BookLooks;
import com.ninjacat.greatjourney.clanlands.life.ClanNames;
import com.ninjacat.greatjourney.clanlands.life.Dialog;
import com.ninjacat.greatjourney.clanlands.life.Dialog.Choice;
import com.ninjacat.greatjourney.clanlands.life.Dialog.Line;
import com.ninjacat.greatjourney.clanlands.life.Homeland;
import com.ninjacat.greatjourney.clanlands.life.Journey;
import com.ninjacat.greatjourney.clanlands.life.Mode;
import com.ninjacat.greatjourney.clanlands.life.events.Cats;
import com.ninjacat.greatjourney.clanlands.life.events.EventNews;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobSpawnType;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.snowteb.warriorcats_events.entity.ModEntities;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;

/**
 * A friendship across a border, in Free Mode, as the books have more than once: a cat of a neighbouring Clan is met by
 * the scent line, and met again if the player stays to talk. The code asks a warrior's loyalty to be for their own
 * Clan. Each meeting may be seen, and then there is the truth or a lie to tell; a friend who is met often enough will
 * one day ask for something that sets friendship against Clan. It is a friendship between two cats, nothing more.
 *
 * <p>One friend at a time. The friend is a cat of the story with no Clan id (as for {@code Skirmish}'s trespassers),
 * present only for the meeting; who they are and how the friendship stands is kept with the player.
 */
final class Friendship {
    private Friendship() {}

    static final String TAG = "greatjourney_friend";
    /** Friend brings herbs at this many meetings, and asks their hard favour at this many. */
    static final int GIFT = 3, TEST = 5;
    static final long BETWEEN = 24000L, AFTER_AN_END = 24000L * 8, WAITS = 20L * 60 * 3;
    private static final String ROOT = "friend", ASKING = "friendAsking";
    private static final int ONE_IN = 300;
    /** The friends waiting at a border now: by player, the cat and when they give up waiting. */
    private static final Map<UUID, UUID> HERE = new HashMap<>();
    private static final Map<UUID, Long> UNTIL = new HashMap<>();
    /** Players who have spoken with the friend now waiting. */
    private static final java.util.Set<UUID> SPOKEN = new java.util.HashSet<>();

    private static CompoundTag state(ServerPlayer p) {
        CompoundTag root = QuestState.root(p);
        if (!root.contains(ROOT)) root.put(ROOT, new CompoundTag());
        return root.getCompound(ROOT);
    }

    static int bond(ServerPlayer p) {
        return state(p).getInt("bond");
    }

    static String name(ServerPlayer p) {
        return state(p).getString("name");
    }

    private static Clan clanOfFriend(ServerPlayer p) {
        try {
            return Clan.valueOf(state(p).getString("clan"));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static void reset() {
        HERE.clear();
        UNTIL.clear();
        SPOKEN.clear();
    }

    /** The other Clan whose land lies within a short walk of here, or null. */
    static Clan across(ClanWorld w, Clan mine, double x, double z) {
        for (int[] d : new int[][] {{32, 0}, {-32, 0}, {0, 32}, {0, -32}}) {
            Clan there = Homeland.territoryAt(w, x + d[0], z + d[1]);
            if (there != null && there != mine) return there;
        }
        return null;
    }

    /** Once a second for every player in the overworld. */
    static void tick(ServerPlayer p) {
        ServerLevel ow = p.serverLevel();
        long now = ow.getGameTime();
        UUID waiting = HERE.get(p.getUUID());
        if (waiting != null) {
            if (now >= UNTIL.get(p.getUUID())) leave(ow, p);
            return;
        }
        ClanWorld w = ClanWorld.current();
        Clan mine = w == null ? null : Kin.bookClan(p);
        if (mine == null || Mode.of(p.getServer()) != Mode.FREE || Journey.stage(p) != Journey.Stage.PLAYING || !p.isAlive() || Dialog.busy(p)
                || p.getRandom().nextInt(ONE_IN) != 0 || now < state(p).getLong("next") || Homeland.territoryAt(w, p.getX(), p.getZ()) != mine) return;
        Clan other = across(w, mine, p.getX(), p.getZ());
        Clan known = clanOfFriend(p);
        // a friend already made is only met on their own Clan's side
        if (other == null || known != null && other != known) return;
        double a = Math.toRadians(p.getYRot() + 90);
        meet(p, other, BookClans.safeSpot(ow, (int) Math.floor(p.getX() + Math.cos(a) * 9), (int) Math.floor(p.getZ() + Math.sin(a) * 9), 3));
    }

    /** The friend (or a cat who may become one) is there at the scent line. */
    static boolean meet(ServerPlayer p, Clan other, BlockPos at) {
        ServerLevel ow = p.serverLevel();
        CompoundTag s = state(p);
        RandomSource r = p.getRandom();
        if (!s.contains("name")) {
            ClanNames.Name n = new ClanNames(ow.getSeed() ^ p.getUUID().getLeastSignificantBits() ^ ow.getGameTime()).warrior();
            s.putString("name", n.full());
            s.putString("prefix", n.prefix());
            s.putString("clan", other.name());
            s.putInt("sex", r.nextInt(2));
        }
        WCatEntity cat = ModEntities.WCAT.get().create(ow);
        if (cat == null) return false;
        cat.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, r.nextFloat() * 360, 0);
        cat.finalizeSpawn(ow, ow.getCurrentDifficultyAt(at), MobSpawnType.EVENT, null);
        cat.setGender(s.getInt("sex"));
        // the same coat every time: drawn from the friendship, not from chance
        cat.getRandom().setSeed(p.getUUID().getLeastSignificantBits() ^ s.getString("name").hashCode());
        BookLooks.clanCat(cat, other);
        Cats.rename(cat, s.getString("prefix"), s.getString("name"));
        cat.setRank(WCatEntity.Rank.NONE);
        cat.setAgeInMoons(20);
        cat.setInvulnerable(true);
        cat.mode = WCatEntity.CatMode.SIT;
        cat.addTag(TAG);
        HERE.put(p.getUUID(), cat.getUUID());
        UNTIL.put(p.getUUID(), ow.getGameTime() + WAITS);
        ow.addFreshEntity(cat);
        s.putLong("next", ow.getGameTime() + BETWEEN);
        Ui.subtitle(p, List.of(Line.narrate("A cat is sitting just across the scent line, watching you: " + Info.of(other).title + " by the smell.")));
        return true;
    }

    private static void leave(ServerLevel ow, ServerPlayer p) {
        UUID id = HERE.remove(p.getUUID());
        UNTIL.remove(p.getUUID());
        SPOKEN.remove(p.getUUID());
        Entity e = id == null ? null : ow.getEntity(id);
        if (e != null) e.discard();
    }

    static void onInteract(PlayerInteractEvent.EntityInteract e) {
        if (e.getHand() != InteractionHand.MAIN_HAND || !(e.getEntity() instanceof ServerPlayer p) || !e.getTarget().getTags().contains(TAG)) return;
        if (p.isShiftKeyDown()) return;
        e.setCanceled(true);
        e.setCancellationResult(InteractionResult.SUCCESS);
        if (Dialog.busy(p) || !e.getTarget().getUUID().equals(HERE.get(p.getUUID()))) return;
        Ui.play(p, greeting(p, e.getTarget()));
    }

    /** The friend waiting now, once the player has met them (spoken with them, today or before), or null. */
    static UUID met(ServerPlayer p) {
        UUID id = HERE.get(p.getUUID());
        return id != null && (SPOKEN.contains(p.getUUID()) || bond(p) > 0) ? id : null;
    }

    /** What the friend says, and the answers open to the player, by how far the friendship has come. */
    static List<Line> greeting(ServerPlayer p, Entity cat) {
        Clan other = clanOfFriend(p);
        int colour = other == null ? 0xC8C8C8 : Info.of(other).colour, bond = bond(p);
        boolean asked = bond >= TEST;
        SPOKEN.add(p.getUUID());
        state(p).putString(ASKING, asked ? "test" : "meet");
        String words = asked ? "My Clan is starving, " + Deeds.name(p) + ". Let us hunt on your side tonight. Just tonight. Please."
                : bond == 0 ? "Don't call your patrol. I only wanted to see the stream from this side. ...You're not going to shout, are you?"
                : bond < GIFT ? "I wondered if you'd come back. What's your camp like? Ours is nothing like yours, I bet."
                : "There you are! I saved the best of the day to tell you about.";
        Line line = Line.say(name(p), colour, words).speakerEntity(cat);
        return List.of(asked ? line.withChoices(
                        new Choice("Hunt. I never saw you.", "greatjourney quest friend hunt", 0xAAAAAA),
                        Choice.of("I can't. My Clan comes first.", "greatjourney quest friend refuse"))
                : line.withChoices(
                        Choice.of("Stay and talk", "greatjourney quest friend stay"),
                        Choice.of("We shouldn't be doing this. Go home.", "greatjourney quest friend end"),
                        new Choice("Not now", "greatjourney quest bye", 0xAAAAAA)));
    }

    /** "stay" or "end" at a meeting; "truth" or "deny" once seen; "hunt" or "refuse" to the friend's favour. */
    static boolean answer(ServerPlayer p, String what) {
        CompoundTag s = state(p);
        String asking = s.getString(ASKING);
        String expect = switch (what) {
            case "stay", "end" -> "meet";
            case "truth", "deny" -> "seen";
            case "hunt", "refuse" -> "test";
            default -> null;
        };
        Dialog.clear(p);
        if (expect == null || !expect.equals(asking)) return false;
        s.remove(ASKING);
        ServerLevel ow = p.serverLevel();
        String friend = name(p);
        Clan other = clanOfFriend(p), mine = Kin.bookClan(p);
        int colour = other == null ? 0xC8C8C8 : Info.of(other).colour;
        switch (what) {
            case "stay" -> {
                int bond = bond(p) + 1;
                s.putInt("bond", bond);
                leave(ow, p);
                Ui.play(p, List.of(Line.narrate("You talk until the shadows are long: about kits, and prey, and what the elders say about your Clan. " + friend + " is not so different.")));
                if (bond == GIFT) {
                    Bag.give(p, "warriorcats_events:traveling_herbs", 2);
                    Ui.play(p, List.of(Line.say(friend, colour, "Here. Traveling herbs, from our store. Don't ask me how I got them.")));
                }
                if (bond >= 2 && p.getRandom().nextInt(4) == 0) seen(p);
            }
            case "end" -> {
                Ui.play(p, List.of(Line.say(friend, colour, "...You're right. I know you're right. Goodbye, then."),
                        Line.narrate(friend + " slips away into their own territory and does not look back.")));
                Deeds.weigh(p, bond(p) > 0 ? 2 : 0, 1);
                over(p, ow);
            }
            case "truth" -> {
                s.putBoolean("known", true);
                Ui.play(p, List.of(Line.narrate("You tell your leader everything. Their face is stern for a long while. \"Thank you for not lying to me,\" they say at last.")));
                Deeds.weigh(p, -4, 2);
            }
            case "deny" -> {
                Ui.play(p, List.of(Line.narrate("\"You must have smelled it wrong.\" Your Clanmate looks at you for a long time, and says nothing more.")));
                Deeds.weigh(p, 0, -2);
            }
            case "hunt" -> {
                Ui.play(p, List.of(Line.say(friend, colour, "I'll never forget this. Never."),
                        Line.narrate("By morning there is less prey on your side of the stream, and your Clanmates are asking why.")));
                if (mine != null) EventNews.record(mine, "Prey went missing along " + Info.of(mine).title + "'s border, and no cat could say why.");
                Deeds.weigh(p, -6, -1);
                s.putInt("bond", GIFT);                       // closer still, and one day they may ask again
                leave(ow, p);
            }
            case "refuse" -> {
                Ui.play(p, List.of(Line.say(friend, colour, "I thought you would say that. I think I'd have said it too. May StarClan walk with you."),
                        Line.narrate("It is the last time you meet at the stream. At the Gathering, across the clearing, " + friend + " gives you one small nod.")));
                Deeds.weigh(p, 3, 2);
                over(p, ow);
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    /** A Clanmate caught the scent: what the player says now. */
    static void seen(ServerPlayer p) {
        if (state(p).getBoolean("known")) return;
        state(p).putString(ASKING, "seen");
        Ui.play(p, List.of(Line.narrate("On the way home a Clanmate wrinkles their nose. \"You smell of another Clan. Were you meeting someone at the border?\"")
                .withChoices(Choice.of("Tell your leader the truth", "greatjourney quest friend truth"),
                        new Choice("Deny it", "greatjourney quest friend deny", 0xAAAAAA))));
    }

    private static void over(ServerPlayer p, ServerLevel ow) {
        leave(ow, p);
        CompoundTag s = new CompoundTag();
        s.putLong("next", ow.getGameTime() + AFTER_AN_END);
        QuestState.root(p).put(ROOT, s);
    }

    /** A friend loading with no meeting of their own (after a restart) has gone home. */
    static void onJoin(EntityJoinLevelEvent e) {
        if (!e.getLevel().isClientSide() && e.getEntity().getTags().contains(TAG) && !HERE.containsValue(e.getEntity().getUUID())) e.setCanceled(true);
    }
}
