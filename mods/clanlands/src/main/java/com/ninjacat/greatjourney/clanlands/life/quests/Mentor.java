package com.ninjacat.greatjourney.clanlands.life.quests;

import com.ninjacat.greatjourney.clanlands.layout.Landmark;
import com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan;
import com.ninjacat.greatjourney.clanlands.life.BookClans;
import com.ninjacat.greatjourney.clanlands.life.Dialog.Line;
import com.ninjacat.greatjourney.clanlands.life.Homeland;
import com.ninjacat.greatjourney.clanlands.life.Joining;
import com.ninjacat.greatjourney.clanlands.life.Lead;
import com.ninjacat.greatjourney.clanlands.life.LifeData;
import com.ninjacat.greatjourney.clanlands.life.Starts;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;

/**
 * The mentor as a presence, not only a voice. On the first step they lead the apprentice round the territory (out of
 * camp, down to the lake shore or the edge of Fourtrees, past a landmark or two, out to the far reaches and home
 * again) and say something of each place; they stay near through the first hunt with a tip and a word for each
 * catch, walk the border patrol at the apprentice's side, take them to the places every apprentice knows (round the
 * lake and over the tree-bridge to the Gathering island), and watch the assessment from nearby. Between steps they look in on the
 * apprentice in camp now and then. Everything is per player and goes through {@link Lead}: a walk cut short (a
 * logout, a restart) is offered again the next time mentor and apprentice meet.
 *
 * <p>No mentor takes an apprentice out at night, or onto a hunt, a patrol or a long walk in the rain: one already out
 * turns for camp, leads the apprentice home and says they will go on at first light (or when it clears). Kiki's mentor
 * kept her out on the border patrol all night and through rainstorms (playtest 2026-10-19).
 */
final class Mentor {
    private Mentor() {}

    /** Mentor and apprentice this close: a walk or a watch can begin. */
    static final double MEET = 32;
    /** After a walk was cut short (or a watch ran its time), the mentor waits this long before the next. */
    static final long RETRY = 20L * 45;
    /** A word in camp between steps at most this often. */
    static final long CHECK_IN = 20L * 60 * 6;
    /** How long the mentor stays near for a hunt or the assessment before going about its own business a while. */
    static final int WATCH = 20 * 60 * 10;

    /** Once a second from {@link Story#tick} while the player is on step 1..6 and no window is open. */
    static void tick(ServerPlayer p, Clan clan, int step, Landmark camp, double toCamp) {
        Task t = QuestState.story(p);
        if (t == null) return;
        CompoundTag root = QuestState.root(p);
        long now = p.level().getGameTime();
        WCatEntity m = mentorCat(p);
        Lead lead = Lead.of(p);
        boolean ours = lead != null && m != null && lead.cat().getUUID().equals(m.getUUID());
        if (ours && lead.accompanying() && !watches(t)) {
            Lead.stop(p);                                          // the step it was watching is over
            lead = null;
        }
        react(p, t, step, m, root);
        if (lead == null) root.remove("mentorHome");                  // a walk home cut short (a restart) is over
        if (m == null || m.level() != p.level()) return;
        int colour = BookClans.Info.of(clan).colour;
        var ow = p.serverLevel().getServer().overworld();
        boolean keptIn = Tasks.keptIn(ow, t.kind);
        if (ours && keptIn && !root.getBoolean("mentorHome")) {
            callOff(p, m, camp, colour, Tasks.night(ow), root, now);  // night fell, or the rain came, while they were out
            return;
        }
        // done, with the mentor right there: the mentor asks for it (once a step; talking to them works as ever)
        if (t.readyToHandIn() && m.distanceTo(p) <= 12) {
            if (ours && lead.accompanying()) {
                Lead.stop(p);
                lead = null;
            }
            if (lead == null && root.getInt("mentorAskedStep") != step) {
                root.putInt("mentorAskedStep", step);
                Talk.greet(p, m, LifeData.get(p.serverLevel().getServer().overworld()));
            }
            return;
        }
        if (lead != null) return;
        boolean free = !m.getTags().contains(BookClans.BUSY_TAG) && m.distanceTo(p) <= MEET && now >= root.getLong("mentorNext");
        if (free && !t.readyToHandIn() && keptIn) {
            stayIn(p, m, t, colour, Tasks.night(ow), root, ow);
        } else if (free && !t.readyToHandIn()) {
            boolean began = switch (t.kind) {
                case STORY_TERRITORY -> tour(p, clan, m, camp, t, colour);
                case STORY_PATROL -> patrol(p, clan, m, camp, t, colour);
                case STORY_PLACES -> places(p, clan, m, camp, t, colour);
                case STORY_HUNT, STORY_ASSESSMENT -> watch(p, clan, m, t, colour);
                default -> false;
            };
            if (began) return;
        }
        checkIn(p, t, m, camp, toCamp, root, now, colour);
        goHome(p, m, camp);
    }

