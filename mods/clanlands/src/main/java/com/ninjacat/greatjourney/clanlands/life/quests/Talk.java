package com.ninjacat.greatjourney.clanlands.life.quests;

import com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan;
import com.ninjacat.greatjourney.clanlands.life.BookClans;
import com.ninjacat.greatjourney.clanlands.life.Dialog;
import com.ninjacat.greatjourney.clanlands.life.Dialog.Choice;
import com.ninjacat.greatjourney.clanlands.life.Dialog.Line;
import com.ninjacat.greatjourney.clanlands.life.Joining;
import com.ninjacat.greatjourney.clanlands.life.Journey;
import com.ninjacat.greatjourney.clanlands.life.LifeData;
import com.ninjacat.greatjourney.clanlands.life.Mode;
import com.ninjacat.greatjourney.clanlands.life.quests.Kin.Role;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.snowteb.warriorcats_events.clan.ClanData;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;

/**
 * Talking to cats: right-click one with an empty paw (not sneaking, so Warrior Cats Events' own shift-click
 * commands still work). Your own Clanmates greet you by who they are and can give you duties or take them back;
 * cats of other Clans send you on your way; kittypets, loners and rogues answer as {@link Strangers}.
 */
final class Talk {
    private Talk() {}

    // how far off the cat may be when the player answers it: cats keep walking while the player reads, and at 8 a
    // warrior had strolled out of reach within three seconds (playtest 2026-10-12), so "I'll do it" did nothing
    private static final double REACH = 24;

    static void onInteract(PlayerInteractEvent.EntityInteract e) {
        if (e.getHand() != InteractionHand.MAIN_HAND || !(e.getEntity() instanceof ServerPlayer p)) return;
        if (!talksWith(p.getMainHandItem()) || p.isShiftKeyDown()) return;
        Strangers.Kind stranger = Strangers.kind(e.getTarget(), p);
        if (stranger != null) {                              // a kittypet, a loner, a rogue (Strangers)
            e.setCanceled(true);
            e.setCancellationResult(InteractionResult.SUCCESS);
            if (!Dialog.busy(p)) Strangers.greet(p, e.getTarget(), stranger);
            return;
        }
        if (!(e.getTarget() instanceof WCatEntity cat) || cat.getClanUUID() == null || cat.getCustomName() == null) return;
        e.setCanceled(true);
        e.setCancellationResult(InteractionResult.SUCCESS);
        if (Dialog.busy(p)) return;
        ClanData.Clan mine = Joining.clanOf(p);
        LifeData life = LifeData.get(p.serverLevel().getServer().overworld());
        Clan catClan = life.created() ? life.clanOf(cat.getClanUUID()) : null;
        int colour = catClan != null ? BookClans.Info.of(catClan).colour : 0xC8C8C8;
        String name = Kin.name(cat);
        Mode mode = Mode.of(p.serverLevel().getServer());
        if (mine == null && catClan != null && Journey.stage(p) == Journey.Stage.PLAYING
                && (mode == Mode.FREE || com.ninjacat.greatjourney.clanlands.life.campaign.Campaign.clanless(p))) {
            Role role = Kin.role(cat, life);
            if (role == Role.DEPUTY || role == Role.LEADER) {
                // the deputy (or the leader) can always take in a clanless cat who asks, whatever they said before
                BookClans.Info info = BookClans.Info.of(catClan);
                Joining.offer(p, info, name, List.of(Line.say(name, colour, role == Role.LEADER
                        ? "You come to me with no Clan scent on your fur. Have you come to ask for a place among us?"
                        : "Looking for a Clan? We hunt hard, we train hard, and we look after our own.").speakerEntity(cat)));
                return;
            }
        }
        if (mine == null || !mine.clanUUID.equals(cat.getClanUUID())) {
            String clanTitle = catClan != null ? BookClans.Info.of(catClan).title : "our Clan";
            Dialog.play(p, Line.say(name, colour, mine == null
                    ? "You smell of no Clan. If you wish to join " + clanTitle + ", speak to our deputy in camp."
                    : "You smell of " + mine.name + ". Go back to your own territory before a patrol finds you!").speakerEntity(cat));
            return;
        }
        greet(p, cat, life);
    }

