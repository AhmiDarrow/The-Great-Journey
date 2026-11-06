package com.ninjacat.greatjourney.qa.client;

import static com.ninjacat.greatjourney.qa.client.S.*;

import com.ninjacat.greatjourney.qa.Qa;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.screens.DeathScreen;

/**
 * TEST ONLY. The playtest suites. Each client plays one suite (tools/playtest.py picks it), made of scenarios:
 * <ul>
 *   <li>{@code free-main}: S1 title and a new Free world (cat creator, no spawn picker, the dream, the start choice),
 *   S2 joining ThunderClan with camp views and the nests, S10 the mentor's territory tour (walked behind them), S5
 *   duties and the journal, S11 a border patrol walked with two warriors, S12 the camp's day, S4 every Clan event and
 *   weather, S6 a monster on the Thunderpath, S8 leaving mid-dialogue and mid-tour, S7 the whole tour, S13 a night in
 *   the open with no monsters.</li>
 *   <li>{@code free-starts}: a fresh Free world per start: kittypet and loner (each seeks a Clan and walks behind its
 *   guide to the camp, S10), WindClan, RiverClan, ShadowClan.</li>
 *   <li>{@code campaign}: S3 the Campaign from Fourtrees through all four acts, plus the kittypet and loner starts.</li>
 *   <li>{@code mp-a} / {@code mp-b}: S9 two players on one dedicated server.</li>
 * </ul>
 */
final class Scenarios {
    private Scenarios() {}

    static final List<String> CLANS = List.of("thunderclan", "windclan", "riverclan", "shadowclan");
    static final List<String> CAMP_VIEWS = List.of("centre", "gate", "outside", "above", "leader", "medicine", "nursery", "warriors");

    static List<Step> suite(String name) {
        List<Step> s = new ArrayList<>();
        switch (name) {
            case "smoke" -> {                                // the focus check: title, one world, a picture, quit
                s.add(scenario("S0-smoke"));
                s.add(titleReady());
                s.add(shot("title"));
                s.add(titleButtons());
            }
            case "free-main" -> freeMain(s);
            case "route" -> {                                // the Great Journey's folk settle where they belong
                newWorld(s, "S3-route", "menu.clanlands.campaign", "20261016", false);
                s.add(skipDream());
                s.add(awaitDialogue("gathering", 20 * 90));
                s.add(dialogue("gathering", List.of("a kittypet"), 120, true, 1));
                s.add(awaitMove("to the start", 40, 20 * 60));
                for (String l : List.of("MIDNIGHT_SETT", "TRIBE_CAVE")) {
                    s.add(cmd("qa tp landmark journey " + l));
                    s.add(waitTicks(400));
                    s.add(cmd("qa route"));
                    s.add(shot("route-" + l.toLowerCase(Locale.ROOT)));
                }
                s.add(cmd("qa tp landmark lake KITTYPET_HOME"));
                s.add(waitTicks(400));
                s.add(cmd("qa route"));
            }
            case "duties" -> {                               // focused: events then duties, to find a stuck scene
                newWorld(s, "S5-events-then-duties", "menu.clanlands.free", "20261015", false);
                s.add(skipDream());
                s.add(dialogue("start", List.of("join a clan now", "thunderclan"), 120, true, 2));
                s.add(awaitMove("to the camp", 40, 20 * 60));
                s.add(waitTicks(100));
                s.add(cmd("qa cats"));
                s.add(cmd("qa talk medicine"));
                s.add(dialogue("medicine-first", List.of("do you need help", "i'll do it", "goodbye"), 60, true, 3));
                s.add(check("duties 1"));
                for (String k : List.of("injury", "kits", "raid", "lost_kit")) {
                    s.add(majorEventOver());                 // the lost kit waits for the raid to end, as in the game
                    s.add(cmd("qa run greatjourney event thunderclan " + k));
                    s.add(waitTicks(100));
                    s.add(dialogue("event-" + k, List.of("goodbye", "continue"), 40, false, 2));
                    s.add(cmd("qa state"));
                }
                s.add(waitTicks(20 * 60));
                s.add(cmd("qa state"));
                s.add(cmd("qa cats"));
                s.add(cmd("qa talk warrior"));
                s.add(dialogue("warrior", List.of("do you need help", "i'll do it", "goodbye"), 60, true, 3));
                s.add(check("duties 2"));
                s.add(cmd("qa fulfil"));
                s.add(cmd("qa talk warrior"));
                s.add(dialogue("warrior-hand-in", List.of("hand it over", "goodbye"), 60, true, 3));
                s.add(check("standing 1"));
                s.add(journal());
                s.add(cmd("qa state"));
            }
            case "free-starts" -> freeStarts(s);
            case "walks" -> {                                // focused: a border patrol and the camp's day, without the rest of free-main
                newWorld(s, "S2-join-thunderclan", "menu.clanlands.free", "20261012", false);
                s.add(skipDream());
                s.add(dialogue("start", List.of("join a clan now", "thunderclan"), 120, true, 2));
                afterJoin(s, "lake", "thunderclan");
                s.add(dialogue("ceremony", List.of("goodbye"), 60, false, 2));
                nestChecks(s);
                patrolScenario(s, "thunderclan");
                routineScenario(s, "thunderclan");
            }
            case "store" -> Photo.store(s);
            case "portraits" -> Photo.portraits(s);
            case "gathering" -> Photo.gathering(s);
            case "campaign" -> campaign(s);
            case "intro" -> intro(s);
            case "mp-a", "mp-b" -> multiplayer(s, name.endsWith("a"));
            case "tour" -> {
                newWorld(s, "S7-tour", "menu.clanlands.free", "20261012", false);
                s.add(skipDream());
                s.add(dialogue("start", List.of("join a clan now", "thunderclan"), 100, false, 2));
                tourScenario(s);
            }
            default -> s.add(log("unknown suite " + name));
        }
        return s;
    }