    // --- night and rain

    /** The step's walk, if the mentor would take the apprentice on one now. */
    private static boolean walks(Task.Kind kind) {
        return switch (kind) {
            case STORY_TERRITORY, STORY_PATROL, STORY_PLACES, STORY_HUNT, STORY_ASSESSMENT -> true;
            default -> false;
        };
    }

    /** Asked to set out when it is dark or wet: the mentor says no, once a night (or once a shower). */
    private static void stayIn(ServerPlayer p, WCatEntity m, Task t, int colour, boolean dark, CompoundTag root, net.minecraft.server.level.ServerLevel ow) {
        if (!walks(t.kind)) return;
        long key = ow.getDayTime() / 24000L * 2 + (dark ? 1 : 0);
        if (root.getLong("mentorStayIn") == key + 1) return;
        root.putLong("mentorStayIn", key + 1);
        String you = Joining.catName(p);
        Ui.subtitle(p, List.of(line(m, colour, dark
                ? "It's dark, " + you + ". We go on at first light. Get some sleep in your nest."
                : "Not in this rain, " + you + ". The scents are washed out and the prey has gone to ground. We'll go when it clears.")));
    }

    /** Out when night falls or the rain comes: the walk (or the watch) ends, and the mentor leads the apprentice home. */
    private static void callOff(ServerPlayer p, WCatEntity m, Landmark camp, int colour, boolean dark, CompoundTag root, long now) {
        root.putBoolean("mentorHome", true);                          // the walk's own end says nothing (begin)
        Lead.stop(p);
        String you = Joining.catName(p);
        String text = dark ? "It's getting dark, " + you + ". Back to camp: we'll finish this at first light."
                : "This rain washes out every scent, " + you + ". Back to camp: we'll finish when it clears.";
        ClanWorld w = ClanWorld.current();
        if (w != null && Math.hypot(p.getX() - camp.x(), p.getZ() - camp.z()) > camp.radius() + 8) {
            List<Lead.Stop> stops = new ArrayList<>();
            for (double[] pt : Starts.wayRound(w, p.getX(), p.getZ(), camp.x(), camp.z())) stops.add(new Lead.Stop(pt[0], pt[1], 12, null));
            List<double[]> in = Starts.wayIn(w, camp);
            if (in.size() > 1) stops.add(new Lead.Stop(in.get(0)[0], in.get(0)[1], 8, null));
            stops.add(new Lead.Stop(camp.x(), camp.z(), Math.min(camp.radius(), 12),
                    say(colour, dark ? "Home. Off to your nest now." : "Home. Shake out your fur and rest.")));
            Lead home = Lead.start(p, m, stops, (l, how) -> {
                CompoundTag r = QuestState.root(l.player());
                r.remove("mentorHome");
                r.putLong("mentorNext", l.player().level().getGameTime() + RETRY);
            });
            if (home != null) {
                home.whenWaiting(l -> Ui.subtitle(l.player(), List.of(line(l.cat(), colour, "Keep up, " + you + "! Stay close in the dark."))));
                Ui.play(p, List.of(line(m, colour, text)));
                return;
            }
        }
        root.remove("mentorHome");
        root.putLong("mentorNext", now + RETRY);
        Ui.subtitle(p, List.of(line(m, colour, text)));
    }

