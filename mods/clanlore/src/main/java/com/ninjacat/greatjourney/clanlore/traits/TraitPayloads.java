package com.ninjacat.greatjourney.clanlore.traits;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * The one packet traits need: an entity's trait mask, sent to players as they start seeing it and whenever it changes.
 * The client keeps it in the same attachment the server saves, so the renderer reads it the same way. The handler only
 * touches common classes, so a dedicated server never loads client code through it.
 */
public final class TraitPayloads {
    private TraitPayloads() {}

    public record Sync(int entityId, int mask) implements CustomPacketPayload {
        public static final Type<Sync> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("clanlore", "traits_sync"));
        public static final StreamCodec<ByteBuf, Sync> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Sync::entityId,
                ByteBufCodecs.VAR_INT, Sync::mask,
                Sync::new);

        @Override
        public Type<Sync> type() {
            return TYPE;
        }
    }

    static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar r = event.registrar("1");
        r.playToClient(Sync.TYPE, Sync.STREAM_CODEC, TraitPayloads::onSync);
    }

    private static void onSync(Sync p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() == null) return;
            Entity e = ctx.player().level().getEntity(p.entityId());
            if (e != null) e.setData(Traits.DATA.get(), new TraitRecord(p.mask(), -1L));
        });
    }
}
