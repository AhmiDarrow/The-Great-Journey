package com.ninjacat.greatjourney.clanlands.life.campaign;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.ninjacat.greatjourney.clanlands.layout.Landmark;
import com.ninjacat.greatjourney.clanlands.layout.Sample;
import com.ninjacat.greatjourney.clanlands.layout.Zone;
import com.ninjacat.greatjourney.clanlands.layout.Sectors.Clan;
import com.ninjacat.greatjourney.clanlands.life.BookClans.Info;
import com.ninjacat.greatjourney.clanlands.life.ChunkWait;
import com.ninjacat.greatjourney.clanlands.life.Dialog;
import com.ninjacat.greatjourney.clanlands.life.Dialog.Choice;
import com.ninjacat.greatjourney.clanlands.life.Dialog.Line;
import com.ninjacat.greatjourney.clanlands.life.Homeland;
import com.ninjacat.greatjourney.clanlands.life.Joining;
import com.ninjacat.greatjourney.clanlands.life.Journey;
import com.ninjacat.greatjourney.clanlands.life.LifeData;
import com.ninjacat.greatjourney.clanlands.life.Mode;
import com.ninjacat.greatjourney.clanlands.life.Starts;
import com.ninjacat.greatjourney.clanlands.life.dream.Dream;
import com.ninjacat.greatjourney.clanlands.life.dream.DreamClearing;
import com.ninjacat.greatjourney.clanlands.life.quests.Errands;
import com.ninjacat.greatjourney.clanlands.life.quests.QuestLog;
import com.ninjacat.greatjourney.clanlands.life.quests.QuestState;
import com.ninjacat.greatjourney.clanlands.life.quests.StoryTasks;
import com.ninjacat.greatjourney.clanlands.life.rpg.JournalView;
import com.ninjacat.greatjourney.clanlands.life.rpg.Journals;
import com.ninjacat.greatjourney.clanlands.world.ClanWorld;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * The Campaign: the story of the Clans in the books' order.
 * <ol>
 *   <li>Act 1, the old forest: a new cat arrives at Fourtrees on a Gathering night, chooses a Clan and lives as an
 *       apprentice (the apprentice path and duties of the quest engine, in the forest camps).</li>
 *   <li>Act 2, the forest's end: Twoleg monsters fell the forest stretch by stretch; StarClan sends a sign in a dream;
 *       a cat travels to where the sun drowns and hears Midnight; the message goes home.</li>
 *   <li>Act 3, the Great Journey: the Clans leave together, across the Thunderpath, past Twoleg farms, through the
 *       mountains and the Tribe of Rushing Water, until the lake.</li>
 *   <li>Act 4, the lake: the Clans claim their new territories and settle; the Moonpool; the first Gathering on the
 *       island. Then the world goes on as free play.</li>
 * </ol>
 * The acts are the world's (shared by every player, each step happens once); chapters are each player's own, and a
 * player who joins late starts at the chapter that fits where the world's story is.
 */
public final class Campaign {
    private Campaign() {}

    private static final String ROOT = "greatjourney_campaign";
    private static final int STORY = 0xD9C27A, STARCLAN = 0xA8C8FF, MIDNIGHT = 0x9AA0A6, TRIBE = 0xB8A07A;
    private static final long DAY = 24000;
    /** Without a warrior in the world, Act 2 comes after this many days of the old forest. */
    private static final long ACT1_DAYS = 14;
    /** However few stretches have been felled, StarClan's sign comes after this many days of Act 2. */
    private static final long SIGN_DAYS = 3;
    /** How often a cat on a long walk (to the sea, or home with the message) is reminded of the way. */
    private static final long WAY_HINT_TICKS = 20 * 150;
    /** Scene cats (the leaders at the Gathering) vanish after this long. */
    private static final String SCENE_TAG = "greatjourney_scene";

