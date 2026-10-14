# Builder engine audit: hands, feet, and verification

This document records the initial evidence-based audit. It is a work plan, not a claim that all behavior is fixed.

## Hands: placement and interaction

### Fixed in the staging snapshot
1. Placement prediction now restores the player's original yaw and pitch in a `finally` block if `BlockItem.getStateForPlacement` throws. This prevents a failed prediction from leaving the player's view rotation altered.
2. Block-state property comparisons now use `Objects.equals` instead of reference identity, so custom property values compare by value.
3. Litematica region volume uses checked 64-bit multiplication and rejects dimensions/volumes the current decoder cannot safely index.

### Remaining placement cases to verify in-game
- Face/raycast accuracy on partial collision shapes (slabs, stairs, fences, trapdoors, walls).
- Direction-dependent placement (stairs, logs, repeaters, observers, pistons, dispensers).
- Support dependencies (torches, rails, buttons, redstone components, plants) and ordering when a required support is also part of the schematic.
- Neighbor-derived states (redstone-wire connections, fences, walls, panes) after surrounding blocks update.
- Inventory refill and slot selection when desired blocks are outside the hotbar.
- Server rejection, delayed block updates, and retry/backoff instead of repeatedly clicking a rejected placement.
- Liquid/environment-dependent blocks and block entities are separate support work; they must not be claimed as complete.

## Feet: movement and reachability

Inspected the BuilderProcess path from nearby placement search into schematic goal assembly and the PathExecutor movement loop. PathExecutor recalculates movement break/place/walk-into sets when the world changes, checks changed movement costs, and cancels when movements become impossible or take too long.

No broad pathfinding algorithm rewrite is included yet. Before changing movement heuristics, capture a reproducible stuck case with start position, target placement, world blocks, selected movement, and log output. Changes to movement costs can affect safety and path quality across the entire game.

## Acceptance tests to add or perform

1. Small static structure: foundation, walls, stairs, doors, and torches.
2. Direction-sensitive blocks: logs, stairs, repeaters, observers, pistons.
3. Redstone update test: two adjacent dust pieces, repeater line, and a lever/lamp output; wait for neighbor updates and verify output.
4. Inventory test: required blocks partly in main inventory, not hotbar.
5. Unreachable support test: builder pauses with a specific explanation instead of repeatedly attempting the same impossible placement.
6. Pathing test: move to a placement vantage point around obstacles without mining support blocks or abandoning a fall/parkour movement unsafely.
7. Large/malformed Litematica region test: reject invalid dimensions/volume with a clear error rather than integer overflow or allocation failure.

## Current verification state

The Litematica-only revision has a successful CI run. The combined BuilderProcess revision is being tested by GitHub Actions. Do not treat the combined edits as verified until the latest run finishes successfully. Real in-game functional tests remain required.
