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
        NeoForge.EVENT_BUS.addListener(Tracking::onFished);
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> {
            if (e.getServer().getTickCount() % 20 == 7 && ClanWorld.current() != null
                    && ClanWorld.isClanWorld(e.getServer().overworld())) {
                Tracking.tick(e.getServer());
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
        q.then(Commands.literal("bye").executes(c -> {
            Dialog.clear(c.getSource().getPlayerOrException());
            return 1;
        }));
        q.then(Commands.literal("warrior").then(Commands.argument("suffix", StringArgumentType.word()).executes(c ->
                Story.becomeWarrior(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "suffix")) ? 1 : 0)));
        q.then(Commands.literal("drop").then(Commands.argument("slot", IntegerArgumentType.integer(1, QuestState.MAX_DUTIES)).executes(c -> {
            ServerPlayer p = c.getSource().getPlayerOrException();
            List<Task> d = QuestState.duties(p);
            int i = IntegerArgumentType.getInteger(c, "slot") - 1;
            if (i >= d.size()) return 0;
            Task gone = d.remove(i);
            QuestState.setDuties(p, d);
            p.sendSystemMessage(Component.literal("You set aside: " + gone.title));
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
