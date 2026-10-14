# Baritone 1.21.11 development handoff

This repository is a **staging/handoff repository**, not a replacement for upstream Baritone. GitHub Actions clones the upstream branch and overlays the staged source changes before testing/building.

## Upstream target

- Repository: https://github.com/Nevarielle/baritone-1.21.11
- Branch: `update/1.21.11-fabric`
- Minecraft target: 1.21.11 / Fabric
- Java: 21

## Staged code changes

### Litematica volume safety
`src/main/java/baritone/utils/schematic/format/defaults/LitematicaSchematic.java`

- Avoids multiplying three `int` dimensions before widening.
- Uses checked 64-bit multiplication and rejects the `Integer.MIN_VALUE` dimension edge case.
- Rejects zero-sized regions and limits each decoded region to 8,388,608 blocks before allocating the 3D state array.
- Reuses validated dimensions before region-array allocation.
- Checks signed region-end coordinates and region-to-schematic offsets to reject integer wraparound.
- Caps aggregate volume and nested column-array counts across all regions before allocating arrays.
- Rejects empty/unknown block palettes, invalid palette indices, and truncated packed `BlockStates` arrays.

### Builder placement prediction safety
`src/main/java/baritone/process/BuilderProcess.java`

- Restores the player's original yaw/pitch in `finally`; a runtime exception from a modded block's placement prediction is logged in debug mode and skipped so another hotbar candidate can be tried.
- Guards the inventory-wide `approxPlaceable` scan and preserves the candidate block identity even if the local placement prediction returns null or throws.
- Inventory estimates match candidate block type rather than the player's current facing or neighbor-derived properties; the exact target context is still checked before each placement.
- Adds bounded placement retry/backoff: after five click attempts with no target-state change, the builder pauses and reports the target coordinates instead of hammering the same placement indefinitely.
- Handles repeated path-calculation failures: it allows a retry when the goal changes, and pauses with troubleshooting hints after the same goal fails twice without movement or block progress.
- Compares block-state property values correctly and ignores runtime-derived redstone power flags and neighbor-derived connections/shapes so active circuitry is not dismantled.
- Requires the actual raycast to hit the intended support block and face before issuing a right-click.
- Defers placement goals for blocks that cannot currently survive at the target, so support-dependent blocks are considered after their foundations or side supports.

These are narrow defensive fixes. They are **not** a general rewrite of block placement, inventory management, redstone updates, or pathfinding.

## Verification and artifacts

Workflow: [GitHub Actions runs](https://github.com/2015zander15-netizen/its-cool/actions/workflows/verify-litematica-patch.yml)

Each run clones the upstream branch, overlays the two staged main Java files and the schematic safety regression test, checks the diff, packages the full source tree, generates a patch from the actual source diff, runs tests, and builds the Fabric remapped JAR. It uploads:
- `baritone-1.21.11-source-with-engine-fixes.zip` — full patched source and tests

- `baritone-engine-fixes-patch.zip` — source diff against upstream
- `baritone-1.21.11-fabric-build.zip` — the generated Fabric JAR variants

Workflow artifacts are temporary downloads (currently retained for 30 days); they are not a permanent GitHub Release. Recent combined source revisions have passed unit tests and the Fabric remapped JAR build. Always inspect the newest run's summary before using a new artifact. CI covers schematic loader safety and compile-time integration, not live block placement or redstone behavior.

## Known unfinished work

- In-game acceptance testing for orientation, supports, partial collision shapes, inventory refill, server-rejected placements, and neighbor-derived states.
- A reproducible stuck-path case before changing movement/pathing heuristics.
- Validation of block-entity NBT, inventories, fluids, and other schematic-format features.
- A small redstone test circuit before attempting a 64-bit calculator.

Do not claim a working calculator, automated farms, or universal fast-build mode is complete. No world-editing bypass is intended: building must use normal placement, actual inventory, support blocks, world updates, and server rules.

## Local build

Use Java 21.

Windows:
```bat
gradlew.bat test
gradlew.bat :fabric:remapJar
```

Linux/macOS:
```bash
bash ./gradlew test --no-daemon
bash ./gradlew :fabric:remapJar --no-daemon
```

Only describe a test/build as passing when its actual result confirms success.
