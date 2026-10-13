package com.ninjacat.greatjourney.clanlands.life;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Waits for chunks without stalling the server. Asking for a far chunk directly (getChunk) generates it on the
 * server thread, in the middle of a tick: a teleport to a camp kilometres away, or filling a camp whose edge is not
 * loaded yet, froze the game for a while. Here the chunks are asked for with a ticket, generate off the server thread,
 * and the job runs on a later tick once every one is loaded (or, if that takes too long, runs anyway).
 */
public final class ChunkWait {
    private ChunkWait() {}

    private static final TicketType<ChunkPos> TICKET = TicketType.create("greatjourney_wait", Comparator.comparingLong(ChunkPos::toLong), 20 * 60);
    /** A job still waiting after this long runs anyway (whatever is missing then loads the slow way). */
    private static final int GIVE_UP_TICKS = 20 * 30;

    private record Job(ServerLevel level, List<ChunkPos> chunks, Runnable then, int deadline) {}

    private static final List<Job> JOBS = new ArrayList<>();

    public static void init() {
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> tick(e.getServer()));
        // a world closing with jobs still waiting lets their chunks go, so the save can finish
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStoppingEvent e) -> {
            JOBS.forEach(ChunkWait::release);
            JOBS.clear();
        });
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent e) -> JOBS.clear());
    }

    /** Runs {@code then} once every chunk within {@code radius} blocks of x, z is loaded: now, if they already are. */
    public static void whenLoaded(ServerLevel level, double x, double z, int radius, Runnable then) {
        whenLoaded(level, x, z, radius, GIVE_UP_TICKS, then);
    }

    /** As above, but runs anyway after {@code giveUpTicks} if the chunks are still not all loaded. */
    public static void whenLoaded(ServerLevel level, double x, double z, int radius, int giveUpTicks, Runnable then) {
        List<ChunkPos> need = new ArrayList<>();
        int x0 = (int) Math.floor(x - radius) >> 4, x1 = (int) Math.floor(x + radius) >> 4;
        int z0 = (int) Math.floor(z - radius) >> 4, z1 = (int) Math.floor(z + radius) >> 4;
        for (int cx = x0; cx <= x1; cx++) for (int cz = z0; cz <= z1; cz++) need.add(new ChunkPos(cx, cz));
        if (loaded(level, need)) {
            then.run();
            return;
        }
        for (ChunkPos c : need) level.getChunkSource().addRegionTicket(TICKET, c, 0, c);
        JOBS.add(new Job(level, need, then, level.getServer().getTickCount() + giveUpTicks));
    }

    /** Whether any job is still waiting (for tests). */
    public static boolean waiting() {
        return !JOBS.isEmpty();
    }

    private static boolean loaded(ServerLevel level, List<ChunkPos> chunks) {
        for (ChunkPos c : chunks) if (level.getChunkSource().getChunkNow(c.x, c.z) == null) return false;
        return true;
    }

    private static void tick(MinecraftServer server) {
        if (JOBS.isEmpty()) return;
        int now = server.getTickCount();
        List<Job> ready = new ArrayList<>();
        JOBS.removeIf(j -> {
            boolean go = loaded(j.level, j.chunks) || now > j.deadline;
            if (go) ready.add(j);
            return go;
        });
        for (Job j : ready) {
            release(j);
            j.then.run();
        }
    }

    /** Lets the chunks go again (they stay loaded only while something else holds them). */
    private static void release(Job j) {
        for (ChunkPos c : j.chunks) j.level.getChunkSource().removeRegionTicket(TICKET, c, 0, c);
    }
}
