package com.ninjacat.greatjourney.clanlands.life;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.snowteb.warriorcats_events.diseases.DiseaseManager;
import net.snowteb.warriorcats_events.diseases.Diseaseable;

/**
 * Players are cats too: one can give another the herb that cures them, by using it on them. Warrior Cats Events lets a
 * sick cat be treated this way, but a sick player could only eat the herb themselves. (Its poultices already work on
 * players.)
 */
public final class Healing {
    private Healing() {}

    public static void init() {
        NeoForge.EVENT_BUS.addListener((PlayerInteractEvent.EntityInteract e) -> {
            if (e.getHand() != InteractionHand.MAIN_HAND || !(e.getEntity() instanceof ServerPlayer healer)
                    || !(e.getTarget() instanceof ServerPlayer patient)) return;
            if (!treat(healer, patient, healer.getMainHandItem())) return;
            e.setCanceled(true);
            e.setCancellationResult(InteractionResult.SUCCESS);
        });
    }

    private static boolean isCure(ItemStack held) {
        return net.snowteb.warriorcats_events.diseases.DiseaseRegistry.getList().values().stream()
                .anyMatch(d -> DiseaseManager.getCuresFor(d).contains(held.getItem()));
    }

    /** Gives {@code herb} to the patient if it cures something they have now; one is used up. */
    public static boolean treat(ServerPlayer healer, ServerPlayer patient, ItemStack herb) {
        if (herb.isEmpty() || !((Object) patient instanceof Diseaseable<?> sick)) return false;
        String what = herb.getHoverName().getString();
        if (!DiseaseManager.healDiseaseMobInteract(herb, sick)) {
            if (isCure(herb) && !(healer instanceof net.neoforged.neoforge.common.util.FakePlayer)) {
                Dialog.subtitle(healer, Dialog.Line.narrate("That won't help " + Joining.catName(patient) + " just now."));
            }
            return false;
        }
        if (!(healer instanceof net.neoforged.neoforge.common.util.FakePlayer)) {
            Dialog.subtitle(healer, Dialog.Line.narrate("You give " + Joining.catName(patient) + " the " + what + "."));
            Dialog.subtitle(patient, Dialog.Line.narrate(Joining.catName(healer) + " treats you with " + what + "."));
        }
        return true;
    }
}
