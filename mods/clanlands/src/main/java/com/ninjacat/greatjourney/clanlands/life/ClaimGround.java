package com.ninjacat.greatjourney.clanlands.life;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.snowteb.warriorcats_events.clan.ClanData;

/**
 * Warrior Cats Events claims land only from empty air, short or tall grass, or a flower, since its scent marker goes
 * where the cat stands. Here the ground is thick with ferns and other plants, so claims were refused nearly anywhere.
 * A plant a block could simply be placed over is cleared first.
 */
public final class ClaimGround {
    private ClaimGround() {}

    public static void clear(ServerPlayer p) {
        ClanData.Clan wc = Joining.clanOf(p);
        // only for a cat who may claim at all: nobody else gets to tidy plants with the claim button
        if (p.level().dimension() != Level.OVERWORLD || wc == null || !ClanData.get(p.getServer().overworld()).canManage(wc, p.getUUID())) return;
        clearAt(p.serverLevel(), p.blockPosition());
    }

    /** Removes a replaceable, dry plant standing on solid ground at {@code at}; true if one was removed. */
    public static boolean clearAt(ServerLevel level, BlockPos at) {
        BlockState state = level.getBlockState(at);
        if (state.isAir() || !state.canBeReplaced() || !state.getFluidState().isEmpty() || !level.getBlockState(at.below()).isSolid()) return false;
        return level.destroyBlock(at, false);
    }
}
