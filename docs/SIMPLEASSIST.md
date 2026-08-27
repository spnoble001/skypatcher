# SimpleAssist Architecture

`SimpleAssist` is the selected Beta3 architecture, consolidated into one public
module class: `SimpleAssist.java`.

SimpleAssist reserves `RightClickCoordinator` with priority 31 during the
client-tick START phase whenever a captured patch or valid terminal is active.
The generic RightClicker uses priority 20. This makes RightClicker a transparent
fallback: it cannot synthesize a click while SimpleAssist owns a valid patch,
and resumes when SimpleAssist releases the coordinator.

## Data model

```text
ActivePatch capturedPatch
BlockPos cursor
PlacementState placementState
BlockPos pendingTarget
Block pendingExpectedBlock
```

`PatchCrumbs` supplies directional geometry:

```text
ActivePatch
  origin:   crumb-side coordinate
  terminal: obsidian wall reached by the scan
  box:      visual and logical bounds
```

The visual patch history can remain for 2.5 seconds, but SimpleAssist only uses
fresh `ActivePatch` geometry to authorize capture.

## Directional flow

For a north/south shot axis:

```text
Player west:
terminal X+n → X+(n-1) → ... → origin X

Player east:
terminal X-n → X-(n-1) → ... → origin X
```

East/west shots apply the same formula on Z. The terminal is the existing
obsidian wall, so the first placement is the adjacent coordinate toward origin.

## Placement flow

```text
Module enabled with its user-selected keybind
  → active crumb and directional geometry
  → raycast touches terminal obsidian
  → capture ActivePatch
  → cursor = terminal
  → skip accepted terminal wall
  → calculate next cell toward origin
  → validate reach and solid support
  → execute placement
  → WAITING_CONFIRMATION
  → confirm expected block in world
  → advance cursor
  → confirmed origin releases capture
  → immediate handoff to next terminal under crosshair
```

SimpleAssist does not rotate, move the player, change inventory slots or require
holding right click. Obsidian or bedrock must be held.

## SpatialAssistanceEngine

`SpatialAssistanceEngine` is an internal SimpleAssist input-pipeline submodule.
For an accepted support face, it permits a placement only when the requested hit
point lies within a 1.25-block perpendicular capture radius of the player's real
view ray and a strict world raytrace reaches that support block. It then sends a
look packet calculated for that hit point immediately before the normal placement
dispatch. The raytrace endpoint is nudged 0.001 blocks into the selected support
face to make grazing boundary hits deterministic; the placement hit vector and
the player's local yaw and pitch are never changed.

After capture, spatial execution does not reuse the 1.25-block activation cone.
For every requested cell it evaluates all six adjacent support faces, retaining
only solid, reachable and raytrace-visible candidates, then chooses the one
with the smallest angular change before issuing C05 followed by the normal
placement dispatch.

When enabled in Assist mode, `getActivationHit` is the only virtual input. It
first accepts the physical raycast only if PatchCrumbs recognizes it as an
activation hit; otherwise it finds a recognized, visible terminal inside the
same 1.25-block look cone and returns a synthesized `MovingObjectPosition`.
This adapts only the capture/arbitration read: the existing SimpleAssist support
selection, credit scheduler and state machine are unchanged. With `Asistencia
espacial` OFF, those paths read `mc.objectMouseOver` unchanged.

Its token bucket starts with one credit, refills one credit at each pre-motion
tick and caps at two credits. A successful placement spends one credit, so the
submodule cannot dispatch more than two assisted interactions from accumulated
credit in a tick cycle.

The `Asistencia espacial` row in the expanded `Simple Patch` panel of ClickGuiObj
toggles this submodule independently. OFF uses the original SimpleAssist
placement dispatch with no packet rotation correction or spatial capture check.

## Natural mode

`Natural` is an optional boolean setting and is disabled by default. With it
disabled, Assist mode chooses the nearest valid support face. With it enabled,
the live crosshair raycast must resolve exactly to the current cursor cell;
SimpleAssist then uses that raycast's support block, face and `hitVec`. Natural
mode therefore treats the CPS value as a maximum because progress also depends
on the player's camera sweep.

## Timing

The CPS slider ranges from 1 to 20. A fractional client-tick credit scheduler
preserves the requested average rate without sending more than one interaction
per tick. At 20 CPS this produces a distributed tick pattern instead of rounding
every 66.7 ms interval up to 100 ms.

Only one placement can be sent per client tick, and only one placement may wait
for confirmation at a time. Confirmation timeout adapts to ping from 3 to 10
ticks. A failed placement receives a two-tick retry backoff.

## Deliberate tradeoff

SimpleAssist uses a mathematical cursor instead of a coordinate list or
`HashSet`. This minimizes state and avoids mixing plans from different
quadrants. It does not perform a final full-patch audit, so a block reverted
after the cursor advances may not be revisited automatically.