    /**
     * A cat can be talked to with an empty paw, or while carrying StarClan's Guidance or the journal: new players start
     * holding the guide, and the playtest harness found right-clicks then did nothing at all.
     */
    private static boolean talksWith(net.minecraft.world.item.ItemStack held) {
        if (held.isEmpty()) return true;
        var id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(held.getItem());
        return id.equals(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("patchouli", "guide_book"))
                || id.equals(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("clanlands", "journal"))
                || held.is(net.minecraft.world.item.Items.BOOK) || held.is(net.minecraft.world.item.Items.WRITTEN_BOOK);
    }

    static void greet(ServerPlayer p, WCatEntity cat, LifeData life) {
        Role role = Kin.role(cat, life);
        String name = Kin.name(cat);
        int colour = Kin.colour(p);
        String you = Joining.catName(p);
        List<Line> lines = new ArrayList<>();
        if (Pupils.greet(p, cat)) return;                    // their own apprentice (Pupils)
        if (CodeMoments.again(p)) return;                    // a choice they walked away from still waits (CodeMoments)
        if (Patrols.inPatrolOf(p, cat)) {                    // a cat of the patrol they lead: it wants its orders
            orders(p, cat);
            return;
        }

        // reporting back on something this cat asked for
        for (Task t : QuestState.active(p)) {
            if (Story.answers(p, cat, t)) {
                lines.add(Line.say(name, colour, t.readyToHandIn() || hasDeliveries(p, t)
                        ? "Back already, " + you + "? Let's see what you have."
                        : remind(t, Kin.role(p))).speakerEntity(cat).withChoices(
                        Choice.of(t.readyToHandIn() || hasDeliveries(p, t) ? "Hand it over" : "I'm still working on it",
                                t.readyToHandIn() || hasDeliveries(p, t) ? "greatjourney quest give " + cat.getId() : "greatjourney quest bye"),
                        Choice.of("Goodbye", "greatjourney quest bye")));
                Dialog.play(p, lines);
                return;
            }
        }
        List<Choice> choices = new ArrayList<>(List.of(
                Choice.of("Do you need help?", "greatjourney quest help " + cat.getId()),
                Choice.of("Tell me about the Clan", "greatjourney quest clan " + cat.getId())));
        // the leader decides a cat's place in the Clan: a medicine cat's path, the elders' den, the nursery (Paths)
        // (where a player leads, their deputy hears it; and the leader names a deputy by talking to a warrior)
        boolean leading = Ranks.leads(p);
        if (role == Role.LEADER || leading && role == Role.DEPUTY) choices.add(Choice.of("About my place in the Clan", "greatjourney quest place " + cat.getId()));
        if (leading && role == Role.WARRIOR) choices.add(Choice.of("Be my deputy", "greatjourney quest deputy " + cat.getId()));
        // StarClan's riddle is the medicine cat's business (and a medicine cat can think aloud to any Clanmate)
        if (Riddles.puzzling(p) != null && (role == Role.MEDICINE || Kin.role(p) == Role.MEDICINE)) {
            choices.add(Choice.of("About the prophecy...", "greatjourney quest prophecy " + cat.getId()));
        }
        if (commands(p) && takesOrders(role)) choices.add(Choice.of("I have orders for you", "greatjourney quest orders " + cat.getId()));
        choices.add(Choice.of("Sit with me a while", "greatjourney quest sit " + cat.getId()));
        choices.add(Choice.of("Goodbye", "greatjourney quest bye"));
        lines.add(Line.say(name, colour, greeting(role, name, you, Kin.role(p))).speakerEntity(cat).withChoices(choices.toArray(Choice[]::new)));
        Dialog.play(p, lines);
    }

    // --- a deputy's or leader's orders

    /** Whether the player gives the Clan's orders: its deputy, or its leader. */
    static boolean commands(ServerPlayer p) {
        Role mine = Kin.role(p);
        return mine == Role.DEPUTY || mine == Role.LEADER;
    }

    private static boolean takesOrders(Role role) {
        return role == Role.WARRIOR || role == Role.APPRENTICE || role == Role.DEPUTY;
    }

