# Builder engine audit: placement, movement, and acceptance tests

This is an evidence-based audit and test plan, not a claim that every building behavior is fixed.

## Hands: placement and interaction

### Changes in the staged source
1. Targeted placement prediction restores the player's original yaw/pitch in `finally`; a runtime exception from a modded `BlockItem` is debug-logged and skipped.
2. The inventory-wide `approxPlaceable` scan retains a block's default state if local placement prediction returns null or throws. The inventory throwaway selector also catches modded `BlockItem` prediction errors and falls back to block identity. Inventory matching is by block type; exact target-context placement is verified separately. If all temporary slots 1–7 are needed, the inventory behavior now uses a reversible swap to cycle stacks rather than no-op; unit tests cover candidate-slot selection.
3. Support-face sampling uses all six correctly oriented AABB faces, and a placement click requires the real raycast to match the exact support block and face. Mining also requires the real raycast to hit the intended block; approximate rotation alone cannot force a break. Failed clicks use increasing retry delays; waiting targets do not monopolize path goals, and after five attempts with no target-state change the builder defers that target and continues with other work.
4. Repeated path calculation failures are bounded; a changed goal can be tried once, but the same goal failing twice without player/block progress pauses with diagnostic guidance.
5. Block-state property comparisons use `Objects.equals` instead of reference identity, while known runtime redstone power flags and neighbor-derived connection/shape properties are ignored during progress checks.
6. Litematica decoding uses checked 64-bit volume arithmetic; it rejects empty schematics, more than 4,096 regions, empty palettes, wrong palette-entry or `Properties` tag types, invalid/unknown block identifiers, unknown state-property names, invalid state-property values, invalid palette indices, truncated packed `BlockStates`, over-budget regions or aggregate schematics, excessive composite bounding-box scan volume, and overflowing coordinates/extents.

7. Stable right-click-adjustable mismatches for doors, trapdoors, fence gates, levers, repeaters, comparators, note-block pitch, and daylight-sensor inversion are routed through verified right-clicks rather than breaking the existing block. Waiting retries yield to other build work; exhausted failures are diagnosed. Temporary button pulses and externally powered flags are treated as derived state.

These changes improve defensive behavior; they do not guarantee that every modded block can be predicted or placed.

### Cases that still require acceptance tests
- Face/raycast accuracy on partial collision shapes (slabs, stairs, fences, trapdoors, walls).
- Direction-dependent placement (stairs, logs, repeaters, observers, pistons, dispensers).
- Support dependencies and placement order (torches, rails, buttons, redstone components, plants); the builder now defers goals until `canSurvive` succeeds, but this still needs acceptance testing.
- Neighbor-derived states (dust, fences, walls, panes) after surrounding blocks update; confirm the derived-state comparison does not hide missing connections in the acceptance circuit.
- Inventory refill and slot selection when required items are in the main inventory rather than the hotbar.
- Server rejection and delayed block updates still need in-game acceptance testing to tune retry behavior and false positives.
- Stateful interaction timing needs live testing, especially paired door halves and levers affected by external redstone.
- Fluids, environmental requirements, and block-entity contents; these need separate support work.

## Feet: movement and reachability

The inspected BuilderProcess searches for nearby placements before assembling schematic goals. It now offers a composite of valid support-side approach goals instead of selecting the first support side, and it bounds repeated path-calculation failures when the same goal makes no progress. PathExecutor recalculates movement break/place/walk-into sets after world changes, checks changed movement costs, and cancels paths when movements become impossible or time out. Off-path distance diagnostics now use Baritone debug logging instead of printing to standard output every tick.

No broad pathfinding algorithm or movement-cost rewrite is included. Before changing those heuristics, record a reproducible stuck case: start position, target block, surrounding blocks, selected movement, and relevant log output. Movement-cost changes can affect general path quality and safety.

## Acceptance test sequence

1. Static structure: foundation, walls, stairs, doors, and torches.
2. Direction-sensitive blocks: logs, stairs, repeaters, observers, pistons.
3. Redstone updates: adjacent dust, a repeater line, and lever/lamp output; verify after neighbor updates.
4. Inventory: required blocks partly in main inventory, not hotbar.
5. Unreachable support: builder gives a clear failure/pause rather than retrying an impossible placement indefinitely.
6. Pathing: reach a placement vantage point around obstacles without mining required support blocks or unsafe movement.
7. Malformed Litematica data: reject zero or more than 4,096 regions, zero dimensions, over-budget regions/aggregate schematics or composite bounding-box scan volume, overflowing signed coordinates/extents, missing palettes, unknown block identifiers or properties, invalid property values or palette indices, and truncated packed data cleanly, including tiny regions separated by enormous coordinate gaps.
8. Loader integration: decode a minimal one-block Litematica NBT structure and reject an invalid packed palette index through the actual constructor/decode path.
8. Calculator milestone: first build and verify a tiny combinational circuit, then a one-bit adder, then a small multi-bit adder with test inputs/outputs. Do not jump directly to a 64-bit calculator.

## Verification boundary

GitHub Actions overlays the staged loader, builder process, inventory behavior, path executor, and regression-test files onto the upstream 1.21.11 Fabric branch, then runs unit tests and builds the Fabric remapped JAR. Use the newest run's summary to verify the exact source commit being downloaded. A passed test/build is necessary but not sufficient: CI cannot prove real-world placement correctness, movement quality, block-entity preservation, or calculator functionality. Complete the in-game acceptance sequence above before claiming those capabilities.
