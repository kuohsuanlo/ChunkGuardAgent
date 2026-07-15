#!/usr/bin/env python3
"""Minimal, self-contained Anvil (.mca) reader for the ChunkGuardAgent validation harness.

Subcommands:
  info   <region.mca> <cx> <cz>   -> "<Status> <block_entities_count> <sections_count>"
  digest <region.mca> <cx> <cz>   -> md5 of ONLY that chunk's raw payload (inline or external .mcc)

`digest` is header/timestamp/neighbour agnostic: a byte-identical payload means the target chunk's
content was preserved exactly. No third-party dependencies.
"""
import sys, os, struct, zlib, gzip, hashlib

def _read_payload(region, cx, cz):
    data = open(region, 'rb').read()
    if len(data) < 8192:
        return None, None
    rx, rz = (int(v) for v in os.path.basename(region).split('.')[1:3])
    idx = (cx - rx * 32) + (cz - rz * 32) * 32
    e = data[idx * 4: idx * 4 + 4]
    off = (e[0] << 16) | (e[1] << 8) | e[2]
    sectors = e[3]
    if not (off and sectors):
        return None, None
    st = off * 4096
    length = struct.unpack('>i', data[st:st + 4])[0]
    comp = data[st + 4]
    if comp & 0x80:  # external .mcc
        mcc = os.path.join(os.path.dirname(region), "c.%d.%d.mcc" % (cx, cz))
        payload = open(mcc, 'rb').read() if os.path.exists(mcc) else None
        return payload, (comp & 0x7f)
    return data[st + 5: st + 4 + length], comp

class _R:
    def __init__(self, b): self.b, self.i = b, 0
    def u1(self): v = self.b[self.i]; self.i += 1; return v
    def n(self, n): v = self.b[self.i:self.i + n]; self.i += n; return v
    def i2(self): return struct.unpack('>h', self.n(2))[0]
    def i4(self): return struct.unpack('>i', self.n(4))[0]
    def i8(self): return struct.unpack('>q', self.n(8))[0]
    def name(self): return self.n(struct.unpack('>H', self.n(2))[0]).decode('utf-8', 'replace')

def _val(r, t):
    if t == 1: return r.u1()
    if t == 2: return r.i2()
    if t == 3: return r.i4()
    if t == 4: return r.i8()
    if t == 5: return struct.unpack('>f', r.n(4))[0]
    if t == 6: return struct.unpack('>d', r.n(8))[0]
    if t == 7: return r.n(r.i4())
    if t == 8: return r.n(struct.unpack('>H', r.n(2))[0]).decode('utf-8', 'replace')
    if t == 9:
        et = r.u1(); ln = r.i4(); return [_val(r, et) for _ in range(ln)]
    if t == 10:
        d = {}
        while True:
            tt = r.u1()
            if tt == 0: break
            k = r.name()   # wire order is name-then-value; d[r.name()]=_val(...) would read the value first
            d[k] = _val(r, tt)
        return d
    if t == 11: return [r.i4() for _ in range(r.i4())]
    if t == 12: return [r.i8() for _ in range(r.i4())]
    raise ValueError("bad tag %d" % t)

def _parse(buf, comp):
    raw = gzip.decompress(buf) if comp == 1 else zlib.decompress(buf) if comp == 2 else buf
    r = _R(raw); t = r.u1()
    # Region chunk roots come in two shapes: the classic empty-NAMED root (0A 00 00 <body>) and the
    # nameless root newer Paper writes (0A <body>, body starting straight at the first inner tag).
    # An empty name reads as u16 0; anything else means those bytes already belong to the body.
    if len(raw) >= r.i + 2 and raw[r.i] == 0 and raw[r.i + 1] == 0:
        r.i += 2
    return _val(r, t)

def main():
    if len(sys.argv) < 5:
        print("usage: mca_read.py info|digest <region.mca> <cx> <cz>"); sys.exit(2)
    mode, region, cx, cz = sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4])
    if not os.path.exists(region):
        print("NOFILE" if mode == "digest" else "NOFILE 0 0"); return
    payload, comp = _read_payload(region, cx, cz)
    if payload is None:
        print("ABSENT" if mode == "digest" else "ABSENT 0 0"); return
    if mode == "digest":
        print(hashlib.md5(payload).hexdigest()); return
    try:
        root = _parse(payload, comp)
        be = root.get('block_entities'); se = root.get('sections')
        print((root.get('Status') or '?'),
              len(be) if isinstance(be, list) else 0,
              len(se) if isinstance(se, list) else 0)
    except Exception:
        print("PARSE_ERR 0 0")

if __name__ == "__main__":
    main()
