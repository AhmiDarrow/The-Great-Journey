package com.ninjacat.greatjourney.clanlore.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.ninjacat.greatjourney.clanlore.role.Role;
import com.ninjacat.greatjourney.clanlore.role.RoleConfig;
import com.ninjacat.greatjourney.clanlore.role.RoleNet;
import com.ninjacat.greatjourney.clanlore.role.Roles;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.EntityAttachment;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.ClientHooks;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderNameTagEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;
import org.joml.Matrix4f;

/**
 * The second line under a Clan cat's name tag: "Leader · 7 lives", "Elder", "Queen", "Mentor of Ashpaw",
 * "Apprentice of Rainwhisker". Warrior Cats Events draws its cats' names with the game's own name tag, so this line
 * is drawn the same way, just below it and a little smaller, whenever the name itself shows. Only what the server
 * sent is shown.
 */
@EventBusSubscriber(modid = "clanlore", bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class RoleTags {
    private RoleTags() {}

    /** What each cat's line says, by entity id; cats with nothing to show are absent. */
    private static final Map<Integer, String> LINES = new HashMap<>();
    private static final Map<Integer, Boolean> LEADERS = new HashMap<>();

    static final int LEADER_COLOUR = 0xF2D58C, COLOUR = 0xD8D8D8;
    /** The line's size against the name's, and how far below the name it sits (in name-tag pixels). */
    static final float SCALE = 0.8F, BELOW = 10F;

    @SubscribeEvent
    static void setup(FMLClientSetupEvent e) {
        RoleNet.clientSink = RoleTags::accept;
        NeoForge.EVENT_BUS.addListener(RoleTags::nameTag);
        NeoForge.EVENT_BUS.addListener((EntityLeaveLevelEvent ev) -> {
            if (ev.getLevel().isClientSide()) forget(ev.getEntity().getId());
        });
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut ev) -> {
            LINES.clear();
            LEADERS.clear();
        });
    }

    private static void accept(RoleNet.RoleSync p) {
        String line = Roles.describe(p.roleKind(), p.lives(), p.mentor(), p.apprentice());
        if (line.isEmpty()) {
            forget(p.entity());
            return;
        }
        LINES.put(p.entity(), line);
        LEADERS.put(p.entity(), p.roleKind() == Role.LEADER);
    }

    private static void forget(int id) {
        LINES.remove(id);
        LEADERS.remove(id);
    }

    private static void nameTag(RenderNameTagEvent e) {
        if (!(e.getEntity() instanceof WCatEntity cat)) return;
        String line = LINES.get(cat.getId());
        if (line == null || !RoleConfig.SHOW_ROLES.get()) return;
        Minecraft mc = Minecraft.getInstance();
        EntityRenderDispatcher d = mc.getEntityRenderDispatcher();
        // the same test the game uses for the name itself, so the two lines show and hide together
        boolean shows = e.canRender().isTrue() || e.canRender().isDefault()
                && (cat.shouldShowName() || cat.hasCustomName() && cat == d.crosshairPickEntity);
        if (!shows || !ClientHooks.isNameplateInRenderDistance(cat, d.distanceToSqr(cat))) return;
        Vec3 at = cat.getAttachments().getNullable(EntityAttachment.NAME_TAG, 0, cat.getViewYRot(e.getPartialTick()));
        if (at == null) return;
        PoseStack pose = e.getPoseStack();
        pose.pushPose();
        pose.translate(at.x, at.y + 0.5, at.z);
        pose.mulPose(d.cameraOrientation());
        pose.scale(0.025F, -0.025F, 0.025F);
        pose.translate(0, BELOW, 0);
        pose.scale(SCALE, SCALE, SCALE);
        Matrix4f m = pose.last().pose();
        Font font = mc.font;
        float x = -font.width(line) / 2F;
        int back = (int) (mc.options.getBackgroundOpacity(0.25F) * 255.0F) << 24;
        int colour = LEADERS.getOrDefault(cat.getId(), false) ? LEADER_COLOUR : COLOUR;
        boolean open = !cat.isDiscrete();
        font.drawInBatch(line, x, 0, 0x20000000 | (colour & 0xFFFFFF), false, m, e.getMultiBufferSource(),
                open ? Font.DisplayMode.SEE_THROUGH : Font.DisplayMode.NORMAL, back, e.getPackedLight());
        if (open) font.drawInBatch(line, x, 0, 0xFF000000 | colour, false, m, e.getMultiBufferSource(), Font.DisplayMode.NORMAL, 0, e.getPackedLight());
        pose.popPose();
    }
}
