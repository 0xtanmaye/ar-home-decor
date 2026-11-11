# Testing guide

How to run the AR view so that results are reliable and repeatable.

## Before a session

- **Phone:** battery above 30% or plugged in, battery saver **off** (it throttles the GPU and lowers FPS), camera lens wiped clean.
- **Screen:** Developer options → **Stay awake** keeps the screen on while charging, so it doesn't lock mid-test.
- **Room:** lights on and as even as possible. Avoid patches of direct sunlight and strong glare on glossy floors.
- **Logs and screenshots** (only needed when the VM should read results): Wireless debugging on, then run `./tools/connect-phone.sh` on the VM.

## Scanning a room

1. Open the app → **Start AR**. Wait until the panel shows `Tracking  tracking`.
2. Hold the phone at chest height, pointing about 45° down at the floor 1–2 m ahead.
3. **Move the phone sideways** slowly for 5–10 seconds: take a step, or sweep your arm. Turning on the spot isn't enough. ARCore judges distance from how the view shifts as the camera moves.
4. Grid colours: **cyan** = floor, **amber** = tables/beds, **pink** = walls, **grey** = ceilings.
5. **Walls:** stand 1–2 m away and sweep sideways along the wall, keeping edges, corners, switches or frames in view. Plain walls with no texture may not be detected; this is a known limitation of plane detection.

Avoid: fast movement, covering the camera, pointing only at one blank surface, mirrors and glass, people or pets walking through the view.

## Reading the debug panel

| Line | Meaning | Good value |
|---|---|---|
| FPS | Frames drawn per second | ~60 (30 is acceptable on mid-range phones) |
| Tracking | ARCore state; when lost, the reason is shown and the bottom hint says what to do | `tracking` |
| Floors / Surfaces / Walls | Planes currently detected | — |
| Height | Camera height above the detected floor | Matches a tape measure |
| Depth | Whether depth images are used to check the grids | `on` (supported phones) |
| Floor fit | Gap between the floor plane and the real floor measured by depth | Within ±2 cm |
| Cube | Distance from the camera to the reference cube | — |

## Accuracy checks

Record every result in `docs/experiments/` with: date, room, lighting, phone, distance, result.

- **Height:** after tracking starts, rest the phone on a stable edge (chair, table) with the camera facing the floor. Measure floor → camera lens with a tape and compare with **Height**.
- **Cube size:** place the 50 cm cube next to a real reference (a tape laid on the floor, or A4 sheets: 21.0 × 29.7 cm). Look from directly above and compare the edges.
- **Drift:** place the cube on a tile corner, walk around the room for 60 seconds, return to the same spot and measure how far the cube has moved from the corner.

## Common mistakes

- Tapping before the panel says `tracking`: nothing is placed.
- Switching apps or locking the screen mid-session: the session pauses and placed objects may shift. Reopen the AR screen if things look off.
- Starting while pointed at a blank wall or the ceiling: tracking is slow to start.
- Testing in dim light: tracking is lost with `insufficient light`.
- Moving to another room: go back and press **Start AR** again; the previous room's map doesn't carry over.
