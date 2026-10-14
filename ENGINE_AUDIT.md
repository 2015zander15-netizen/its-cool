# Builder engine audit: placement, movement, and acceptance tests

This is an evidence-based audit and test plan, not a claim that every building behavior is fixed.

## Hands: placement and interaction

### Changes in the staged source
1. Placement prediction restores the player's original yaw/pitch in a `finally` block if `BlockItem.getStateForPlacement` throws.
2. Block-state property comparisons use `Objects.equals` instead of reference identity.
3. Litematica region volume uses checked 64-bit multiplication and rejects dimensions/volumes the int-indexed decoder cannot safely represent.

These changes improve defensive behavior; they do not guarantee that every modded block can be predicted or placed.

### Cases that still require acceptance tests
- Face/raycast accuracy on partial collision shapes (slabs, stairs, fences, trapdoors, walls).
- Direction-dependent placement (stairs, logs, repeaters, observers, pistons, dispensers).
- Support dependencies and placement order (torches, rails, buttons, redstone components, plants).
- Neighbor-derived states (dust, fences, walls, panes) after surrounding blocks update.
- Inventory refill and slot selection when required items are in the main inventory rather than the hotbar.
- Server rejection, delayed block updates, and a bounded retry/backoff policy.
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
7. Malformed/oversized Litematica region: reject invalid dimensions/volume cleanly.
8. Calculator milestone: first build and verify a tiny combinational circuit, then a one-bit adder, then a small multi-bit adder with test inputs/outputs. Do not jump directly to a 64-bit calculator.

## Verification boundary

The prior successful CI run tested the Litematica-only snapshot. The combined revision's own GitHub Actions run must finish with both tests and the Fabric remapped JAR build successful before that revision is called build-verified. CI cannot prove real-world placement correctness or calculator functionality; the in-game acceptance sequence above remains required.
