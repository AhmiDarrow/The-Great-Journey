# Book geography: the lake territories and the old forest

The source for Highstones' "book world" generator. There are two maps:

- the **lake territories** at spawn;
- the **old forest** far away, toward the south-east. In the books the lake lies "far northwest from the forest", so the Great Journey can be walked.

Each map keeps the books' layout. Each seed varies the shapes, sizes and positions inside the limits below, so no two worlds match.

**Sources:**
- Warriors Wiki (warriors.fandom.com): page wikitext fetched through the MediaWiki API on 2026-10-11. Pages used: The lake territories, The forest territories, The lake, The island, Moonpool, Camp, Fourtrees, Sunningrocks, Highstones, Moonstone, The Sky Oak, The tunnels, Horseplace, Twoleg nest, Carrionplace, Snakerocks, Tallpines, Barley's farm, Thunderpath, The Great Sycamore.
- **[C]** marks something the wiki states, usually from the book maps or text.
- **[U]** marks something uncertain or invented. The wiki itself warns that the official maps and the text disagree in places.

Compass: north is -Z and east is +X, as in Minecraft. Sea level is 63. Heights are normal-world: no giant mountains.

---

## 1. The lake territories (spawn)

### Arrangement [C]
- A large, irregular lake sits in the middle. In the books a cat needs two days to walk around it.
- The northern half is woodland: **ShadowClan** to the north-west (conifers, damp ground, little undergrowth) and **ThunderClan** to the north-east (deciduous woodland).
- The southern half is open land: **RiverClan** to the south-west (streams, creeks, marsh) and **WindClan** to the south-east (open moor).
- Two small streams flow into the lake from the north, and one river flows out.
- Borders:
  - ThunderClan / WindClan: a **border stream** running down from the hills in the east. [C]
  - ThunderClan / ShadowClan: a stream. [U] The wiki implies one of the northern inflow streams.
  - RiverClan / WindClan: the **horseplace**. [C]
  - ShadowClan / RiverClan: [U]. Use a stream or a strip of greenleaf Twolegplace, since the wiki puts greenleaf Twolegplaces on both the ThunderClan and the RiverClan borders.
- The lake is neutral. Cats may walk the shore if they keep within three tail-lengths of the water. [C]

### Landmarks
| # | Landmark | Where | What to build | Src |
|---|---|---|---|---|
| L1 | **Stone hollow** (ThunderClan camp) | ThunderClan, inland, roughly mid-territory | An abandoned quarry: a bowl in stone with cliffs taller on one side, about 40–60 blocks across and 12–20 deep. A thorn-tunnel entrance, a ledge (Highledge) under a wall, a medicine cave with a drip pool, dens under bramble thickets against the wall | C |
| L2 | **Sky Oak** (Ancient Oak) | ThunderClan, south of the camp and west of the WindClan border stream, on a slope that can see the lake | A huge gnarled oak, taller than the woods around it, with a burrow in its roots | C |
| L3 | **Abandoned Twoleg nest** | ThunderClan, north of the camp, near the ShadowClan border | A crumbling cottage with a garden that has the only catmint, plus a tunnel entrance on the ridge above it | C |
| L4 | **Old Thunderpath** | ThunderClan, from the hollow heading north | An overgrown gravel or coarse-dirt track 3 blocks wide | C |
| L5 | **Mossy clearing** (training) | ThunderClan, near the camp | A mossy glade | C |
| L6 | **Tunnels** | Under the ThunderClan / WindClan border | A cave network with a central cavern holding a dark underground river that drains toward the lake. Entrances: one near the border stream, one above the camp, one on the WindClan moor near its camp | C |
| L7 | **ShadowClan camp** | ShadowClan, among the pines | A wide dip ringed by brambles, entered through a thorn tunnel. The leader's den sits behind a big boulder under a pine branch (the Pinebranch) | C |
| L8 | **Sanctuary Cottage** | ShadowClan | A Twoleg house with a fenced garden (kittypets live there) | C |
| L9 | **Greenleaf Twolegplace** | ShadowClan / ThunderClan border clearing, and the ShadowClan / RiverClan border | A clearing with tents (wool and fence), a firepit and a gravel path | C |
| L10 | **RiverClan camp** | RiverClan, on a triangle of land between two streams that meet near the lake | Hazel, brambles and a reed wall, a reed tunnel in, stepping stones across one stream, and a medicine den under a thorn bush over the water | C |
| L11 | **The island** (Gatherings) | In the lake, just off the RiverClan shore. Use the stretch toward the ShadowClan side [U] | A rocky shore with thorns, a ring of pine, beech and bushes, an open grassy middle with the Great Oak. A **fallen-tree bridge** links it to the RiverClan shore | C |
| L12 | **Half-bridges** | RiverClan shore (several around the lake) | Wooden docks ending in the water | C |
| L13 | **Horseplace** | Between RiverClan and WindClan, on the south side | A farm of four buildings, paddock fences and horses | C |
| L14 | **WindClan camp** | WindClan, on the open moor | A shallow scoop edged with gorse (berry bushes and azalea), with boulders and the Tallrock | C |
| L15 | **Moonpool** | Far north-east, upstream along the ThunderClan / WindClan border stream, outside every territory | A steep stone hollow ringed with thorn bushes. A waterfall drops from a cleft halfway up one cliff into a small round pool, and a spiral path of pawprints leads down | C |
| L16 | Hills to the Moonpool | Between the territories and the Moonpool | A rocky, rising upland | C |

