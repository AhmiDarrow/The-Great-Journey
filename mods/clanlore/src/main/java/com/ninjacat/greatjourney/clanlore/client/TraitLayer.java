package com.ninjacat.greatjourney.clanlore.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.ninjacat.greatjourney.clanlore.traits.Trait;
import com.ninjacat.greatjourney.clanlore.traits.Traits;
import com.ninjacat.greatjourney.clanlore.traits.TraitsConfig;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.GeoRenderer;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;

/**
 * Draws a cat's traits over its coat: the whole model again, once per drawn trait, with that trait's mostly see-through
 * overlay (made on WCE's own 64x64 UV layout). It calls the renderer's {@code actuallyRender} rather than GeckoLib's
 * {@code reRender}, because WCE's {@code preRender} scales the cat to its size without checking for a re-render, and a
 * re-render through it would draw the overlay a size smaller than the cat. Long-fur cards and the bobtail are hidden
 * or shown exactly as in the coat pass, since the bones are not animated again.
 */
public final class TraitLayer extends GeoRenderLayer<WCatEntity> {
    public TraitLayer(GeoRenderer<WCatEntity> renderer) {
        super(renderer);
    }

    @Override
    public void render(PoseStack poseStack, WCatEntity cat, BakedGeoModel model, RenderType renderType, MultiBufferSource buffers,
                       VertexConsumer buffer, float partialTick, int packedLight, int packedOverlay) {
        if (cat.isInvisible() || !TraitsConfig.DRAW.get()) return;
        int mask = Traits.shown(cat);
        if (mask == 0) return;
        int colour = getRenderer().getRenderColor(cat, partialTick, packedLight).argbInt();
        for (Trait t : Trait.values()) {
            if (!t.in(mask) || t.texture() == null) continue;
            RenderType overlay = RenderType.entityTranslucent(t.texture());
            getRenderer().actuallyRender(poseStack, cat, model, overlay, buffers, buffers.getBuffer(overlay), true,
                    partialTick, packedLight, packedOverlay, colour);
        }
    }
}
