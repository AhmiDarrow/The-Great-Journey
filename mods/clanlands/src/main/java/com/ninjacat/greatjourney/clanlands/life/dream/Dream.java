package com.ninjacat.greatjourney.clanlands.life.dream;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.ninjacat.greatjourney.clanlands.life.Dialog;
import com.ninjacat.greatjourney.clanlands.life.Journey;
import com.ninjacat.greatjourney.clanlands.life.Starts;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * The StarClan dream: a new cat's first night, in StarClan's hunting grounds, where Bluestar, Lionheart, Spottedleaf
 * and Yellowfang teach the basics. Steps (Journey.tutorialStep):
 * <ol>
 *   <li>Arrival: Bluestar welcomes the player. [Continue]</li>
 *   <li>Hunting: Lionheart releases a mouse; killing it moves on.</li>
 *   <li>Herbs: Spottedleaf explains the herb the player picks (breaking a patch herb, or one turning up in the
 *       inventory); then [Continue].</li>
 *   <li>Dangers and the warrior code: Yellowfang. [Continue]</li>
 *   <li>Waking: Bluestar sends them off. [Wake up] returns them to the overworld and the start choice.</li>
 * </ol>
 * [Skip the dream] is offered at every step. Nothing in the dream can hurt the dreamer, and nothing they do changes
 * the clearing. A player who logs out mid-dream (or is somehow killed) comes back to the clearing at the same step;
 * one who closed the window without answering is asked again after a little while, or at once by right-clicking a
 * StarClan cat. Several players can dream at once: each has their own mouse and their own place in the lessons.
 */
public final class Dream {
    private Dream() {}

    static final int ARRIVAL = 1, HUNT = 2, HERBS = 3, CODE = 4, WAKING = 5, DONE = 6;

    /** Herb items as they turn up in an inventory, mapped to the herb block Spottedleaf talks about. */
    private static final Map<String, String> HERB_ITEMS = Map.ofEntries(
            Map.entry("catmint_leaves", "catmint"), Map.entry("catmint", "catmint"),
            Map.entry("yarrow_leaves", "yarrow"), Map.entry("comfrey_leaves", "comfrey"), Map.entry("comfrey_root", "comfrey"),
            Map.entry("feverfew_leaves", "feverfew"), Map.entry("juniper_berries", "juniper"), Map.entry("dock_leaves", "dock"),
            Map.entry("sorrel_leaves", "sorrel"), Map.entry("burnet_leaves", "burnet"), Map.entry("chamomile", "chamomile_stems"),
            Map.entry("daisy_leaves", "daisy"));

    /** Herb blocks picked from the patch, to put back a moment later so the patch never runs out. */
    private static final Deque<Object[]> REGROW = new ArrayDeque<>();