### Lake layout (parametric)
- **Origin:** the lake centre sits at world spawn (0, 0), jittered ±64.
- **Lake shape:**
  - mean radius **R = 210–280**;
  - shoreline radius r(θ) = R·(1 + 0.18·n1(θ) + 0.08·n2(θ)), where n1 and n2 are seeded 1D noises at two and five lobes per circle;
  - an east–west stretch of 1.0–1.25 (seeded), so the lake is not round;
  - depth 6–14 below sea level, with gentle shelving.
- **Territories:** angular sectors around the lake centre, each running from the shore out to radius **Rout = R + 450 to R + 600** (noise ±60 on the outer edge). Sector centres, each jittered ±12°:

  | Clan | Centre | Width |
  |---|---|---|
  | ThunderClan | NE, 45° | 95° ±10 |
  | WindClan | SE, 135° | 85° ±10 |
  | RiverClan | SW, 225° | 90° ±10 |
  | ShadowClan | NW, 315° | 90° ±10 |

  The widths are normalised to 360°. Measure the angle clockwise from north, so 0 = -Z and 90 = +X.
- **Borders:** each border is a radial line with ±40 blocks of 1D noise along its length. The north–east one (ThunderClan / WindClan, at about 90°) and the north one (ThunderClan / ShadowClan, at about 0°) are **streams**: carve a 3–5 wide, 1–2 deep channel along the border that runs downhill into the lake.
- **Outflow river:** leaves the lake on the RiverClan side at 200–250°. It is 8–12 wide, flows away from the lake, and splits once to make the camp triangle (L10) 80–160 blocks from the shore.
- **Moonpool:** follow the ThunderClan / WindClan border stream upstream past Rout by another 250–400, at a bearing of 60–100°. Put the Moonpool hollow at the stream's source. The stream starts from the Moonpool waterfall.
- **Horseplace:** at the RiverClan / WindClan border (about 180°), 120–220 blocks out from the shore.
- **Island:**
  - centre at 0.55–0.75·r(θ) from the lake centre, at a bearing inside the RiverClan sector's half nearer ShadowClan (240–280°);
  - radius 22–32, rising 4–8 above the water;
  - the tree-bridge spans the shortest gap to the shore, which must be 8–20 blocks. Move the island toward the shore until it fits.
- **ThunderClan placements:**
  - Stone hollow: ThunderClan sector centre ±20°, at shore + 160–260.
  - Sky Oak: between the hollow and the lake, offset toward WindClan (bearing +15–30° from the hollow), at shore + 40–90.
  - Abandoned nest: 120–200 north of the hollow, within 80 of the ShadowClan border.
  - Old Thunderpath: from the hollow, north to the territory edge.