    // building blocks ---------------------------------------------------------------------------------------------

    /** From the title screen: the mode button, a new world (optional seed), into it, and Warrior Cats' first join. */
    static void newWorld(List<Step> s, String scenario, String modeKey, String seed, boolean photographTitle) {
        s.add(scenario(scenario));
        s.add(titleReady());
        if (photographTitle) {
            s.add(shot("title"));
            s.add(titleButtons());
        }
        s.add(click(modeKey));
        s.add(createWorld(seed));
        s.add(inWorld());
        s.add(cmd("gamerule sendCommandFeedback false"));
        s.add(cmd("gamerule disablePlayerMovementCheck true"));
        s.add(cmd("time set day"));
        s.add(cmd("weather clear"));
        s.add(wceFirstJoin(20 * 25));
    }

    /** In the StarClan dream: read the first lessons, photograph them, then skip the rest of the tutorial. */
    static Step skipDreamAfterLooking(List<Step> s) {
        s.add(dimension("starclan", 20 * 90));
        s.add(dialogue("dream", List.of("yes", "ready", "continue"), 80, true, 6));
        s.add(shot("dream-world"));
        s.add(cmd("greatjourney tutorial skip"));
        return dimension("overworld", 20 * 90);
    }

    static Step skipDream() {
        // done only once the dream has been and gone: a lagging server can still have the player in the overworld,
        // dream not yet begun, when this step starts (free-starts 2026-10-12: a 37 s stall, the "skip" passed early,
        // and the dream's first scene was then answered as if it were the start choice)
        boolean[] dreamed = {false};
        return of("skip the dream", 20 * 120, false, (mc, age) -> {
            if (mc.screen != null && mc.screen.getClass().getName().contains("snowteb")) mc.setScreen(null);
            if (mc.level == null || mc.player == null) return false;
            String dim = mc.level.dimension().location().getPath();
            if (dim.equals("starclan")) dreamed[0] = true;
            if (dim.equals("starclan") && age % 100 == 20) mc.player.connection.sendCommand("greatjourney tutorial skip");
            return dim.equals("overworld") && age > 60 && (dreamed[0] || age > 20 * 90);
        });
    }

    static void campViews(List<Step> s, String map, String clan) {
        s.add(camera(true));
        s.add(cmd("qa tp camp " + map + " " + clan + " centre"));
        s.add(waitTicks(60));
        s.add(check("nostructures 96"));
        for (String v : CAMP_VIEWS) {
            s.add(cmd("qa tp camp " + map + " " + clan + " " + v));
            s.add(waitTicks(v.equals("centre") ? 120 : 50));
            s.add(shot(map + "-" + clan + "-" + v));
        }
        s.add(cmd("qa tp camp " + map + " " + clan + " centre"));
        s.add(cmd("gamemode survival"));
        s.add(camera(false));
        s.add(waitTicks(40));
    }

    static void afterJoin(List<Step> s, String map, String clan) {
        s.add(awaitMove("to the camp", 40, 20 * 60));
        s.add(waitTicks(100));
        s.add(check("alive"));
        s.add(check("ground"));
        s.add(check("clan " + clan.replace("clan", "")));
        s.add(check("incamp " + map + " " + clan));
        s.add(check("stage playing"));
        s.add(check("mentor"));
        s.add(shot("arrived-" + clan));
        s.add(cmd("qa state"));
    }

