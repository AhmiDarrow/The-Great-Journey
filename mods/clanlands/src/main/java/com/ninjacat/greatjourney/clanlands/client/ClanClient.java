package com.ninjacat.greatjourney.clanlands.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.ninjacat.greatjourney.clanlands.net.ClanPayloads;
import com.ninjacat.greatjourney.clanlands.net.ClientHooks;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * The client half of the dialogue window, subtitles and journal. Installs the {@link ClientHooks} the network
 * handlers call, registers the journal key (J) and the subtitle layer. A scene that arrives while another screen is
 * open (an inventory, a chest) waits until that screen closes.
 */
@EventBusSubscriber(modid = "clanlands", bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClanClient {
    private ClanClient() {}

    public static final KeyMapping JOURNAL_KEY = new KeyMapping("key.clanlands.journal", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_J, "key.categories.greatjourney");
    /** Opens the full-screen Clan map. No key of its own: the player gives it one in Controls. */
    public static final KeyMapping MAP_KEY = new KeyMapping("key.clanlands.map", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_UNKNOWN, "key.categories.greatjourney");

    private static ClanPayloads.OpenDialogue waitingScene;

    @SubscribeEvent
    static void setup(FMLClientSetupEvent event) {
        ClientHooks.openDialogue = ClanClient::openDialogue;
        ClientHooks.closeDialogue = ClanClient::closeDialogue;
        ClientHooks.subtitle = p -> {
            if (!TourClient.active()) SubtitleOverlay.add(p.lines());   // Clan news is not shown over the tour
        };
        ClientHooks.openJournal = p -> Minecraft.getInstance().setScreen(new JournalScreen(p.view()));
        ClientHooks.journalUpdate = p -> {
            if (Minecraft.getInstance().screen instanceof JournalScreen js) js.update(p.view());
        };
        ClientHooks.mapData = MapPainter::receive;
        ClientHooks.tourStart = TourClient::start;
        ClientHooks.tourGo = TourClient::go;
        ClientHooks.tourEnd = TourClient::end;
        TourClient.init();
        NeoForge.EVENT_BUS.addListener(ClanClient::tick);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> {
            waitingScene = null;
            SubtitleOverlay.clear();
        });
    }

    @SubscribeEvent
    static void keys(RegisterKeyMappingsEvent event) {
        event.register(JOURNAL_KEY);
        event.register(MAP_KEY);
    }

    @SubscribeEvent
    static void layers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.HOTBAR, ResourceLocation.fromNamespaceAndPath("clanlands", "subtitles"), new SubtitleOverlay());
    }

    private static void openDialogue(ClanPayloads.OpenDialogue scene) {
        Minecraft mc = Minecraft.getInstance();
        com.mojang.logging.LogUtils.getLogger().debug("Dialogue scene {} arrived ({} lines); screen {}, tour {}", scene.sceneId(),
                scene.lines().size(), mc.screen == null ? "none" : mc.screen.getClass().getSimpleName(), TourClient.active());
        if (mc.screen instanceof DialogueScreen ds) {
            ds.show(scene);
        } else if (mc.screen == null) {
            mc.setScreen(new DialogueScreen(scene));
        } else {
            waitingScene = scene;                    // another window is open: show it when that closes
        }
    }

    private static void closeDialogue(ClanPayloads.CloseDialogue p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof DialogueScreen ds && ds.sceneId() == p.sceneId()) ds.closeQuietly();
        if (waitingScene != null && waitingScene.sceneId() == p.sceneId()) waitingScene = null;
    }

    private static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (waitingScene != null && mc.screen == null && !TourClient.active()) {
            ClanPayloads.OpenDialogue s = waitingScene;
            waitingScene = null;
            mc.setScreen(new DialogueScreen(s));
        }
        while (JOURNAL_KEY.consumeClick()) {
            if (mc.screen == null) PacketDistributor.sendToServer(new ClanPayloads.RequestJournal());
        }
        while (MAP_KEY.consumeClick()) {
            if (mc.screen == null && !TourClient.active()) {
                MapPainter.show(-1);
                mc.setScreen(new ClanMapScreen(null));
            }
        }
    }
}
