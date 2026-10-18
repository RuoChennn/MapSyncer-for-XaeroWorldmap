# -*- coding: utf-8 -*-
"""Parse region.xaero (raw or inside .zip) for inspection."""
import struct
import zipfile
import collections
import sys
import io

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')


def read_utf(data, pos):
    ln = struct.unpack_from('>H', data, pos)[0]
    pos += 2
    s = data[pos : pos + ln].decode('utf-8', errors='replace')
    return s, pos + ln


def parse_block_nbt(data, pos):
    if data[pos] != 10:
        raise ValueError(f'expected TAG_Compound at {pos}, got {data[pos]}')
    pos += 1
    pos += 2
    block_name = None
    while True:
        tag = data[pos]
        pos += 1
        if tag == 0:
            break
        field_name, pos = read_utf(data, pos)
        if tag == 8:
            val, pos = read_utf(data, pos)
            if field_name == 'Name':
                block_name = val
        elif tag == 10:
            while True:
                t2 = data[pos]
                pos += 1
                if t2 == 0:
                    break
                _, pos = read_utf(data, pos)
                if t2 == 8:
                    _, pos = read_utf(data, pos)
                else:
                    raise ValueError(f'unhandled nested tag {t2}')
        else:
            raise ValueError(f'unhandled tag {tag}')
    return block_name, pos


def load_data(path):
    if path.lower().endswith('.zip'):
        with zipfile.ZipFile(path, 'r') as z:
            return z.read('region.xaero')
    return open(path, 'rb').read()


def parse(data):
    pos = 5
    block_palette = []
    biome_palette = []
    skipped = filled = air = 0
    block_counts = collections.Counter()
    biome_counts = collections.Counter()
    light_counts = collections.Counter()
    height_counts = collections.Counter()
    footers = []
    sample_pixels = []

    for tco in range(8):
        for tcp in range(8):
            if pos >= len(data):
                break
            pos += 1
            for ti in range(4):
                for tj in range(4):
                    cx, cz = tco * 4 + ti, tcp * 4 + tj
                    for bx in range(16):
                        for bz in range(16):
                            rx, rz = cx * 16 + bx, cz * 16 + bz
                            if pos + 4 > len(data):
                                raise RuntimeError(f'truncated at ({rx},{rz}) pos={pos}')
                            params = struct.unpack_from('>I', data, pos)[0]
                            pos += 4
                            if params == 0xFFFFFFFF:
                                skipped += 1
                                continue
                            filled += 1
                            is_grass = (params & 1) == 0
                            has_overlay = (params & 2) != 0
                            has_biome = (params & 0x100000) != 0
                            block_new = (params & 0x200000) != 0
                            biome_new = (params & 0x400000) != 0
                            top_diff = (params & 0x1000000) != 0
                            light = (params >> 8) & 0xF
                            height = ((params >> 12) & 0xFF) | ((params >> 25) & 0xF) << 8
                            light_counts[light] += 1
                            height_counts[height] += 1

                            if is_grass:
                                block_name = 'minecraft:grass_block'
                            elif block_new:
                                block_name, pos = parse_block_nbt(data, pos)
                                block_palette.append(block_name)
                            else:
                                idx = struct.unpack_from('>I', data, pos)[0]
                                pos += 4
                                block_name = (
                                    block_palette[idx]
                                    if idx < len(block_palette)
                                    else f'INVALID#{idx}'
                                )

                            if block_name and 'air' in block_name:
                                air += 1

                            top_height = height
                            if top_diff:
                                top_height = struct.unpack_from('b', data, pos)[0]
                                pos += 1

                            if has_overlay:
                                oc = data[pos]
                                pos += 1
                                for _ in range(oc):
                                    op = struct.unpack_from('>I', data, pos)[0]
                                    pos += 4
                                    if (op & 1) != 0:
                                    if (op & 0x400:
                                        obn, pos = parse_block_nbt(data, pos)
                                        block_palette.append(obn)
                                        else:
                                            pos += 4

                            biome_name = None
                            if has_biome:
                                if biome_new:
                                    biome_name, pos = read_utf(data, pos)
                                    biome_palette.append(biome_name)
                                else:
                                    idx = struct.unpack_from('>I', data, pos)[0]
                                    pos += 4
                                    biome_name = (
                                        biome_palette[idx]
                                        if idx < len(biome_palette)
                                        else f'BINVALID#{idx}'
                                    )
                                biome_counts[biome_name] += 1

                            block_counts[block_name] += 1
                            if len(sample_pixels) < 8 and block_name:
                                sample_pixels.append(
                                    {
                                        'x': rx,
                                        'z': rz,
                                        'block': block_name,
                                        'biome': biome_name,
                                        'light': light,
                                        'height': height,
                                        'params': params,
                                    }
                                )

                    if pos + 6 > len(data):
                        raise RuntimeError(f'truncated footer tile ({cx},{cz}) pos={pos}')
                    wver = data[pos]
                    cs = struct.unpack_from('>I', data, pos + 1)[0]
                    cd = data[pos + 5]
                    footers.append((cx, cz, wver, cs, cd))
                    pos += 6

    return {
        'size': len(data),
        'pos_end': pos,
        'filled': filled,
        'skipped': skipped,
        'air': air,
        'block_counts': block_counts,
        'biome_counts': biome_counts,
        'light_counts': light_counts,
        'height_counts': height_counts,
        'footers': footers,
        'block_palette': block_palette,
        'biome_palette': biome_palette,
        'samples': sample_pixels,
    }


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else r'region compare\minus2_1\region.xaero'
    data = load_data(path)
    print(f'File: {path}')
    print(f'magic=0x{data[0]:02x} version={(struct.unpack_from(">I", data, 1)[0] >> 16)}.{struct.unpack_from(">I", data, 1)[0] & 0xFFFF}')
    print(f'size={len(data)} bytes')
    r = parse(data)
    print(f'parsed bytes: {r["pos_end"]}/{r["size"]} (delta={r["size"]-r["pos_end"]})')
    print()
    print('=== Pixels ===')
    print(f'filled={r["filled"]} skipped(-1)={r["skipped"]} air_in_filled={r["air"]}')
    print()
    print('=== Tile footers (unique) ===')
    for item in sorted(set((w, cs, cd) for _, _, w, cs, cd in r['footers'])):
        wver, cs, cd = item
        cs_label = cs if cs < 2_000_000_000 else ('MAX' if cs == 0x7FFFFFFF else str(cs))
        print(f'  worldVer={wver} caveStart={cs_label} caveDepth={cd}')
    print(f'tiles with footer: {len(r["footers"])}')
    print()
    print('=== Blocks (top 15) ===')
    for k, v in r['block_counts'].most_common(15):
        print(f'  {k}: {v}')
    print()
    print('=== Biomes (top 10) ===')
    for k, v in r['biome_counts'].most_common(10):
        print(f'  {k}: {v}')
    print()
    print('=== Light ===')
    for k, v in sorted(r['light_counts'].items()):
        print(f'  {k}: {v}')
    print()
    print('=== Height Y (top 12) ===')
    for k, v in r['height_counts'].most_common(12):
        print(f'  Y={k}: {v}')
    print()
    print('=== Sample pixels ===')
    for p in r['samples']:
        print(f'  ({p["x"]},{p["z"]}) {p["block"]} biome={p["biome"]} light={p["light"]} Y={p["height"]} params=0x{p["params"]:08X}')


if __name__ == '__main__':
    main()