- **Other camps:**
  - ShadowClan camp: ShadowClan sector centre ±20°, at shore + 150–250.
  - WindClan camp: WindClan centre ±20°, at shore + 140–240, on a local high.
- **Twoleg places:**
  - Greenleaf Twolegplaces: one on each of the ShadowClan / ThunderClan and ShadowClan / RiverClan borders, at shore + 60–160.
  - Sanctuary Cottage: ShadowClan, outer half.
  - Half-bridges: two or three on the RiverClan shore.
- **Total footprint:** the radius out to Rout is 660–880, plus the Moonpool upland, so the map is roughly 1.8–2.4 km across.

### Lake heights
| Zone | Height | Character |
|---|---|---|
| Lake | 63 water, floor 50–57 | Shelving, with a pebbly shore band 3–6 wide |
| ThunderClan woods | 68–88 | Rising gently from the shore and rolling, with a ridge north of the camp (tunnel entrance). The hollow floor is 12–20 below the rim |
| ShadowClan pines | 64–76 | Flat and damp, with shallow pools (marsh spots) |
| RiverClan | 63–70 | Low, with creeks, reed beds and wet meadow |
| WindClan moor | 75–95 | Open rolling upland, highest away from the lake, with gentle hills and boulders |
| Moonpool upland | 100–140 | Rocky hills. The Moonpool hollow is 15–25 deep with the pool at its floor |
| Horseplace | 66–72 | Flat paddocks |
| Map edge | — | Blend to the pack's normal generation over 150–250 blocks |

---

## 2. The old forest (the Great Journey destination)

Placement: put its centre (Fourtrees) at about **8–10 km** from spawn, at a bearing of **125–145°** (south-east), seeded. The books put the lake "far northwest from the forest". [C]

### Arrangement
- **Fourtrees** is the centre, where all four territories meet. [C]
- **ThunderClan** to the south-east: deciduous woods. [C]
- **ShadowClan** to the north-east: marshy, damp ground with few trees, plus pines. [C] It lies across the **Thunderpath** that runs east–west along ThunderClan's northern border. [C]
- **RiverClan** to the south-west: open plains along the **river**. [C]
- **WindClan** to the west: rolling moor wedged between the Thunderpath and the river. [C]
- **Highstones** sits on the far western edge, past Barley's farm and a second Thunderpath. [C] The wiki also says north-west. [U] Use west-north-west.
- The **Twolegplace** lies south-east beyond ThunderClan, with **Tallpines** on the boundary between them. [C]
- **The river** flows north to south through RiverClan land:
  - it runs through a **gorge** with a waterfall (in the north, upstream, near WindClan); [C]
  - it continues through open plains past the RiverClan camp; [C]
  - **Sunningrocks** sits on the east bank, on the ThunderClan side. [C]

