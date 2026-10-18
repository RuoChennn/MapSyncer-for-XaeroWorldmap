# -*- coding: utf-8 -*-
import struct
import sys

sys.path.insert(0, __file__.rsplit("\\", 1)[0].rsplit("/", 1)[0] + "/scripts")
from inspect_xaero_region import load_data, read_utf, parse_block_nbt


def trace(path, limit=40):
    data = load_data(path)
    pos = 5
    block_palette = []
    n = 0

    for tco in range(8):
        for tcp in range(8):
            pos += 1
            for ti in range(4):
                for tj in range(4):
                    for bx in range(16):
                        for bz in range(16):
                            rx, rz = (tco * 4 + ti) * 16 + bx, (tcp * 4 + tj) * 16 + bz
                            params = struct.unpack_from(">I", data, pos)[0]
                            pos += 4
                            if params == 0xFFFFFFFF:
                                continue
                            is_grass = (params & 1) == 0
                            block_new = (params & 0x200000) != 0
                            has_overlay = (params & 2) != 0
                            has_biome = (params & 0x100000) != 0
                            biome_new = (params & 0x400000) != 0
                            top_diff = (params & 0x1000000) != 0
                            before = len(block_palette)
                            bad = False
                            if is_grass:
                                bn = "GRASS"
                            elif block_new:
                                bn, pos = parse_block_nbt(data, pos)
                                block_palette.append(bn)
                            else:
                                idx = struct.unpack_from(">I", data, pos)[0]
                                pos += 4
                                bad = idx >= len(block_palette)
                                bn = (
                                    f"IDX#{idx}"
                                    if bad
                                    else block_palette[idx]
                                )
                            if top_diff:
                                pos += 1
                            if has_overlay:
                                oc = data[pos]
                                pos += 1
                                for _ in range(oc):
                                    op = struct.unpack_from(">I", data, pos)[0]
                                    pos += 4
                                    if (op & 1) != 0:
                                        if op & 0x400:
                                            obn, pos = parse_block_nbt(data, pos)
                                            block_palette.append(obn)
                                        else:
                                            pos += 4
                            if has_biome:
                                if biome_new:
                                    _, pos = read_utf(data, pos)
                                else:
                                    pos += 4
                            n += 1
                            if n <= limit:
                                tag = " BAD" if str(bn).startswith("IDX#") else ""
                                print(
                                    f"#{n:3d} ({rx:3d},{rz:3d}) {bn} "
                                    f"pal {before}->{len(block_palette)} "
                                    f"params=0x{params:08x} new={block_new}{tag}"
                                )
                    pos += 6
                    if n >= limit:
                        return


if __name__ == "__main__":
    import sys
    sys.path.insert(0, __file__.replace("\\", "/").rsplit("/", 1)[0])
    from inspect_xaero_region import load_data, read_utf, parse_block_nbt

    path = sys.argv[1]
    if len(sys.argv) > 2 and sys.argv[2] == "--find-bad":
        data = load_data(path)
        pos = 5
        block_palette = []
        n = 0
        for tco in range(8):
            for tcp in range(8):
                pos += 1
                for ti in range(4):
                    for tj in range(4):
                        for bx in range(16):
                            for bz in range(16):
                                rx, rz = (tco * 4 + ti) * 16 + bx, (tcp * 4 + tj) * 16 + bz
                                params = struct.unpack_from(">I", data, pos)[0]
                                pos += 4
                                if params == 0xFFFFFFFF:
                                    continue
                                is_grass = (params & 1) == 0
                                block_new = (params & 0x200000) != 0
                                has_overlay = (params & 2) != 0
                                has_biome = (params & 0x100000) != 0
                                biome_new = (params & 0x400000) != 0
                                top_diff = (params & 0x1000000) != 0
                                before = len(block_palette)
                                bad = False
                                if is_grass:
                                    bn = "GRASS"
                                elif block_new:
                                    bn, pos = parse_block_nbt(data, pos)
                                    block_palette.append(bn)
                                else:
                                    idx = struct.unpack_from(">I", data, pos)[0]
                                    pos += 4
                                    bad = idx >= len(block_palette)
                                    bn = block_palette[idx] if not bad else f"IDX#{idx}"
                                if top_diff:
                                    pos += 1
                                if has_overlay:
                                    oc = data[pos]
                                    pos += 1
                                    for _ in range(oc):
                                        op = struct.unpack_from(">I", data, pos)[0]
                                        pos += 4
                                        if (op & 1) != 0:
                                            if op & 0x400:
                                                obn, pos = parse_block_nbt(data, pos)
                                                block_palette.append(obn)
                                            else:
                                                pos += 4
                                if has_biome:
                                    if biome_new:
                                        _, pos = read_utf(data, pos)
                                    else:
                                        pos += 4
                                n += 1
                                if bad:
                                    print(f"FIRST BAD #{n} ({rx},{rz}) idx={idx} pal_len={before} params=0x{params:08x}")
                                    for i, b in enumerate(block_palette):
                                        print(f"  [{i}] {b}")
                                    raise SystemExit(0)
                        pos += 6
        print("No bad refs")
    else:
        trace(path)
