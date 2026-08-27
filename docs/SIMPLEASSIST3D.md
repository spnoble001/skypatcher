# Simple Patch 3D Architecture

`Simple Patch 3D` is an independent module. It does not use the native
terminal-to-origin cursor from `SimpleAssist`; it uses a local reactive cursor
to place one live neighbour at a time.

## Scope and activation

The module is exposed in the Patch ClickGUI as `Simple Patch 3D`. It requires:

- an active `PatchCrumbs` crumb;
- obsidian or bedrock in the held slot;
- an obsidian/bedrock anchor acquired from the physical ray or, when Spatial
  Assistance is enabled, from its 1.25-block expanded capture cone.

The crumb defines a goal plane rather than one fixed 3D endpoint:

```text
North/South trajectory:  goal X = crumb X, goal Y = crumb Y, Z is depth.
East/West trajectory:    goal Z = crumb Z, goal Y = crumb Y, X is depth.
```

## Reactive cursor

The executor stores only the current session state:

```text
goal crumb + trajectory axis
last confirmed block
up to two pending placements
depth anchor/parity
credit and watchdog timestamps
```

It also records the PatchCrumbs revision at capture. A later revision refreshes
the session without reset only when it represents the same trajectory axis,
goal-plane coordinate and Y level. Expiration or a genuine plane change resets
the cursor before any further placement.

It runs in `ClientTickEvent.Phase.START`. On each cycle it evaluates live
candidates around the last confirmed block (or the newest pending block),
instead of retaining a precomputed N1 -> N2 -> N3 route.

A candidate must improve the goal-plane/Y potential. A depth candidate may
also be accepted when it improves the bounded motion lead described below.
The cursor is complete when its last confirmed block reaches the goal plane.

## Geometry and hard filters

Before a candidate may dispatch, it must pass all of these checks:

- destination is replaceable;
- destination AABB does not intersect the player AABB (expanded only in X/Z);
- selected support is solid, reachable from the current eye position and has
  unobstructed line of sight;
- the actual eye-to-hit-vector distance is at most 4.5 blocks;
- the support face is inside the 1.25-block expanded look cone;
- mixed X/Z+Y routes remain inside crumb `Y-1 .. Y+1`.

Pure vertical columns already aligned to the goal line may use `Y-3 .. Y+3`.

For horizontal progress, the player side of the crumb dividing plane restricts
the usable face:

```text
North/South: player west -> WEST, player east -> EAST
East/West:   player north -> NORTH, player south -> SOUTH
```

Vertical steps are not constrained by that lateral face policy.

## Kinematic local cost

The selector remains O(1): it considers at most six directions per START tick.
Its ranking cost is based on:

```text
goal-plane/Y progress
+ bounded depth-lead distance
+ hit-vector distance from predicted eyes
+ local view-angle delta
+ penalty for a face behind horizontal motion
```

Prediction is preference only. Reach, hitbox, cone and line-of-sight checks
always use the current eye position. The lead is bounded to 0, 0.5 or 1 tick
depending on horizontal speed.

## Depth lead and patch separation

Depth is free with respect to the final crumb plane, but it is not arbitrary.
When horizontal motion points along the depth axis, the cursor may choose one
two-block depth lead (`+2` or `-2`) from the session anchor.

This preserves the patch lattice:

```text
water -> obsidian -> water -> obsidian
```

All accepted patch nodes must have the same depth parity as the anchor. The
intermediate depth cell is never placed or used as virtual support; a depth
lead requires an independently real, valid support face.

## Dispatch, credits and confirmation

`SpatialAssistanceEngine` sends the silent C05 look state immediately before
the normal controller C08 dispatch. Local yaw/pitch are not mutated.

The module has its own fractional 1-20 CPS credit bucket, capped at two
credits. At most two placements may be pending. A second C08 may use the first
already-queued block as support only when that support relation is valid and
packet ordering remains:

```text
C05 -> C08 -> C05 -> C08
```

The selector retains a candidate intent for at most two ticks. Its support,
reach, line of sight, hitbox and guide node are still validated from live state
before reuse. A competing face replaces it only when its local score is at
least 15% lower, preventing micro-movement from causing target oscillation.

Each pending block is confirmed from the client world before it advances the
confirmed cursor. Individual pending timeouts, a three-miss request limit and
an 80-tick no-progress watchdog release the module cleanly. The watchdog is
suspended while the player is using an item.

## Intentional boundaries

The module does not move the player, change slots, modify local rotation or
extend server reach. Prediction and depth lead only select the most suitable
currently valid placement; they never authorize a placement that fails the
current physical checks.