    // suites ------------------------------------------------------------------------------------------------------

    static void freeMain(List<Step> s) {
        newWorld(s, "S1-new-free-world", "menu.clanlands.free", "20261012", true);
        s.add(skipDreamAfterLooking(s));
        s.add(awaitDialogue("start choice", 20 * 60));
        s.add(check("stage choosing"));

        s.add(scenario("S2-join-thunderclan"));
        s.add(dialogue("start", List.of("join a clan now", "thunderclan"), 120, true, 4));
        afterJoin(s, "lake", "thunderclan");
        s.add(dialogue("ceremony", List.of("goodbye"), 60, false, 4));
        nestChecks(s);
        mentorTour(s);                                       // it begins by itself once the mentor is named, as for a player
        campViews(s, "lake", "thunderclan");
        s.add(check("cats thunderclan 6"));

        s.add(scenario("S5-duties"));
        s.add(cmd("qa tp camp lake thunderclan centre"));
        s.add(waitTicks(80));
        s.add(awaitCats(6, 20 * 40));
        s.add(cmd("qa cats"));
        s.add(cmd("qa talk medicine"));
        s.add(dialogue("medicine", List.of("do you need help", "i'll do it", "goodbye"), 60, true, 4));
        s.add(check("duties 1"));
        s.add(cmd("qa talk deputy"));
        s.add(dialogue("deputy", List.of("do you need help", "i'll do it", "goodbye"), 60, true, 3));
        s.add(cmd("qa state"));
        s.add(journal());
        s.add(cmd("qa fulfil"));
        s.add(cmd("qa talk medicine"));
        s.add(dialogue("medicine-hand-in", List.of("hand it over", "goodbye"), 60, true, 3));
        s.add(check("standing 1"));
        s.add(cmd("qa state"));
        s.add(journal());

        patrolScenario(s, "thunderclan");
        routineScenario(s, "thunderclan");

        s.add(scenario("S4-events"));
        for (String k : List.of("injury", "sickness", "kits", "ceremony", "hunt", "predator", "old_age", "freeze")) {
            s.add(cmd("qa run greatjourney event thunderclan " + k));
            s.add(waitTicks(70));
            s.add(shot("event-" + k));
            s.add(dialogue("event-" + k, List.of("goodbye", "continue"), 40, false, 2));
        }
        for (String k : List.of("raid", "badger", "lost_kit")) {
            s.add(majorEventOver());                         // the game runs one big event at a time (a raid lasts up to 3 minutes)
            s.add(cmd("qa run greatjourney event thunderclan " + k));
            s.add(waitTicks(60));
            s.add(shot("event-" + k + "-1"));
            s.add(waitTicks(200));
            s.add(shot("event-" + k + "-2"));
            s.add(dialogue("event-" + k, List.of("goodbye", "continue"), 40, false, 2));
            s.add(waitTicks(20 * 40));
        }
        s.add(cmd("qa tp camp lake thunderclan centre"));
        s.add(cmd("gamemode survival"));
        s.add(waitTicks(80));
        s.add(check("alive"));

        // the old-age death may have taken the medicine cat: within a minute the Clan names another
        s.add(waitTicks(20 * 70));
        s.add(cmd("qa cats"));
        s.add(cmd("qa talk medicine"));
        s.add(awaitDialogue("a medicine cat after the events", 20 * 15));
        s.add(dialogue("medicine-after", List.of("goodbye"), 40, false, 2));
        s.add(scenario("S6-monster"));
        s.add(cmd("qa tp road lake thunderpath"));
        s.add(waitTicks(400));                               // the road's stretch loads before a monster can come
        s.add(shot("roadside"));
        s.add(cmd("qa monster"));
        for (int i = 0; i < 8; i++) {
            s.add(waitTicks(14));
            s.add(shot("monster-" + i));
        }
        s.add(waitTicks(20 * 30));
        s.add(check("monsters gone"));
        s.add(cmd("qa tp road lake thunderpath"));
        s.add(waitTicks(40));
        s.add(cmd("qa note standing in the lane"));
        s.add(cmd("qa tp road lake thunderpath on"));
        s.add(waitTicks(40));
        s.add(cmd("qa monster at-me"));
        final int[] dead = {-1};
        s.add(of("struck by a monster", 20 * 40, false, (mc, age) -> {
            if (mc.screen instanceof DeathScreen) {
                if (dead[0] < 0) dead[0] = age;
                if (age - dead[0] < 30) return false;           // let the death screen fade in before the picture
                Driver.shot(mc, "death-screen");
                Driver.pass(mc, "monster strikes");
                mc.player.respawn();
                mc.setScreen(null);
                return true;
            }
            if (age >= 20 * 40 - 2) Driver.fail(mc, "monster strikes", "the player was never hit");
            return false;
        }));
        s.add(waitTicks(60));
        s.add(check("alive"));
        s.add(waitTicks(20 * 25));
        s.add(check("monsters none"));

        s.add(scenario("S8-persistence"));
        s.add(cmd("qa tp camp lake thunderclan centre"));
        s.add(waitTicks(60));
        s.add(cmd("qa talk deputy"));
        s.add(awaitDialogue("deputy", 20 * 15));
        s.add(shot("mid-dialogue"));
        s.add(quitToTitle());
        s.add(reopenWorld());
        s.add(waitTicks(100));
        s.add(check("alive"));
        s.add(check("ground"));
        s.add(check("stage playing"));
        s.add(dialogue("after-reload", List.of("goodbye"), 60, false, 2));
        s.add(cmd("qa talk deputy"));
        s.add(awaitDialogue("deputy-again", 20 * 15));
        s.add(dialogue("deputy-again", List.of("goodbye"), 40, false, 2));
        s.add(cmd("greatjourney tour"));
        s.add(waitTicks(20 * 15));
        s.add(shot("mid-tour"));
        s.add(quitToTitle());
        s.add(reopenWorld());
        s.add(waitTicks(120));
        s.add(check("touring false"));
        s.add(check("gamemode survival"));
        s.add(check("ground"));
        s.add(shot("after-tour-reload"));

        tourScenario(s);
        monstersScenario(s);
    }

