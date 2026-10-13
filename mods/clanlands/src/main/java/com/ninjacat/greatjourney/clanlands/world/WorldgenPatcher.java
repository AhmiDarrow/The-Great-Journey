package com.ninjacat.greatjourney.clanlands.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.Map;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;

/**
 * Rewrites the overworld's terrain shaping as it is read from the data packs, so inside the Clan lands the ground
 * follows the map. Wherever the overworld refers to vanilla's surface offset, jaggedness or factor, the reference
 * is blended toward the map by its weight (1 inside a map, 0 in the normal world):
 * <ul>
 *   <li>offset: toward the map's ground height;</li>
 *   <li>jaggedness: toward none, so there are no mountain spikes inside the territories;</li>
 *   <li>factor: toward the zone's smoothness.</li>
 * </ul>
 * Everything else (caves, ores, aquifers, surface rules) stays vanilla, and so does the world outside the maps, with one
 * exception: deep inside a map the noise caves are sealed near the planned surface ({@link #sealNearSurface}).
 */
public final class WorldgenPatcher {
    private WorldgenPatcher() {}

    private static final ResourceLocation OVERWORLD = ResourceLocation.withDefaultNamespace("overworld");

    public static JsonElement patch(ResourceKey<?> key, JsonElement json) {
        ResourceLocation id = key.location();
        if (key.registry().equals(Registries.NOISE_SETTINGS.location()) && id.equals(OVERWORLD) && json.isJsonObject()) {
            JsonObject o = json.getAsJsonObject();
            o.add("noise_router", rewrite(o.get("noise_router")));
            if (o.get("noise_router") instanceof JsonObject router && router.has("final_density")) {
                router.add("final_density", sealNearSurface(router.get("final_density")));
            }
            return o;
        }
        if (key.registry().equals(Registries.DENSITY_FUNCTION.location())
                && id.getNamespace().equals("minecraft") && id.getPath().startsWith("overworld/")) {
            return rewrite(json);
        }
        return json;
    }

    private static final Map<String, java.util.function.Function<JsonElement, JsonElement>> BLENDS = Map.of(
            "minecraft:overworld/offset", ref -> lerpTo(ref, channel("offset")),
            "minecraft:overworld/jaggedness", ref -> mul(ref, add(new JsonPrimitive(1.0), mul(new JsonPrimitive(-1.0), channel("weight")))),
            "minecraft:overworld/factor", ref -> lerpTo(ref, channel("factor")));

