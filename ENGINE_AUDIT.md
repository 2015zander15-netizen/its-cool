# Builder engine audit: placement, movement, and acceptance tests

This is an evidence-based audit and test plan, not a claim that every building behavior is fixed.

## Hands: placement and interaction

### Changes in the staged source
1. Targeted placement prediction restores the player's original yaw/pitch in `finally`; a runtime exception from a modded `BlockItem` is debug-logged and skipped.
2. The inventory-wide `approxPlaceable` scan retains a block's default state if local placement prediction returns null or throws. Inventory matching is by block type; exact target-context placement is verified separately.
3. A placement click requires the real raycast to match the exact support block and face. Failed clicks use increasing retry delays; after five attempts with no target-state change, the builder pauses with coordinates and troubleshooting hints.
4. Block-state property comparisons use `Objects.equals` instead of reference identity, while known runtime redstone power flags and neighbor-derived connection/shape properties are ignored during progress checks.
5. Litematica decoding uses checked 64-bit volume arithmetic; it rejects empty schematics and palettes, invalid/unknown block identifiers, invalid palette indices, truncated packed `BlockStates`, over-budget regions or aggregate schematics, and overflowing coordinates/extents.

These changes improve defensive behavior; they do not guarantee that every modded block can be predicted or placed.

### Cases that still require acceptance tests
- Face/raycast accuracy on partial collision shapes (slabs, stairs, fences, trapdoors, walls).
- Direction-dependent placement (stairs, logs, repeaters, observers, pistons, dispensers).
- Support dependencies and placement order (torches, rails, buttons, redstone components, plants).
- Neighbor-derived states (dust, fences, walls, panes) after surrounding blocks update; confirm the derived-state comparison does not hide missing connections in the acceptance circuit.
- Inventory refill and slot selection when required items are in the main inventory rather than the hotbar.
- Server rejection and delayed block updates still need in-game acceptance testing to tune retry behavior and false positives.
- Fluids, environmental requirements, and block-entity contents; these need separate support work.

## Feet: movement and reachability

The inspected BuilderProcess searches for nearby placements before assembling schematic goals. PathExecutor recalculates movement break/place/walk-into sets after world changes, checks changed movement costs, and cancels paths when movements become impossible or time out.

No broad pathfinding rewrite is included. Before changing movement heuristics, record a reproducible stuck case: start position, target block, surrounding blocks, selected movement, and relevant log output. Movement-cost changes can affect general path quality and safety.

## Acceptance test sequence

1. Static structure: foundation, walls, stairs, doors, and torches.
2. Direction-sensitive blocks: logs, stairs, repeaters, observers, pistons.
3. Redstone updates: adjacent dust, a repeater line, and lever/lamp output; verify after neighbor updates.
4. Inventory: required blocks partly in main inventory, not hotbar.
5. Unreachable support: builder gives a clear failure/pause rather than retrying an impossible placement indefinitely.
6. Pathing: reach a placement vantage point around obstacles without mining required support blocks or unsafe movement.
7. Malformed Litematica data: reject zero dimensions, over-budget regions/aggregate schematics, overflowing signed coordinates/extents, missing palettes, invalid palette indices, unknown block identifiers, and truncated packed data cleanly.
8. Calculator milestone: first build and verify a tiny combinational circuit, then a one-bit adder, then a small multi-bit adder with test inputs/outputs. Do not jump directly to a 64-bit calculator.

## Verification boundary

GitHub Actions overlays the staged main source and schematic safety regression tests onto the upstream 1.21.11 Fabric branch, then runs unit tests and builds the Fabric remapped JAR. Use the newest run's summary to verify the exact source commit being downloaded. A passed test/build is necessary but not sufficient: CI cannot prove real-world placement correctness, movement quality, block-entity preservation, or calculator functionality. Complete the in-game acceptance sequence above before claiming those capabilities.