    static void tourScenario(List<Step> s) {
        s.add(scenario("S7-tour"));
        s.add(closeScreens());
        s.add(tour());
        s.add(waitTicks(60));
        s.add(check("touring false"));
        s.add(check("gamemode survival"));
        s.add(check("ground"));
        s.add(shot("after-tour"));
    }

    static void freeStarts(List<Step> s) {
        String[][] starts = {{"kittypet", "a kittypet"}, {"loner", "a loner"}, {"windclan", "windclan"}, {"riverclan", "riverclan"},
                {"shadowclan", "shadowclan"}};
        for (int i = 0; i < starts.length; i++) {
            String id = starts[i][0];
            if (i > 0) s.add(quitToTitle());
            newWorld(s, "S2-start-" + id, "menu.clanlands.free", null, false);
            s.add(skipDream());
            s.add(awaitDialogue("start choice", 20 * 60));
            boolean clan = id.endsWith("clan");
            // a quick arrival brings "Which Clan's cats will you seek?" and the guide while this step still reads windows
            s.add(dialogue("start", clan ? List.of("join a clan now", id) : List.of(starts[i][1], seekFor(id), "lead the way"), 120, true, 3));
            if (clan) {
                afterJoin(s, "lake", id);
                nestChecks(s);
                campViews(s, "lake", id);
                s.add(check("cats " + id + " 6"));
                // each Clan's own weather, one per world (only one weather event runs at a time)
                String weather = switch (id) {
                    case "windclan" -> "drought";
                    case "riverclan" -> "flood";
                    default -> "fog";
                };
                s.add(cmd("qa run greatjourney event " + id + " " + weather));
                s.add(waitTicks(200));
                s.add(shot("event-" + weather));
                s.add(dialogue("event-" + weather, List.of("goodbye"), 40, false, 2));
            } else {
                s.add(awaitMove("to the start", 40, 20 * 60));
                s.add(waitTicks(100));
                s.add(check("alive"));
                s.add(check("ground"));
                s.add(check("start " + id));
                s.add(check("clan none"));
                if (id.equals("kittypet")) s.add(check("near lake KITTYPET_HOME 40"));
                s.add(shot("arrived-" + id));
                s.add(cmd("qa state"));
                // the kittypet seeks ThunderClan next door, as in the books; the loner a Clan round the lake
                seekWithGuide(s, id, seekFor(id));
                if (id.equals("loner")) {                     // ThunderClan's fire, as in the books
                    s.add(cmd("qa tp camp lake thunderclan gate"));
                    s.add(waitTicks(160));
                    s.add(cmd("qa run greatjourney event thunderclan fire"));
                    s.add(waitTicks(100));
                    s.add(shot("event-fire"));
                    s.add(dialogue("event-fire", List.of("goodbye"), 40, false, 2));
                    s.add(waitTicks(300));
                    s.add(shot("event-fire-later"));
                }
            }
        }
    }