    public static void init(IEventBus modBus) {
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent e) -> start(e.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent e) -> {
            Homeland.setForest(false);
            SceneCats.reset();
        });
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> {
            if (e.getServer().getTickCount() % 20 == 13) tick(e.getServer());
            if (e.getServer().getTickCount() % 100 == 0) SceneCats.tidy(e.getServer());
        });
        NeoForge.EVENT_BUS.addListener(Campaign::chunkLoaded);
        NeoForge.EVENT_BUS.addListener(Campaign::entityJoined);
        // the journal shows the story's chapter first in a Campaign world
        Journals.provider(p -> decorate(p, QuestLog.view(p)));
        // the Clan map marks every quest's place, the Campaign's chapter among them
        com.ninjacat.greatjourney.clanlands.life.rpg.ClanMaps.marks(Campaign::mapMarks);
        com.ninjacat.greatjourney.clanlands.life.rpg.ClanMaps.spots(com.ninjacat.greatjourney.clanlands.life.quests.QuestMarks::spots);
    }

    /** Every quest's place for the Clan map, the Campaign chapter's first. */
    static java.util.List<com.ninjacat.greatjourney.clanlands.net.ClanPayloads.MapMark> mapMarks(ServerPlayer p) {
        return com.ninjacat.greatjourney.clanlands.life.quests.QuestMarks.of(p, chapterMark(p));
    }

    /** Where the Campaign's chapter sends the player, or null. */
    private static com.ninjacat.greatjourney.clanlands.net.ClanPayloads.MapMark chapterMark(ServerPlayer p) {
        ClanWorld w = ClanWorld.current();
        MinecraftServer server = p.getServer();
        if (w != null && server != null && active(server)) {
            Clan c = clanOf(p);
            double[] at = null;
            String name = null;
            switch (chapter(p)) {
                case SUN_DROWN -> { at = w.journey.sunDrownPlace(); name = "Where the sun drowns"; }
                case TELL_CLAN -> {
                    if (c != null) {
                        Landmark camp = Homeland.forestCamp(w, c);
                        at = new double[] {camp.x(), camp.z()};
                        name = "Your Clan's camp: tell them!";
                    }
                }
                case HIGHWAY -> { at = w.journey.highwayCrossing(); name = "Cross the great Thunderpath"; }
                case FARM -> { at = w.journey.twolegFarm(); name = "The Twoleg farms"; }
                case PASS -> { at = w.journey.mountainPass(); name = "The mountain pass"; }
                case TRIBE -> { at = w.journey.tribeCave(); name = "The Tribe's cave"; }
                case LAKE -> { at = w.journey.lakeArrival(); name = "The ridge above the lake"; }
                case LAKE_CAMP -> {
                    if (c != null) {
                        Landmark camp = w.lake.camp(c);
                        at = new double[] {camp.x(), camp.z()};
                        name = "Your new camp";
                    }
                }
                case MOONPOOL -> { at = w.lake.moonpool(); name = "The Moonpool"; }
                case GATHERING -> {
                    Landmark island = w.lake.landmarks().stream().filter(l -> l.kind() == Landmark.Kind.ISLAND_TREE).findFirst().orElse(null);
                    if (island != null) {
                        at = new double[] {island.x(), island.z()};
                        name = "The Gathering island (at night)";
                    }
                }
                default -> { }
            }
            if (at != null) return com.ninjacat.greatjourney.clanlands.net.ClanPayloads.MapMark.at("campaign", name, at[0], at[1]);
        }
        return null;
    }

    private static boolean active(MinecraftServer server) {
        return ClanWorld.current() != null && ClanWorld.isClanWorld(server.overworld()) && Mode.of(server) == Mode.CAMPAIGN;
    }

    private static void start(MinecraftServer server) {
        if (!active(server)) {
            Homeland.setForest(false);
            return;
        }
        CampaignData cd = CampaignData.get(server.overworld());
        ForestClans.found(server, cd);
        Homeland.setForest(cd.clansInForest());
    }

    // --- a new cat

    /**
     * A cat who has just finished making themselves in a Campaign world. First StarClan's dream (the same short,
     * skippable lesson as in Free Mode); it ends on the rim of Fourtrees, where {@link #offerStarts} asks how the
     * cat's story begins. While the Clans are away on the Great Journey there is only one way to begin: walk with them.
     */
    public static void begin(ServerPlayer p) {
        MinecraftServer server = p.getServer();
        if (server == null || !active(server)) {
            Journey.setStage(p, Journey.Stage.PLAYING);
            return;
        }
        CampaignData cd = CampaignData.get(server.overworld());
        ForestClans.found(server, cd);
        setChapter(p, Chapter.CHOOSE);
        if (cd.departed && !cd.lakeFounded) {
            journeyStart(p);
            return;
        }
        Dream.begin(p);
    }

    /** Joining the Clans on the road: they would let a brave cat walk with them. */
    private static void journeyStart(ServerPlayer p) {
        Journey.setStage(p, Journey.Stage.PLAYING);
        setChapter(p, Chapter.CHOOSE);
        double[] at = ClanWorld.current().journey.forestExit();
        go(p, at[0], at[1], q -> Dialog.play(q,
                Line.narrate("The old forest lies torn and empty behind you. Ahead, cats of four Clans are walking together into the unknown."),
                Line.narrate("They are on the Great Journey, looking for a new home. They would let a brave cat walk with them."),
                chooseLine(q, "Which Clan will you walk with?")));
    }

    /**
     * The start choice in a Campaign world (the player woke from the dream at Fourtrees): the Gathering under the full
     * moon, then kittypet, Clan or loner, in the old forest. Once the Clans live at the lake, the same three starts
     * begin there. False when this is not a Campaign world (Free Mode asks its own way).
     */
    public static boolean offerStarts(ServerPlayer p) {
        MinecraftServer server = p.getServer();
        if (server == null || !active(server)) return false;
        CampaignData cd = CampaignData.get(server.overworld());
        setChapter(p, Chapter.CHOOSE);
        if (cd.departed && !cd.lakeFounded) {
            journeyStart(p);
        } else if (cd.lakeFounded) {
            Dialog.play(p,
                    Line.narrate("You wake among broken stumps where a forest once stood. The Clans left it long ago, on a journey still told in every camp."),
                    Line.narrate("Far to the north-west, past the mountains, four Clans live around a great lake now."),
                    startLine(p, "How will your story begin?"));
        } else {
            gathering(p, cd);
        }
        return true;
    }

    /** The three ways to begin; the commands are the same as Free Mode's, and {@link #beginStart} carries them out. */
    public static Line startLine(ServerPlayer p, String question) {
        boolean lake = p.getServer() != null && CampaignData.get(p.getServer().overworld()).lakeFounded;
        return Line.narrate(question).withChoices(
                Choice.of(lake ? "A kittypet by the lake woods" : "A kittypet at the forest's edge", "greatjourney start kittypet"),
                Choice.of(lake ? "Join a Clan of the lake" : "Join one of the four Clans", "greatjourney start clan"),
                Choice.of(lake ? "A loner by the lake" : "A loner by the farm near Highstones", "greatjourney start loner"));
    }

    /** A start chosen in a Campaign world. False if the player is not choosing (or this is not a Campaign world). */
    public static boolean beginStart(ServerPlayer p, Journey.Start start, Info clan) {
        MinecraftServer server = p.getServer();
        if (server == null || !active(server) || Journey.stage(p) != Journey.Stage.CHOOSING) return false;
        CampaignData cd = CampaignData.get(server.overworld());
        ClanWorld w = ClanWorld.current();
        if (cd.departed && !cd.lakeFounded) {
            journeyStart(p);
            return true;
        }
        Dialog.clear(p);
        p.removeEffect(net.minecraft.world.effect.MobEffects.NIGHT_VISION);   // the Gathering's (see faceRock)
        boolean lake = cd.lakeFounded;
        switch (start) {
            case CLAN -> {
                if (clan == null) return false;
                Journey.setStart(p, start);
                Journey.setStage(p, Journey.Stage.PLAYING);
                data(p).putBoolean("journal", true);              // the guide below brings the journal
                Starts.giveGuide(p);
                if (!join(p, clan)) setChapter(p, Chapter.OUTSIDER);
                return true;
            }
            case KITTYPET -> {
                Landmark home = lake ? w.lake.kittypetHome() : w.forest.kittypetHome();
                double x = home.x() + 2, z = home.z() + 7;
                outsider(p, start, x, z);
                go(p, x, z, q -> Dialog.play(q, lake
                        ? List.of(Line.narrate("You are a kittypet, warm and well fed in your Twoleg garden."),
                                Line.narrate("Beyond the fence the woods rustle with strange scents. Wild cats live out there, they say..."),
                                Starts.seekLine("Four Clans of wild cats live around the lake. Which Clan's cats will you seek? Or climb the fence and explore when you are ready.",
                                        "None yet: I'll explore", null))
                        : List.of(Line.narrate("You are a kittypet, warm and well fed in a Twoleg nest at the very edge of the forest."),
                                Line.narrate("Every night you dream of the woods beyond your garden fence: of hunting, and of the wild cats who live there."),
                                Starts.seekLine("ThunderClan's woods begin just past the fence. Which Clan's cats will you seek? Or climb the fence and explore when you are ready.",
                                        "None yet: I'll explore", null))));
            }
            case LONER -> {
                double[] s = lake ? w.lake.lonerSpot() : w.forest.lonerSpot();
                outsider(p, start, s[0], s[1]);
                go(p, s[0], s[1], q -> Dialog.play(q, lake
                        ? List.of(Line.narrate("You are a loner, belonging to no one. The great lake shines before you."),
                                Starts.seekLine("Four Clans live around these shores. Which Clan's cats will you seek? Their patrols may find you anyway if you wander into their land.",
                                        "None yet: I'll walk my own path", null))
                        : List.of(Line.narrate("You are a loner, belonging to no one. Behind you lies a Twoleg farm; ahead, the high moor where WindClan runs."),
                                Starts.seekLine("Four Clans live in the forest beyond. Which Clan's cats will you seek? Their patrols may find you anyway if you wander into their land.",
                                        "None yet: I'll walk my own path", null))));
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    /** A kittypet or a loner: no Clan yet, and a story that waits for a patrol to find them. */
    private static void outsider(ServerPlayer p, Journey.Start start, double x, double z) {
        Journey.setStart(p, start);
        Journey.setStage(p, Journey.Stage.PLAYING);
        setChapter(p, Chapter.OUTSIDER);
        CompoundTag d = data(p);
        d.putDouble("startX", x);
        d.putDouble("startZ", z);
        d.putBoolean("journal", true);
        Starts.giveGuide(p);
    }

    /**
     * Whether a kittypet or loner in a Campaign world may seek a Clan and be guided to it: they have no Clan yet and
     * the Clans have camps (not while they are away on the Great Journey).
     */
    public static boolean maySeek(ServerPlayer p) {
        MinecraftServer server = p.getServer();
        if (server == null || !active(server) || chapter(p) != Chapter.OUTSIDER) return false;
        CampaignData cd = CampaignData.get(server.overworld());
        return !(cd.departed && !cd.lakeFounded);
    }

    /** Whether the player has no Clan yet in a Campaign world (choosing, or a kittypet or loner). */
    public static boolean clanless(ServerPlayer p) {
        Chapter ch = chapter(p);
        return ch == Chapter.CHOOSE || ch == Chapter.OUTSIDER;
    }

    /** A patrol's invitation was accepted ("greatjourney join"): the cat goes with the patrol to its camp. */
    public static boolean acceptInvite(ServerPlayer p, Info clan) {
        return clanless(p) && join(p, clan);
    }

    private static Line chooseLine(ServerPlayer p, String question) {
        List<Choice> choices = new ArrayList<>();
        for (Info i : Info.values()) choices.add(new Choice(i.title, "greatjourney campaign join " + i.key(), i.colour));
        return Line.narrate(question).withChoices(choices.toArray(Choice[]::new));
    }

    /** Act 1's opening: a Gathering at Fourtrees under the full moon, the four leaders on the Great Rock. */
    private static void gathering(ServerPlayer p, CampaignData cd) {
        ServerLevel level = p.serverLevel();
        ClanWorld w = ClanWorld.current();
        LifeData life = LifeData.get(level.getServer().overworld());
        double fx = w.forest.centreX(), fz = w.forest.centreZ();
        if (level.getServer().getPlayerCount() <= 1 && level.dimension() == Level.OVERWORLD) {
            long day = level.getDayTime() / DAY;
            long fullMoon = ((day + 7) / 8) * 8;                    // the next full moon (phase 0 every eight days)
            level.setDayTime(fullMoon * DAY + 14000);
        }
        Landmark ft = w.forest.landmarks().stream().filter(l -> l.kind() == Landmark.Kind.FOURTREES).findFirst().orElse(null);
        double ring = ft == null ? 30 : ft.radius() - 14;
        // each Clan's warriors sit in a gap between two oaks (they stand on the diagonals of the landmark's facing),
        // clear of the trunks and of the limbs overhead, which a cat would otherwise be set down on
        double turn = ft == null ? 0.4 : ft.facing();
        RandomSource r = p.getRandom();
        Clan host = Clan.values()[r.nextInt(Clan.values().length)];
        UUID who = p.getUUID();
        boolean busy = cd.act >= 2;
        Journey.stamp(p, "choose.asked", level.getServer().overworld().getGameTime());   // nothing re-asks while the hollow loads
        // the leaders on the Great Rock and their warriors around the hollow, once the hollow has loaded; the scene
        // opens only then, and hands each leader's line its cat (the hollow can be more than 32 blocks across, and
        // the cats do not exist yet when the player wakes, so looking them up by name would find nobody)
        ChunkWait.whenLoaded(level, fx, fz, (int) (ring * 0.6) + 8, () -> {
            Map<Clan, Entity> leaders = new EnumMap<>(Clan.class);
            int i = 0;
            for (Clan c : Clan.values()) {
                double a = i++ * Math.PI / 2 + turn;
                // the leaders stand on the rock looking out over their Clans; the warriors sit below, facing the rock
                leaders.put(c, SceneCats.spawn(level, life.leader(c), fx + Math.cos(a) * 4, fz + Math.sin(a) * 4, SCENE_TAG,
                        fx + Math.cos(a) * 40, fz + Math.sin(a) * 40, false));
                for (int k = 0; k < 2; k++) {
                    double b = a + (k == 0 ? -0.35 : 0.35);
                    SceneCats.spawn(level, ForestClans.names(w, c).warrior().full(), fx + Math.cos(b) * ring * 0.6, fz + Math.sin(b) * ring * 0.6, SCENE_TAG,
                            fx, fz, true);
                }
            }
            ServerPlayer q = level.getServer().getPlayerList().getPlayer(who);
            if (q == null) return;
            faceRock(q, level, fx, fz);
            Dialog.play(q, gatheringLines(q, life, leaders, host));
            if (busy) {
                Dialog.subtitle(q, Line.narrate("Even here, beneath the oaks, you can hear the distant growl of Twoleg monsters."));
            }
        });
    }

    /**
     * The player's spot at the Gathering: clear of tall flowers and other plants, turned to face the Great Rock (they
     * woke facing whatever way they faced in the dream, which could be the hollow's wall), with a Night Vision effect
     * so the leaders can be seen under the night sky (it ends when the start is chosen).
     */
    private static void faceRock(ServerPlayer q, ServerLevel level, double fx, double fz) {
        BlockPos at = q.blockPosition();
        for (BlockPos b : BlockPos.betweenClosed(at.offset(-1, 0, -1), at.offset(1, 2, 1))) {
            var state = level.getBlockState(b);
            if (!state.isAir() && state.getFluidState().isEmpty() && state.getCollisionShape(level, b).isEmpty()) {
                level.destroyBlock(b, false);                   // a flower, a fern, tall grass: anything a cat walks through
            }
        }
        double dx = fx - q.getX(), dz = fz - q.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        q.teleportTo(level, q.getX(), q.getY(), q.getZ(), yaw, 20);      // looking a little down into the hollow
        q.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.NIGHT_VISION, 20 * 60 * 10, 0, false, false, false));
    }

    /** The Gathering's lines: each leader speaks with their own cat in the portrait ({@code leaders} may lack a Clan). */
    static List<Line> gatheringLines(ServerPlayer p, LifeData life, Map<Clan, Entity> leaders, Clan host) {
        List<Line> lines = new ArrayList<>();
        lines.add(Line.narrate("You wake on the rim of a deep hollow. The full moon rides high over Fourtrees, and below the four great oaks, cats of four Clans sit together in peace."));
        lines.add(Line.narrate("You have never seen so many wild cats. On the Great Rock, four leaders stand against the moonlight."));
        for (Clan c : Clan.values()) {
            lines.add(Line.say(life.leader(c), Info.of(c).colour, switch (c) {
                case THUNDER -> "Prey runs well in ThunderClan's woods, and our apprentices grow strong.";
                case WIND -> "The wind on the moor is cold, but WindClan's warriors are swift as ever.";
                case RIVER -> "The river is full of fish, and RiverClan's kits have learned to swim.";
                case SHADOW -> "ShadowClan hunts well in the pines. Our borders are strong.";
            }).speakerEntity(leaders.get(c)));
        }
        lines.add(Line.say(life.leader(host), Info.of(host).colour,
                "A stranger watches from the edge of the hollow. Under the truce of the full moon, no cat will harm you here, young one.").speakerEntity(leaders.get(host)));
        lines.add(Line.narrate("The Gathering ends. The Clans slip away into the night, each toward its own territory. And you?"));
        lines.add(startLine(p, "How will your story begin?"));
        return lines;
    }

    /** The player joins a Clan: they go with it to its camp, wherever the Clans are now, and are made an apprentice. */
    static boolean join(ServerPlayer p, Info clan) {
        MinecraftServer server = p.getServer();
        if (server == null || !active(server)) return false;
        if (Joining.clanOf(p) != null) {
            p.sendSystemMessage(Component.literal("You already belong to a Clan."));
            return false;
        }
        CampaignData cd = CampaignData.get(server.overworld());
        ClanWorld w = ClanWorld.current();
        var id = LifeData.get(server.overworld()).clanId(clan.clan);
        if (id == null || net.snowteb.warriorcats_events.clan.ClanData.get(server.overworld()).getClan(id) == null) {
            p.sendSystemMessage(Component.literal(clan.title + " does not live in this world."));
            return false;
        }
        Dialog.clear(p);
        Journey.clearOffer(p);
        // the chapter is set at once, so nothing asks the cat to choose again while it travels to its camp
        Chapter start = Chapter.startFor(cd.act, cd.signGiven, cd.departed, cd.lakeFounded);
        setChapter(p, start);
        double[] at;
        if (cd.departed && !cd.lakeFounded) {
            at = w.journey.forestExit();
        } else {
            Landmark camp = cd.lakeFounded ? w.lake.camp(clan.clan) : Homeland.forestCamp(w, clan.clan);
            at = new double[] {camp.x() + 1, camp.z() + 1};
        }
        go(p, at[0], at[1], q -> {
            if (!Joining.join(q, clan)) {
                setChapter(q, Chapter.OUTSIDER);
                return;
            }
            if (start == Chapter.HIGHWAY) {
                ForestClans.escort(q.serverLevel(), q, clan.clan, cd);
                data(q).putBoolean("escorted", true);
            }
            giveJournal(q);
            Journals.push(q);
        });
        return true;
    }

    // --- the world's story and each player's chapter, once a second

    private static void tick(MinecraftServer server) {
        if (!active(server)) return;
        ServerLevel ow = server.overworld();
        CampaignData cd = CampaignData.get(ow);
        if (!cd.forestFounded) ForestClans.found(server, cd);
        long now = ow.getGameTime();
        if (cd.actStartedAt < 0) {
            cd.actStartedAt = now;
            cd.setDirty();
        }
        ClanWorld w = ClanWorld.current();
        List<ServerPlayer> players = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (Journey.stage(p) == Journey.Stage.PLAYING) players.add(p);
        }

        // Act 1 -> 2: when a cat of the world earns a warrior name, or after a while in the old forest
        if (cd.act == 1) {
            boolean warrior = players.stream().anyMatch(p -> Joining.clanOf(p) != null && QuestState.storyStep(p) >= QuestState.WARRIOR);
            boolean longEnough = now - cd.actStartedAt > ACT1_DAYS * DAY && players.stream().anyMatch(p -> Joining.clanOf(p) != null);
            if (warrior || longEnough) {
                cd.advance(2, now);
                for (ServerPlayer p : players) {
                    Dialog.play(p, Line.narrate("A strange, harsh roar echoes through the forest. Birds burst from the trees."),
                            Line.narrate("Twoleg monsters have come into the forest, and they are tearing down the trees."));
                }
            }
        }
        // Act 2: the forest falls, stretch by stretch, near the cats who live in it; then StarClan's sign
        if (cd.act == 2 && !cd.departed && now % 400 < 20) {
            List<ServerPlayer> inForest = players.stream().filter(p -> p.level() == ow && w.forest.covers(p.getX(), p.getZ())).toList();
            if (Felling.fellNear(ow, inForest, ow.getRandom())) {
                cd.felled++;
                cd.setDirty();
                for (ServerPlayer p : inForest) {
                    Dialog.subtitle(p, Line.narrate("Somewhere in the forest, Twoleg monsters growl. Another stretch of trees has fallen."));
                }
                if (cd.felled >= Felling.BEFORE_SIGN && !cd.signGiven) {
                    cd.signGiven = true;
                    cd.setDirty();
                }
            }
            // the felling only happens near cats in the forest; a cat who wanders off still gets the sign in time
            if (!cd.signGiven && now - cd.actStartedAt > SIGN_DAYS * DAY) {
                cd.signGiven = true;
                cd.setDirty();
            }
        }

        for (ServerPlayer p : players) {
            if (data(p).getBoolean("inSign")) {
                signDreamTick(p, now);
                continue;
            }
            if (p.level().dimension() == DreamClearing.DREAM) continue;
            Chapter now2 = chapter(p);
            Chapter caught = Chapter.catchUp(now2, cd.act, cd.signGiven, cd.midnightMet, cd.departed, cd.lakeFounded);
            if (caught != now2) {
                setChapter(p, caught);
                announce(p, caught);
                now2 = caught;
            }
            progress(p, now2, cd, w);
        }
    }

    /** What happens for one player in their current chapter. */
    private static void progress(ServerPlayer p, Chapter ch, CampaignData cd, ClanWorld w) {
        if (Dialog.busy(p)) return;
        errands(p, ch, cd, w);
        if (Dialog.busy(p)) return;
        CompoundTag d = data(p);
        long now = p.level().getGameTime();
        switch (ch) {
            case CHOOSE -> {
                if (Joining.clanOf(p) != null) {
                    setChapter(p, Chapter.startFor(cd.act, cd.signGiven, cd.departed, cd.lakeFounded));
                } else if (now - d.getLong("asked") > 2400) {
                    d.putLong("asked", now);
                    Dialog.play(p, chooseLine(p, "The Clans are waiting for your answer. Which Clan will you join?"));
                }
            }
            case OUTSIDER -> Outsiders.tick(p, cd, w, d, now);
            case APPRENTICE -> {
                if (QuestState.storyStep(p) >= QuestState.WARRIOR) setChapter(p, Chapter.WARRIOR_LIFE);
            }
            case DARK_TIMES -> {
                if (cd.signGiven && Joining.clanOf(p) != null && !d.getBoolean("signDone")) sign(p);
            }
            case SUN_DROWN -> {
                if (atSunDrown(p, w)) {
                    cd.midnightMet = true;
                    cd.setDirty();
                    setChapter(p, Chapter.TELL_CLAN);
                    midnight(p, near(p, w.journey.midnightSett(), 60));
                } else {
                    wayHint(p, d, now, w.journey.sunDrownPlace(), "The great water where the sun drowns");
                }
            }
            case TELL_CLAN -> {
                Clan c = clanOf(p);
                if (c == null) return;
                Landmark camp = Homeland.forestCamp(w, c);
                if (near(p, new double[] {camp.x(), camp.z()}, camp.radius() + 10)) {
                    depart(p, c, cd);
                } else {
                    wayHint(p, d, now, new double[] {camp.x(), camp.z()}, "Your Clan's camp");
                }
            }
            case HIGHWAY -> {
                if (!d.getBoolean("escorted") && clanOf(p) != null) {
                    ForestClans.escort(p.serverLevel(), p, clanOf(p), cd);
                    d.putBoolean("escorted", true);
                }
                reach(p, w.journey.highwayCrossing(), 40, Chapter.FARM,
                        Line.narrate("The Thunderpath is wider than any you have seen. Monsters roar past in both directions, faster than a diving hawk."),
                        Line.narrate("Wait... wait... now! You race across with your Clanmates, fur flattened by the wind of a passing monster."),
                        Line.narrate("Safe on the far side, the Clans count their cats. Everyone made it."));
            }
            case FARM -> reach(p, w.journey.twolegFarm(), 70, Chapter.PASS,
                    Line.narrate("Twoleg farms spread out before you, fenced and full of strange smells: sheep, dogs, hay."),
                    Line.narrate("A barn gives the Clans shelter from the rain for the night. Tomorrow, the mountains."));
            case PASS -> reach(p, w.journey.mountainPass(), 70, Chapter.TRIBE,
                    Line.narrate("The mountains are cold and bare. Wind howls between the rocks and there is little prey."),
                    Line.narrate("A shadow sweeps over the snow: an eagle! The Clans press close together under an overhang until it is gone."));
            case TRIBE -> {
                boolean healer = healer(p);
                if (reach(p, w.journey.tribeCave(), 35, Chapter.LAKE,
                        Line.narrate("Behind a roaring waterfall you find a cave, and in it, cats who live among the peaks: the Tribe of Rushing Water."),
                        Line.say("A Tribe healer", TRIBE, "Welcome, travellers. Rest in our cave and share our prey. The mountains are hard for those who do not know them."),
                        healer ? Line.say("A Tribe healer", TRIBE, "You carry the scent of herbs. You are a healer too? Take these: juniper grows high among the peaks, and it keeps the cold out of a cat's chest.")
                                : Line.say("A Tribe cave-guard", TRIBE, "You walk like one of our cave-guards, stranger. If an eagle comes for you on the way down, strike first and strike hard."),
                        Line.say("A Tribe healer", TRIBE, "Go on when you are rested. Your ancestors have shown you the way. We have seen it in the stars.")) && healer) {
                    giveItem(p, "warriorcats_events:juniper_berries", 3);
                }
            }
            case LAKE -> {
                if (near(p, w.journey.lakeArrival(), 70)) {
                    setChapter(p, Chapter.LAKE_CAMP);
                    if (!cd.lakeFounded) ForestClans.settleAtLake(p.getServer(), cd);
                    cd.advance(4, now);
                    Dialog.play(p,
                            Line.narrate("You come over the last ridge, and stop. Below you lies a great lake, silver under the sky."),
                            Line.narrate("Woods and moors and marshes ring its shores. An island sits in the water."),
                            Line.narrate("This is it. This is the place StarClan showed you. The Clans have come home."));
                }
            }
            case LAKE_CAMP -> {
                Clan c = clanOf(p);
                if (c == null) return;
                Landmark camp = w.lake.camp(c);
                reach(p, new double[] {camp.x(), camp.z()}, camp.radius() + 10, Chapter.MOONPOOL,
                        Line.narrate("Your Clan has chosen its new camp. Clanmates are already gathering moss for new dens."),
                        Line.narrate("It smells strange and new. It will smell like home soon enough."));
            }
            case MOONPOOL -> {
                if (healer(p)) {
                    reach(p, w.lake.moonpool(), 30, Chapter.GATHERING,
                            Line.narrate("You follow a stream up into the hills, and find a hollow of stone. A waterfall spills into a small, still pool."),
                            Line.narrate("The stars shine in the water, brighter than they should. A sweet scent drifts over it, and for a heartbeat you see a dappled pelt among the stars."),
                            Line.say("Spottedleaf", STARCLAN, "You found it, " + Joining.catName(p) + ". This is where you will come at every half moon, you and the other medicine cats, to share tongues with us."),
                            Line.say("Spottedleaf", STARCLAN, "Bring your Clan's troubles here, and we will listen. We are never far."),
                            Line.narrate("This is the Moonpool. Your Clan's medicine cats will dream here for all the moons to come."));
                } else {
                    reach(p, w.lake.moonpool(), 30, Chapter.GATHERING,
                            Line.narrate("You follow a stream up into the hills, and find a hollow of stone. A waterfall spills into a small, still pool."),
                            Line.narrate("The stars shine in the water, brighter than they should. You feel warm, as if old friends were watching."),
                            Line.narrate("This is the Moonpool, where medicine cats will share tongues with StarClan."));
                }
            }
            case GATHERING -> {
                Landmark island = w.lake.landmarks().stream().filter(l -> l.kind() == Landmark.Kind.ISLAND_TREE).findFirst().orElse(null);
                if (island != null && p.level().isNight() && near(p, new double[] {island.x(), island.z()}, 45)) {
                    setChapter(p, Chapter.COMPLETE);
                    if (!cd.complete) {
                        cd.complete = true;
                        cd.setDirty();
                    }
                    LifeData life = LifeData.get(p.getServer().overworld());
                    // the leader steps out beside the player for the speech (nobody stands on the island otherwise)
                    Entity leader = SceneCats.spawn(p.serverLevel(), life.leader(ownOr(p, Clan.THUNDER)), p.getX() + 4, p.getZ(), SCENE_TAG,
                            p.getX(), p.getZ(), false);
                    Dialog.play(p,
                            Line.narrate("The moon is full. Across the fallen-tree bridge they come, cats of all four Clans, to the first Gathering at the lake."),
                            Line.say(life.leader(ownOr(p, Clan.THUNDER)), Info.of(ownOr(p, Clan.THUNDER)).colour, "We have come a long way together. Now each Clan has its own home again, and here we will meet in peace, as we always have.").speakerEntity(leader),
                            Line.narrate(healer(p)
                                    ? "At the foot of the great tree the medicine cats of all four Clans sit together, as they always have, sharing news of the sick and of the herbs in their new lands."
                                    : "Beneath the great tree, warriors of all four Clans share tongues and trade stories of the journey: the Thunderpath, the mountains, the eagles."),
                            Line.narrate("The story of the Great Journey is told. Your own story goes on. Live it as you choose."));
                }
            }
            default -> { }
        }
    }

    private static boolean reach(ServerPlayer p, double[] at, double radius, Chapter next, Line... scene) {
        if (!near(p, at, radius)) return false;
        setChapter(p, next);
        Dialog.play(p, scene);
        Journals.push(p);
        return true;
    }

    // --- the errands along the way, a warrior's or a medicine cat's (StoryTasks)

    private static boolean healer(ServerPlayer p) {
        return QuestState.path(p) == QuestState.Path.MEDICINE;
    }

    /** Which of the four errands this chapter brings (null for none). */
    private static String errandFor(Chapter ch, boolean healer) {
        String stage = switch (ch) {
            case DARK_TIMES, SUN_DROWN, TELL_CLAN -> "dark";
            case HIGHWAY, FARM -> "journey";
            case PASS, TRIBE -> "pass";
            case LAKE_CAMP, MOONPOOL, GATHERING, COMPLETE -> "lake";
            default -> null;
        };
        if (stage == null) return null;
        return stage + "." + switch (stage) {
            case "dark" -> healer ? "herbs" : "hunt";
            case "journey" -> healer ? "herbs" : "hunt";
            case "pass" -> healer ? "tend" : "scout";
            default -> healer ? "den" : "borders";
        };
    }

    /**
     * Gives the chapter's errand once, if no other is under way; the forest's errand is set aside when the Clans
     * leave. Nothing in the story waits on an errand.
     */
    private static void errands(ServerPlayer p, Chapter ch, CampaignData cd, ClanWorld w) {
        Clan c = clanOf(p);
        if (c == null) return;
        if (cd.departed && Errands.current(p).startsWith("dark.")) {
            Errands.drop(p, "There is no time for that now: the Clans are leaving the forest.");
        }
        String id = errandFor(ch, healer(p));
        if (id == null || Errands.busy(p) || Errands.given(p, id)) return;
        StoryTasks.Def def = switch (id.substring(0, id.indexOf('.'))) {
            case "dark" -> StoryTasks.forestEnd(p, Homeland.forestCamp(w, c));
            case "journey" -> StoryTasks.journeyStart(p);
            case "pass" -> StoryTasks.mountains(p, w.journey.mountainPass(), w.journey.tribeCave(), companions(p, c));
            default -> cd.lakeFounded ? StoryTasks.newHome(p, c, w.lake.camp(c)) : null;
        };
        if (def != null) def.give(p);
    }

    /** The Clanmates walking with the player on the Great Journey. */
    private static List<net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity> companions(ServerPlayer p, Clan c) {
        UUID id = LifeData.get(p.getServer().overworld()).clanId(c);
        return p.serverLevel().getEntitiesOfClass(net.snowteb.warriorcats_events.entity.custom.wcat.WCatEntity.class, p.getBoundingBox().inflate(32),
                cat -> cat.isAlive() && id != null && id.equals(cat.getClanUUID()) && cat.getTags().contains(ForestClans.ESCORT_TAG));
    }

    private static void giveItem(ServerPlayer p, String id, int n) {
        var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse(id));
        if (item == net.minecraft.world.item.Items.AIR) return;
        var stack = new net.minecraft.world.item.ItemStack(item, n);
        if (!p.getInventory().add(stack)) p.drop(stack, false);
    }

    /** StarClan's sign: a dream under the stars, then back to the forest with a task. */
    private static void sign(ServerPlayer p) {
        CompoundTag d = data(p);
        d.putBoolean("signDone", true);
        ServerLevel dream = p.getServer().getLevel(DreamClearing.DREAM);
        if (dream != null) {
            d.putDouble("returnX", p.getX());
            d.putDouble("returnY", p.getY());
            d.putDouble("returnZ", p.getZ());
            d.putBoolean("inSign", true);
            d.putLong("signAsked", p.level().getGameTime());
            DreamClearing.ensure(dream);
            BlockPos a = DreamClearing.ARRIVE;
            p.teleportTo(dream, a.getX() + 0.5, a.getY(), a.getZ() + 0.5, 180, 0);
        }
        setChapter(p, Chapter.SUN_DROWN);
        Dialog.play(p,
                Line.narrate("That night you dream. You stand in a clearing under a sky crowded with stars."),
                Line.say("Bluestar", STARCLAN, "Dark times have come to the forest, " + Joining.catName(p) + ". The Twolegs will not stop."),
                Line.say("Spottedleaf", STARCLAN, "Go to where the sun drowns. Far to the " + seaward() + ", where the land ends in water. Listen to the one you meet there."),
                Line.say("Spottedleaf", STARCLAN, healer(p)
                        ? "You are a medicine cat, " + Joining.catName(p) + ". We speak to you so that you can speak to your Clan, and they will listen."
                        : "Your Clan will need every warrior's strength, " + Joining.catName(p) + ". Be ready."),
                Line.say("Bluestar", STARCLAN, "Go with courage. StarClan walks with you.").withChoices(Choice.of("Wake up", "greatjourney campaign wake")));
    }

    /**
     * A player in StarClan's sign whose window is gone (dismissed, or they logged out mid-dream) is offered the way
     * back again; one who left the dream another way (died, an operator's teleport) simply wakes.
     */
    private static void signDreamTick(ServerPlayer p, long now) {
        CompoundTag d = data(p);
        if (p.level().dimension() != DreamClearing.DREAM) {
            d.putBoolean("inSign", false);
            Journals.push(p);
            return;
        }
        if (Dialog.busy(p) || now - d.getLong("signAsked") < 400) return;
        d.putLong("signAsked", now);
        Dialog.play(p, Line.say("Bluestar", STARCLAN, "The stars are fading, " + Joining.catName(p) + ". It is time to go back to your Clan.")
                .withChoices(Choice.of("Wake up", "greatjourney campaign wake")));
    }

    static void wake(ServerPlayer p) {
        CompoundTag d = data(p);
        if (!d.getBoolean("inSign")) return;
        d.putBoolean("inSign", false);
        ServerLevel ow = p.getServer().overworld();
        p.teleportTo(ow, d.getDouble("returnX"), d.getDouble("returnY"), d.getDouble("returnZ"), p.getYRot(), 0);
        Dialog.play(p, Line.narrate("You wake in your nest with the dream still bright in your mind. Where the sun drowns... far to the " + seaward() + "."));
        Journals.push(p);
    }

    /**
     * Whether the player has reached the sun-drown-place: anywhere on its beach or in its sea, not only the one spot
     * by Midnight's sett (the bay is hundreds of blocks wide, and a cat who walks to the water has done what StarClan
     * asked).
     */
    static boolean atSunDrown(ServerPlayer p, ClanWorld w) {
        if (p.level().dimension() != Level.OVERWORLD) return false;
        if (near(p, w.journey.sunDrownPlace(), 40) || near(p, w.journey.midnightSett(), 40)) return true;
        Sample s = w.journey.sample(p.getX(), p.getZ());
        return s.weight() > 0.5 && (s.zone() == Zone.SUN_DROWN_SHORE || s.zone() == Zone.SUN_DROWN_SEA);
    }

    /** The direction of the sun-drown-place from the old forest, in words ("west", "south-west"...). */
    private static String seaward() {
        ClanWorld w = ClanWorld.current();
        if (w == null) return "west";
        double[] v = w.journey.seaward();
        return compass(v[0], v[1]);
    }

    /** A compass direction in words for a world-space vector (north is -z, east is +x). */
    static String compass(double dx, double dz) {
        String[] names = {"east", "south-east", "south", "south-west", "west", "north-west", "north", "north-east"};
        int i = (int) Math.round(Math.atan2(dz, dx) / (Math.PI / 4));
        return names[Math.floorMod(i, names.length)];
    }

    /** "about 2,300 blocks to the south-west" from the player to {@code at}. */
    private static String wayTo(ServerPlayer p, double[] at) {
        double dx = at[0] - p.getX(), dz = at[1] - p.getZ();
        long dist = Math.max(50, Math.round(Math.hypot(dx, dz) / 50.0) * 50);
        return String.format(java.util.Locale.ROOT, "about %,d blocks to the %s", dist, compass(dx, dz));
    }

    /** Every so often on a long walk, a reminder of which way to go. */
    private static void wayHint(ServerPlayer p, CompoundTag d, long now, double[] at, String what) {
        if (p.level().dimension() != Level.OVERWORLD || now - d.getLong("wayHint") < WAY_HINT_TICKS) return;
        d.putLong("wayHint", now);
        Dialog.subtitle(p, Line.narrate(what + " lies " + wayTo(p, at) + "."));
    }

    /** At the sun-drown-place: Midnight the badger, who knows the stars' message. */
    private static void midnight(ServerPlayer p, boolean atSett) {
        Dialog.play(p,
                Line.narrate("The land ends in sand and roaring water that stretches to the edge of the sky. The sun sinks into it, red as fire."),
                Line.narrate(atSett
                        ? "A badger shuffles out of a sett in the dunes. She does not attack. She looks at you with bright, wise eyes."
                        : "A badger comes shuffling along the dunes toward you. She does not attack. She looks at you with bright, wise eyes."),
                Line.say("Midnight", MIDNIGHT, "Come you have, as the stars said. Midnight I am called."),
                Line.say("Midnight", MIDNIGHT, "Your forest is dying. Twolegs will destroy all of it. You must leave, all Clans together, or all will die."),
                Line.say("Midnight", MIDNIGHT, "A new home waits, past the mountains. Where the stars shine on still water. Go now. Tell your Clans."));
        Journals.push(p);
    }

    /** The message reaches a camp: the Clans agree to leave, all together. */
    private static void depart(ServerPlayer p, Clan c, CampaignData cd) {
        LifeData life = LifeData.get(p.getServer().overworld());
        Info info = Info.of(c);
        Dialog.play(p,
                Line.say(life.leader(c), info.colour, "Midnight said this? Then StarClan has spoken. We cannot stay here any longer."),
                Line.say(life.leader(c), info.colour, "We will send word to the other Clans. We leave together, all four Clans, or not at all."),
                Line.narrate("Before dawn the Clans gather at the edge of the ruined forest, kits and elders and warriors side by side. The Great Journey begins."));
        ForestClans.depart(p.getServer(), cd);
        cd.advance(3, p.level().getGameTime());
        setChapter(p, Chapter.HIGHWAY);
        ForestClans.escort(p.serverLevel(), p, c, cd);
        data(p).putBoolean("escorted", true);
    }

    private static void announce(ServerPlayer p, Chapter ch) {
        switch (ch) {
            case DARK_TIMES -> Dialog.play(p, Line.narrate("Twoleg monsters have come into the forest. Trees are falling. Your Clan is afraid."));
            case TELL_CLAN -> Dialog.subtitle(p, Line.narrate("Word has come back from where the sun drowns: the Clans must leave the forest."));
            case HIGHWAY -> Dialog.play(p, Line.narrate("The Clans are leaving the forest, together! Follow them on the Great Journey."));
            case LAKE_CAMP -> Dialog.play(p, Line.narrate("Word comes down the trail: the Clans have found their new home, a great lake past the mountains!"));
            default -> { }
        }
        Journals.push(p);
    }

    // --- loading, leaving

    private static void chunkLoaded(ChunkEvent.Load e) {
        if (!(e.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        if (Mode.of(level.getServer()) != Mode.CAMPAIGN || ClanWorld.current() == null) return;
        ForestClans.populateIfDue(level, e.getChunk().getPos(), CampaignData.get(level));
    }

    private static void entityJoined(EntityJoinLevelEvent e) {
        if (!(e.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD || ClanWorld.current() == null) return;
        if (e.getEntity().getTags().contains(SCENE_TAG) && !e.loadedFromDisk()) return;
        if (e.getEntity().getTags().contains(SCENE_TAG)) {
            e.setCanceled(true);                                    // scene cats never come back after a reload
            return;
        }
        if (Mode.of(level.getServer()) != Mode.CAMPAIGN) return;
        CampaignData cd = CampaignData.get(level);
        if (cd.departed && ForestClans.leftBehind(e.getEntity(), ClanWorld.current(), LifeData.get(level))) e.setCanceled(true);
        // a travelling companion whose chunk was unloaded when the Clans settled has already arrived with its Clan
        if (cd.lakeFounded && e.loadedFromDisk() && e.getEntity().getTags().contains(ForestClans.ESCORT_TAG)) e.setCanceled(true);
    }

    // --- per-player data and the journal

    private static CompoundTag data(ServerPlayer p) {
        CompoundTag root = p.getPersistentData();
        CompoundTag persisted = root.getCompound(ServerPlayer.PERSISTED_NBT_TAG);
        if (!root.contains(ServerPlayer.PERSISTED_NBT_TAG)) root.put(ServerPlayer.PERSISTED_NBT_TAG, persisted);
        CompoundTag mine = persisted.getCompound(ROOT);
        if (!persisted.contains(ROOT)) persisted.put(ROOT, mine);
        return mine;
    }

    public static Chapter chapter(ServerPlayer p) {
        return Chapter.parse(data(p).getString("chapter"));
    }

    static void setChapter(ServerPlayer p, Chapter c) {
        data(p).putString("chapter", c.name());
        Journals.push(p);
    }

    /** The player's own Clan, or the fallback for a cat without one. */
    private static Clan ownOr(ServerPlayer p, Clan fallback) {
        Clan c = clanOf(p);
        return c == null ? fallback : c;
    }

    private static Clan clanOf(ServerPlayer p) {
        var wc = Joining.clanOf(p);
        return wc == null ? null : LifeData.get(p.getServer().overworld()).clanOf(wc.clanUUID);
    }

    private static boolean near(ServerPlayer p, double[] at, double r) {
        return p.level().dimension() == Level.OVERWORLD && Math.hypot(p.getX() - at[0], p.getZ() - at[1]) <= r;
    }

    /** Takes the player to x, z once the land there has loaded (without stalling the server), then runs {@code then}. */
    private static void go(ServerPlayer p, double x, double z, java.util.function.Consumer<ServerPlayer> then) {
        Starts.arrive(p, p.getServer().overworld(), x, z, then);
    }

    private static void giveJournal(ServerPlayer p) {
        data(p).putBoolean("journal", true);
        com.ninjacat.greatjourney.clanlands.life.Keepsakes.ensure(p);
    }

    /** In a Campaign world the journal leads with the story's current chapter. */
    static JournalView decorate(ServerPlayer p, JournalView v) {
        MinecraftServer server = p.getServer();
        if (server == null || Mode.of(server) != Mode.CAMPAIGN) return v;
        Chapter ch = chapter(p);
        boolean healer = healer(p);
        String goal = ch == Chapter.OUTSIDER ? Outsiders.goal(p) : ch.goal(healer);
        ClanWorld w = ClanWorld.current();
        if (w != null && p.level().dimension() == Level.OVERWORLD) {
            if (ch == Chapter.SUN_DROWN) {
                goal = "StarClan sent a sign: go to where the sun drowns, and listen to what you are told there. The sea lies "
                        + wayTo(p, w.journey.sunDrownPlace()) + ".";
            } else if (ch == Chapter.TELL_CLAN && clanOf(p) != null) {
                Landmark camp = Homeland.forestCamp(w, clanOf(p));
                goal = ch.goal(healer) + " Your camp lies " + wayTo(p, new double[] {camp.x(), camp.z()}) + ".";
            }
        }
        List<JournalView.Quest> quests = new ArrayList<>();
        quests.add(new JournalView.Quest("campaign", "Act " + ch.act + ": " + ch.title(healer), "The story", goal,
                List.of(new JournalView.Objective(goal, ch == Chapter.COMPLETE ? 1 : 0, 1)), true, ch == Chapter.COMPLETE));
        quests.addAll(v.quests());
        boolean marked = false, followed = false;
        for (var m : com.ninjacat.greatjourney.clanlands.life.rpg.ClanMaps.marksFor(p)) {
            if (!m.quest().equals("campaign")) continue;
            marked = true;
            followed = m.followed();
        }
        quests.set(0, quests.get(0).withMap(marked, followed));
        return new JournalView(v.catName(), v.clan(), v.rank(), v.clanColour(), v.standing(), goal, quests, v.places(), v.clanmates(), v.starclan(), v.ladder());
    }

    // --- commands

    public static void commands(LiteralArgumentBuilder<CommandSourceStack> root) {
        LiteralArgumentBuilder<CommandSourceStack> c = Commands.literal("campaign");
        c.then(Commands.literal("join").then(Commands.argument("clan", StringArgumentType.word()).executes(ctx -> {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            Info clan = Info.byKey(StringArgumentType.getString(ctx, "clan"));
            return clan != null && clanless(p) && join(p, clan) ? 1 : 0;
        })));
        c.then(Commands.literal("wake").executes(ctx -> {
            wake(ctx.getSource().getPlayerOrException());
            return 1;
        }));
        c.then(Commands.literal("status").executes(ctx -> {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            CampaignData cd = CampaignData.get(p.getServer().overworld());
            ctx.getSource().sendSuccess(() -> Component.literal("Act " + cd.act + ", chapter " + chapter(p).title + ", felled " + cd.felled
                    + (cd.departed ? ", departed" : "") + (cd.lakeFounded ? ", settled at the lake" : "") + (cd.complete ? ", complete" : "")), false);
            return 1;
        }));
        // operators: move the world's story on, for testing
        c.then(Commands.literal("act").requires(s -> s.hasPermission(2))
                .then(Commands.argument("act", IntegerArgumentType.integer(2, 4)).executes(ctx -> {
                    MinecraftServer server = ctx.getSource().getServer();
                    CampaignData cd = CampaignData.get(server.overworld());
                    int act = IntegerArgumentType.getInteger(ctx, "act");
                    long now = server.overworld().getGameTime();
                    cd.advance(act, now);
                    if (act >= 2) {
                        cd.felled = Math.max(cd.felled, Felling.BEFORE_SIGN);
                        cd.signGiven = true;
                    }
                    if (act >= 3) {
                        cd.midnightMet = true;
                        ForestClans.depart(server, cd);
                    }
                    if (act >= 4) ForestClans.settleAtLake(server, cd);
                    cd.setDirty();
                    ctx.getSource().sendSuccess(() -> Component.literal("The Campaign's world is now in Act " + act), true);
                    return 1;
                })));
        root.then(c);
    }
}
