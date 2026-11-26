package com.ninjacat.greatjourney.clanlands.verification;

import com.ninjacat.greatjourney.clanlands.life.rpg.JournalView;
import com.ninjacat.greatjourney.clanlands.net.ClanPayloads;
import io.netty.buffer.Unpooled;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** The dialogue window's and journal's messages survive the trip through their codecs unchanged. */
@GameTestHolder("clanlands")
@PrefixGameTestTemplate(false)
public final class RpgUiGameTests {
    private RpgUiGameTests() {}

    private static <T> T roundTrip(GameTestHelper h, StreamCodec<RegistryFriendlyByteBuf, T> codec, T value) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), h.getLevel().registryAccess());
        codec.encode(buf, value);
        T back = codec.decode(buf);
        h.assertTrue(buf.readableBytes() == 0, "the codec reads back everything it wrote for " + value.getClass().getSimpleName());
        return back;
    }

    @GameTest(template = "empty")
    public static void dialogueMessagesRoundTrip(GameTestHelper h) {
        var lines = List.of(
                new ClanPayloads.DialogueLine("Mossheart", 0xE8A33D, "Welcome to ThunderClan, young one.", 42, false),
                new ClanPayloads.DialogueLine("", 0xAAAAAA, "The wind stirs the ferns.", -1, true));
        var open = new ClanPayloads.OpenDialogue(7, lines,
                List.of(new ClanPayloads.ChoiceView("Join ThunderClan", 0x55FF55), new ClanPayloads.ChoiceView("Not yet", 0xAAAAAA)));
        h.assertTrue(roundTrip(h, ClanPayloads.OpenDialogue.STREAM_CODEC, open).equals(open), "a scene with a portrait and two choices");
        var sub = new ClanPayloads.Subtitle(lines);
        h.assertTrue(roundTrip(h, ClanPayloads.Subtitle.STREAM_CODEC, sub).equals(sub), "subtitles");
        var choice = new ClanPayloads.DialogueChoice(7, 1);
        h.assertTrue(roundTrip(h, ClanPayloads.DialogueChoice.STREAM_CODEC, choice).equals(choice), "a choice");
        var closed = new ClanPayloads.DialogueClosed(7);
        h.assertTrue(roundTrip(h, ClanPayloads.DialogueClosed.STREAM_CODEC, closed).equals(closed), "a dismissal");
        var close = new ClanPayloads.CloseDialogue(9);
        h.assertTrue(roundTrip(h, ClanPayloads.CloseDialogue.STREAM_CODEC, close).equals(close), "a close");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void journalViewRoundTrips(GameTestHelper h) {
        var view = new JournalView("Fernpaw", "ThunderClan", "Apprentice", 0xE8A33D, 12, "Earn your warrior name",
                List.of(new JournalView.Quest("story.borders", "Learn the borders", "Mossheart",
                                "Walk every border of ThunderClan's territory with your mentor.",
                                List.of(new JournalView.Objective("Mark the WindClan border", 1, 1),
                                        new JournalView.Objective("Mark the ShadowClan border", 0, 1)), true, false)
                                .withDetail("+4 standing", "Ready: take it back to Mossheart").withMap(true, true),
                        new JournalView.Quest("duty.herbs", "Help the medicine cat", "Sorrelleaf", "Bring yarrow.",
                                List.of(new JournalView.Objective("Bring yarrow", 2, 3)), false, false)),
                List.of(new JournalView.Place("The Moonpool", false, "Far up the border stream"),
                        new JournalView.Place("The stone hollow", true, "")),
                List.of("Mossheart — Warrior", "Sorrelleaf — Medicine cat"),
                List.of("Oakstripe of WindClan"), JournalView.LADDER);
        h.assertTrue(roundTrip(h, JournalView.STREAM_CODEC, view).equals(view), "a full journal");
        var open = new ClanPayloads.OpenJournal(view);
        h.assertTrue(roundTrip(h, ClanPayloads.OpenJournal.STREAM_CODEC, open).equals(open), "the open-journal message");
        var empty = JournalView.empty("Loner");
        h.assertTrue(roundTrip(h, ClanPayloads.JournalUpdate.STREAM_CODEC, new ClanPayloads.JournalUpdate(empty)).view().equals(empty),
                "an empty journal update");
        h.succeed();
    }
}
