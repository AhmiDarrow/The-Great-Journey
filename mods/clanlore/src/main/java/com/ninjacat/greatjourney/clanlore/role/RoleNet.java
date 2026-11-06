package com.ninjacat.greatjourney.clanlore.role;

import com.ninjacat.greatjourney.clanlore.Clanlore;
import java.util.function.Consumer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;

/**
 * Tells clients each cat's role, for the line under its name tag. The server sends a cat's role to a player when
 * they start seeing the cat, and to everyone seeing it whenever what the line would say changes. Names are sent as
 * text, since the mentor or apprentice may be far away and not loaded on the client.
 */
public final class RoleNet {
    private RoleNet() {}

    static final String VERSION = "1";

    /** Set by the client side; the server never calls it. */
    public static volatile Consumer<RoleSync> clientSink = p -> {};

    /** S2C: a cat's role, a leader's lives, and the names of its mentor and apprentice ("" for none). */
    public record RoleSync(int entity, int role, int lives, String mentor, String apprentice) implements CustomPacketPayload {
        public static final Type<RoleSync> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Clanlore.MOD_ID, "role_sync"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RoleSync> STREAM_CODEC = StreamCodec.of((b, p) -> {
            b.writeVarInt(p.entity);
            b.writeByte(p.role);
            b.writeByte(p.lives);
            b.writeUtf(p.mentor, 64);
            b.writeUtf(p.apprentice, 64);
        }, b -> new RoleSync(b.readVarInt(), b.readByte(), b.readByte(), b.readUtf(64), b.readUtf(64)));

        static RoleSync of(WCatEntity cat, RoleData d) {
            int lives = d.role == Role.LEADER ? Roles.lives(cat) : 0;
            return new RoleSync(cat.getId(), d.role.ordinal(), lives, clip(d.mentor == null ? "" : d.mentorName),
                    clip(d.apprentice == null ? "" : d.apprenticeName));
        }

        public Role roleKind() {
            return Role.byId(role);
        }

        /** Nothing to show. */
        public boolean empty() {
            return role == 0 && mentor.isEmpty() && apprentice.isEmpty();
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private static String clip(String s) {
        return s.length() > 64 ? s.substring(0, 64) : s;
    }

    static void payloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar r = event.registrar(VERSION).optional();
        r.playToClient(RoleSync.TYPE, RoleSync.STREAM_CODEC, (p, ctx) -> ctx.enqueueWork(() -> clientSink.accept(p)));
    }

    /** Sends the cat's line to everyone seeing it, if it has changed since last sent. */
    static void update(WCatEntity cat, RoleData d) {
        RoleSync now = RoleSync.of(cat, d);
        if (now.equals(d.sent)) return;
        boolean shown = d.sent != null && !d.sent.empty();
        d.sent = now;
        if (now.empty() && !shown) return;
        PacketDistributor.sendToPlayersTrackingEntity(cat, now);
    }

    /** A player has just started seeing the cat. */
    static void startSeeing(ServerPlayer player, WCatEntity cat) {
        RoleSync now = RoleSync.of(cat, Roles.data(cat));
        if (!now.empty()) PacketDistributor.sendToPlayer(player, now);
    }
}
