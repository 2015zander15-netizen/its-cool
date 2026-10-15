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
- Rejects empty schematics and more than 4,096 regions, zero-sized regions, and any decoded region above 8,388,608 blocks before allocating the 3D state array.
- Reuses validated dimensions before region-array allocation.
- Checks signed region-end coordinates and region-to-schematic offsets to reject integer wraparound.
- Caps aggregate region volume, all nested 3D-array objects (intermediate plus column arrays), and composite bounding-box scan volume across all regions before allocating arrays.
- Rejects empty/unknown block palettes, palettes above 65,536 entries, non-compound palette entries or `Properties` values, unknown block-state property names, invalid property values, invalid palette indices, and truncated packed `BlockStates` arrays.
- Regression tests exercise a complete one-block Litematica NBT decode and verify an invalid packed palette index is rejected during decoding.

### Builder placement prediction safety
`src/main/java/baritone/process/BuilderProcess.java`

- Restores the player's original yaw/pitch in `finally`; a runtime exception from a modded block's placement prediction is logged in debug mode and skipped so another hotbar candidate can be tried.
- Guards the inventory-wide `approxPlaceable` scan and preserves the candidate block identity even if the local placement prediction returns null or throws.
- Inventory estimates match candidate block type rather than the player's current facing or neighbor-derived properties; the exact target context is still checked before each placement.
- Adds bounded placement retry/backoff: targets wait through their retry timers without monopolizing path goals; after five click attempts with no target-state change, that target is deferred while other buildable work continues, and failed coordinates are reported when no other useful goal remains. If only retry-timed work remains, the builder keeps a goal for those targets and moves toward them while the cooldown expires instead of pausing prematurely.
- Handles repeated path-calculation failures: it allows a retry when the goal changes, and pauses with troubleshooting hints after the same goal fails twice without movement or block progress.
- Compares block-state property values correctly and ignores runtime-derived redstone power flags and neighbor-derived connections/shapes—including repeater locking, tripwire attachment, sculk-sensor phase, fence-gate wall connection, and downward plant/pipe connections—while keeping real placement properties such as repeater delay strict.
- Samples the support face facing toward the target and requires the actual raycast to hit that exact support block and face before right-clicking; AABB coordinate weights are tested for all six faces.
- Requires the actual raycast to hit the selected block before mining; approximate rotation alone no longer forces a left-click.
- Defers placement goals for blocks that cannot currently survive at the target, so support-dependent blocks are considered after their foundations or side supports.
- Runs a structural pass before the manual-interaction pass, so toggles cannot hide unfinished placement/removal/support work even when the schematic scan is capped. Manual-only mismatches are tracked separately as structurally visited, then re-queued once for the final interaction pass; this avoids repeatedly revisiting unloaded chunks. Layered structural scans and the final interaction pass both clamp to `stopAtHeight` and the active selection mask, including the last partial layer. Layer advancement and `skipFailedLayers` use the same clamped depth rule; failed-layer skipping is disabled during the final manual-interaction pass and stops at `stopAtHeight`.
- Interacts with stable manual state properties for hand-operable doors, trapdoors, fence gates, levers, note pitches, daylight detector inversion, repeater delay, and comparator mode; iron doors/trapdoors are not treated as hand-toggleable, and buttons’ short-lived powered pulses are not treated as durable targets.
- Defers redstone-only iron door/trapdoor open-state mismatches and pure open-state mismatches held by active redstone power rather than repeatedly clicking or breaking those blocks.
- Ignores neighbor-derived chest type and connection properties for vines/connection blocks while preserving facing checks.
- Handles stable right-click-adjustable mismatches for doors, trapdoors, fence gates, levers, repeaters, comparators, note-block pitch, and daylight-sensor inversion before considering a break; rejected interactions back off, are deferred while other work proceeds, and are reported when no other work remains. Temporary button pulses and externally powered states are not treated as user-settable targets. If a door/trapdoor/gate is held open by redstone, its open-state mismatch stays outstanding, but the builder does not click or break it; it reports the power dependency instead.
- Provides composite approach goals for multiple usable support sides so pathfinding can choose a reachable side instead of the first candidate.
- Guards the inventory/throwaway selector against exceptions from modded block placement prediction, with a block-identity fallback.
- Does not select the temporary hotbar destination until a main-inventory swap has actually succeeded; movement placement waits in `ATTEMPTING` rather than reporting `READY_TO_PLACE` while a swap is rate-limited or awaiting stationary safety.
- Defers inventory clicks while a chest or other non-inventory container is open, retaining the queued swap until the player returns to their inventory; drops queued requests if the source stack disappeared or either slot index is invalid.
- Cycles material stacks through hotbar slots 1–7 with reversible swaps if every temporary slot is needed, while preserving slots 0 and 8 for the tool and throwaway.

### Path executor diagnostics
`src/main/java/baritone/pathing/path/PathExecutor.java`

- Sends off-path distance and movement-recalculation timing diagnostics through Baritone's debug logger instead of printing to standard output.

These are narrow defensive fixes. They are **not** a general rewrite of block placement, inventory management, redstone updates, or pathfinding.

## Verification and artifacts

Workflow: [GitHub Actions runs](https://github.com/2015zander15-netizen/its-cool/actions/workflows/verify-litematica-patch.yml)

Each run clones the upstream branch, overlays the staged Litematica loader, builder process, inventory behavior, movement helper, path executor, and regression-test files, checks the diff, packages the full source tree, generates a patch from the actual source diff, runs tests, and builds the Fabric remapped JAR. The newest source edits require their own successful run; older artifacts may omit later fixes. It uploads:
- `baritone-1.21.11-source-with-engine-fixes.zip` — full patched source and tests

- `baritone-engine-fixes-patch.zip` — source diff against upstream
- `baritone-1.21.11-fabric-build.zip` — the generated Fabric JAR variants

Workflow artifacts are temporary downloads (currently retained for 30 days); they are not a permanent GitHub Release. The test suite uses one shared `MinecraftTestBootstrap` helper so both loader and builder tests initialize Minecraft's version and registries exactly once per test JVM. The current phase-separation, layer-boundary, interaction, loader-safety, and pathing changes must be judged from their own completed run. Do not treat the newest interaction/cooldown revision as verified until its own run passes both tests and the Fabric remapped JAR build. CI covers schematic loader safety, interaction decision logic, and compile-time integration, not live block placement or redstone behavior.

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