    static JsonElement rewrite(JsonElement e) {
        if (e == null) {
            return null;
        }
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
            var blend = BLENDS.get(e.getAsString());
            return blend == null ? e : blend.apply(e);
        }
        if (e.isJsonArray()) {
            JsonArray out = new JsonArray();
            for (JsonElement c : e.getAsJsonArray()) out.add(rewrite(c));
            return out;
        }
        if (!e.isJsonObject()) {
            return e;
        }
        JsonObject out = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : e.getAsJsonObject().entrySet()) {
            // only rewrite density-function positions: a "noise" field names noise parameters, never a function
            out.add(entry.getKey(), entry.getKey().equals("noise") ? entry.getValue() : rewrite(entry.getValue()));
        }
        return out;
    }

    /** Noise caves are sealed from this many blocks above the planned surface ... */
    static final double SEAL_ABOVE = 32;
    /** ... down to this many below it (deeper caves stay, under a roof at least about half this thick). */
    static final double SEAL_BELOW = 12;
    /** How fast the sealed band thins as the map's weight falls: gone below a weight of about 0.94. */
    private static final double SEAL_FADE = 200;
    /** The density a sealed cave below the ground's first few blocks is raised to: just solid, so solid rock is unchanged. */
    private static final double SEAL_DENSITY = 0.05;

    /**
     * Vanilla's noise caves break out through the planned ground: the cave entrances ({@code overworld/caves/entrances},
     * the big pits vanilla opens into its hillsides) cut a dry stone bowl 30 blocks deep into the WindClan moor on seed
     * -84596914, and with the map's high factor the cheese and spaghetti caves start a few blocks under the ground,
     * closer than one 8-block noise cell. Inside a map (weight near 1), from {@link #SEAL_ABOVE} above the planned
     * surface down to {@link #SEAL_BELOW} below it, every noise cave there is filled: vanilla's cave choice (its
     * range_choice on sloped_cheese at 1.5625) is raised to at least sloped_cheese itself near the ground, the terrain
     * with no cave entrances in it (above the ground sloped_cheese is below zero, so the air stays air), and to
     * {@link #SEAL_DENSITY} deeper down; the noodle caves are lifted out of reach. Outside the band, and anywhere
     * outside the maps, the router is exactly vanilla's. Carvers still run.
     */
    static JsonElement sealNearSurface(JsonElement finalDensity) {
        boolean[] found = new boolean[2];
        JsonElement out = seal(finalDensity, found);
        if (!found[0] || !found[1]) {
            com.mojang.logging.LogUtils.getLogger().warn(
                    "Clanlands: the overworld's final density has no {}; noise caves near the Clan lands' ground are left open",
                    !found[0] ? "vanilla cave choice on sloped_cheese" : "noodle cave reference");
        }
        return out;
    }

    private static JsonElement seal(JsonElement e, boolean[] found) {
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() && e.getAsString().equals("minecraft:overworld/caves/noodle")) {
            found[1] = true;
            return max(e, inSealedBand(new JsonPrimitive(1.0)));                // above anything the squeezed terrain reaches
        }
        if (e.isJsonArray()) {
            JsonArray out = new JsonArray();
            for (JsonElement c : e.getAsJsonArray()) out.add(seal(c, found));
            return out;
        }
        if (!e.isJsonObject()) {
            return e;
        }
        JsonObject o = e.getAsJsonObject();
        if (o.has("type") && o.get("type").getAsString().equals("minecraft:range_choice") && o.has("input")
                && o.get("input").isJsonPrimitive() && o.get("input").getAsString().equals("minecraft:overworld/sloped_cheese")
                && o.has("max_exclusive") && Math.abs(o.get("max_exclusive").getAsDouble() - 1.5625) < 1e-6) {
            found[0] = true;
            // near the ground (sloped_cheese under 1.5625) the terrain itself, as if there were no cave entrances;
            // below that, where vanilla picks among its caves, just solid
            JsonObject floor = new JsonObject();
            floor.addProperty("type", "minecraft:range_choice");
            floor.addProperty("input", "minecraft:overworld/sloped_cheese");
            floor.addProperty("min_inclusive", -1000000.0);
            floor.addProperty("max_exclusive", 1.5625);
            floor.addProperty("when_in_range", "minecraft:overworld/sloped_cheese");
            floor.addProperty("when_out_of_range", SEAL_DENSITY);
            return max(o, inSealedBand(floor));
        }
        JsonObject out = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : o.entrySet()) {
            out.add(entry.getKey(), entry.getKey().equals("noise") ? entry.getValue() : seal(entry.getValue(), found));
        }
        return out;
    }

    /**
     * {@code value} inside the sealed band, a million below zero (so a {@code max} with it changes nothing) outside it.
     * The band is measured in blocks below the blended surface: {@code 128 * overworld/depth} (the offset in it is
     * already blended toward the map), pushed out of range by {@link #SEAL_FADE} per unit of weight missing.
     */
    private static JsonObject inSealedBand(JsonElement value) {
        JsonElement below = mul(new JsonPrimitive(128.0), new JsonPrimitive("minecraft:overworld/depth"));
        JsonElement fade = mul(new JsonPrimitive(-SEAL_FADE), add(channel("weight"), new JsonPrimitive(-1.0)));
        JsonObject o = new JsonObject();
        o.addProperty("type", "minecraft:range_choice");
        o.add("input", add(below, fade));
        o.addProperty("min_inclusive", -SEAL_ABOVE);
        o.addProperty("max_exclusive", SEAL_BELOW);
        o.add("when_in_range", value);
        o.addProperty("when_out_of_range", -1000000.0);
        return o;
    }

    private static JsonObject max(JsonElement a, JsonElement b) {
        return binary("minecraft:max", a, b);
    }

    /** ref + weight * (target - ref) */
    private static JsonElement lerpTo(JsonElement ref, JsonElement target) {
        return add(ref, mul(channel("weight"), add(target, mul(new JsonPrimitive(-1.0), ref))));
    }

    private static JsonElement channel(String name) {
        JsonObject f = new JsonObject();
        f.addProperty("type", "clanlands:map");
        f.addProperty("channel", name);
        JsonObject cache = new JsonObject();
        cache.addProperty("type", "minecraft:cache_2d");
        cache.add("argument", f);
        return cache;
    }

    private static JsonObject add(JsonElement a, JsonElement b) {
        return binary("minecraft:add", a, b);
    }

    private static JsonObject mul(JsonElement a, JsonElement b) {
        return binary("minecraft:mul", a, b);
    }

    private static JsonObject binary(String type, JsonElement a, JsonElement b) {
        JsonObject o = new JsonObject();
        o.addProperty("type", type);
        o.add("argument1", a);
        o.add("argument2", b);
        return o;
    }
}