### Landmarks
| # | Landmark | Where | What to build | Src |
|---|---|---|---|---|
| F1 | **Fourtrees** | Centre | A steep, round hollow 50–70 across and 10–14 deep, its rim lined with ferns and bushes. Four huge oaks stand in a square, plus the **Great Rock**, several tail-lengths high, with a ledge to climb it | C |
| F2 | **ThunderClan ravine camp** | ThunderClan, mid-territory | A small sandy ravine (an old riverbed) 8–12 deep. A gorse-tunnel entrance at the bottom, boulders to climb down, the **Highrock**, and dens in brambles and ferns | C |
| F3 | **Sandy hollow** | Next to the ThunderClan camp | A sandy training dip | C |
| F4 | **Owl tree** | West of the ThunderClan camp | A big hollow tree | C |
| F5 | **Snakerocks** | East of the ThunderClan camp, south of the Thunderpath | A steep grassy clearing with a tumbled pile of rounded sand and grey rocks, cracks and a cave underneath | C |
| F6 | **Great Sycamore** | Beside Snakerocks | A giant tree whose crown rises above the canopy | C |
| F7 | **Sunningrocks** | East bank of the river, at the ThunderClan / RiverClan border | Big smooth pale-grey granite slabs stacked up, sheer on the river side, with cracks and gullies. A sandy, reedy shore and stepping stones across the river nearby | C |
| F8 | **Thunderpath** | ThunderClan / ShadowClan border, running east–west and continuing west | A 7–9 wide road of black concrete or gray wool with a line, plus a tunnel under it toward Fourtrees and another toward WindClan | C |
| F9 | **ShadowClan camp** | ShadowClan | A muddy hollow ringed by brambles and pines, a thorn-tunnel entrance and the lichened Clanrock | C |
| F10 | **Burnt Sycamore** | South of the ShadowClan camp | A charred dead tree | C |
| F11 | **Carrionplace** | North-west of the ShadowClan camp | A fenced dump: coarse dirt heaps of junk blocks behind an iron-bar fence | C |
| F12 | **RiverClan island camp** | RiverClan, in the river | A well-drained island ringed by reeds and sedge, reached by swimming or stepping stones | C |
| F13 | **The gorge** | Upstream (north) on the river, on the WindClan / RiverClan edge | A narrow rock canyon 15–25 deep with fast water and a waterfall at its lower end | C |
| F14 | **Twoleg bridge** | Across the river, downstream of Sunningrocks | A stone or wooden bridge | C |
| F15 | **WindClan camp** | WindClan, on the moor | A sandy hollow ringed with gorse and heather, the Tallrock, Outlook Rock to its east and a badger set to its south. The moor above has many tunnels | C |
| F16 | **Barley's farm** | Between WindClan and Highstones | A barn with a hay loft, a stable, a chicken coop, a farmhouse and fields | C |
| F17 | **Highstones / Mothermouth / Moonstone** | Far west | Three tall, narrow, craggy peaks. Around them, rough grass with boulders and heather. **Mothermouth** is a square black mine entrance under a stone arch, with a winding, branching tunnel down to a cavern lit through a triangular roof hole, where a glowing quartz/calcite **Moonstone** stands | C |
| F18 | **Tallpines** | Between ThunderClan and the Twolegplace | A plantation of thin pines in straight rows, needles underfoot, no undergrowth, log stacks and the Treecut place | C |
| F19 | **Twolegplace** | South-east beyond Tallpines | Red-roofed houses with gardens and a fence along the ThunderClan side | C |

### Old forest layout (parametric)
- **Origin:** Fourtrees at the forest centre F0. Each territory is a sector from F0 out to radius **Rf = 650–850**, with ±60 of noise on the outer edge:

  | Clan | Bearing | Width |
  |---|---|---|
  | ShadowClan | NE, 45° | 90° ±10 |
  | ThunderClan | SE, 135° | 95° ±10 |
  | RiverClan | SW, 215° | 80° ±10 |
  | WindClan | W–NW, 300° | 95° ±10 |

  The sectors begin at radius 45, outside the Fourtrees hollow, and each Clan touches it. [C]
- **Thunderpath:**
  - a near-straight line passing 60–110 north of F0 and bending ±10° along its length;
  - it is the ThunderClan / ShadowClan border east of F0;
  - west of F0 it runs on between WindClan and ShadowClan, then bends toward Highstones as the second Thunderpath. [U]
  - Two underpasses: one on the Fourtrees line, one 250–350 to the west.
- **River:**
  - enters from the north-west, about 200–260 west of F0 at the map edge;
  - runs south through the gorge (F13), 150–300 long, 25–55 north-west of F0 along the WindClan / RiverClan boundary [U];
  - leaves the gorge over the waterfall into the plains, passes 120–220 west of F0, then bends south-east;
  - its east bank between 150 and 300 south of F0 is the ThunderClan / RiverClan border, with Sunningrocks there;
  - it is 10–16 wide on the plains and 5–8 in the gorge.
