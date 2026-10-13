package com.ninjacat.greatjourney.qa.server;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.logging.LogUtils;
import com.ninjacat.greatjourney.clanlands.layout.ClanMap;
import com.ninjacat.greatjourney.clanlands.layout.Landmark;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCGenetics;
import net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity;
import org.slf4j.Logger;

/**
 * TEST ONLY. Store art: {@code /qa studio} builds a magenta photo booth high in the sky (easy to cut a cat out of),
 * {@code /qa portrait <who> [k=v,...]} stands one Warrior Cats Events cat in it, its coat set through the mod's own
 * genetics (base, orange, dilute, agouti, tabby, white, silver, long fur, eyes), and {@code /qa gathering <map> <kind>}
 * stands the whole cast in an arc by a landmark with the player's camera facing them. The cats have no AI, so they
 * hold still for the camera; the client's photo steps (client/Photo) frame and photograph them.
 */
final class Portraits {
    private Portraits() {}

    private static final Logger LOG = LogUtils.getLogger();
    static final int STUDIO_Y = 230;
    static BlockPos studio;
    private static final List<UUID> SPAWNED = new ArrayList<>();

    /** The cast: a book cat's look, as Warrior Cats Events genes. Keys are read by {@link #spawn}; any can be overridden. */
    static final Map<String, String> CAST = new LinkedHashMap<>();

    static {
        String plain = "base=B-B,orange=o-o,dil=d-d,ag=a-a,tab=Mc-Mc,tabv=0,white=w-w,whitev=0,silver=i-i,silverv=0,fur=s,"
                + "ruf=0,bruf=0,noise=0,size=1.0,torv=0,yaw=0,";
        CAST.put("firestar", plain + "g=0,orange=O-O,tab=Mc-Mc,tabv=1,ruf=6,eye=green,eyev=4,skin=16,size=1.0");
        CAST.put("graystripe", plain + "g=0,dil=D-D,fur=L,eye=yellow,eyev=8,skin=2,size=1.05");
        CAST.put("bluestar", plain + "g=1,dil=D-D,silver=I-i,silverv=0,eye=blue,eyev=8,skin=2,size=0.95,furcheek=L");
        CAST.put("yellowfang", plain + "g=1,dil=D-D,fur=L,eye=yellow,eyev=6,skin=2,size=1.0");
        CAST.put("ravenpaw", plain + "g=0,white=S-w,whitev=4,eye=yellow,eyev=1,skin=0,size=0.85");
        CAST.put("sandstorm", plain + "g=1,orange=O-O,dil=D-D,tab=mc-mc,tabv=1,ruf=3,eye=green,eyev=0,skin=12,size=0.9");
        CAST.put("spottedleaf", plain + "g=1,orange=O-o,torv=3,white=S-w,whitev=4,eye=yellow,eyev=5,skin=17,size=0.9");
        CAST.put("tigerstar", plain + "g=0,ag=A-A,tab=mc-mc,tabv=0,ruf=2,eye=yellow,eyev=5,skin=5,size=1.15,furcheek=L");
        CAST.put("cinderpelt", plain + "g=1,dil=D-D,eye=blue,eyev=1,skin=2,size=0.85");
    }

    static void register(LiteralArgumentBuilder<CommandSourceStack> qa) {
        qa.then(Commands.literal("studio").executes(c -> {
            ServerPlayer p = c.getSource().getPlayerOrException();
            ServerLevel level = p.serverLevel();
            studio = new BlockPos(p.getBlockX(), STUDIO_Y, p.getBlockZ());
            p.teleportTo(level, studio.getX() + 0.5, STUDIO_Y + 1, studio.getZ() - 3.5, 0, 10);
            var magenta = Blocks.MAGENTA_CONCRETE.defaultBlockState();
            for (int dx = -12; dx <= 12; dx++) {
                for (int dz = -12; dz <= 12; dz++) {
                    boolean wall = Math.abs(dx) == 12 || Math.abs(dz) == 12;
                    for (int dy = -1; dy <= 12; dy++) {
                        BlockPos at = studio.offset(dx, dy, dz);
                        level.setBlock(at, dy == -1 || wall ? magenta : Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            }
            clearCats(level, studio, 48);
            QaCommands.note(p, "studio", "built at " + studio.toShortString());
            return 1;
        }));
        qa.then(Commands.literal("portrait")
                .then(Commands.literal("clear").executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    clearSpawned(p.serverLevel());
                    return 1;
                }))
                .then(Commands.argument("who", StringArgumentType.word()).executes(c -> portrait(c.getSource().getPlayerOrException(),
                                StringArgumentType.getString(c, "who"), ""))
                        .then(Commands.argument("spec", StringArgumentType.greedyString()).executes(c -> portrait(
                                c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "who"), StringArgumentType.getString(c, "spec"))))));
        qa.then(Commands.literal("gathering").then(Commands.literal("here").executes(c -> gatheringHere(c.getSource().getPlayerOrException())))
                .then(Commands.argument("map", StringArgumentType.word())
                .then(Commands.argument("kind", StringArgumentType.word()).executes(c -> gathering(c.getSource().getPlayerOrException(),
                        StringArgumentType.getString(c, "map"), StringArgumentType.getString(c, "kind"))))));
    }

