# Testing guide

How to run the AR view so that results are reliable and repeatable.

## Before a session

- **Phone:** battery above 30% or plugged in, battery saver **off** (it throttles the GPU and lowers FPS), camera lens wiped clean. For sessions longer than about 15 minutes, keep it charging.
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

## Marking a plain wall

Walls without texture can't be detected automatically yet, so they are marked by hand:

1. Scan the floor first, including the area right in front of the wall.
2. Tap **Mark wall** at the bottom of the screen.
3. Tap the floor **exactly where it meets the wall** (on the line at the base of the wall or skirting board). A small pink marker appears.
4. Tap a second point along the same base line, at least 20 cm away. The further apart the two taps, the more accurate the wall's direction; aim for 1 m or more.
5. The wall appears as a pink grid from the floor to the ceiling (or 2.8 m). With depth on, it's trimmed where the real wall ends and hidden behind furniture in front of it.
6. **Wall fit** shows how far the marked wall is from the real wall measured by depth (within ±3 cm is good). **Clear walls** removes all marked walls.

Tip: tap from 1–2 m away, looking at the base of the wall at an angle rather than straight down.

**Steps, pillars and recesses:** a marked wall is one flat face. Shallow steps (up to about 10 cm) are bridged, but a pillar or a deeper step is a separate face: mark its front, and each side if needed, with its own two taps. A marked wall extends about 3 m past your taps, and depth cuts it back to where the real wall ends, so tapping near the middle of a wall is enough.

**Fit readings** show "(NN% on surface)": the share of the view that saw the surface itself rather than furniture or curtains in front of it. Readings with a higher share are more trustworthy.

## Reading the debug panel

| Line | Meaning | Good value |
|---|---|---|
| FPS | Frames drawn per second. The view is only redrawn when the camera delivers a new image, so this matches the camera rate | ~30 (steady) |
| Tracking | ARCore state; when lost, the reason is shown and the bottom hint says what to do | `tracking` |
| Floors / Surfaces / Walls | Planes currently detected (walls: detected by ARCore, and marked by hand) | — |
| Height | Camera height above the detected floor | Matches a tape measure |
| Depth | Whether depth images are used to check the grids | `on` (supported phones) |
| Floor fit | Gap between the floor plane and the real floor measured by depth | Within ±2 cm |
| Wall fit | Gap between marked walls and the real walls measured by depth (+ = in front) | Within ±3 cm |
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