- **Placements:**
  - ThunderClan camp: ThunderClan centre ±15°, 280–380 from F0.
  - Owl tree: 80–140 west of it. Sandy hollow: 40–70 from it.
  - Snakerocks: 180–260 east of the camp, 40–90 south of the Thunderpath. Great Sycamore: 30–50 from Snakerocks.
  - ShadowClan camp: ShadowClan centre ±15°, 300–420 from F0. Burnt Sycamore: 60–100 south of it. Carrionplace: 150–250 north-west of it, near the outer edge.
  - RiverClan camp: a mid-river island 250–400 from F0, south-south-west. Twoleg bridge: 80–150 downstream of Sunningrocks. Stepping stones: within 40 of Sunningrocks.
  - WindClan camp: WindClan centre ±15°, 300–420 from F0, on the moor top.
- **Beyond the territories:**
  - Tallpines: a ring segment at 120–135°, from Rf - 80 to Rf + 200.
  - Twolegplace: 130–145°, from Rf + 200 to Rf + 450. Its town grid is cosmetic, built from vanilla village pieces or simple houses.
  - Barley's farm: bearing 285–295°, Rf + 150–300.
  - Highstones: bearing 285–300°, Rf + 450–650. Spread the three peaks 25–45 apart.
- **Total footprint:** about 2.4–3.0 km including Highstones.

### Old forest heights
| Zone | Height | Character |
|---|---|---|
| Fourtrees hollow | Rim 76–84, floor 66–70 | Steep-sided bowl |
| ThunderClan woods | 70–88 | Rolling, with the ravine camp 8–12 deep |
| ShadowClan | 64–74 | Low and wet, with many shallow pools, pines on drier knolls |
| RiverClan plains | 63–72 | Flat, with the river at 62–63 |
| WindClan moor | 80–100 | The highest land of the four territories |
| Gorge | Walls 85–95, water 63–66 | A cut through the moor's edge |
| Highstones | Approach 90–120, peaks up to 150–170 | Narrow rocky spires, not mountains |
| Farm, Tallpines, Twolegplace | 66–76 | Flat |

---

## 3. Biomes

### Available in the pack
These come from a scan of `pack/mods` (jar-in-jar included) plus vanilla 1.21.1:
- **minecraft:** all vanilla overworld biomes.
- **biomesoplenty (69):**
  - woodland, old_growth_woodland, seasonal_forest, coniferous_forest, fir_clearing;
  - moor, highland, crag, rocky_shrubland, shrubland, scrubland, field, grassland, pasture, prairie, forested_field;
  - marsh, wetland, bog, muskeg, floodplain, bayou;
  - wasteland, lavender_field, orchard, and others.
- **biomeswevegone (55):** temperate_grove, black_forest, coniferous_forest, prairie, cypress_wetlands, white_mangrove_marshes, pale_bog, overgrowth_woodlands, rose_fields, and others.
- **bf_biomes (4):** maple_forest and others.
- **mysticsbiomes (7):** lagoon, lavender_meadow, autumnal_grove and others.
- **None:** Geophilic and Oh The Trees You'll Grow restyle trees and biomes but add no biome ids.