    /**
     * Before a long walk: fed for its length (no stopping to hunt), and kept from harm, since the bot only walks and
     * cannot fight (in the first run a fox killed the guide, then the kittypet). The log still shows any attack.
     */
    /** The Clan a kittypet or loner seeks: ThunderClan next door for the kittypet, as in the books; one round the lake for the loner. */
    static String seekFor(String start) {
        return start.equals("kittypet") ? "thunderclan" : "riverclan";
    }

    static void walker(List<Step> s) {
        s.add(cmd("effect give @s minecraft:saturation infinite 0 true"));
        s.add(cmd("effect give @s minecraft:resistance infinite 4 true"));
    }

    /** The player's own nest in the apprentices' den, where they wake, and every den's full set of beds. */
    static void nestChecks(List<Step> s) {
        s.add(check("nest apprentices"));
        s.add(check("respawn nest"));
        s.add(check("beds"));
    }

    /**
     * A kittypet or loner just arrived: "Which Clan's cats will you seek?", the guide who comes, the walk in its
     * footsteps to that camp (timed, its teleports counted) and the deputy's invitation there; then the nest.
     */
    static void seekWithGuide(List<Step> s, String start, String clan) {
        s.add(scenario("S10-guide-" + start));
        walker(s);
        s.add(cmd("qa watch lead"));
        s.add(camera(true));
        // the seek choice and the guide's offer, whenever they come (often already answered in the start's own step)
        s.add(Walk.await("guide lead", 20 * 120, List.of(clan, "lead the way")));
        s.add(check("seeking " + clan));
        s.add(shot("guide-ready"));
        s.add(Walk.follow("guide", 20 * 60 * 16, List.of("lead the way", "continue"), 10, 20 * 45));
        s.add(cmd("qa watch report"));
        s.add(shot("guide-at-camp"));
        s.add(awaitDialogue("invitation", 20 * 30));
        s.add(dialogue("invitation", List.of("join"), 100, true, 3));
        s.add(waitTicks(60));
        s.add(check("clan " + clan.replace("clan", "")));
        s.add(check("incamp lake " + clan));
        s.add(check("stage playing"));
        s.add(check("mentor"));
        nestChecks(s);
        s.add(shot("joined-" + clan));
        s.add(camera(false));
        s.add(cmd("qa state"));
    }

    /** Apprentice step 1: the mentor leads the territory tour, the bot walks behind; then the mentor keeps near for the hunt. */
    static void mentorTour(List<Step> s) {
        s.add(scenario("S10-mentor-tour"));
        walker(s);
        s.add(cmd("qa watch lead"));
        s.add(camera(true));
        s.add(Walk.await("mentor tour", 20 * 60 * 3, List.of("goodbye", "continue")));
        s.add(check("lead leading"));
        s.add(Walk.follow("mentor-tour", 20 * 60 * 14, List.of("goodbye", "continue"), 12, 20 * 60));
        s.add(cmd("qa watch report"));
        s.add(dialogue("tour-hand-in", List.of("goodbye", "continue"), 60, false, 3));
        s.add(check("story 2"));
        s.add(waitTicks(20 * 15));                           // the hunt: the mentor comes along and keeps near
        s.add(check("lead accompanying 12"));
        s.add(shot("mentor-on-the-hunt"));
        s.add(camera(false));
        s.add(cmd("qa state"));
    }

    /** A border patrol from the deputy: two warriors walk it beside the player, out to the points and home, timed. */
    static void patrolScenario(List<Step> s, String clan) {
        s.add(scenario("S11-patrol"));
        s.add(cmd("qa tp camp lake " + clan + " centre"));
        s.add(waitTicks(60));
        s.add(awaitCats(6, 20 * 40));
        s.add(cmd("qa duties clear"));
        s.add(cmd("qa patrolday"));                          // a day when the deputy's duty is a patrol
        s.add(waitTicks(100));
        s.add(cmd("qa talk deputy"));
        s.add(dialogue("deputy-patrol", List.of("do you need help", "i'll do it", "goodbye"), 60, true, 3));
        s.add(check("duties 1"));
        s.add(check("escorts 2"));
        s.add(cmd("qa watch patrol"));
        walker(s);
        s.add(camera(true));
        s.add(Walk.await("patrol", 20 * 20, List.of("goodbye")));
        s.add(Walk.follow("patrol", 20 * 60 * 14, List.of("goodbye", "continue"), 4, 20 * 60));
        s.add(cmd("qa watch report"));
        s.add(shot("patrol-home"));
        s.add(cmd("qa talk deputy"));
        s.add(dialogue("deputy-patrol-hand-in", List.of("hand it over", "goodbye"), 60, true, 3));
        s.add(camera(false));
        s.add(cmd("qa state"));
    }