    /**
     * What a deputy or leader can tell a warrior: take cats out on Warrior Cats Events' own patrols (several at once,
     * or this one cat to a place picked on its patrol map), or bring a patrol and walk with them. Asked for after play
     * (2026-11-26): a deputy could only order cats about one at a time.
     */
    static void orders(ServerPlayer p, WCatEntity cat) {
        if (!commands(p)) return;
        String name = Kin.name(cat), you = Joining.catName(p);
        int id = cat.getId();
        Choice never = Choice.of("Never mind", "greatjourney quest bye");
        if (Patrols.leads(p)) {
            Dialog.play(p, Line.say(name, Kin.colour(p), "We're with you, " + you + ". What now?").speakerEntity(cat).withChoices(
                    Choice.of("Patrol, with me", "greatjourney quest patrolorder follow"),
                    Choice.of("Patrol, wait here", "greatjourney quest patrolorder stay"),
                    Choice.of("Back to camp, all of you", "greatjourney quest patrolorder home"), never));
            return;
        }
        Dialog.play(p, Line.say(name, Kin.colour(p), "Yes, " + you + "?").speakerEntity(cat).withChoices(
                Choice.of("Take a patrol round our territory", "greatjourney quest sendpatrol border " + id),
                Choice.of("Take a hunting patrol out", "greatjourney quest sendpatrol hunt " + id),
                Choice.of("Patrol where I show you", "greatjourney quest patrolmap " + id),
                Choice.of("Bring a patrol and come with me", "greatjourney quest patrol " + id), never));
    }

    /** "Take a patrol out": this cat and up to two more free ones go on Warrior Cats Events' own patrol, by themselves. */
    static void sendPatrol(ServerPlayer p, WCatEntity cat, boolean hunt) {
        Clan clan = Kin.bookClan(p);
        if (!commands(p) || clan == null) return;
        ServerLevel ow = p.serverLevel().getServer().overworld();
        String name = Kin.name(cat);
        int colour = Kin.colour(p);
        if (Tasks.keptIn(ow, Task.Kind.BORDER_PATROL)) {
            Dialog.play(p, Line.say(name, colour, Tasks.night(ow) ? "In the dark? We'd mark nothing and catch less. At first light."
                    : "In this rain every scent is washed out. When it clears.").speakerEntity(cat));
            return;
        }
        LifeData life = LifeData.get(ow);
        List<WCatEntity> party = new ArrayList<>(p.serverLevel().getEntitiesOfClass(WCatEntity.class, cat.getBoundingBox().inflate(32),
                c -> Patrols.free(c, cat, life) && c.hasHomePosition()));
        party.sort(java.util.Comparator.comparingDouble(c -> c == cat ? -1 : c.distanceToSqr(cat)));
        java.util.Map<Integer, net.minecraft.core.BlockPos> points = new java.util.HashMap<>();
        for (int i = 0; i < 4; i++) {
            net.minecraft.core.BlockPos at = com.ninjacat.greatjourney.clanlands.life.events.Cats.inTerritory(ow, clan, p.getRandom(), 60, 120);
            if (at != null) points.put(points.size(), at);
        }
        if (party.isEmpty() || points.isEmpty()) {
            Dialog.play(p, Line.say(name, colour, "There's no cat free to go just now, " + Joining.catName(p) + ".").speakerEntity(cat));
            return;
        }
        if (party.size() > 3) party = party.subList(0, 3);
        for (WCatEntity c : party) {
            if (hunt) c.setOnHuntingPatrol(new java.util.HashMap<>(points));
            else c.setOnBorderPatrol(new java.util.HashMap<>(points));
        }
        // sending the patrols out is a deputy's duty: the Clan notices, once a day
        long day = ow.getDayTime() / 24000L;
        boolean first = QuestState.root(p).getLong("ordersDay") != day + 1;
        if (first) {
            QuestState.root(p).putLong("ordersDay", day + 1);
            QuestState.addStanding(p, 2);
            Ui.push(p);
        }
        String others = party.size() == 1 ? "" : " " + String.join(" and ", party.stream().filter(c -> c != cat).map(Kin::name).toList()) + ", with me!";
        Dialog.play(p, Line.say(name, colour, (hunt ? "We'll bring back all we can carry." : "We'll see the markers are fresh.") + others).speakerEntity(cat),
                Line.narrate(party.size() + (party.size() == 1 ? " cat sets" : " cats set") + " out, and will come home by themselves."
                        + (first ? " The Clan sees its patrols well led today (+2 standing)." : "")));
    }