    public static void init(IEventBus modBus) {
        NeoForge.EVENT_BUS.addListener(Dream::onDeath);
        NeoForge.EVENT_BUS.addListener(Dream::onBreak);
        NeoForge.EVENT_BUS.addListener(Dream::onLogin);
        NeoForge.EVENT_BUS.addListener(Dream::onRespawn);
        NeoForge.EVENT_BUS.addListener(Dream::onPlayerTick);
        NeoForge.EVENT_BUS.addListener(Dream::onServerTick);
        NeoForge.EVENT_BUS.addListener(Dream::onHurt);
        NeoForge.EVENT_BUS.addListener(Dream::onPlace);
        NeoForge.EVENT_BUS.addListener(Dream::onInteract);
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent e) -> reset());
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> {
            HERB_DONE.remove(e.getEntity().getUUID());
            IDLE.remove(e.getEntity().getUUID());
        });
    }

    /** Forgets everything held between ticks (the regrow queue holds levels), so nothing leaks into the next world. */
    public static void reset() {
        REGROW.clear();
        HERB_DONE.clear();
        IDLE.clear();
    }

    /** Adds the "tutorial ..." subcommands under /greatjourney. */
    public static void commands(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("tutorial")
                .then(Commands.literal("next").executes(c -> run(c, Dream::next)))
                .then(Commands.literal("skip").executes(c -> run(c, Dream::finish)))
                .then(Commands.literal("mouse").executes(c -> run(c, Dream::newMouse))));
    }

    private static int run(CommandContext<CommandSourceStack> c, java.util.function.Consumer<ServerPlayer> action) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        if (Journey.stage(p) != Journey.Stage.DREAM) return 0;
        action.accept(p);
        return 1;
    }

    /** Sends a player who has just made their cat into the dream. */
    public static void begin(ServerPlayer player) {
        ServerLevel dream = player.server.getLevel(DreamClearing.DREAM);
        if (dream == null) {                                             // no dream dimension: go straight to the choice
            Journey.setStage(player, Journey.Stage.CHOOSING);
            Starts.offerChoices(player);
            return;
        }
        Journey.setStage(player, Journey.Stage.DREAM);
        Journey.setTutorialStep(player, ARRIVAL);
        enter(player, dream);
    }

    /** Puts the player in the clearing and plays their current step from the start. */
    private static void enter(ServerPlayer player, ServerLevel dream) {
        DreamClearing.ensure(dream);
        BlockPos a = DreamClearing.ARRIVE;
        if (player.level() != dream || player.getY() < 1) {
            player.teleportTo(dream, a.getX() + 0.5, a.getY(), a.getZ() + 0.5, 180, 0);
        }
        Dialog.clear(player);
        int step = Journey.tutorialStep(player);
        if (step == HUNT) {
            removeMouse(player, dream);
            DreamClearing.spawnMouse(dream, player.getUUID());
        }
        if (step == HERBS) {
            HERB_DONE.remove(player.getUUID());
        }
        Dialog.play(player, DreamScript.step(step));
    }

    private static void go(ServerPlayer player, int step) {
        Journey.setTutorialStep(player, step);
        ServerLevel dream = player.server.getLevel(DreamClearing.DREAM);
        if (dream == null) {
            finish(player);
            return;
        }
        if (step == HUNT) {
            removeMouse(player, dream);
            DreamClearing.spawnMouse(dream, player.getUUID());
        }
        if (step == HERBS) {
            HERB_DONE.remove(player.getUUID());
        }
        Dialog.play(player, DreamScript.step(step));
    }

    /** [Continue]: only moves on from steps that end with it (the hunt and the herbs move on by doing them). */
    private static void next(ServerPlayer player) {
        int step = Journey.tutorialStep(player);
        switch (step) {
            case ARRIVAL -> go(player, HUNT);
            case HERBS -> {
                if (picked(player)) go(player, CODE);
            }
            case CODE -> go(player, WAKING);
            case WAKING -> finish(player);
            default -> { }
        }
    }

    /** Whether the herb lesson was given this session (after a re-login the player simply picks a herb again). */
    private static boolean picked(ServerPlayer player) {
        return HERB_DONE.contains(player.getUUID());
    }

    private static final java.util.Set<java.util.UUID> HERB_DONE = new java.util.HashSet<>();

    private static void newMouse(ServerPlayer player) {
        if (Journey.tutorialStep(player) != HUNT) return;
        ServerLevel dream = player.server.getLevel(DreamClearing.DREAM);
        if (dream == null) return;
        removeMouse(player, dream);
        DreamClearing.spawnMouse(dream, player.getUUID());
        Dialog.play(player, DreamScript.mouseAgain());
    }

    private static void removeMouse(ServerPlayer player, ServerLevel dream) {
        String mine = DreamClearing.MOUSE_TAG + ":" + player.getUUID();
        for (Entity e : dream.getEntities((Entity) null, new AABB(-60, 0, -60, 60, 60, 60), e -> e.getTags().contains(mine))) {
            e.discard();
        }
    }

    /** Ends the dream: back to the overworld spawn, then the start choice. */
    public static void finish(ServerPlayer player) {
        ServerLevel dream = player.server.getLevel(DreamClearing.DREAM);
        if (dream != null) removeMouse(player, dream);
        Dialog.clear(player);
        Journey.setTutorialStep(player, DONE);
        Journey.setStage(player, Journey.Stage.CHOOSING);
        IDLE.remove(player.getUUID());
        HERB_DONE.remove(player.getUUID());
        ServerLevel overworld = player.server.overworld();
        BlockPos sp = overworld.getSharedSpawnPos();
        overworld.getChunk(sp.getX() >> 4, sp.getZ() >> 4);              // an unloaded chunk reads as the world's floor
        int y = overworld.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, sp.getX(), sp.getZ());
        player.fallDistance = 0;
        player.teleportTo(overworld, sp.getX() + 0.5, y, sp.getZ() + 0.5, player.getYRot(), 0);
        Starts.offerChoices(player);
    }

    /**
     * Twice a second for a dreamer: each StarClan teacher near them turns their head to them (they have no AI, so
     * they would otherwise sit staring one way like statues), and a few faint stars drift off their fur, the way the
     * books draw StarClan cats, seen by this dreamer only.
     */
    private static void teachersLook(ServerPlayer p) {
        ServerLevel level = p.serverLevel();
        for (Entity e : level.getEntities((Entity) null, p.getBoundingBox().inflate(24), e -> e.getTags().contains(DreamClearing.TAG)
                && e instanceof net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity)) {
            var cat = (net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity) e;
            double dx = p.getX() - cat.getX(), dz = p.getZ() - cat.getZ();
            float want = (float) Math.toDegrees(Math.atan2(-dx, dz));
            // the head turns toward the dreamer; the body follows only when the head would have to turn too far
            float body = cat.yBodyRot;
            float off = net.minecraft.util.Mth.wrapDegrees(want - body);
            if (Math.abs(off) > 60) {
                body = want - Math.signum(off) * 40;
                cat.setYBodyRot(body);
                cat.setYRot(body);
            }
            cat.setYHeadRot(want);
            if (p.getRandom().nextInt(3) == 0) {
                level.sendParticles(p, net.minecraft.core.particles.ParticleTypes.END_ROD, false,
                        cat.getX(), cat.getY() + cat.getBbHeight() * 0.6, cat.getZ(), 1, 0.3, 0.25, 0.3, 0.004);
            }
        }
    }

    private static boolean inDream(ServerPlayer p) {
        return p.level().dimension() == DreamClearing.DREAM;
    }

    private static void onDeath(LivingDeathEvent event) {
        Entity mouse = event.getEntity();
        if (!mouse.getTags().contains(DreamClearing.MOUSE_TAG)) return;
        if (!(event.getSource().getEntity() instanceof ServerPlayer p)) return;
        if (Journey.stage(p) == Journey.Stage.DREAM && Journey.tutorialStep(p) == HUNT
                && mouse.getTags().contains(DreamClearing.MOUSE_TAG + ":" + p.getUUID())) {
            Dialog.clear(p);
            Dialog.play(p, DreamScript.caught());
            go(p, HERBS);
            return;
        }
        // another dreamer's mouse: StarClan sends its owner a new one
        for (ServerPlayer owner : p.server.getPlayerList().getPlayers()) {
            if (owner != p && mouse.getTags().contains(DreamClearing.MOUSE_TAG + ":" + owner.getUUID())
                    && Journey.tutorialStep(owner) == HUNT && owner.level() == mouse.level()) {
                DreamClearing.spawnMouse((ServerLevel) mouse.level(), owner.getUUID());
            }
        }
    }

    /** Nothing hurts a dreamer: StarClan's hunting grounds are safe. */
    private static void onHurt(LivingIncomingDamageEvent event) {
        if (event.getEntity() instanceof ServerPlayer p && inDream(p)
                && !event.getSource().is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            event.setCanceled(true);
        }
    }

    /** The clearing stays as StarClan made it. */
    private static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getEntity() instanceof ServerPlayer p && inDream(p) && !p.isCreative()) event.setCanceled(true);
    }

    /** Right-clicking a StarClan cat brings back the lesson (and its choices) the dreamer is on. */
    private static void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || event.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) return;
        if (!event.getTarget().getTags().contains(DreamClearing.TAG)) return;
        event.setCanceled(true);
        event.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
        if (Journey.stage(p) != Journey.Stage.DREAM || Dialog.busy(p)) return;
        IDLE.remove(p.getUUID());
        Dialog.play(p, reminder(p));
    }

    /** What to say again to a dreamer who closed the window: the current lesson's question, with its choices. */
    private static List<Dialog.Line> reminder(ServerPlayer p) {
        int step = Journey.tutorialStep(p);
        if (step == HERBS && picked(p)) return List.of(DreamScript.herbDone());
        List<Dialog.Line> lines = DreamScript.step(step);
        return lines.isEmpty() ? lines : List.of(lines.get(lines.size() - 1));
    }

    /** Ticks (counted in tens) each dreamer has spent with no window open and no answer given. */
    private static final Map<java.util.UUID, Integer> IDLE = new java.util.HashMap<>();
    private static final int NUDGE_AFTER = 40;                         // 40 checks of 10 ticks: 20 seconds

    /**
     * Whether the dreamer's lesson can only go on by answering (so a closed window would leave them stuck). The hunt
     * and the herb picking go on by doing, so a dreamer busy stalking a mouse is never interrupted.
     */
    private static boolean waitsOnChoice(ServerPlayer p) {
        int step = Journey.tutorialStep(p);
        return step == ARRIVAL || step == CODE || step == WAKING || (step == HERBS && picked(p));
    }

    private static void onBreak(BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer p) || !inDream(p)) return;
        BlockState s = event.getState();
        var id = BuiltInRegistries.BLOCK.getKey(s.getBlock());
        boolean herb = id.getNamespace().equals("warriorcats_events") && DreamClearing.HERB_BLOCKS.contains(id.getPath());
        if (!herb) {
            if (!p.isCreative()) event.setCanceled(true);                // the clearing stays as StarClan made it
            return;
        }
        REGROW.add(new Object[] {p.serverLevel(), event.getPos().immutable(), id.getPath(), p.server.getTickCount() + 100});
        if (Journey.stage(p) == Journey.Stage.DREAM && Journey.tutorialStep(p) == HERBS) herbPicked(p, id.getPath());
    }

    private static void herbPicked(ServerPlayer p, String herb) {
        if (!HERB_DONE.add(p.getUUID())) return;
        Dialog.clear(p);
        Dialog.play(p, DreamScript.herb(herb));
    }

    private static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer p && Journey.stage(p) == Journey.Stage.DREAM) {
            ServerLevel dream = p.server.getLevel(DreamClearing.DREAM);
            if (dream == null) finish(p); else enter(p, dream);
        }
    }

    private static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer p && Journey.stage(p) == Journey.Stage.DREAM) {
            ServerLevel dream = p.server.getLevel(DreamClearing.DREAM);
            if (dream == null) finish(p); else enter(p, dream);
        }
    }

    private static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || p.tickCount % 10 != 0 || !inDream(p)) return;
        if (p.getY() < 1) {
            BlockPos a = DreamClearing.ARRIVE;
            p.teleportTo(p.serverLevel(), a.getX() + 0.5, a.getY(), a.getZ() + 0.5, 180, 0);
        }
        if (p.tickCount % 100 == 0) DreamClearing.ensure(p.serverLevel());   // teachers whose chunk had not loaded yet
        teachersLook(p);
        if (Journey.stage(p) == Journey.Stage.DREAM && waitsOnChoice(p)) {
            if (Dialog.busy(p)) {
                IDLE.remove(p.getUUID());
            } else if (IDLE.merge(p.getUUID(), 1, Integer::sum) >= NUDGE_AFTER) {
                IDLE.remove(p.getUUID());
                Dialog.play(p, reminder(p));
            }
        }
        if (Journey.stage(p) == Journey.Stage.DREAM && Journey.tutorialStep(p) == HERBS && !HERB_DONE.contains(p.getUUID())) {
            for (ItemStack s : p.getInventory().items) {
                var id = BuiltInRegistries.ITEM.getKey(s.getItem());
                if (!s.isEmpty() && id.getNamespace().equals("warriorcats_events") && HERB_ITEMS.containsKey(id.getPath())) {
                    herbPicked(p, HERB_ITEMS.get(id.getPath()));
                    break;
                }
            }
        }
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        while (!REGROW.isEmpty() && (int) REGROW.peek()[3] <= server.getTickCount()) {
            Object[] r = REGROW.poll();
            ServerLevel level = (ServerLevel) r[0];
            BlockPos pos = (BlockPos) r[1];
            if (level.getBlockState(pos).isAir()) level.setBlock(pos, DreamClearing.herb((String) r[2]), 3);
        }
    }
}