    /** The camp's day: dawn, midday and night views, a tally of what the cats do, and where they cross the barrier. */
    static void routineScenario(List<Step> s, String clan) {
        s.add(scenario("S12-camp-routine"));
        s.add(cmd("qa tp camp lake " + clan + " centre"));
        s.add(waitTicks(40));
        s.add(cmd("qa watch barrier"));
        for (String[] t : new String[][] {{"dawn", "23400"}, {"midday", "6000"}, {"night", "18000"}}) {
            s.add(cmd("qa time " + t[1]));
            s.add(cmd("qa tp camp lake " + clan + " above"));
            s.add(waitTicks(20 * 75));
            s.add(shot("routine-" + t[0] + "-above"));
            s.add(cmd("qa routine"));
            s.add(cmd("qa tp camp lake " + clan + " gate"));
            s.add(waitTicks(80));
            s.add(shot("routine-" + t[0] + "-gate"));
            s.add(cmd("qa tp camp lake " + clan + " centre"));
            s.add(waitTicks(80));
            s.add(shot("routine-" + t[0] + "-centre"));
        }
        s.add(cmd("qa watch report"));
        s.add(cmd("gamemode survival"));
    }

    /** At night out in the open, far from any camp: no zombie, skeleton, creeper or other monster comes; no tigers. */
    static void monstersScenario(List<Step> s) {
        s.add(scenario("S13-no-monsters"));
        s.add(closeScreens());
        s.add(cmd("qa open"));
        s.add(cmd("gamemode survival"));
        s.add(cmd("qa time 14000"));
        s.add(waitTicks(200));
        s.add(shot("night-open"));
        s.add(check("outofplace"));
        for (int i = 1; i <= 4; i++) {
            s.add(waitTicks(20 * 60));
            s.add(check("hostiles 128"));
        }
        s.add(shot("night-open-later"));
        s.add(check("outofplace"));
        s.add(check("alive"));
    }

    /**
     * The story cats close up: each StarClan teacher in the dream (are they on the ground, sitting, in their book
     * coats?), then the Campaign's opening Gathering at Fourtrees (leaders on the rock, warriors on the ground, nobody up
     * in the oaks) and a Gathering crowd there.
     */
    static void intro(List<Step> s) {
        newWorld(s, "I1-dream-teachers", "menu.clanlands.free", "20261105", false);
        s.add(dimension("starclan", 20 * 90));
        s.add(waitTicks(80));
        s.add(Photo.looks(true, 70, true));
        s.add(camera(true));
        s.add(cmd("gamemode spectator"));
        // {name, x, feet y, z, yaw, pitch}: three blocks south of each teacher, looking north at them
        String[][] views = {
                {"arrival", "0.5", "5", "6.5", "180", "8"},
                {"bluestar", "-7.5", "9.4", "-4.8", "180", "12"},
                {"lionheart", "-7.5", "4.4", "10.2", "180", "12"},
                {"spottedleaf", "5.5", "4.4", "12.2", "180", "12"},
                {"yellowfang", "5.5", "4.4", "-1.8", "180", "12"},
                {"yellowfang-side", "8.8", "4.6", "-4.5", "90", "10"}};
        for (String[] v : views) {
            s.add(Photo.clean());
            s.add(cmd("tp @s " + v[1] + " " + v[2] + " " + v[3] + " " + v[4] + " " + v[5]));
            s.add(waitTicks(40));
            s.add(Photo.clean());
            s.add(shot("dream-" + v[0]));
        }
        s.add(cmd("qa cats"));
        s.add(Photo.looks(false, 70, true));
        s.add(cmd("gamemode survival"));
        s.add(cmd("greatjourney tutorial skip"));
        s.add(dimension("overworld", 20 * 90));
        s.add(quitToTitle());

        newWorld(s, "I2-campaign-opening", "menu.clanlands.campaign", "20261013", false);
        s.add(skipDream());
        s.add(awaitDialogue("gathering", 20 * 90));
        s.add(shot("fourtrees-first-line"));
        s.add(Photo.clean());
        s.add(Photo.looks(true, 70, true));
        s.add(camera(true));
        s.add(Photo.burst("fourtrees-opening", 3, 30));
        s.add(cmd("gamemode spectator"));
        s.add(cmd("qa tp landmark forest FOURTREES"));
        s.add(waitTicks(120));
        s.add(Photo.clean());
        s.add(Photo.burst("fourtrees-landmark-view", 2, 30));
        s.add(cmd("qa gathering here"));
        s.add(waitTicks(220));
        s.add(Photo.burst("fourtrees-gathering-crowd", 3, 30));
        s.add(Photo.looks(false, 70, true));
    }