    private static boolean watches(Task t) {
        return t.kind == Task.Kind.STORY_HUNT || t.kind == Task.Kind.STORY_ASSESSMENT;
    }

    /** The player's mentor, if it is loaded and alive. */
    private static WCatEntity mentorCat(ServerPlayer p) {
        UUID id = QuestState.mentorId(p);
        return id != null && p.serverLevel().getEntity(id) instanceof WCatEntity c && c.isAlive() ? c : null;
    }

    // --- step 1: the tour

    /**
     * The camp, den by den: the way in, each den with a word on whose it is, the rock the leader speaks from, and back
     * to the middle, where the mentor takes the step. The walk round the whole territory (the shore, the far reaches)
     * was a long trek over land that had to load as they went; the owner asked for just the camp (2026-10-18). The
     * territory comes on the border patrol and the places step.
     */
    private static boolean tour(ServerPlayer p, Clan clan, WCatEntity m, Landmark camp, Task t, int colour) {
        ClanWorld w = ClanWorld.current();
        if (w == null) return false;
        List<Lead.Stop> stops = new ArrayList<>();
        List<double[]> in = Starts.wayIn(w, camp);
        if (in.size() > 1) stops.add(new Lead.Stop(in.get(0)[0], in.get(0)[1], 6, say(colour,
                "This is the only way in or out of camp. Learn it by scent: one day you'll come home in the dark.")));
        com.ninjacat.greatjourney.clanlands.landmark.CampPlan plan = com.ninjacat.greatjourney.clanlands.landmark.CampPlan.of(w, camp);
        boolean healer = QuestState.path(p) == QuestState.Path.MEDICINE;
        if (plan != null) {
            // round the camp from the way in, den by den
            List<com.ninjacat.greatjourney.clanlands.landmark.CampPlan.Den> dens = new ArrayList<>(plan.dens);
            dens.sort(java.util.Comparator.comparingDouble(d -> Math.floorMod((long) Math.round(Math.toDegrees(Math.atan2(d.z() - plan.cz, d.x() - plan.cx) - plan.gate) * 10), 3600L)));
            for (var d : dens) {
                double out = (d.shape() == com.ninjacat.greatjourney.clanlands.landmark.CampPlan.Den.Shape.DOME ? d.radius() : Math.max(d.radius(), d.length())) + 1.6;
                stops.add(new Lead.Stop(d.x() + Math.cos(d.door()) * out, d.z() + Math.sin(d.door()) * out, 4, say(colour, denWords(d.kind(), healer))));
            }
            if (plan.rock != null) {
                double dx = plan.cx - plan.rock[0], dz = plan.cz - plan.rock[1], len = Math.max(0.1, Math.hypot(dx, dz));
                String leader = LifeData.get(p.serverLevel().getServer().overworld()).leader(clan);
                stops.add(new Lead.Stop(plan.rock[0] + dx / len * (plan.rock[2] + 1.5), plan.rock[1] + dz / len * (plan.rock[2] + 1.5), 5, say(colour,
                        rockName(clan) + ". From up there " + leader + " calls the Clan together. When you hear \"Let all cats old enough to catch their own prey...\", come here.")));
            }
        }
        stops.add(new Lead.Stop(camp.x(), camp.z(), 6, l -> {
            // shown round the camp: every place of the step counts, and the mentor takes the step there and then
            Task cur = QuestState.story(l.player());
            if (cur == null || cur.kind != Task.Kind.STORY_TERRITORY) return;
            Ui.subtitle(l.player(), List.of(line(l.cat(), colour, healer
                    ? "That's our camp. You'll know every den by scent soon, and every cat in them. The herbs you'll learn out in the territory."
                    : "That's our camp. You'll know every den by scent soon. The territory you'll learn on patrol.")));
            for (Task.Objective o : cur.objectives) if (o.type == Task.Objective.Type.VISIT) o.progress = o.goal;
            QuestState.save(l.player(), cur);
            Talk.handIn(l.player(), l.cat());
        }));
        return begin(p, m, stops, colour, "Come on, " + Joining.catName(p) + ". Before anything else, you need to know your own camp. Stay close.");
    }

