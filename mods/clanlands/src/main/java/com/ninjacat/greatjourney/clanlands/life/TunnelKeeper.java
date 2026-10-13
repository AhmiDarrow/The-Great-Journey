package com.ninjacat.greatjourney.clanlands.life;

import com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan;
import com.ninjacat.greatjourney.clanlands.layout.Tunnels;
import com.ninjacat.greatjourney.clanlands.life.Dialog.Line;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.decoration.GlowItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;

/**
 * The keeper of the tunnels: a sharp-tongued ThunderClan medicine cat (in the spirit of the books' blind medicine
 * cat, with his own name in every world) who keeps to the cavern under the ThunderClan/WindClan border, beside the
 * Ancient Stick. Its scratches remember the cats who walked these tunnels long before the Clans.
 */
public final class TunnelKeeper {
    private TunnelKeeper() {}

    public static final String TAG = "greatjourney_tunnel_keeper";

    /** Settles the keeper and the stick in the cavern the first time its chunk loads. */
    public static void populateIfDue(ServerLevel level, ChunkPos loaded) {
        ClanWorld w = ClanWorld.current();
        if (w == null || w.lake.tunnels() == null) return;
        LifeData life = LifeData.get(level);
        if (!life.created || life.tunnelsPopulated) return;
        // in the Campaign the lake is wild until the Clans arrive in Act 4; the keeper comes with ThunderClan
        if (Mode.of(level) == Mode.CAMPAIGN && com.ninjacat.greatjourney.clanlands.life.campaign.CampaignData.get(level).act() < 4) return;
        Tunnels t = w.lake.tunnels();
        if (!new ChunkPos(BlockPos.containing(t.cx, 0, t.cz)).equals(loaded)) return;
        life.tunnelsPopulated = true;
        life.setDirty();
        level.getServer().execute(() -> ChunkWait.whenLoaded(level, t.cx, t.cz, 4, () -> settle(level, w, life, t)));
    }

    private static void settle(ServerLevel level, ClanWorld w, LifeData life, Tunnels t) {
        RandomSource r = RandomSource.create(w.seed ^ 0x7E11E15L);
        int floor = (int) Math.floor(t.cy - t.cavernRy + 2);
        BlockPos home = BlockPos.containing(t.cx + 2, floor, t.cz);
        BlockPos stone = BlockPos.containing(t.cx - 2, floor, t.cz + 1);
        level.setBlock(stone, Blocks.MOSSY_COBBLESTONE.defaultBlockState(), 3);
        // the Ancient Stick lies on the mossy stone, there for whoever the keeper trusts with it
        var stickItem = BuiltInRegistries.ITEM.get(ResourceLocation.parse("warriorcats_events:ancient_stick"));
        GlowItemFrame frame = new GlowItemFrame(level, stone.above(), Direction.UP);
        frame.setItem(new ItemStack(stickItem), false);
        frame.setInvisible(true);
        level.addFreshEntity(frame);

        ClanNames names = new ClanNames(w.seed ^ 0x51CC5L);
        ClanNames.Name name = names.warrior();
        UUID clanId = life.clanId(Clan.THUNDER);
        WCatEntity cat = BookClans.spawnCat(level, Clan.THUNDER, clanId, BookClans.Role.MEDICINE, name, 0,
                home, BlockPos.containing(t.cx, floor, t.cz), r);
        if (cat != null) {
            cat.setPersonality(WCatEntity.Personality.GRUMPY);
            cat.addTag(TAG);
        }
        life.tunnelKeeper = name.full();
        life.setDirty();
    }

    /** Once a second: a player who reaches the cavern for the first time hears the keeper. */
    public static void tick(MinecraftServer server) {
        ClanWorld w = ClanWorld.current();
        if (w == null || w.lake.tunnels() == null) return;
        Tunnels t = w.lake.tunnels();
        LifeData life = LifeData.get(server.overworld());
        if (life.tunnelKeeper == null) return;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.level().dimension() != Level.OVERWORLD || Dialog.busy(p)) continue;
            double dxz = Math.hypot(p.getX() - t.cx, p.getZ() - t.cz);
            if (dxz > t.cavernRx + 3 || Math.abs(p.getY() - t.cy) > t.cavernRy + 3) continue;
            if (!Journey.once(p, "tunnels.met")) continue;
            String k = life.tunnelKeeper;
            int c = BookClans.Info.THUNDER.colour;
            if (life.starClan().stream().anyMatch(f -> f.name().equalsIgnoreCase(k))) {
                // the keeper has gone to StarClan: the cavern is quiet, and the stick waits for whoever comes
                Dialog.play(p,
                        Line.narrate("The cavern is silent. A faint, old scent clings to a mossy stone: " + k + " used to sit here."),
                        Line.narrate("On the stone lies a stick covered in scratches, one for every cat lost in these tunnels long ago."),
                        Line.narrate("Somebody should remember them now. The Ancient Stick feels warm under your paw."));
                continue;
            }
            Dialog.play(p,
                    Line.narrate("A grey cat sits by a mossy stone, his clouded blue eyes turned toward you before you make a sound."),
                    Line.say(k, c, "You breathe like a whole patrol. Did you think I wouldn't hear you coming?"),
                    Line.say(k, c, "These tunnels are older than any Clan. Cats lived here once, long, long ago. Some never found their way out."),
                    Line.say(k, c, "See the stick? Every scratch on it is one of them. I come here to remember them, so somebody does."),
                    Line.say(k, c, "Hmph. You may as well take it. It calls cats to you, if they trust you. Don't lose it, and don't get lost."),
                    Line.narrate("The Ancient Stick rests on the mossy stone. Its scratches feel warm under your paw."));
        }
    }
}
