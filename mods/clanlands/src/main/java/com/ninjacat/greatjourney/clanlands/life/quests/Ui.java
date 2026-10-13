package com.ninjacat.greatjourney.clanlands.life.quests;

import com.ninjacat.greatjourney.clanlands.life.Dialog;
import com.ninjacat.greatjourney.clanlands.life.rpg.Journals;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.util.FakePlayer;

/** Sends quest updates only to real players: fake players (machines, tests) have no client to show them to. */
final class Ui {
    private Ui() {}

    static void push(ServerPlayer p) {
        if (!(p instanceof FakePlayer)) Journals.push(p);
    }

    static void subtitle(ServerPlayer p, List<Dialog.Line> lines) {
        if (!(p instanceof FakePlayer)) Dialog.subtitle(p, lines);
    }

    static void play(ServerPlayer p, List<Dialog.Line> lines) {
        if (!(p instanceof FakePlayer)) Dialog.play(p, lines);
    }
}
