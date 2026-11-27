package com.ninjacat.greatjourney.clanlands.life.quests;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.ninjacat.greatjourney.clanlands.life.Dialog;
import com.ninjacat.greatjourney.clanlands.life.rpg.Journals;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import java.util.List;
import java.util.function.BiConsumer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.ItemFishedEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;

/**
 * Quests and duties: talk to your Clanmates (right-click with an empty paw) to help them, as apprentices and
 * warriors do in the books, and walk the apprentice path to your warrior name. Everything is per player.
 */
public final class Quests {
    private Quests() {}

    public static void init(IEventBus modBus) {
        Journals.provider(QuestLog::view);
        NeoForge.EVENT_BUS.addListener(Talk::onInteract);
        NeoForge.EVENT_BUS.addListener(Tracking::onDeath);
        NeoForge.EVENT_BUS.addListener(Ranks::onDeath);
        NeoForge.EVENT_BUS.addListener(Tracking::onFished);
        Patrols.init();
        NeoForge.EVENT_BUS.addListener(DarkForest::onWake);
        NeoForge.EVENT_BUS.addListener(Friendship::onInteract);
        NeoForge.EVENT_BUS.addListener(Friendship::onJoin);
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStoppedEvent e) -> Friendship.reset());
        NeoForge.EVENT_BUS.addListener(Homestead::onInteract);
        NeoForge.EVENT_BUS.addListener(Homestead::onJoin);
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStoppedEvent e) -> Homestead.reset());
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> {
            if (e.getServer().getTickCount() % 20 == 7 && ClanWorld.current() != null
                    && ClanWorld.isClanWorld(e.getServer().overworld())) {
                Tracking.tick(e.getServer());
                MoonDreams.tick(e.getServer());
            }
        });
    }

    /** /greatjourney quest ...: what the dialogue choices run (as the player), plus admin tools for testing. */
    public static void commands(LiteralArgumentBuilder<CommandSourceStack> root) {
        LiteralArgumentBuilder<CommandSourceStack> q = Commands.literal("quest");
        q.then(withCat("help", Talk::offerDuty));
        q.then(withCat("clan", Talk::tellAboutClan));
        q.then(withCat("accept", Talk::accept));
        q.then(withCat("give", Talk::handIn));
        q.then(Commands.literal("quiet")
                .then(Commands.literal("on").executes(c -> Quiet.set(c.getSource().getPlayerOrException(), true) ? 1 : 0))
                .then(Commands.literal("off").executes(c -> Quiet.set(c.getSource().getPlayerOrException(), false) ? 1 : 0)));
        q.then(Commands.literal("bye").executes(c -> {
            Dialog.clear(c.getSource().getPlayerOrException());
            return 1;
        }));
        q.then(Commands.literal("warrior").then(Commands.argument("suffix", StringArgumentType.word()).executes(c ->
                Story.becomeWarrior(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "suffix")) ? 1 : 0)));
        q.then(Commands.literal("names").executes(c -> Story.otherNames(c.getSource().getPlayerOrException()) ? 1 : 0));
        // a place in the Clan: the apprentice's path, and the grown cat's role (Paths)
        q.then(withCat("place", Paths::offer));
        // a grown cat's own apprentice (Pupils), and a leader's choice of deputy (Ranks)
        q.then(withCat("pupilcome", Pupils::come));
        q.then(Commands.literal("pupilstay").executes(c -> {
            Pupils.stay(c.getSource().getPlayerOrException());
            return 1;
        }));
        q.then(withCat("deputy", Ranks::nameDeputy));
        // a deputy's or leader's orders: patrols sent out, and a patrol of their own (Talk, Patrols)
        q.then(withCat("orders", Talk::orders));
        q.then(withCat("patrolmap", Talk::patrolMap));
        q.then(withCat("patrol", Talk::gather));
        q.then(Commands.literal("sendpatrol")
                .then(withCat("border", (p, cat) -> Talk.sendPatrol(p, cat, false)))
                .then(withCat("hunt", (p, cat) -> Talk.sendPatrol(p, cat, true))));
        // a moment of the warrior code (CodeMoments)
        q.then(Commands.literal("code").then(Commands.argument("which", StringArgumentType.word()).executes(c ->
                CodeMoments.answer(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "which")) ? 1 : 0)));
        // a prophecy puzzled out with the medicine cat (Riddles)
        q.then(withCat("prophecy", (p, cat) -> Riddles.ask(p, Kin.name(cat))));
        q.then(Commands.literal("riddle").then(Commands.argument("guess", StringArgumentType.word()).executes(c ->
                Riddles.guess(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "guess")) ? 1 : 0)));
        // the Dark Forest's offer and StarClan's question (DarkForest), and a friend across the border (Friendship)
        q.then(Commands.literal("dark").then(Commands.argument("answer", StringArgumentType.word()).executes(c ->
                DarkForest.answer(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "answer")) ? 1 : 0)));
        q.then(Commands.literal("friend").then(Commands.argument("answer", StringArgumentType.word()).executes(c ->
                Friendship.answer(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "answer")) ? 1 : 0)));
        // quiet things done together in camp (CampMoments), and a wanderer at a founded Clan's edge (Homestead)
        q.then(withCat("sit", CampMoments::sit));
        q.then(withCat("tongues", CampMoments::tongues));
        q.then(withCat("tale", (p, cat) -> Ui.play(p, CampMoments.tale(p, Kin.name(cat)))));
        q.then(withCat("vigil", CampMoments::sitVigil));
        var wanderer = Commands.literal("wanderer");
        for (String what : new String[] {"welcome", "turn"}) {
            wanderer.then(Commands.literal(what).then(Commands.argument("cat", IntegerArgumentType.integer()).executes(c ->
                    Homestead.answer(c.getSource().getPlayerOrException(), what, IntegerArgumentType.getInteger(c, "cat")) ? 1 : 0)));
        }
        q.then(wanderer);
        // "Show on map": the quest the Clan map's gold star follows (QuestMarks)
        q.then(Commands.literal("follow").then(Commands.argument("quest", StringArgumentType.greedyString()).executes(c ->
                QuestMarks.follow(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "quest")) ? 1 : 0)));
        q.then(Commands.literal("teller").then(Commands.argument("name", StringArgumentType.word()).executes(c ->
                MoonDreams.choose(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "name")) ? 1 : 0)));
        // operators: who watches over this world, from anywhere
        q.then(Commands.literal("storyteller").requires(s -> s.hasPermission(2))
                .then(Commands.argument("name", StringArgumentType.word()).executes(c -> {
                    var t = com.ninjacat.greatjourney.clanlands.life.story.Teller.byKey(StringArgumentType.getString(c, "name"));
                    if (t == null) return 0;
                    MoonDreams.watchedBy(c.getSource().getServer().overworld(), t, "StarClan");
                    c.getSource().sendSuccess(() -> Component.literal(t.title + " watches over this world"), true);
                    return 1;
                })));
        q.then(Commands.literal("stranger").then(Commands.argument("topic", StringArgumentType.word())
                .then(Commands.argument("cat", IntegerArgumentType.integer()).executes(c -> Strangers.answer(c.getSource().getPlayerOrException(),
                        IntegerArgumentType.getInteger(c, "cat"), StringArgumentType.getString(c, "topic")) ? 1 : 0))));
        q.then(Commands.literal("patrolorder").then(Commands.argument("order", StringArgumentType.word()).executes(c -> {
            Talk.patrolOrder(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "order"));
            return 1;
        })));
        q.then(Commands.literal("path").then(Commands.argument("path", StringArgumentType.word()).executes(c -> {
            String path = StringArgumentType.getString(c, "path");
            if (!path.equals("warrior") && !path.equals("medicine")) return 0;
            return Paths.choosePath(c.getSource().getPlayerOrException(),
                    path.equals("medicine") ? QuestState.Path.MEDICINE : QuestState.Path.WARRIOR) ? 1 : 0;
        })));
        q.then(Commands.literal("role").then(Commands.argument("role", StringArgumentType.word()).executes(c ->
                Paths.takeRole(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "role")) ? 1 : 0)));
        q.then(Commands.literal("drop").then(Commands.argument("slot", IntegerArgumentType.integer(1, QuestState.MAX_DUTIES)).executes(c -> {
            ServerPlayer p = c.getSource().getPlayerOrException();
            List<Task> d = QuestState.duties(p);
            int i = IntegerArgumentType.getInteger(c, "slot") - 1;
            if (i >= d.size()) return 0;
            Task gone = d.remove(i);
            QuestState.setDuties(p, d);
            Ui.subtitle(p, List.of(com.ninjacat.greatjourney.clanlands.life.Dialog.Line.narrate("You set aside: " + gone.title)));
            Ui.push(p);
            return 1;
        })));
        // admin tools (operators): reset a player's quests, or jump the apprentice path to a step
        q.then(Commands.literal("reset").requires(s -> s.hasPermission(2))
                .then(Commands.argument("player", EntityArgument.player()).executes(c -> {
                    ServerPlayer p = EntityArgument.getPlayer(c, "player");
                    QuestState.reset(p);
                    Ui.push(p);
                    c.getSource().sendSuccess(() -> Component.literal("Quests reset for " + p.getGameProfile().getName()), true);
                    return 1;
                })));
        q.then(Commands.literal("step").requires(s -> s.hasPermission(2))
                .then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.argument("step", IntegerArgumentType.integer(0, QuestState.WARRIOR)).executes(c -> {
                            ServerPlayer p = EntityArgument.getPlayer(c, "player");
                            QuestState.setStoryStep(p, IntegerArgumentType.getInteger(c, "step"));
                            QuestState.setStory(p, null);
                            Ui.push(p);
                            c.getSource().sendSuccess(() -> Component.literal("Apprentice path set to step " + IntegerArgumentType.getInteger(c, "step")), true);
                            return 1;
                        }))));
        root.then(q);
        Founding.commands(root);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> withCat(String name, BiConsumer<ServerPlayer, WCatEntity> action) {
        return Commands.literal(name).then(Commands.argument("cat", IntegerArgumentType.integer()).executes(c -> {
            ServerPlayer p = c.getSource().getPlayerOrException();
            WCatEntity cat = Talk.catFor(p, IntegerArgumentType.getInteger(c, "cat"));
            if (cat == null) {
                p.sendSystemMessage(Component.literal("That cat has wandered off."));
                return 0;
            }
            action.accept(p, cat);
            return 1;
        }));
    }
}