    private static int portrait(ServerPlayer p, String who, String overrides) {
        if (studio == null) {
            QaCommands.fail(p, "portrait", "no studio yet (/qa studio)");
            return 0;
        }
        Map<String, String> spec = spec(who, overrides);
        if (spec == null) {
            QaCommands.fail(p, "portrait", "no such cat " + who);
            return 0;
        }
        ServerLevel level = p.serverLevel();
        clearSpawned(level);
        clearCats(level, studio, 30);
        float yaw = Float.parseFloat(spec.getOrDefault("yaw", "0"));
        WCatEntity cat = spawn(level, spec, studio.getX() + 0.5, STUDIO_Y, studio.getZ() + 0.5, yaw);
        QaCommands.note(p, "portrait " + who, cat == null ? "spawn failed" : "spawned " + who + " " + overrides);
        return cat == null ? 0 : 1;
    }

    /**
     * The whole cast in an arc on the near side of a landmark, each facing the camera, and the player (a spectator)
     * placed as the camera: eye 1.3 blocks over the ground, 11 blocks from the landmark.
     */
    private static int gathering(ServerPlayer p, String mapName, String kind) {
        ClanWorld w = ClanWorld.current();
        ClanMap m = switch (mapName) {
            case "lake" -> w.lake;
            case "forest" -> w.forest;
            case "journey" -> w.journey;
            default -> null;
        };
        Landmark l = m == null ? null : m.landmarks().stream().filter(x -> x.kind().name().equalsIgnoreCase(kind)).findFirst().orElse(null);
        if (l == null) {
            QaCommands.fail(p, "gathering", "no landmark " + mapName + " " + kind);
            return 0;
        }
        ServerLevel level = p.serverLevel();
        clearSpawned(level);
        double a = l.facing() + Math.PI, ux = Math.cos(a), uz = Math.sin(a);
        double camX = l.x() + ux * 11, camZ = l.z() + uz * 11;
        double gx = l.x() + ux * 5, gz = l.z() + uz * 5;
        List<String> names = new ArrayList<>(CAST.keySet());
        names.remove("cinderpelt");
        int n = names.size();
        for (int i = 0; i < n; i++) {
            double t = (i - (n - 1) / 2.0) / ((n - 1) / 2.0);                // -1 .. 1 across the arc
            double ang = a + Math.PI + t * Math.toRadians(62);               // the arc bows away from the camera
            double r = 3.2 + (i % 2) * 0.9;                                   // two loose rows
            double x = gx + Math.cos(ang) * r, z = gz + Math.sin(ang) * r;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(x), (int) Math.floor(z));
            float yaw = (float) Math.toDegrees(Math.atan2(-(camX - x), camZ - z)) + (float) (t * -12);
            spawn(level, spec(names.get(i), ""), x, y, z, yaw);
        }
        int gy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(camX), (int) Math.floor(camZ));
        int ty = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(gx), (int) Math.floor(gz));
        double eye = gy + 1.3;
        float yaw = (float) Math.toDegrees(Math.atan2(-(gx - camX), gz - camZ));
        float pitch = (float) -Math.toDegrees(Math.atan2(ty + 0.4 - eye, Math.hypot(gx - camX, gz - camZ)));
        p.teleportTo(level, camX, eye - p.getEyeHeight(), camZ, yaw, pitch);
        QaCommands.note(p, "gathering", String.format(Locale.ROOT, "%d cats by %s at %.0f %.0f, camera %.0f %.1f %.0f", n, kind, gx, gz, camX, eye, camZ));
        return 1;
    }

    /** The cast in an arc in front of the player, who stays where they are as the camera. */
    private static final double D = 4.6;

    private static int gatheringHere(ServerPlayer p) {
        ServerLevel level = p.serverLevel();
        clearSpawned(level);
        double yawR = Math.toRadians(p.getYRot());
        double fx = -Math.sin(yawR), fz = Math.cos(yawR);
        double camX = p.getX(), camZ = p.getZ();
        double gx = camX + fx * D, gz = camZ + fz * D;
        double a = Math.atan2(-fz, -fx);                                      // from the group toward the camera
        List<String> names = new ArrayList<>(CAST.keySet());
        names.remove("cinderpelt");
        int n = names.size();
        for (int i = 0; i < n; i++) {
            double t = (i - (n - 1) / 2.0) / ((n - 1) / 2.0);
            double ang = a + Math.PI + t * Math.toRadians(70);
            double r = 1.9 + (i % 2) * 0.7;
            double x = gx + Math.cos(ang) * r, z = gz + Math.sin(ang) * r;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(x), (int) Math.floor(z));
            float yaw = (float) Math.toDegrees(Math.atan2(-(camX - x), camZ - z)) + (float) (t * -15);
            spawn(level, spec(names.get(i), FINAL_OVERRIDES.getOrDefault(names.get(i), "")), x, y, z, yaw);
        }
        int gy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(camX), (int) Math.floor(camZ));
        int ty = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(gx), (int) Math.floor(gz));
        double eye = gy + 0.85;
        float pitch = (float) -Math.toDegrees(Math.atan2(ty + 0.3 - eye, D));
        p.teleportTo(level, camX, eye - p.getEyeHeight(), camZ, p.getYRot(), pitch);
        QaCommands.note(p, "gathering", String.format(Locale.ROOT, "%d cats in front of the camera at %.0f %.0f", n, gx, gz));
        return 1;
    }

    /** The coats chosen from the store suite's casting sweep (2026-10-13), for the gathering pictures. */
    static final Map<String, String> FINAL_OVERRIDES = Map.of(
            "graystripe", "ag=A-A,tab=Mc-Mc,tabv=0",
            "yellowfang", "dil=d-d,silver=I-i,silverv=1",
            "ravenpaw", "whitev=8",
            "spottedleaf", "torv=0");

    static Map<String, String> spec(String who, String overrides) {
        String base = CAST.get(who.toLowerCase(Locale.ROOT));
        if (base == null) return null;
        Map<String, String> s = new LinkedHashMap<>();
        for (String part : (base + "," + overrides).split("[,\\s]+")) {
            int eq = part.indexOf('=');
            if (eq > 0) s.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
        }
        return s;
    }

    static WCatEntity spawn(ServerLevel level, Map<String, String> s, double x, double y, double z, float yaw) {
        var type = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse("warriorcats_events:warrior_cat"));
        Entity e = type.create(level);
        if (!(e instanceof WCatEntity cat)) return null;
        cat.moveTo(x, y, z, yaw, 0);
        cat.setYBodyRot(yaw);
        cat.setYHeadRot(yaw);
        cat.setNoAi(true);
        cat.setPersistenceRequired();
        cat.setGender(Integer.parseInt(s.getOrDefault("g", "0")));
        cat.setAgeInMoons(12f);
        WCGenetics g = new WCGenetics();
        String fur = s.getOrDefault("fur", "s");
        String furAll = fur + "-" + fur;
        g.chestFur = s.containsKey("furchest") ? both(s.get("furchest")) : furAll;
        g.bellyFur = furAll;
        g.legsFur = furAll;
        g.headFur = s.containsKey("furhead") ? both(s.get("furhead")) : furAll;
        g.cheekFur = s.containsKey("furcheek") ? both(s.get("furcheek")) : furAll;
        g.backFur = furAll;
        g.tailFur = s.containsKey("furtail") ? both(s.get("furtail")) : furAll;
        g.bobtail = "B-B";
        g.base = s.get("base");
        g.orangeBase = s.get("orange");
        g.whiteRatio = s.get("white");
        g.albino = "C-C";
        g.dilute = s.get("dil");
        g.agouti = s.get("ag");
        g.tabbyStripes = s.get("tab");
        g.eyesAnomaly = "H-H";
        g.silver = s.get("silver");
        g.chimeraGene = "C-C";
        cat.getGeneticsModule().setGenetics(g);
        WCGenetics.GeneticalVariants v = new WCGenetics.GeneticalVariants();
        v.eyeColorLeft = s.getOrDefault("eye", "yellow");
        v.eyeColorRight = v.eyeColorLeft;
        v.leftEyeVar = num(s, "eyev");
        v.rightEyeVar = v.leftEyeVar;
        v.orangeVar = num(s, "torv");
        v.whiteVar = num(s, "whitev");
        v.tabbyVar = num(s, "tabv");
        v.silverVar = num(s, "silverv");
        v.albinoVar = 0;
        v.size = Float.parseFloat(s.getOrDefault("size", "1.0"));
        v.scars = 0;
        v.rufousingVariant = num(s, "ruf");
        v.blueRufousingVariant = num(s, "bruf");
        v.noise = num(s, "noise");
        v.skin_color = num(s, "skin");
        cat.getGeneticsModule().setGeneticalVariants(v);
        cat.setOnGeneticalSkin(true);
        cat.addTag("qa_portrait");
        level.addFreshEntity(cat);
        SPAWNED.add(cat.getUUID());
        return cat;
    }

    private static String both(String allele) {
        return allele + "-" + allele;
    }

    private static int num(Map<String, String> s, String k) {
        return Integer.parseInt(s.getOrDefault(k, "0"));
    }

    private static void clearSpawned(ServerLevel level) {
        for (UUID id : SPAWNED) {
            Entity e = level.getEntity(id);
            if (e != null) e.discard();
        }
        SPAWNED.clear();
    }

    /** Wild or Clan cats that wandered in (or a previous portrait): out of the shot. */
    private static void clearCats(ServerLevel level, BlockPos at, int r) {
        for (WCatEntity cat : level.getEntitiesOfClass(WCatEntity.class, new AABB(at).inflate(r))) cat.discard();
        LOG.info("[QA] NOTE * - cleared cats near {}", at.toShortString());
    }
}
