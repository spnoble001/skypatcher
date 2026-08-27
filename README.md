# SkyPatcher

Standalone Minecraft Forge 1.8.9 debug harness for:

- `PatchCrumbs`
- `RightClicker`
- `SimpleAssist`
- `Simple Patch 3D`

## Debug controls

- PatchCrumbs, RightClicker and SimpleAssist keybinds are selected in ClickGUI.
- `.patch` in chat: open the settings GUI locally.
- `.flip` in chat: alternate PrePatch between East/West and North/South.
- `.patch 1` in chat: load SimpleAssist speed 15 and the PrePatch manual
  preset (render off, East/West, vertical click speed 15).
- `.patchdebug` in chat: toggle the 3D route preview (off by default). Point at
  an obsidian block to draw its tentative Manhattan route to the active crumb line.
  Near the crumb's height it prefers an alternating stair preview; otherwise it
  renders the fallback L route.

PatchCrumbs starts enabled. The overlay reports the active crumb, revision,
trajectory, targeting state, crosshair hit, right-click owner and the internal
SimpleAssist placement/confirmation state.

## Build

```bash
JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64 ./gradlew reobfJar
```

The distributable JAR is generated in `build/libs/`.

## SimpleAssist architecture

See [SimpleAssist Architecture](docs/SIMPLEASSIST.md) for its directional flow,
state model, timing and deliberate tradeoffs.

See [Simple Patch 3D Architecture](docs/SIMPLEASSIST3D.md) for the independent
reactive cursor, kinematic ranking, depth lead and safety boundaries.
The complete mathematical specification is available in
[LaTeX](docs/SIMPLEASSIST3D.tex).

Placement and confirmation now advance exclusively on client ticks. Captured
patches are invalidated when the PatchCrumbs revision changes, and unreachable
targets are released automatically instead of retaining stale geometry. Toggling
PatchCrumbs also resets its detection coordinates and timers.

The CPS scheduler preserves fractional tick credit. At 20 CPS it distributes
placements across the 20 TPS client clock instead of rounding every 66.7 ms
interval up to two ticks, while still allowing at most one interaction per tick.

SimpleAssist includes an optional `Natural` setting. When disabled, Assist mode
selects a valid support automatically. When enabled, placement is allowed only
when the physical crosshair ray produces the current patch cell, and the real
hit block, face and impact vector are sent unchanged.

## Latest changes

See [Latest Changes](docs/LATEST-CHANGES.md) for the recent module cleanup,
RightClicker integration, coordinator filtering, commands and configurable
keybinds.
