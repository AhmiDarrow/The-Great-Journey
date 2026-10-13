# Branding still needed

The original pack's FancyMenu art was removed because its origin could not be confirmed
(`d95yas0-52914c3f-8087-4ce3-b595-9cf353ec4171.png` is named like a DeviantArt download;
`orestsketch.png`, `tribe.png` and `w.png` had no source). The layouts in
`pack/overrides/config/fancymenu/customization/` keep their buttons and arrangement.

| Where | Was | Now | Needed |
|---|---|---|---|
| Title screen background | `orestsketch.png` (full-screen image) | Background block switched off, so the menu shows the normal panorama, which Warrior Cats Events replaces with its own (`wcePanorama = true` in `warriorcats_events-client.toml`) | Optional: an original forest background image |
| Title screen logo | `d95yas0-…png`, image element 157x54 at mid-centred x -205, y -115 | Element removed | An original pack logo, ~314x108 px (2x), transparent PNG |
| Pause screen logo | `w.png`, image element 96x29 at mid-centred x -48, y -63 | Element removed | A small logo, ~192x58 px (2x), transparent PNG |
| CurseForge project icons | — | — | Pack icon and Core icon (square, 400x400); `tools/build_core_jar.py` embeds `docs/public/core-icon-256.png` if present |

No local GPU generation (ComfyUI is off-limits on this PC). Options: PixelLab, art by hand, or an artist's work
with written permission. When an asset is ready, put it in `pack/overrides/config/fancymenu/assets/` and add an
image element back with FancyMenu's layout editor in a client.

# Default options

The pack does not ship `options.txt`: shipping it would overwrite every player's settings on each update. The
original pack did not ship one either, and its resource packs were all off by default. If we want defaults later
(for example a lower render distance for weaker PCs), add a defaults mod rather than an `options.txt`.
