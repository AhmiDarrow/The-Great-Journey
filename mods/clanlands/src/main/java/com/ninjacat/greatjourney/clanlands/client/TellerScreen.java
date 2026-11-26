package com.ninjacat.greatjourney.clanlands.client;

import com.ninjacat.greatjourney.clanlands.life.story.Teller;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * "Who watches over your Clans?": the storyteller for a new Free Mode world, picked between the title screen and the
 * world creation screen. Each button says who they are; the blurb of the one under the mouse or the keyboard focus is
 * shown below, and read by the narrator as the button's tooltip.
 */
final class TellerScreen extends Screen {
    private final Screen parent;
    private final Consumer<Teller> chosen;

    TellerScreen(Screen parent, Consumer<Teller> chosen) {
        super(Component.translatable("menu.clanlands.teller"));
        this.parent = parent;
        this.chosen = chosen;
    }

    @Override
    protected void init() {
        int w = Math.min(220, width - 40), x = (width - w) / 2, y = Math.max(40, height / 2 - 90);
        for (Teller t : Teller.values()) {
            Button b = Button.builder(Component.literal(t.title).withColor(t.colour), pressed -> chosen.accept(t))
                    .bounds(x, y, w, 20).tooltip(Tooltip.create(Component.literal(t.blurb))).build();
            addRenderableWidget(b);
            if (t == Teller.BLUESTAR) setInitialFocus(b);
            y += 24;
        }
        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> onClose()).bounds(x, Math.min(y + 36, height - 24), w, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int top = Math.max(40, height / 2 - 90);
        g.drawCenteredString(font, title, width / 2, top - 22, 0xFFFFFF);
        g.drawCenteredString(font, Component.translatable("menu.clanlands.teller.hint"), width / 2, top + Teller.values().length * 24 + 6, 0xA0A0A0);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
