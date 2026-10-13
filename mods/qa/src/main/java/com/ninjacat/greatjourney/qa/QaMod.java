package com.ninjacat.greatjourney.qa;

import com.ninjacat.greatjourney.qa.server.QaCommands;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** TEST ONLY: the playtest harness mod. Never shipped (the Core and pack export tools refuse it). */
@Mod("greatjourney_qa")
public final class QaMod {
    public QaMod() {
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent e) -> QaCommands.register(e.getDispatcher()));
    }
}