    /** What the mentor says at each den's door (to a medicine cat apprentice, {@code healer}, of their own den). */
    static String denWords(com.ninjacat.greatjourney.clanlands.landmark.CampPlan.DenKind kind, boolean healer) {
        if (healer && kind == com.ninjacat.greatjourney.clanlands.landmark.CampPlan.DenKind.MEDICINE) {
            return "The medicine den. You'll sleep here now, and work here. Learn every scent in it: one day the whole Clan will come to you.";
        }
        if (healer && kind == com.ninjacat.greatjourney.clanlands.landmark.CampPlan.DenKind.APPRENTICES) {
            return "The apprentices' den. You sleep with me, but they're still your denmates. Keep an eye on them: they're always getting hurt.";
        }
        return switch (kind) {
            case LEADER -> "The leader's den. No cat goes in without being asked.";
            case MEDICINE -> "The medicine cat's den. Hurt, or sick, or bitten by something? Come here first.";
            case NURSERY -> "The nursery. The queens raise our kits here. Be gentle, and keep your voice down.";
            case WARRIORS -> "The warriors' den. You'll sleep here one day, when you've earned your warrior name.";
            case APPRENTICES -> "And this is where you sleep: the apprentices' den. Your nest is in there.";
            case ELDERS -> "The elders' den. Bring them fresh-kill and clean moss, and listen to their stories. They've earned it.";
        };
    }

    /** The rock the Clan's leader speaks from, by its name in the books. */
    static String rockName(Clan clan) {
        return switch (clan) {
            case THUNDER -> Homeland.forest() ? "Highrock" : "Highledge";
            case WIND -> "Tallrock";
            default -> "The great rock";
        };
    }

    /** The other Clan whose land lies just past a point, or null. */
    static Clan beyond(ClanWorld w, Clan clan, double x, double z) {
        for (double d = 20; d <= 80; d += 20) {
            for (double b = 0; b < 360; b += 45) {
                double[] at = com.ninjacat.greatjourney.clanlands.layout.Geo.toward(x, z, b, d);
                Clan c = Homeland.territoryAt(w, at[0], at[1]);
                if (c != null && c != clan) return c;
            }
        }
        return null;
    }

    /** What a mentor says of a landmark on the tour (null: not one to stop at). */
    static String sightWords(Landmark.Kind kind, Clan clan) {
        return switch (kind) {
            case GREAT_OAK, ANCIENT_OAK -> "This oak has stood longer than any Clan remembers. Squirrels love it: remember that when you hunt.";
            case SKY_OAK -> "The Sky Oak, the tallest tree in the woods. From the top you can see right across the lake.";
            case OWL_TREE -> "The Owl Tree. An owl nests up there, so keep your head down at dusk.";
            case GREAT_SYCAMORE -> "The Great Sycamore. Apprentices learn to climb here, and so will you.";
            case SNAKEROCKS -> "Snakerocks. Adders sun themselves here. Never put your paw in a crack you can't see into.";
            case SUNNINGROCKS -> "Sunningrocks. " + (clan == Clan.RIVER ? "ThunderClan says they're theirs. They're wrong." : "RiverClan wants these warm stones. They've fought us for them for moons.");
            case ABANDONED_NEST -> "An old Twoleg nest. No one lives there now, but catmint grows in the garden. Remember it for the medicine cat.";
            case HALF_BRIDGE -> "The half-bridge. Twolegs bring their boats here in greenleaf. Stay out of sight when they do.";
            case HORSEPLACE -> "The horseplace. The horses are big, but they won't chase you. The barn cats there are loners.";
            case CARRIONPLACE -> "Carrionplace. It stinks, and the rats carry sickness. Never eat anything from there.";
            case TUNNELS -> "The old tunnels under the border. Never go in alone: they flood, and they're dark as night.";
            case STEPPING_STONES -> "The stepping stones, the one dry way across the river. Mind your paws, they're slippery.";
            case TWOLEG_NEST -> "A Twoleg nest. Kittypets live there. Don't let their soft food tempt you.";
            default -> null;
        };
    }

