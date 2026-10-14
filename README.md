# Baritone 1.21.11 development handoff

This repository is a **staging branch/handoff**, not a replacement for the upstream Baritone repository. Changes are tested in a clean checkout of the upstream branch by GitHub Actions.

## Upstream

- Repository: https://github.com/Nevarielle/baritone-1.21.11
- Branch: `update/1.21.11-fabric`
- Target game version: Minecraft 1.21.11 / Fabric
- Java: 21

## Current staged changes

### 1. Litematica volume overflow

File: `src/main/java/baritone/utils/schematic/format/defaults/LitematicaSchematic.java`

- Avoids multiplying three `int` dimensions before widening.
- Uses checked 64-bit multiplication.
- Rejects the `Integer.MIN_VALUE` dimension edge case.
- Rejects volumes larger than the current int-indexed decode loop can represent.
- Reuses the same validated dimension helper when allocating the region array.

### 2. Builder placement simulation cleanup

File: `src/main/java/baritone/process/BuilderProcess.java`

- `hasAnyItemThatWouldPlace` temporarily adjusts player yaw/pitch to simulate orientation-sensitive placement.
- The original code restored yaw/pitch only after `getStateForPlacement` returned normally.
- The staged change uses `try/finally` so the player's real yaw/pitch are restored even if a modded `BlockItem` throws during placement simulation.

This is a narrow defensive fix, not a full rewrite of placement logic. It does not yet solve every orientation, support, redstone update, inventory, or pathing issue.

## CI verification

GitHub Actions workflow: [Verify Litematica overflow patch](https://github.com/2015zander15-netizen/its-cool/actions/workflows/verify-litematica-patch.yml)

A successful run on the Litematica-only snapshot is available here:
https://github.com/2015zander15-netizen/its-cool/actions/runs/38574140718

That run passed unit tests and `./gradlew :fabric:remapJar` for the Litematica-only staged snapshot. It does **not** verify the later BuilderProcess change. A new run is triggered by the BuilderProcess update; check the latest workflow run for the combined result.

The workflow packages the full upstream source checkout with the staged files overlaid, generates a patch from the actual diff, runs tests and builds the Fabric remapped JAR, and uploads ZIP artifacts.

## What is still unfinished

- Combined CI verification of the BuilderProcess change.
- A systematic audit of placement mechanics: support blocks, reach/raycast, block placement context, orientation, neighbor-derived states, and server rejection/retry behavior.
- A systematic movement/pathing audit for reaching placement positions without breaking supports or getting stuck.
- Functional verification with real in-game builds, including a small known redstone circuit before attempting a 64-bit calculator.
- Block-entity contents and other schematic features need separate format support review.

Do not claim the calculator, redstone automation, farms, or a universal “fast-build” mode are complete. Normal placement rules, real inventory, supports, world updates, and server rules must remain respected.

## Reproduce locally

Clone upstream and apply the latest generated patch artifact from the Actions run, or use the full source ZIP artifact. Use Java 21:

Windows:
```bat
gradlew.bat :fabric:remapJar
```

Linux/macOS:
```bash
bash ./gradlew test --no-daemon
bash ./gradlew :fabric:remapJar --no-daemon
```

Only report tests/builds as passing when the actual run confirms success.
