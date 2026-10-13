package com.ninjacat.greatjourney.clanlands.verification;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Everything of ours the game names from the registries has an English line: our items, and the death messages of
 * our damage types (a cat struck by a monster on the Thunderpath). tools/gates/check_lang.py covers the keys named
 * in code.
 */
@GameTestHolder("clanlands")
@PrefixGameTestTemplate(false)
public final class LangGameTests {
    private LangGameTests() {}

    @GameTest(template = "empty")
    public static void registeredThingsHaveEnglishNames(GameTestHelper h) {
        JsonObject lang;
        try (InputStream in = LangGameTests.class.getResourceAsStream("/assets/clanlands/lang/en_us.json")) {
            h.assertTrue(in != null, "the English lang file ships with the mod");
            lang = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        List<String> missing = new ArrayList<>();
        for (var item : BuiltInRegistries.ITEM) {
            if (!"clanlands".equals(BuiltInRegistries.ITEM.getKey(item).getNamespace())) continue;
            if (!lang.has(item.getDescriptionId())) missing.add(item.getDescriptionId());
        }
        var damage = h.getLevel().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE);
        for (var e : damage.entrySet()) {
            if (!"clanlands".equals(e.getKey().location().getNamespace())) continue;
            String msg = "death.attack." + e.getValue().msgId();
            if (!lang.has(msg)) missing.add(msg);
            if (!lang.has(msg + ".player")) missing.add(msg + ".player");
        }
        h.assertTrue(missing.isEmpty(), "missing English lines: " + missing);
        h.succeed();
    }
}