    // --- step 4: the border patrol

    /** Along the patrol's own route, a word at each border, then home and the step is taken there. */
    private static boolean patrol(ServerPlayer p, Clan clan, WCatEntity m, Landmark camp, Task t, int colour) {
        ClanWorld w = ClanWorld.current();
        if (w == null) return false;
        List<Lead.Stop> stops = new ArrayList<>();
        for (Task.Objective o : t.objectives) {
            if (o.type != Task.Objective.Type.VISIT || o.done()) continue;
            Clan other = beyond(w, clan, o.x, o.z);
            stops.add(new Lead.Stop(o.x, o.z, o.radius, say(colour, other != null
                    ? "Here's the " + BookClans.Info.of(other).title + " border. Mark it well, on our side, and watch for any cat who's crossed it."
                    : "We mark the border here. Past it, anything could be waiting: rogues, foxes, Twolegs.")));
        }
        if (stops.isEmpty()) return false;
        List<double[]> in = Starts.wayIn(w, camp);
        if (in.size() > 1) stops.add(new Lead.Stop(in.get(0)[0], in.get(0)[1], 8, null));
        stops.add(new Lead.Stop(camp.x(), camp.z(), Math.min(camp.radius(), 16), l -> {
            Task cur = QuestState.story(l.player());
            if (cur != null && cur.kind == Task.Kind.STORY_PATROL && cur.readyToHandIn()) Talk.handIn(l.player(), l.cat());
        }));
        return begin(p, m, stops, colour, "Come, " + Joining.catName(p) + ". We walk the borders together. Keep your nose open for anything that doesn't belong.");
    }

    // --- step 5: the places every apprentice knows

    /**
     * To each place of the step in its order, round the lake (or Fourtrees) rather than through it, and over the
     * tree-bridge to the Gathering island; a word at each, and the step is taken at the last.
     */
    private static boolean places(ServerPlayer p, Clan clan, WCatEntity m, Landmark camp, Task t, int colour) {
        ClanWorld w = ClanWorld.current();
        if (w == null) return false;
        Landmark bridge = null;
        for (Landmark l : w.lake.landmarks()) if (l.kind() == Landmark.Kind.TREE_BRIDGE) bridge = l;
        List<Lead.Stop> stops = new ArrayList<>();
        List<double[]> in = Starts.wayIn(w, camp);
        if (in.size() > 1 && Math.hypot(p.getX() - camp.x(), p.getZ() - camp.z()) < camp.radius() + 8) stops.add(new Lead.Stop(in.get(0)[0], in.get(0)[1], 8, null));
        double fx = p.getX(), fz = p.getZ();
        boolean healer = QuestState.path(p) == QuestState.Path.MEDICINE;
        boolean any = false;
        for (Task.Objective o : t.objectives) {
            if (o.type != Task.Objective.Type.VISIT || o.done()) continue;
            any = true;
            boolean island = o.text.contains("Gathering island") && !Homeland.forest() && bridge != null;
            double tx = island ? bridge.x() : o.x, tz = island ? bridge.z() : o.z;
            for (double[] pt : Starts.wayRound(w, fx, fz, tx, tz)) stops.add(new Lead.Stop(pt[0], pt[1], 12, null));
            if (island) stops.add(new Lead.Stop(bridge.x(), bridge.z(), 8, say(colour, "The tree-bridge. Dig your claws into the bark, and don't look down.")));
            boolean pool = healer && o.text.toLowerCase(java.util.Locale.ROOT).contains("moonpool");
            stops.add(new Lead.Stop(o.x, o.z, o.radius, pool ? l -> Ui.play(l.player(), moonpoolScene(l.cat(), colour, Joining.catName(l.player())))
                    : say(colour, placeWords(w, clan, o))));
            fx = o.x;
            fz = o.z;
        }
        if (!any) return false;
        if (healer) {
            // a medicine cat does not leave the apprentice up in the hills: they walk home together, and the step is
            // taken in camp (the mentor used to take it at the pool and then vanish back to camp the moment the
            // apprentice looked away, with nothing said: playtest 2026-11-05)
            for (double[] pt : Starts.wayShore(w, fx, fz, camp.x(), camp.z())) stops.add(new Lead.Stop(pt[0], pt[1], 12, null));
            List<double[]> home = Starts.wayIn(w, camp);
            if (home.size() > 1) stops.add(new Lead.Stop(home.get(0)[0], home.get(0)[1], 8, null));
            stops.add(new Lead.Stop(camp.x(), camp.z(), Math.min(camp.radius(), 14), l -> {
                Task cur = QuestState.story(l.player());
                if (cur != null && cur.kind == Task.Kind.STORY_PLACES && cur.readyToHandIn()) Talk.handIn(l.player(), l.cat());
            }));
            return begin(p, m, stops, colour, "Today we go up to the Moonpool, " + Joining.catName(p)
                    + ". Every medicine cat must know the way, even in the dark. It's a long walk: stay close, and I'll bring you home again.");
        }
        Lead.Stop last = stops.remove(stops.size() - 1);
        stops.add(new Lead.Stop(last.x(), last.z(), last.radius(), l -> {
            if (last.reached() != null) last.reached().accept(l);
            Task cur = QuestState.story(l.player());
            if (cur != null && cur.kind == Task.Kind.STORY_PLACES && cur.readyToHandIn()) Talk.handIn(l.player(), l.cat());
        }));
        return begin(p, m, stops, colour, "Today I'll show you the places every apprentice should know, " + Joining.catName(p) + ". It's a long walk: stay close.");
    }