### Zone → biome (primary, then fallbacks)
| Zone | Primary | Fallback 1 | Fallback 2 |
|---|---|---|---|
| ThunderClan woods (both eras) | biomesoplenty:woodland | minecraft:forest | biomeswevegone:temperate_grove |
| ThunderClan old-growth patches (Sky Oak, Great Sycamore area) | biomesoplenty:old_growth_woodland | minecraft:dark_forest (edges only) | minecraft:forest |
| ShadowClan pines (lake) | minecraft:old_growth_pine_taiga | biomesoplenty:coniferous_forest | minecraft:taiga |
| ShadowClan marsh (old forest, plus damp spots at the lake) | biomesoplenty:bog | minecraft:swamp | biomesoplenty:marsh |
| RiverClan reed marsh (lake shore) | biomesoplenty:marsh | biomesoplenty:wetland | minecraft:swamp |
| RiverClan creeks and meadow (lake, inland) | biomesoplenty:wetland | minecraft:meadow | minecraft:plains |
| RiverClan plains (old forest) | biomesoplenty:grassland | minecraft:plains | biomesoplenty:field |
| WindClan moor (both eras) | biomesoplenty:moor | biomesoplenty:highland | minecraft:meadow |
| Lake water | minecraft:river | minecraft:lukewarm_ocean (avoid: it brings ocean features) | — |
| River (old forest) | minecraft:river | — | — |
| Gathering island | minecraft:forest | biomesoplenty:woodland | — |
| Fourtrees hollow | minecraft:forest | biomesoplenty:woodland | — |
| Moonpool upland | biomesoplenty:rocky_shrubland | biomesoplenty:crag (rocky) | minecraft:windswept_gravelly_hills (heights capped by our terrain) |
| Highstones approach | biomesoplenty:highland | biomesoplenty:rocky_shrubland | minecraft:meadow |
| Highstones peaks | minecraft:stony_peaks (surface only; our terrain caps the height) | biomesoplenty:crag | minecraft:windswept_gravelly_hills |
| Horseplace, Barley's farm | biomesoplenty:pasture | minecraft:plains | biomesoplenty:field |
| Tallpines | minecraft:taiga (trees replaced by planted rows) | biomesoplenty:coniferous_forest | — |
| Twolegplace, greenleaf Twolegplace | minecraft:plains | minecraft:meadow | — |
| Carrionplace | biomesoplenty:wasteland | minecraft:plains | — |
| Map edge | The pack's normal biome source | — | — |

Notes:
- **Moor:** BoP `moor` is the closest match for WindClan (heathland, low shrubs, rocks).
- **Wetland:** BoP `wetland` has willows, which suits RiverClan.
- **Old forest ShadowClan:** use `bog`/`swamp` for most of it and `old_growth_pine_taiga` on knolls. The forest-era books stress marsh with few trees. [C]
- **Gathering places and camps:** keep their biome the same as the surrounding territory so mob spawns, colours and seasons (Serene Seasons) match.
- **Wild cats:** Warrior Cats Events spawns wild cats only in biomes listed in its `mod_spawn_biomes` tag (see docs/clanwild.md). These territory biomes need adding there, or the territories will be empty of wild Clan cats.

---

## 4. Rules that keep each seed different but recognisable
1. **Never change:** the compass order of the Clans, which Clan holds each landmark, the neighbour relationships (Fourtrees touches all four Clans; the horseplace divides RiverClan from WindClan; the Moonpool lies upstream of the ThunderClan / WindClan border stream), and the lake-to-forest direction.
2. **Vary per seed:**
   - every radius, angle and distance inside the ranges above;
   - the lake outline and border wiggles, from seeded noise;
   - the river's course, using seeded noise over the control points;
   - landmark positions inside their allowed areas;
   - tree placement and small terrain detail, which the normal biome decoration supplies.
3. **Derive every random value from the world seed** with a fixed salt per item (for example seed ^ hash("lake.radius")). The same seed then rebuilds the same map, and changing one parameter does not reshuffle the rest.
4. **Validation for a GameTest:**
   - the island's tree-bridge gap is 8–20;
   - every camp is at least 60 blocks inside its territory and at least 120 from any other camp;
   - Fourtrees borders all four sectors;
   - the Moonpool and Highstones are outside every territory;
   - no landmark sits on water unless it is meant to (the island, the river camp, the half-bridges).

## 5. Uncertain or invented details [U]
- **Lake:**
  - the island's exact bearing (the wiki says only "just off the RiverClan shore");
  - the shape of the ShadowClan / RiverClan border;
  - which stream is the ThunderClan / ShadowClan border.
- **Old forest:**
  - the exact path of the second Thunderpath to Highstones;
  - whether Highstones is due west or north-west (the wiki uses both);
  - the gorge's exact position on the river;
  - the RiverClan island camp is not drawn on the official map.
- **Scale:** both maps are compressed. The books' lake takes two days to circle, which would be several kilometres. The ranges above make each map a 1.8–3 km area that can be walked in a few minutes per territory.
