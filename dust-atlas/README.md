# Dust Atlas

A searchable flowchart of every dust in GTNH. Each dust shows the recipes that make it on the left and the recipes that consume it on the right, grouped by machine, with in-game icons and tooltips.

The current data comes from **GTNH 2.9.0-RC1 (GT5U 5.09.54.183)**: 3,470 dusts and 60,435 recipes across 96 recipe types.

GT generates most dust recipes at load time from material properties, so the data can't be read out of the GitHub sources. It has to be exported from a running game, and that's what `dumper/` does.

## Folders

- `site/`: the published page (`index.html`, `data.json`, `atlas/a*.png` icon sheets).
- `dumper/`: a client-only Forge 1.7.10 mod (`recipedump`) that exports the data. It's built against the GTNH buildscript and GT5-Unofficial. Servers don't need it.
- `scripts/`: Python scripts that turn the export into `site/`.

## Regenerating for a new pack version

1. Set the GT version in `dumper/dependencies.gradle`, then build with `./gradlew assemble` (JDK 17+). Copy `build/libs/*-dirty.jar` (not `-dev`) into the instance's `mods/`.
2. Launch the game once. At the title screen it writes `.minecraft/recipedump/recipes.json` and `items.json`, then creates `DONE`.
3. Run `python -I scripts/filter.py <recipedump dir> dust.json`. This also writes `icons_wanted.txt` into the dump folder.
4. Launch the game again. At the title screen a blank screen renders every icon into `recipedump/icons/`, writes `ICONS_DONE`, then returns to the menu. In a world you can run `/recipedump icons` instead.
5. Run `python -I scripts/build_data.py <recipedump dir> dust.json site`.
6. Run `python -I scripts/pack_atlas.py <recipedump dir>/icons site/atlas`. This step needs `numpy` and `pillow`.
7. Remove the jar from `mods/`, commit `site/`, and push. Pages redeploys on push to `main`.

Delete `DONE` or `ICONS_DONE` to make the mod run a pass again.