    /** At the Moonpool with a medicine cat apprentice: what the place is, what happens there, and what comes next. */
    static List<Line> moonpoolScene(WCatEntity m, int colour, String you) {
        String name = Kin.name(m);
        return List.of(
                Line.narrate("The path winds up between the rocks and opens on a hollow. A stream spills down into a still pool, and the stars shine in it."),
                line(m, colour, "This is the Moonpool, " + you + ". Look at the stones on the path: they are worn into hollows by the paws of every medicine cat who ever came here."),
                line(m, colour, "At every half moon the medicine cats of all four Clans meet here. Whatever our Clans are fighting over, here there are no borders. We share tongues in peace."),
                line(m, colour, "We lie at the water's edge and touch our noses to it, and StarClan comes to us in our dreams: warnings, signs, sometimes only a scent on the wind. "
                        + "It is our task to understand them, and to tell our leader what they mean."),
                line(m, colour, "When the moon is half full I'll ask you to come with me, and you'll meet the other Clans' medicine cats. They will want to know my apprentice."),
                Line.narrate(name + " touches their nose to the water for a heartbeat, then turns away from the pool."),
                line(m, colour, "Come. It's a long way home, and I'll want to hear every herb you see on the way."));
    }

    private static String placeWords(ClanWorld w, Clan clan, Task.Objective o) {
        String text = o.text.toLowerCase(java.util.Locale.ROOT);
        if (text.contains("moonpool")) return "The Moonpool. At every half moon the medicine cats of all four Clans come here to share tongues with StarClan. One day you'll dream here too.";
        if (text.contains("gathering island")) return "The Gathering island. At every full moon the four Clans cross the tree-bridge and meet here in peace.";
        if (text.contains("fourtrees")) return "Fourtrees: four great oaks, and the Great Rock where the leaders speak at a Gathering. Here the Clans meet in peace.";
        if (text.contains("tunnels")) return sightWords(Landmark.Kind.TUNNELS, clan);
        if (text.contains("gorge")) return "The gorge. The river roars down there between the rocks. Never, ever try to cross it.";
        for (Landmark l : Homeland.map(w).landmarks()) {
            String words = sightWords(l.kind(), clan);
            if (words != null && Math.hypot(l.x() - o.x, l.z() - o.z) < 40) return words;
        }
        return "Here it is. Remember the way: one day you'll bring an apprentice of your own.";
    }

    // --- steps 2 and 6: the mentor stays near (close for the hunt, further off for the assessment)

