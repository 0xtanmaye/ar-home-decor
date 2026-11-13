"""Compare how floors and raised surfaces were drawn in two sets of replay logs.

Usage: python3 tools/compare-replays.py <before-dir> <after-dir>

Each directory holds logs from tools/replay-logs.sh (<recording>-<run>.log). Per recording, runs
summed, from the once-a-second ArSurfaces lines:
- floorAsRaised: seconds in which a raised surface over 1 m² was drawn less than 0.3 m above the
  lowest floor at the time: a piece of floor shown as a table or bed. In brackets, the largest
  such surface in m².
- bedAsFloor: seconds in which one floor was more than 0.3 m above another: a bed or table shown
  as floor.
- changes: plane classifications switched between floor and raised surface (ArPlanes).
Replays differ run to run, so compare totals over several runs, not single seconds.
"""
import glob
import os
import re
import sys

SURFACE = re.compile(
    r"(\d\d:\d\d:\d\d)\.\d+ I/ArSurfaces\(\s*\d+\): P(\d+) (FLOOR|RAISED_SURFACE) y=(-?[\d.]+) "
    r"depthConfirmed=[\d.]+(?: atHeight=[\d.]+)? box=\S+ inside=([\d.]+)"
)
CHANGE = re.compile(r"ArPlanes.*: P\d+ (FLOOR -> RAISED_SURFACE|RAISED_SURFACE -> FLOOR)")

# A floor and a raised surface less than this apart are taken to be the same floor.
SAME_LEVEL_METRES = 0.3
# Raised surfaces smaller than this are left out of floorAsRaised (small pieces come and go).
MIN_AREA_SQUARE_METRES = 1.0


def measure(path):
    seconds, changes = {}, 0
    with open(path, errors="replace") as f:
        for line in f:
            if CHANGE.search(line):
                changes += 1
            m = SURFACE.search(line)
            if m:
                t, _, kind, y, inside = m.groups()
                seconds.setdefault(t, []).append((kind, float(y), float(inside)))
    floor_as_raised, largest, bed_as_floor = 0, 0.0, 0
    for planes in seconds.values():
        floors = [y for kind, y, _ in planes if kind == "FLOOR"]
        if not floors:
            continue
        wrong = [inside for kind, y, inside in planes
                 if kind == "RAISED_SURFACE" and y - min(floors) < SAME_LEVEL_METRES and inside > MIN_AREA_SQUARE_METRES]
        if wrong:
            floor_as_raised += 1
            largest = max(largest, max(wrong))
        if max(floors) - min(floors) > SAME_LEVEL_METRES:
            bed_as_floor += 1
    return len(seconds), floor_as_raised, largest, bed_as_floor, changes


def summarise(directory):
    rows = {}
    for log in sorted(glob.glob(os.path.join(directory, "*.log"))):
        recording = re.sub(r"-\d+\.log$", "", os.path.basename(log))
        logged, far, largest, baf, changes = measure(log)
        row = rows.setdefault(recording, [0, 0, 0, 0.0, 0, 0])
        row[0] += 1
        row[1] += logged
        row[2] += far
        row[3] = max(row[3], largest)
        row[4] += baf
        row[5] += changes
    return rows


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    before, after = summarise(sys.argv[1]), summarise(sys.argv[2])
    header = "runs logged floorAsRaised      bedAsFloor changes"
    print(f"{'':36} {'before':<48} | after")
    print(f"{'recording':<36} {header:<48} | {header}")
    for recording in sorted(set(before) | set(after)):
        cells = []
        for rows in (before, after):
            r = rows.get(recording)
            cells.append(f"{r[0]:4} {r[1]:5}s {r[2]:6}s ({r[3]:4.1f} m²) {r[4]:9}s {r[5]:7}" if r else "-")
        print(f"{recording:<36} {cells[0]:<48} | {cells[1]}")


if __name__ == "__main__":
    main()