    static void campaign(List<Step> s) {
        newWorld(s, "S3-campaign-start", "menu.clanlands.campaign", "20261013", false);
        s.add(skipDream());
        s.add(waitTicks(100));
        s.add(check("near forest FOURTREES 160"));
        s.add(shot("fourtrees-arrival"));
        s.add(awaitDialogue("gathering", 20 * 90));
        s.add(dialogue("gathering", List.of("join one of the four clans", "thunderclan"), 120, true, 10));
        afterJoin(s, "forest", "thunderclan");
        s.add(dialogue("ceremony", List.of("goodbye"), 60, false, 3));
        campViews(s, "forest", "thunderclan");
        s.add(cmd("greatjourney campaign status"));
        s.add(check("act 1"));
        for (String c : List.of("windclan", "riverclan", "shadowclan")) {
            for (String v : List.of("above", "gate")) {
                s.add(cmd("qa tp camp forest " + c + " " + v));
                s.add(waitTicks(v.equals("above") ? 140 : 50));
                s.add(shot("forest-" + c + "-" + v));
            }
        }

        s.add(scenario("S3-campaign-acts"));
        s.add(cmd("gamemode survival"));
        s.add(cmd("qa tp camp forest thunderclan centre"));
        s.add(cmd("qa run greatjourney campaign act 2"));
        s.add(waitTicks(100));
        s.add(check("act 2"));
        s.add(dialogue("act-2", List.of("wake up", "continue", "goodbye"), 80, false, 6));
        s.add(shot("act-2"));
        s.add(cmd("qa run greatjourney campaign act 3"));
        s.add(waitTicks(100));
        s.add(check("act 3"));
        s.add(dialogue("act-3", List.of("continue", "goodbye"), 80, false, 6));
        for (String[] l : new String[][] {{"MIDNIGHT_SETT", "midnight"}, {"TRIBE_CAVE", "stoneteller"}}) {
            s.add(cmd("qa tp landmark journey " + l[0]));
            s.add(waitTicks(300));
            s.add(shot("journey-" + l[0].toLowerCase(Locale.ROOT)));
            s.add(cmd("qa route"));
            s.add(check("unique " + l[1]));
        }
        s.add(cmd("qa run greatjourney campaign act 4"));
        s.add(waitTicks(100));
        s.add(check("act 4"));
        s.add(cmd("qa tp landmark lake STONE_HOLLOW"));
        s.add(waitTicks(200));
        s.add(shot("act-4-stone-hollow"));
        s.add(cmd("qa tp landmark lake TUNNELS"));
        s.add(waitTicks(240));
        s.add(shot("act-4-tunnels"));
        s.add(cmd("qa tp landmark lake KITTYPET_HOME"));          // Purdy sits by the lake's Twoleg garden
        s.add(waitTicks(240));
        s.add(shot("purdy-garden"));
        s.add(cmd("qa route"));
        s.add(check("unique purdy"));
        s.add(cmd("qa state"));

        String[][] starts = {{"kittypet", "a kittypet"}, {"loner", "a loner"}};
        for (String[] st : starts) {
            s.add(quitToTitle());
            newWorld(s, "S3-campaign-" + st[0], "menu.clanlands.campaign", null, false);
            s.add(skipDream());
            s.add(awaitDialogue("gathering", 20 * 90));
            s.add(dialogue("gathering", List.of(st[1]), 120, true, 2));
            s.add(awaitMove("to the start", 40, 20 * 60));
            s.add(waitTicks(120));
            s.add(check("alive"));
            s.add(check("ground"));
            s.add(check("start " + st[0]));
            if (st[0].equals("kittypet")) s.add(check("near forest KITTYPET_HOME 40"));
            s.add(shot("arrived-" + st[0]));
            s.add(cmd("qa state"));
        }
    }