    private static boolean watch(ServerPlayer p, Clan clan, WCatEntity m, Task t, int colour) {
        boolean assessment = t.kind == Task.Kind.STORY_ASSESSMENT;
        CompoundTag root = QuestState.root(p);
        Lead lead = Lead.accompany(p, m, assessment ? 12 : 5, WATCH,
                (l, how) -> QuestState.root(l.player()).putLong("mentorNext", l.player().level().getGameTime() + RETRY));
        if (lead == null) return false;
        root.putLong("mentorNext", 0);
        Ui.subtitle(p, List.of(line(m, colour, assessment
                ? "I'll be over there, watching. Pretend I'm not here. Two catches, one quick after the other."
                : huntTip(clan))));
        return true;
    }

    private static String huntTip(Clan clan) {
        return switch (clan) {
            case THUNDER -> "I'll come with you. Stay low and creep up from downwind, so the squirrel can't smell you. Feel the ground through your paws.";
            case WIND -> "I'll come with you. Rabbits are fast, but they run straight: get between them and their burrow, then sprint.";
            case RIVER -> "I'll come with you. Watch for the flash of a fish in the shallows, keep your shadow off the water, then hook it out quick.";
            case SHADOW -> "I'll come with you. Frogs freeze when they're scared. Watch the reeds, and pounce while they still think you can't see them.";
        };
    }

    /** A word from the mentor (if near) for each catch on the hunt and the assessment, and when the assessment slips. */
    private static void react(ServerPlayer p, Task t, int step, WCatEntity m, CompoundTag root) {
        int caught = 0;
        for (Task.Objective o : t.objectives) {
            if (o.type == Task.Objective.Type.CATCH || o.type == Task.Objective.Type.CATCH_IN_TIME || o.type == Task.Objective.Type.STALK) caught += o.progress;
        }
        boolean fresh = root.getInt("mentorSeenStep") != step;
        int seen = root.getInt("mentorSeen");
        root.putInt("mentorSeenStep", step);
        root.putInt("mentorSeen", caught);
        if (fresh || caught == seen || m == null || m.level() != p.level() || m.distanceTo(p) > 40 || !watches(t)) return;
        Clan clan = Kin.bookClan(p);
        int colour = clan == null ? Kin.colour(p) : BookClans.Info.of(clan).colour;
        boolean assessment = t.kind == Task.Kind.STORY_ASSESSMENT;
        String text;
        if (caught < seen) {
            text = "Don't let it rattle you. Steady now, and again.";
        } else if (t.readyToHandIn() || (assessment && caught >= 2)) {
            text = assessment ? "Yes! I saw that. Swift and silent." : "That's the way! Now bring them here.";
        } else {
            String[] lines = assessment
                    ? new String[] {"Good! Now another, quickly!"}
                    : new String[] {"Nice catch! Keep going.", "Good pounce. Keep your tail still next time.", "There you go. The Clan eats tonight."};
            text = lines[p.getRandom().nextInt(lines.length)];
        }
        Ui.subtitle(p, List.of(line(m, colour, text)));
    }

    // --- between steps

