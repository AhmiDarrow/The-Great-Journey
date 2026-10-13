package com.ninjacat.greatjourney.clanlands.client;

import com.ninjacat.greatjourney.clanlands.life.Mode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * The title screen's way into The Great Journey: the vanilla Singleplayer button becomes Campaign and Free Mode
 * side by side, with Load World under them for worlds already started. The buttons below move down one row.
 * Campaign and Free Mode open the usual world creation screen with the mode remembered for the new world.
 */
@EventBusSubscriber(modid = "clanlands", value = Dist.CLIENT)
public final class TitleMenu {
    private TitleMenu() {}

    /** The name to prefill when the create-world screen for a mode button opens. */
    private static String pendingName;

    /** Prefills the world name before the create-world screen builds its name box (after would be too late). */
    @SubscribeEvent
    static void preInit(ScreenEvent.Init.Pre event) {
        if (event.getScreen() instanceof CreateWorldScreen create && pendingName != null) {
            create.getUiState().setName(pendingName);
            pendingName = null;
        }
    }

    @SubscribeEvent
    static void init(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        if (!(screen instanceof TitleScreen title)) return;
        Mode.setPending(null);                                  // backing out of world creation forgets the choice
        Button single = find(event, "menu.singleplayer");
        if (single == null) return;
        int x = single.getX(), y = single.getY(), w = single.getWidth(), h = single.getHeight();
        event.removeListener(single);
        // Realms has no place in a Warrior Cats pack: dropping it gives Load World its row back, so the buttons below
        // stay where vanilla put them (clear of the version text)
        Button realms = find(event, "menu.online");
        int realmsY = realms == null ? Integer.MAX_VALUE : realms.getY();
        if (realms != null) event.removeListener(realms);
        for (GuiEventListener l : event.getListenersList()) {
            if (l instanceof AbstractWidget wd && wd.getY() > y && wd.getY() < realmsY) wd.setY(wd.getY() + 24);
        }
        // the rest of the main buttons in the same parchment style
        for (String key : new String[] {"menu.multiplayer", "fml.menu.mods", "menu.options", "menu.quit"}) {
            Button b = find(event, key);
            if (b == null) continue;
            event.removeListener(b);
            Button restyled = new JourneyButton(b.getX(), b.getY(), b.getWidth(), b.getHeight(), b.getMessage(), x2 -> b.onPress());
            restyled.active = b.active;
            restyled.setTooltip(b.getTooltip());
            event.addListener(restyled);
        }
        int half = (w - 4) / 2;
        Minecraft mc = Minecraft.getInstance();
        event.addListener(button(x, y, half, h, "menu.clanlands.campaign", "menu.clanlands.campaign.tip",
                b -> create(mc, title, Mode.CAMPAIGN, "menu.clanlands.campaign.world")));
        event.addListener(button(x + w - half, y, half, h, "menu.clanlands.free", "menu.clanlands.free.tip",
                b -> create(mc, title, Mode.FREE, "menu.clanlands.free.world")));
        event.addListener(button(x, y + 24, w, h, "menu.clanlands.load", "menu.clanlands.load.tip", b -> {
            Mode.setPending(null);
            mc.setScreen(new SelectWorldScreen(title));
        }));
    }

    private static Button find(ScreenEvent.Init.Post event, String key) {
        for (GuiEventListener l : event.getListenersList()) {
            if (l instanceof Button b && b.getMessage().getContents() instanceof TranslatableContents t && key.equals(t.getKey())) return b;
        }
        return null;
    }

    private static void create(Minecraft mc, Screen parent, Mode mode, String nameKey) {
        Mode.setPending(mode);
        pendingName = Component.translatable(nameKey).getString();
        CreateWorldScreen.openFresh(mc, parent);
    }

    private static Button button(int x, int y, int w, int h, String key, String tipKey, Button.OnPress press) {
        Button b = new JourneyButton(x, y, w, h, Component.translatable(key), press);
        b.setTooltip(Tooltip.create(Component.translatable(tipKey)));
        return b;
    }

    /** A title-screen button in the journal's parchment and bark colours, glowing gold when hovered. */
    private static final class JourneyButton extends Button {
        JourneyButton(int x, int y, int w, int h, Component message, OnPress press) {
            super(x, y, w, h, message, press, DEFAULT_NARRATION);
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            boolean hot = isHoveredOrFocused() && active;
            int border = hot ? UiStyle.GOLD : UiStyle.BARK;
            int body = hot ? 0xF7ECD3 : UiStyle.PARCHMENT;
            UiStyle.rounded(g, getX() - 1, getY() - 1, width + 2, height + 2, 4, UiStyle.argb(border, alpha));
            UiStyle.rounded(g, getX(), getY(), width, height, 3, UiStyle.argb(body, alpha));
            g.fillGradient(getX() + 2, getY() + height / 2, getX() + width - 2, getY() + height - 1,
                    UiStyle.argb(body, 0f), UiStyle.argb(UiStyle.PARCHMENT_DARK, alpha * 0.9f));
            var font = Minecraft.getInstance().font;
            int tw = font.width(getMessage());
            g.drawString(font, getMessage(), getX() + (width - tw) / 2, getY() + (height - 8) / 2,
                    UiStyle.argb(hot ? UiStyle.BARK_DARK : UiStyle.INK, alpha), false);
        }
    }
}
