"""Generate cube models: the reference cube used to check AR scale accuracy, and markers.

Writes a binary glTF (.glb) cube with the given edge length in metres. The origin
is at the centre of the bottom face, so the cube sits flat on the surface it is
placed on. The optional colour is linear RGB, e.g. 0.9,0.38,0.08 (the default orange).

Usage: python3 make_reference_cube.py <edge-metres> <output.glb> [r,g,b]
"""

import json
import struct
import sys

# (normal, the two in-plane axes) for each face, so every face gets its own 4 vertices
# with a flat normal and lighting shows the edges clearly.
FACES = [
    ((1, 0, 0), (0, 1, 0), (0, 0, 1)),
    ((-1, 0, 0), (0, 0, 1), (0, 1, 0)),
    ((0, 1, 0), (0, 0, 1), (1, 0, 0)),
    ((0, -1, 0), (1, 0, 0), (0, 0, 1)),
    ((0, 0, 1), (1, 0, 0), (0, 1, 0)),
    ((0, 0, -1), (0, 1, 0), (1, 0, 0)),
]


def build_geometry(edge: float):
    half = edge / 2
    positions, normals, indices = [], [], []
    for normal, u, v in FACES:
        base = len(positions)
        for su, sv in ((-1, -1), (1, -1), (1, 1), (-1, 1)):
            p = [normal[i] * half + u[i] * su * half + v[i] * sv * half for i in range(3)]
            p[1] += half  # move origin to the bottom face
            positions.append(p)
            normals.append(list(normal))
        # u x v == normal for every face, so this winding is counter-clockwise from outside.
        indices += [base, base + 1, base + 2, base, base + 2, base + 3]
    return positions, normals, indices


def pad(data: bytes, fill: bytes) -> bytes:
    return data + fill * ((4 - len(data) % 4) % 4)


DEFAULT_COLOR = (0.9, 0.38, 0.08)


def build_glb(edge: float, color=DEFAULT_COLOR) -> bytes:
    positions, normals, indices = build_geometry(edge)
    pos_bytes = b"".join(struct.pack("<3f", *p) for p in positions)
    nrm_bytes = b"".join(struct.pack("<3f", *n) for n in normals)
    idx_bytes = pad(struct.pack(f"<{len(indices)}H", *indices), b"\x00")
    binary = pos_bytes + nrm_bytes + idx_bytes

    gltf = {
        "asset": {"version": "2.0"},
        "scene": 0,
        "scenes": [{"nodes": [0]}],
        "nodes": [{"mesh": 0, "name": "ReferenceCube"}],
        "meshes": [{
            "primitives": [{
                "attributes": {"POSITION": 0, "NORMAL": 1},
                "indices": 2,
                "material": 0,
            }],
        }],
        "materials": [{
            "name": "CubeColor",
            "pbrMetallicRoughness": {
                "baseColorFactor": [*color, 1.0],
                "metallicFactor": 0.0,
                "roughnessFactor": 0.7,
            },
        }],
        "buffers": [{"byteLength": len(binary)}],
        "bufferViews": [
            {"buffer": 0, "byteOffset": 0, "byteLength": len(pos_bytes), "target": 34962},
            {"buffer": 0, "byteOffset": len(pos_bytes), "byteLength": len(nrm_bytes), "target": 34962},
            {"buffer": 0, "byteOffset": len(pos_bytes) + len(nrm_bytes),
             "byteLength": len(indices) * 2, "target": 34963},
        ],
        "accessors": [
            {"bufferView": 0, "componentType": 5126, "count": len(positions), "type": "VEC3",
             "min": [min(p[i] for p in positions) for i in range(3)],
             "max": [max(p[i] for p in positions) for i in range(3)]},
            {"bufferView": 1, "componentType": 5126, "count": len(normals), "type": "VEC3"},
            {"bufferView": 2, "componentType": 5123, "count": len(indices), "type": "SCALAR"},
        ],
    }

    json_chunk = pad(json.dumps(gltf, separators=(",", ":")).encode(), b" ")
    chunks = (
        struct.pack("<I4s", len(json_chunk), b"JSON") + json_chunk
        + struct.pack("<I4s", len(binary), b"BIN\x00") + binary
    )
    return struct.pack("<4sII", b"glTF", 2, 12 + len(chunks)) + chunks


def main() -> None:
    if len(sys.argv) not in (3, 4):
        sys.exit(__doc__)
    edge = float(sys.argv[1])
    color = tuple(float(c) for c in sys.argv[3].split(",")) if len(sys.argv) == 4 else DEFAULT_COLOR
    if len(color) != 3:
        sys.exit("colour must be three numbers: r,g,b")
    with open(sys.argv[2], "wb") as f:
        f.write(build_glb(edge, color))


if __name__ == "__main__":
    main()
