package com.ninjacat.greatjourney.clanlore.client;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.snowteb.warriorcats_events.entity.ModEntities;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * Client side of traits: hangs {@link TraitLayer} on WCE's cat renderer through GeckoLib's own layer list, every time
 * the renderers are rebuilt (each resource reload). Walkers draws a player's cat shape with that same renderer, so
 * players' marks show too.
 */
public final class TraitClient {
    private TraitClient() {}

    public static void init(IEventBus modBus) {
        modBus.addListener(TraitClient::addLayers);
    }

    @SuppressWarnings("unchecked")
    private static void addLayers(EntityRenderersEvent.AddLayers event) {
        EntityRenderer<WCatEntity> r = event.getRenderer(ModEntities.WCAT.get());
        if (r instanceof GeoEntityRenderer<?> geo) {
            GeoEntityRenderer<WCatEntity> cats = (GeoEntityRenderer<WCatEntity>) geo;
            cats.addRenderLayer(new TraitLayer(cats));
        }
    }
}
