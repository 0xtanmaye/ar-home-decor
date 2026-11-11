"""Find Android devices advertising wireless debugging on the local network.

Sends an mDNS query for the `_adb-tls-connect._tcp` service from each given
local IPv4 address and prints one `ip:port` line per device found.

Usage: python3 find_adb_devices.py <local-ip> [<local-ip> ...]
"""

import socket
import struct
import sys
import time

MDNS_GROUP = ("224.0.0.251", 5353)
SERVICE = "_adb-tls-connect._tcp.local"
TYPE_A, TYPE_PTR, TYPE_SRV = 1, 12, 33
QU_CLASS_IN = 0x8001  # class IN with the "unicast response" bit set
LISTEN_SECONDS = 3.0


def encode_name(name: str) -> bytes:
    return b"".join(bytes([len(p)]) + p.encode() for p in name.split(".")) + b"\x00"


def build_query(name: str, qtype: int) -> bytes:
    header = struct.pack(">HHHHHH", 0, 0, 1, 0, 0, 0)
    return header + encode_name(name) + struct.pack(">HH", qtype, QU_CLASS_IN)


def read_name(data: bytes, offset: int) -> tuple[str, int]:
    """Decode a DNS name, following compression pointers. Returns (name, offset after name)."""
    labels: list[str] = []
    end = None
    for _ in range(128):  # guard against pointer loops in malformed packets
        length = data[offset]
        if length & 0xC0 == 0xC0:
            if end is None:
                end = offset + 2
            offset = ((length & 0x3F) << 8) | data[offset + 1]
            continue
        if length == 0:
            offset += 1
            break
        labels.append(data[offset + 1 : offset + 1 + length].decode("utf-8", "replace"))
        offset += 1 + length
    return ".".join(labels), end if end is not None else offset


def parse_records(data: bytes) -> list[tuple[str, int, bytes, int]]:
    """Return (name, type, rdata, rdata_offset) for every answer/authority/additional record."""
    _, _, qd, an, ns, ar = struct.unpack(">HHHHHH", data[:12])
    offset = 12
    for _ in range(qd):
        _, offset = read_name(data, offset)
        offset += 4
    records = []
    for _ in range(an + ns + ar):
        name, offset = read_name(data, offset)
        rtype, _, _, rdlen = struct.unpack(">HHIH", data[offset : offset + 10])
        offset += 10
        records.append((name, rtype, data[offset : offset + rdlen], offset))
        offset += rdlen
    return records


def probe(local_ip: str) -> set[str]:
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_IF, socket.inet_aton(local_ip))
    sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 255)
    sock.bind((local_ip, 0))
    sock.settimeout(0.3)

    srv: dict[str, tuple[str, int]] = {}  # instance name -> (target host, port)
    hosts: dict[str, str] = {}  # host name -> IPv4 address
    sources: dict[str, str] = {}  # instance name -> address the reply came from
    asked_srv: set[str] = set()

    for _ in range(2):
        sock.sendto(build_query(SERVICE, TYPE_PTR), MDNS_GROUP)

    deadline = time.time() + LISTEN_SECONDS
    while time.time() < deadline:
        try:
            data, (src, _) = sock.recvfrom(9000)
            records = parse_records(data)
        except socket.timeout:
            continue
        except (IndexError, struct.error):
            continue  # malformed packet from some other device
        for name, rtype, rdata, rdata_offset in records:
            if rtype == TYPE_PTR and name.lower() == SERVICE.lower():
                instance, _ = read_name(data, rdata_offset)
                sources.setdefault(instance, src)
                if instance not in srv and instance not in asked_srv:
                    asked_srv.add(instance)
                    sock.sendto(build_query(instance, TYPE_SRV), MDNS_GROUP)
            elif rtype == TYPE_SRV and SERVICE.lower() in name.lower():
                _, _, port = struct.unpack(">HHH", rdata[:6])
                target, _ = read_name(data, rdata_offset + 6)
                srv[name] = (target, port)
                sources.setdefault(name, src)
            elif rtype == TYPE_A and len(rdata) == 4:
                hosts[name] = socket.inet_ntoa(rdata)
    sock.close()

    return {f"{hosts.get(target, sources[name])}:{port}" for name, (target, port) in srv.items()}


def main() -> None:
    found: set[str] = set()
    for local_ip in sys.argv[1:]:
        try:
            found |= probe(local_ip)
        except OSError as e:
            print(f"skipping {local_ip}: {e}", file=sys.stderr)
    for address in sorted(found):
        print(address)


if __name__ == "__main__":
    main()
