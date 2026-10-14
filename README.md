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

### Builder placement prediction safety
`src/main/java/baritone/process/BuilderProcess.java`

- Restores the player's original yaw/pitch in `finally` if orientation-sensitive placement prediction throws.
- Compares block-state property values with `Objects.equals`, not object identity.

These are narrow defensive fixes. They are **not** a general rewrite of block placement, inventory management, redstone updates, or pathfinding.

## Verification and artifacts

Workflow: [GitHub Actions runs](https://github.com/2015zander15-netizen/its-cool/actions/workflows/verify-litematica-patch.yml)

Each run clones the upstream branch, overlays both staged Java files, checks the diff, packages the full source tree, generates a patch from the actual source diff, runs tests, and builds the Fabric remapped JAR. It uploads:
- `baritone-1.21.11-source-with-engine-fixes.zip` — full patched source
- `baritone-engine-fixes-patch.zip` — source diff against upstream
- `baritone-1.21.11-fabric-build.zip` — the generated Fabric JAR variants

Workflow artifacts are temporary downloads (currently retained for 30 days); they are not a permanent GitHub Release. Always inspect the newest run's test/build summary before treating the newest source snapshot as build-verified. A successful compile/test run does not replace in-game placement, pathing, and redstone acceptance tests.

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