    /** Now and then in camp, the mentor pads over for a word about what the apprentice is doing (a subtitle, nothing to answer). */
    private static void checkIn(ServerPlayer p, Task t, WCatEntity m, Landmark camp, double toCamp, CompoundTag root, long now, int colour) {
        if (toCamp > camp.radius() || m.distanceTo(p) > 40 || m.getTags().contains(BookClans.BUSY_TAG)) return;
        long last = root.getLong("mentorCheckIn");
        if (last == 0) {
            root.putLong("mentorCheckIn", now - CHECK_IN + 20L * 40);          // the first word comes a little after they meet
            return;
        }
        if (now - last < CHECK_IN) return;
        root.putLong("mentorCheckIn", now);
        m.getNavigation().moveTo(p, 1.0);
        String next = "";
        for (Task.Objective o : t.objectives) {
            if (!o.done()) {
                next = o.text;
                break;
            }
        }
        String you = Joining.catName(p);
        String text = switch (t.kind) {
            case STORY_HERBS -> "Any luck with the herbs, " + you + "? " + (next.isEmpty() ? "" : next + ".") + " Try the shady places and the stream banks.";
            case STORY_PLACES -> "Have you been out to see the places I told you of? " + (next.isEmpty() ? "" : next + ".");
            case STORY_TERRITORY -> "Ready to see the camp properly, " + you + "? Stay near me and I'll show you round.";
            case STORY_MED_HERBS -> "Found the herbs yet, " + you + "? " + (next.isEmpty() ? "" : next + ".") + " Use your claws, and leave the roots to grow again.";
            case STORY_MED_REMEDIES -> "Chew the dock leaves to a pulp, and shake the seeds from a poppy head. Gently! " + (next.isEmpty() ? "" : next + ".");
            case STORY_MED_ELDERS -> "Have you seen to the elders yet? Their joints ache when the weather turns. " + (next.isEmpty() ? "" : next + ".");
            case STORY_MED_TEST -> "Take your time, " + you + ". A medicine cat who rushes picks deathberries. " + (next.isEmpty() ? "" : next + ".");
            case STORY_PATROL -> "We walk the border when you're ready. Stay close to me.";
            default -> p.getRandom().nextBoolean() ? "Eat, then rest, " + you + ". A tired apprentice is a careless one."
                    : "How are your paws? Hunting is hard on them at first.";
        };
        Ui.subtitle(p, List.of(line(m, colour, text.trim())));
    }

    /** A mentor left far from camp by a walk goes home once no one is watching (its camp's land must be loaded). */
    private static void goHome(ServerPlayer p, WCatEntity m, Landmark camp) {
        if (Lead.leading(m) || m.getTags().contains(BookClans.BUSY_TAG) || m.distanceTo(p) < 40) return;
        if (Math.hypot(m.getX() - camp.x(), m.getZ() - camp.z()) < camp.radius() + 48) return;
        BlockPos home = m.hasHomePosition() ? m.getHomePosition() : m.wanderCenter;
        if (home == null || !p.serverLevel().hasChunkAt(home)) return;
        if (Lead.inSight(p, m.position()) || Lead.inSight(p, net.minecraft.world.phys.Vec3.atBottomCenterOf(home))) return;
        m.getNavigation().stop();
        m.teleportTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5);
    }

    // --- helpers

    private static boolean begin(ServerPlayer p, WCatEntity m, List<Lead.Stop> stops, int colour, String opening) {
        String name = Kin.name(m), you = Joining.catName(p);
        Lead lead = Lead.start(p, m, stops, (l, how) -> {
            if (how != Lead.End.ABANDONED) return;
            ServerPlayer q = l.player();
            QuestState.root(q).putLong("mentorNext", q.level().getGameTime() + RETRY);
            if (QuestState.root(q).getBoolean("mentorHome")) return;          // called off for the night or the rain
            if (!l.cat().isAlive()) return;                                   // the mentor died on the walk: Story names a new one
            if (!q.hasDisconnected() && q.isAlive()) Ui.subtitle(q, List.of(line(l.cat(), colour, "We'll finish this another time. Find me in camp.")));
        });
        if (lead == null) return false;
        lead.whenWaiting(l -> Ui.subtitle(l.player(), List.of(switch (l.player().getRandom().nextInt(3)) {
            case 0 -> line(l.cat(), colour, "Keep up, " + you + "!");
            case 1 -> line(l.cat(), colour, "Don't dawdle. A warrior keeps pace with the patrol.");
            default -> Line.narrate(name + " stops and looks back, waiting for you.");
        })));
        lead.whenBlocked(l -> Ui.subtitle(l.player(), List.of(line(l.cat(), colour,
                "There's no safe way over there from here. Never mind, I'll show you another day. This way."))));
        Ui.play(p, List.of(line(m, colour, opening)));
        return true;
    }

    private static java.util.function.Consumer<Lead> say(int colour, String text) {
        return l -> Ui.subtitle(l.player(), List.of(line(l.cat(), colour, text)));
    }

    private static Line line(WCatEntity cat, int colour, String text) {
        return Line.say(Kin.name(cat), colour, text).speakerEntity(cat);
    }
}
