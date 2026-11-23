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
 * Talking to Clan cats: right-click one with an empty paw (not sneaking, so Warrior Cats Events' own shift-click
 * commands still work). Your own Clanmates greet you by who they are and can give you duties or take them back;
 * cats of other Clans send you on your way.
 */
final class Talk {
    private Talk() {}

    // how far off the cat may be when the player answers it: cats keep walking while the player reads, and at 8 a
    // warrior had strolled out of reach within three seconds (playtest 2026-10-12), so "I'll do it" did nothing
    private static final double REACH = 24;

    static void onInteract(PlayerInteractEvent.EntityInteract e) {
        if (e.getHand() != InteractionHand.MAIN_HAND || !(e.getEntity() instanceof ServerPlayer p)) return;
        if (!(e.getTarget() instanceof WCatEntity cat) || cat.getClanUUID() == null || cat.getCustomName() == null) return;
        if (!talksWith(p.getMainHandItem()) || p.isShiftKeyDown()) return;
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

        // reporting back on something this cat asked for
        for (Task t : QuestState.active(p)) {
            if (Story.answers(p, cat, t)) {
                lines.add(Line.say(name, colour, t.readyToHandIn() || hasDeliveries(p, t)
                        ? "Back already, " + you + "? Let's see what you have."
                        : remind(t)).speakerEntity(cat).withChoices(
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
        choices.add(Choice.of("Goodbye", "greatjourney quest bye"));
        lines.add(Line.say(name, colour, greeting(role, name, you)).speakerEntity(cat).withChoices(choices.toArray(Choice[]::new)));
        Dialog.play(p, lines);
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

    private static String remind(Task t) {
        for (Task.Objective o : t.objectives) {
            if (!o.done()) return "Have you forgotten? " + o.text + ".";
        }
        return "Come back when you're done.";
    }

    static String greeting(Role role, String name, String you) {
        return switch (role) {
            case LEADER -> "Greetings, " + you + ". StarClan has been good to us this moon. Is all well with you?";
            case DEPUTY -> "There you are, " + you + ". There's always work to be done for the Clan.";
            case MEDICINE -> "Mind the herbs, " + you + "! Oh, it's you. Are you hurt, or have you come to help?";
            case QUEEN -> "Shh, the kits are finally asleep. Hello, " + you + ".";
            case ELDER -> "Eh? Speak up, young one. In my day apprentices fetched our moss without being asked!";
            case WARRIOR -> "Hello, " + you + ". Keep your eyes open on the borders.";
            case APPRENTICE -> "Hey, " + you + "! Want to practise hunting crouches later?";
            case KIT -> "Are you a warrior? When I'm an apprentice I'm going to catch a fox!";
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
                case KIT -> "Help? Play moss-ball with me! ...No? Ask the deputy then.";
                default -> "Not today, thank you. Ask the deputy if you want something to do.";
            }).speakerEntity(cat));
            return;
        }
        var ow = p.serverLevel().getServer().overworld();
        if (Tasks.keptIn(ow, duty.kind)) {
            Dialog.play(p, Line.say(name, colour, Tasks.night(ow)
                    ? "At this hour? The Clan is asleep, " + Joining.catName(p) + ". Go to your nest, and ask me again at dawn."
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
                : Tasks.dutyFor(role, Kin.bookClan(p), name, cat.getUUID(), day);
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
        Dialog.play(p, Line.say(name, Kin.colour(p), "Good. Off you go, and may StarClan light your path.").speakerEntity(cat));
        Ui.push(p);
    }

    /** Hands in whatever the player owes this cat (a duty, or the apprentice path's current step). */
    static void handIn(ServerPlayer p, WCatEntity cat) {
        for (Task t : QuestState.active(p)) {
            if (!Story.answers(p, cat, t)) continue;
            if (!hasDeliveries(p, t)) {
                Dialog.play(p, Line.say(Kin.name(cat), Kin.colour(p), remind(t)).speakerEntity(cat));
                return;
            }
            for (Task.Objective o : t.objectives) {
                if (o.type == Task.Objective.Type.BRING) Bag.take(p, o.key, o.goal);
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