    /** S9: two players on one dedicated server. A and B meet at barriers so the checks line up. */
    static void multiplayer(List<Step> s, boolean a) {
        String me = a ? "a" : "b", clan = a ? "windclan" : "riverclan";
        s.add(scenario("S9-mp-join-" + me));
        s.add(titleReady());
        s.add(connect(Qa.SERVER));
        s.add(cmd("gamerule disablePlayerMovementCheck true"));
        s.add(wceFirstJoin(20 * 20));
        s.add(skipDream());
        s.add(awaitDialogue("start choice", 20 * 60));
        s.add(barrier("chosen", 2));
        s.add(dialogue("start", List.of("join a clan now", clan), 120, true, 3));
        afterJoin(s, "lake", clan);

        s.add(scenario("S9-mp-dialogue-" + me));
        s.add(barrier("talk", 2));
        s.add(cmd("qa talk deputy"));
        s.add(awaitDialogue("deputy at the same time", 20 * 20));
        s.add(shot("both-talking"));
        s.add(dialogue("deputy", List.of("tell me about the clan", "goodbye"), 60, false, 3));
        s.add(journal());

        s.add(scenario("S9-mp-monster-" + me));
        s.add(barrier("road", 2));
        s.add(cmd("qa tp road lake thunderpath"));
        s.add(waitTicks(200));
        s.add(barrier("road-ready", 2));
        if (a) s.add(cmd("qa monster"));
        for (int i = 0; i < 6; i++) {
            s.add(waitTicks(14));
            s.add(shot("shared-monster-" + i));
        }
        s.add(waitTicks(20 * 30));
        s.add(check("monsters gone"));

        s.add(scenario("S9-mp-tour-and-play-" + me));
        s.add(barrier("tour", 2));
        if (a) {
            s.add(cmd("qa tp camp lake " + clan + " centre"));
            s.add(waitTicks(40));
            s.add(awaitCats(6, 20 * 40));                    // the camp's cats load behind its land after a long teleport
            s.add(check("ground"));
            s.add(cmd("qa cats"));
            s.add(check("medicine"));
            s.add(cmd("qa talk medicine"));
            s.add(dialogue("medicine", List.of("do you need help", "i'll do it", "goodbye"), 60, true, 3));
            s.add(check("duties 1"));
            // the medicine cat itself dies (the forced old-age event takes the oldest cat, never yet the medicine cat):
            // the Clan names a successor at the vigil
            s.add(cmd("qa kill medicine"));
            s.add(waitTicks(60));
            s.add(dialogue("medicine-vigil", List.of("continue", "goodbye"), 40, false, 3));
            s.add(check("medicine"));
            s.add(waitTicks(20 * 30));
            s.add(check("alive"));
            s.add(cmd("qa cats"));
        } else {
            s.add(cmd("greatjourney tour"));
            s.add(waitTicks(20 * 40));
            s.add(shot("touring-while-a-plays"));
            s.add(cmd("greatjourney tour stop"));
            s.add(waitTicks(100));
            s.add(check("touring false"));
            s.add(check("gamemode survival"));
            s.add(check("duties 0"));
        }

        s.add(scenario("S9-mp-rejoin-" + me));
        s.add(barrier("rejoin", 2));
        if (!a) {
            s.add(cmd("qa tp camp lake " + clan + " centre"));
            s.add(waitTicks(80));
            s.add(cmd("qa talk deputy"));
            s.add(awaitDialogue("deputy before leaving", 20 * 15));
            s.add(quitToTitle());
            s.add(connect(Qa.SERVER));
            s.add(waitTicks(100));
            s.add(check("alive"));
            s.add(check("ground"));
            s.add(check("clan " + clan.replace("clan", "")));
            s.add(cmd("qa talk deputy"));
            s.add(awaitDialogue("deputy after rejoin", 20 * 15));
            s.add(dialogue("deputy-after", List.of("goodbye"), 40, false, 2));
        } else {
            s.add(waitTicks(20 * 40));
        }
        s.add(barrier("done", 2));
        s.add(cmd("qa state"));
        s.add(shot("end"));
    }

    /**
     * Singleplayer: waits until no big Clan event (a raid, a badger, a lost kit) is still running in the integrated
     * server, since the game refuses a second one meanwhile. The badger lasts up to 4 minutes of game time, which a
     * busy harness server (a new world generating at low priority) can stretch to twice that; longer is a failure.
     */
    static Step majorEventOver() {
        return of("major event over", 20 * 60 * 8, false, (mc, age) -> age % 20 == 0 && majorEvent() == null);
    }

    /** The running big event's kind (ClanEvents keeps it package-private: read here by reflection, test only). */
    private static Object majorEvent() {
        try {
            var f = Class.forName("com.ninjacat.greatjourney.clanlands.life.events.ClanEvents").getDeclaredField("activeKind");
            f.setAccessible(true);
            return f.get(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** Waits until {@code n} players have reached the same point (the server answers "[QA] GO tag" to all). */
    static Step barrier(String tag, int n) {
        return of("barrier " + tag, 20 * 60 * 6, false, (mc, age) -> {
            if (age == 0) {
                Driver.GO.remove(tag);
                mc.player.connection.sendCommand("qa arrive " + tag + " " + n);
                return false;
            }
            if (age % 200 == 199) mc.player.connection.sendCommand("qa arrive " + tag + " " + n);
            return Driver.GO.contains(tag);
        });
    }
}