    /** "Patrol where I show you": Warrior Cats Events' own patrol map, for this one cat. */
    static void patrolMap(ServerPlayer p, WCatEntity cat) {
        ClanData.Clan wc = Joining.clanOf(p);
        if (!commands(p) || wc == null || !wc.clanUUID.equals(cat.getClanUUID())) return;
        Dialog.clear(p);
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p,
                new net.snowteb.warriorcats_events.network.packet.s2c.cats.StCOpenPatrolScreenPacket(List.of(cat.getId()), wc.clanUUID, cat.getId(), true));
    }

    /** "Bring a patrol and come with me": this cat and the free warriors near it walk with the player ({@link Patrols}). */
    static void gather(ServerPlayer p, WCatEntity cat) {
        if (!commands(p)) return;
        int n = Patrols.gather(p, cat);
        Dialog.play(p, Line.say(Kin.name(cat), Kin.colour(p), n == 0 ? "There's no cat free to go just now, " + Joining.catName(p) + "."
                : n == 1 ? "I'm right behind you." : "Patrol, on " + Joining.catName(p) + "! We're right behind you.").speakerEntity(cat),
                Line.narrate(n == 0 ? "Ask again when the warriors are back in camp."
                        : "Your patrol walks with you and fights what you fight. Talk to any of them to have the patrol wait, follow or go home."));
    }

    static void patrolOrder(ServerPlayer p, String what) {
        if (!Patrols.order(p, what)) return;
        Dialog.subtitle(p, Line.narrate(switch (what) {
            case "stay" -> "Your patrol settles to wait where it stands.";
            case "home" -> "Your patrol turns for camp.";
            default -> "Your patrol falls in beside you.";
        }));
    }

    static boolean hasDeliveries(ServerPlayer p, Task t) {
        for (Task.Objective o : t.objectives) {
            if (o.type == Task.Objective.Type.BRING) {
                if (Bag.count(p, o.key) < o.goal) return false;
            } else if (!o.done()) {
                return false;
            }
        }
        return true;
    }

    private static String remind(Task t, Role mine) {
        for (Task.Objective o : t.objectives) {
            // nobody asks their leader, or a warrior, whether they have forgotten
            if (!o.done()) return (Kin.grown(mine) ? "There is still this to do: " : "Have you forgotten? ") + o.text + ".";
        }
        return "Come back when you're done.";
    }

    /** How a Clanmate greets the player: by who they are, and by who the player has become ({@code mine}). */
    static String greeting(Role role, String name, String you, Role mine) {
        if (!Kin.grown(mine)) {
            return switch (role) {
                case LEADER -> "Greetings, " + you + ". StarClan has been good to us this moon. Is all well with you?";
                case DEPUTY -> "There you are, " + you + ". There's always work to be done for the Clan.";
                case MEDICINE -> "Mind the herbs, " + you + "! Oh, it's you. Are you hurt, or have you come to help?";
                case QUEEN -> "Shh, the kits are finally asleep. Hello, " + you + ".";
                case ELDER -> "Eh? Speak up, young one. In my day apprentices fetched our moss without being asked!";
                case WARRIOR -> "Hello, " + you + ". Keep your eyes open on the borders.";
                case APPRENTICE -> mine == Role.KIT ? "Hello, " + you + "! You'll be in the apprentices' den with us soon."
                        : "Hey, " + you + "! Want to practise hunting crouches later?";
                case KIT -> mine == Role.KIT ? you + "! Come and play moss-ball!" : "Are you a warrior? When I'm an apprentice I'm going to catch a fox!";
            };
        }
        boolean leader = mine == Role.LEADER, deputy = mine == Role.DEPUTY, healer = mine == Role.MEDICINE, elder = mine == Role.ELDER;
        return switch (role) {
            case LEADER -> deputy ? "There you are, " + you + ". A leader is only as strong as their deputy. How do the patrols stand?"
                    : healer ? "Greetings, " + you + ". Has StarClan spoken? Is the Clan well?"
                    : elder ? "Greetings, " + you + ". The Clan has not forgotten what you gave it. Are the elders well looked after?"
                    : "Greetings, " + you + ". You serve the Clan well. Is all quiet on the borders?";
            case DEPUTY -> leader ? you + ". The patrols are out and the borders are quiet. What do you need of me?"
                    : healer ? you + ". If any of my patrols come back limping, I'll send them straight to you."
                    : elder ? you + "! The warriors still talk of your patrols. Rest your paws: we have the borders."
                    : "There you are, " + you + ". I can always use a warrior I trust on the patrols.";
            case MEDICINE -> leader ? "Mind the herbs! Oh, " + you + ". The Clan is well, for now. Are you hurt, or have you come to help?"
                    : healer ? you + "! Come and see: the store is nearly sorted. Two medicine cats are better than one."
                    : "Mind the herbs, " + you + "! Are you hurt, or have you come to help?";
            case QUEEN -> leader ? "Shh, the kits are finally asleep. It's good of you to look in on the nursery, " + you + "."
                    : "Shh, the kits are finally asleep. Hello, " + you + ". They ask after "
                    + (healer ? "the medicine cat" : elder ? "your stories" : "the warriors") + " all day.";
            case ELDER -> leader ? you + "! I remember you as a scrap of an apprentice, tripping over your own paws. And now you lead us."
                    : deputy ? "The deputy, come to see the elders? You've done well, " + you + ". I always said you would."
                    : elder ? "Ah, " + you + ". Come and sit. Did I ever tell you about the badger in the hollow?"
                    : healer ? you + "! My joints tell me it will rain. You wouldn't have something for them?"
                    : "Ah, " + you + ". I remember your warrior ceremony. Come to share tongues with an old cat?";
            case WARRIOR -> leader ? you + ". The Clan is with you. Where do you need me?"
                    : deputy ? you + ". Which patrol do you want me on?"
                    : healer ? "Hello, " + you + ". My pads are holding up, before you ask."
                    : elder ? "Hello, " + you + ". We're keeping the borders the way you taught us."
                    : "Hello, " + you + ". Good hunting today?";
            case APPRENTICE -> leader ? you + "! I've been practising my hunting crouch. Will you watch?"
                    : deputy ? you + "! Can I go on a patrol soon? My mentor says I'm nearly ready."
                    : healer ? "Hello, " + you + ". I've got a thorn in my pad. Only a small one."
                    : "Hello, " + you + "! Will you show me how you stalk? You never make a sound.";
            case KIT -> leader ? "It's " + you + "! Is it true you have nine lives? When I'm leader I'll have ten!"
                    : deputy ? "You're the deputy! Can I be on your patrol? I'm nearly six moons. Nearly."
                    : healer ? "I'm not sick! I don't need any yucky herbs, " + you + "!"
                    : elder ? you + ", tell us a story! The one with the fox!"
                    : "You're a real warrior! Did you ever fight a badger?";
        };
    }

    /** "Do you need help?": this cat's duty for today, or a friendly no. */
    static void offerDuty(ServerPlayer p, WCatEntity cat) {
        LifeData life = LifeData.get(p.serverLevel().getServer().overworld());
        Role role = Kin.role(cat, life);
        String name = Kin.name(cat);
        int colour = Kin.colour(p);
        long day = p.level().getDayTime() / 24000L;
        Task duty = dutyFor(p, role, name, cat, day);
        if (duty == null) {
            Dialog.play(p, Line.say(name, colour, switch (role) {
                case LEADER -> "My deputy sees to the patrols. Ask them, and serve your Clan well.";
                case KIT -> "Help? Play moss-ball with me!";
                default -> "Not today, thank you.";
            }).speakerEntity(cat));
            return;
        }
        var ow = p.serverLevel().getServer().overworld();
        if (Tasks.keptIn(ow, duty.kind)) {
            Dialog.play(p, Line.say(name, colour, Tasks.night(ow)
                    ? (Kin.grown(Kin.role(p)) ? "At this hour, " + Joining.catName(p) + "? It will keep until dawn. Even you must sleep."
                    : "At this hour? The Clan is asleep, " + Joining.catName(p) + ". Go to your nest, and ask me again at dawn.")
                    : "In this rain? The scents are washed out and the prey has gone to ground. Ask me again when it clears.").speakerEntity(cat));
            return;
        }
        if (QuestState.duties(p).size() >= QuestState.MAX_DUTIES) {
            Dialog.play(p, Line.say(name, colour, "You already have your paws full. Finish what you've promised first.").speakerEntity(cat));
            return;
        }
        if (QuestState.completedToday(p, cat.getUUID(), day)) {
            Dialog.play(p, Line.say(name, colour, "You've done enough for me today. Rest your paws.").speakerEntity(cat));
            return;
        }
        Dialog.play(p, Line.say(name, colour, duty.description).speakerEntity(cat).withChoices(
                Choice.of("I'll do it", "greatjourney quest accept " + cat.getId()),
                Choice.of("Not now", "greatjourney quest bye")));
    }

    /** This cat's duty for the player today: a warrior's work, or a medicine cat's for one on that path. */
    private static Task dutyFor(ServerPlayer p, Role role, String name, WCatEntity cat, long day) {
        return Paths.healer(p) ? Tasks.healerDutyFor(role, Kin.bookClan(p), name, cat.getUUID(), day)
                : Tasks.dutyFor(Kin.role(p), role, Kin.bookClan(p), name, cat.getUUID(), day);
    }

    static void accept(ServerPlayer p, WCatEntity cat) {
        LifeData life = LifeData.get(p.serverLevel().getServer().overworld());
        String name = Kin.name(cat);
        long day = p.level().getDayTime() / 24000L;
        Task duty = dutyFor(p, Kin.role(cat, life), name, cat, day);
        if (duty == null || QuestState.duties(p).size() >= QuestState.MAX_DUTIES) return;
        if (Tasks.keptIn(p.serverLevel().getServer().overworld(), duty.kind)) return;      // night fell, or rain, since it was offered
        List<Task> d = QuestState.duties(p);
        for (Task t : d) if (cat.getUUID().equals(t.giverId)) return;
        if (duty.kind == Task.Kind.TEND) duty = Patients.prepare(p, cat, duty);     // the sick are chosen now
        d.add(duty);
        QuestState.setDuties(p, d);
        if (duty.kind == Task.Kind.BORDER_PATROL) Patrols.sendAlong(p, cat, duty);
        Role mine = Kin.role(p);
        Dialog.play(p, Line.say(name, Kin.colour(p), mine == Role.LEADER || mine == Role.DEPUTY ? "Thank you, " + Joining.catName(p) + ". May StarClan light your path."
                : Kin.grown(mine) ? "Good. May StarClan light your path." : "Good. Off you go, and may StarClan light your path.").speakerEntity(cat));
        Ui.push(p);
    }

    /** Hands in whatever the player owes this cat (a duty, or the apprentice path's current step). */
    static void handIn(ServerPlayer p, WCatEntity cat) {
        for (Task t : QuestState.active(p)) {
            if (!Story.answers(p, cat, t)) continue;
            if (!hasDeliveries(p, t)) {
                Dialog.play(p, Line.say(Kin.name(cat), Kin.colour(p), remind(t, Kin.role(p))).speakerEntity(cat));
                return;
            }
            for (Task.Objective o : t.objectives) {
                if (o.type == Task.Objective.Type.BRING) Bag.handOver(p, o);
            }
            // a step taken by another cat (the mentor was gone): that cat teaches the player from now on
            if (t.story && !cat.getUUID().equals(t.giverId)) QuestState.setMentor(p, Kin.name(cat), cat.getUUID());
            Rewards.complete(p, t, cat);
            return;
        }
        Dialog.play(p, Line.say(Kin.name(cat), Kin.colour(p), "I didn't ask you for anything... did I?").speakerEntity(cat));
    }

    static void tellAboutClan(ServerPlayer p, WCatEntity cat) {
        Clan c = Kin.bookClan(p);
        String name = Kin.name(cat);
        int colour = Kin.colour(p);
        LifeData life = LifeData.get(p.serverLevel().getServer().overworld());
        String about = c == null ? "Our Clan is young, but we will grow strong."
                : switch (c) {
                    case THUNDER -> "We are ThunderClan. Our camp is " + (com.ninjacat.greatjourney.clanlands.life.Homeland.forest()
                            ? "a sandy ravine hidden in the oak woods" : "the stone hollow") + ", and our woods are full of squirrels. We never back down from a fight we must win.";
                    case WIND -> "We are WindClan, the fastest Clan. We run down rabbits on the open moor, and the wind tells us everything.";
                    case RIVER -> "We are RiverClan. We swim, we fish, and the streams feed us even when the other Clans go hungry.";
                    case SHADOW -> "We are ShadowClan. We hunt in the dark pines and the marsh, where frogs and lizards hide. Other Clans fear our shadows.";
                };
        List<Line> lines = new ArrayList<>();
        lines.add(Line.say(name, colour, about).speakerEntity(cat));
        if (c != null) {
            lines.add(Line.say(name, colour, life.leader(c) + " leads us, and StarClan watches over us all.").speakerEntity(cat));
        }
        Dialog.play(p, lines);
    }

    /** The Clan cat a choice was about, if it is still close enough to talk to. */
    static WCatEntity catFor(ServerPlayer p, int entityId) {
        Entity e = ((ServerLevel) p.level()).getEntity(entityId);
        if (e instanceof WCatEntity cat && e.distanceTo(p) <= REACH) return cat;
        return null;
    }
}
