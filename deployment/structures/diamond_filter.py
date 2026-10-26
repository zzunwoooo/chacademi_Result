#!/usr/bin/env python3
"""랜덤 구조물(구조물 NBT·스키매틱)에 들어 있는 다이아몬드 블록을 세고, 원하면 다른 블록으로 바꾼 사본을 만든다. (오픈 전 메모 #67)

표준 라이브러리만 쓴다. 원본은 절대 고치지 않는다 — 결과는 항상 새 파일/새 ZIP으로 쓴다.

읽을 수 있는 것
  - 데이터팩 ZIP (예: Chacademia-Terralith-1.21.4-v3.zip) 안의 data/*/structure/**/*.nbt (1.21 이름) 와 structures/ (옛 이름)
  - 폴더 (안의 .nbt / .schem 전부)
  - 파일 하나 (.nbt 구조물 템플릿 또는 .schem Sponge 스키매틱 v2·v3)

사용
  python3 diamond_filter.py count  <ZIP|폴더|파일>                 # 파일별 다이아몬드 블록 개수만 출력 (아무것도 쓰지 않음)
  python3 diamond_filter.py strip  <ZIP|폴더|파일> <출력> [--to minecraft:air]
        ZIP  → 출력 ZIP (구조물만 바뀌고 나머지 파일은 그대로 복사)
        폴더 → 출력 폴더 (같은 상대 경로로)
        파일 → 출력 파일
  --block minecraft:diamond_block 로 찾을 블록을 바꿀 수 있다 (기본 다이아몬드 블록).

바꾸는 방법: 팔레트의 블록 이름만 바꾼다 (블록 위치·개수·다른 블록·블록 엔티티는 그대로).
  구조물 NBT: palette / palettes[*] 의 {Name} 을 바꾼다. 다이아몬드 블록은 속성이 없으므로 바꿀 블록도 속성 없이 둔다.
  스키매틱: Palette 키 이름을 바꾼다. 바꿀 블록이 이미 팔레트에 있으면 BlockData 의 번호를 그 블록 번호로 옮긴다.
"""
import argparse
import gzip
import io
import os
import struct
import sys
import zipfile

# ---------------------------------------------------------------- NBT (Java 판, big-endian)
END, BYTE, SHORT, INT, LONG, FLOAT, DOUBLE, BYTE_ARRAY, STRING, LIST, COMPOUND, INT_ARRAY, LONG_ARRAY = range(13)


class Tag:
    __slots__ = ('type', 'value', 'elem')  # elem: LIST 원소 타입

    def __init__(self, type_, value, elem=END):
        self.type, self.value, self.elem = type_, value, elem


def _read_str(b, i):
    n = struct.unpack_from('>H', b, i)[0]
    i += 2
    return b[i:i + n].decode('utf-8', 'surrogatepass'), i + n


def _read_payload(b, i, t):
    if t == BYTE:
        return Tag(t, struct.unpack_from('>b', b, i)[0]), i + 1
    if t == SHORT:
        return Tag(t, struct.unpack_from('>h', b, i)[0]), i + 2
    if t == INT:
        return Tag(t, struct.unpack_from('>i', b, i)[0]), i + 4
    if t == LONG:
        return Tag(t, struct.unpack_from('>q', b, i)[0]), i + 8
    if t == FLOAT:
        return Tag(t, struct.unpack_from('>f', b, i)[0]), i + 4
    if t == DOUBLE:
        return Tag(t, struct.unpack_from('>d', b, i)[0]), i + 8
    if t == BYTE_ARRAY:
        n = struct.unpack_from('>i', b, i)[0]
        i += 4
        return Tag(t, bytearray(b[i:i + n])), i + n
    if t == STRING:
        s, i = _read_str(b, i)
        return Tag(t, s), i
    if t == LIST:
        et = b[i]
        n = struct.unpack_from('>i', b, i + 1)[0]
        i += 5
        items = []
        for _ in range(max(0, n)):
            v, i = _read_payload(b, i, et)
            items.append(v)
        return Tag(t, items, et), i
    if t == COMPOUND:
        d = {}
        while True:
            ct = b[i]
            i += 1
            if ct == END:
                break
            name, i = _read_str(b, i)
            d[name], i = _read_payload(b, i, ct)
        return Tag(t, d), i
    if t == INT_ARRAY:
        n = struct.unpack_from('>i', b, i)[0]
        i += 4
        return Tag(t, list(struct.unpack_from('>%di' % n, b, i))), i + 4 * n
    if t == LONG_ARRAY:
        n = struct.unpack_from('>i', b, i)[0]
        i += 4
        return Tag(t, list(struct.unpack_from('>%dq' % n, b, i))), i + 8 * n
    raise ValueError('알 수 없는 NBT 태그 %d (위치 %d)' % (t, i))


def _write_str(out, s):
    raw = s.encode('utf-8', 'surrogatepass')
    out += struct.pack('>H', len(raw)) + raw


def _write_payload(out, tag):
    t, v = tag.type, tag.value
    if t == BYTE:
        out += struct.pack('>b', v)
    elif t == SHORT:
        out += struct.pack('>h', v)
    elif t == INT:
        out += struct.pack('>i', v)
    elif t == LONG:
        out += struct.pack('>q', v)
    elif t == FLOAT:
        out += struct.pack('>f', v)
    elif t == DOUBLE:
        out += struct.pack('>d', v)
    elif t == BYTE_ARRAY:
        out += struct.pack('>i', len(v)) + bytes(v)
    elif t == STRING:
        _write_str(out, v)
    elif t == LIST:
        out += bytes([tag.elem if v else (tag.elem or END)]) + struct.pack('>i', len(v))
        for item in v:
            _write_payload(out, item)
    elif t == COMPOUND:
        for name, child in v.items():
            out += bytes([child.type])
            _write_str(out, name)
            _write_payload(out, child)
        out += bytes([END])
    elif t == INT_ARRAY:
        out += struct.pack('>i', len(v)) + struct.pack('>%di' % len(v), *v)
    elif t == LONG_ARRAY:
        out += struct.pack('>i', len(v)) + struct.pack('>%dq' % len(v), *v)
    else:
        raise ValueError('알 수 없는 NBT 태그 %d' % t)


def load(data):
    """(gzip 이었는지, 루트 이름, 루트 태그)."""
    gz = data[:2] == b'\x1f\x8b'
    raw = gzip.decompress(data) if gz else data
    if not raw or raw[0] != COMPOUND:
        raise ValueError('NBT 루트가 compound 가 아님')
    name, i = _read_str(raw, 1)
    root, _ = _read_payload(raw, i, COMPOUND)
    return gz, name, root


def dump(gz, name, root):
    out = bytearray([COMPOUND])
    _write_str(out, name)
    _write_payload(out, root)
    raw = bytes(out)
    return gzip.compress(raw, mtime=0) if gz else raw


# ---------------------------------------------------------------- 구조물 템플릿 (.nbt)

def _template_palettes(root):
    v = root.value
    if 'palette' in v:
        yield v['palette'].value
    if 'palettes' in v:
        for p in v['palettes'].value:
            yield p.value


def template(root, block, to):
    """(찾은 개수, 바꿨는지). to 가 None 이면 세기만."""
    v = root.value
    if 'blocks' not in v or ('palette' not in v and 'palettes' not in v):
        raise ValueError('구조물 템플릿이 아님 (blocks/palette 없음)')
    hits = set()
    for palette in _template_palettes(root):
        for idx, state in enumerate(palette):
            name = state.value.get('Name')
            if name is not None and name.value == block:
                hits.add(idx)
    count = 0
    for b in v['blocks'].value:
        st = b.value.get('state')
        if st is not None and st.value in hits:
            count += 1
    changed = False
    if to is not None and hits:
        for palette in _template_palettes(root):
            for idx in hits:
                if idx < len(palette) and palette[idx].value.get('Name') is not None and palette[idx].value['Name'].value == block:
                    palette[idx].value['Name'] = Tag(STRING, to)
                    palette[idx].value.pop('Properties', None)
                    changed = True
    return count, changed


# ---------------------------------------------------------------- Sponge 스키매틱 (.schem v2·v3)

def _varints(data):
    out, i, n = [], 0, len(data)
    while i < n:
        value, shift = 0, 0
        while True:
            byte = data[i]
            i += 1
            value |= (byte & 0x7F) << shift
            if not byte & 0x80:
                break
            shift += 7
        out.append(value)
    return out


def _encode_varints(values):
    out = bytearray()
    for value in values:
        while True:
            byte = value & 0x7F
            value >>= 7
            if value:
                out.append(byte | 0x80)
            else:
                out.append(byte)
                break
    return out


def _schem_blocks(root):
    v = root.value
    if 'Schematic' in v and v['Schematic'].type == COMPOUND:  # v3: 루트 안 Schematic
        v = v['Schematic'].value
    if 'Blocks' in v and v['Blocks'].type == COMPOUND:  # v3
        b = v['Blocks'].value
        return b, 'Palette', 'Data'
    if 'Palette' in v and 'BlockData' in v:  # v2
        return v, 'Palette', 'BlockData'
    raise ValueError('스키매틱이 아님 (Palette/BlockData 없음)')


def _strip_props(state):
    return state.split('[', 1)[0]


def schematic(root, block, to):
    holder, pal_key, data_key = _schem_blocks(root)
    palette = holder[pal_key].value  # 이름 → INT 태그
    ids = {tag.value for name, tag in palette.items() if _strip_props(name) == block}
    values = _varints(holder[data_key].value)
    count = sum(1 for x in values if x in ids)
    changed = False
    if to is not None and ids:
        existing = palette.get(to)
        if existing is None:
            # 다이아몬드 블록 항목 하나를 바꿀 블록 이름으로, 나머지(있다면)는 같은 번호로 모은다
            keep = min(ids)
            for name in [n for n, t in palette.items() if t.value in ids]:
                del palette[name]
            palette[to] = Tag(INT, keep)
            target = keep
        else:
            target = existing.value
            for name in [n for n, t in palette.items() if t.value in ids]:
                del palette[name]
        if any(x in ids and x != target for x in values):
            holder[data_key] = Tag(BYTE_ARRAY, _encode_varints([target if x in ids else x for x in values]))
        if 'PaletteMax' in holder:
            holder['PaletteMax'] = Tag(INT, len(palette))
        changed = True
    return count, changed


# ---------------------------------------------------------------- 실행

def process(name, data, block, to):
    """(개수, 새 바이트 또는 None, 오류 문구 또는 None)."""
    try:
        gz, root_name, root = load(data)
        if name.lower().endswith('.schem') or name.lower().endswith('.schematic'):
            count, changed = schematic(root, block, to)
        else:
            count, changed = template(root, block, to)
        return count, (dump(gz, root_name, root) if changed else None), None
    except Exception as e:  # 한 파일이 깨져도 나머지는 계속
        return 0, None, str(e)


def structure_file(path):
    p = path.replace('\\', '/').lower()
    if p.endswith('.schem') or p.endswith('.schematic'):
        return True
    return p.endswith('.nbt') and ('/structure/' in p or '/structures/' in p or p.count('/') == 0)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('mode', choices=['count', 'strip'])
    ap.add_argument('source')
    ap.add_argument('output', nargs='?')
    ap.add_argument('--block', default='minecraft:diamond_block')
    ap.add_argument('--to', default='minecraft:air')  # 오너 2026-10-26: 비워 둠
    a = ap.parse_args()
    to = a.to if a.mode == 'strip' else None
    if a.mode == 'strip':
        if not a.output:
            ap.error('strip 에는 출력 경로가 필요합니다')
        if os.path.abspath(a.output) == os.path.abspath(a.source):
            ap.error('원본을 덮어쓸 수 없습니다. 다른 출력 경로를 주세요')
        if os.path.exists(a.output) and not os.path.isdir(a.source):
            ap.error('출력 파일이 이미 있습니다: ' + a.output)

    total, files, errors, changed = 0, 0, 0, 0
    rows = []

    def report(name, count, err):
        nonlocal total, files, errors
        if err:
            errors += 1
            rows.append('  [읽기 실패] %s: %s' % (name, err))
        elif count:
            files += 1
            total += count
            rows.append('  %6d  %s' % (count, name))

    if zipfile.is_zipfile(a.source):
        with zipfile.ZipFile(a.source) as zin:
            zout = zipfile.ZipFile(a.output, 'w', zipfile.ZIP_DEFLATED) if to else None
            try:
                for info in zin.infolist():
                    data = zin.read(info.filename)
                    if not info.is_dir() and structure_file(info.filename):
                        count, new, err = process(info.filename, data, a.block, to)
                        report(info.filename, count, err)
                        if new is not None:
                            data = new
                            changed += 1
                    if zout:
                        zout.writestr(info, data)
            finally:
                if zout:
                    zout.close()
    elif os.path.isdir(a.source):
        for base, _, names in os.walk(a.source):
            for n in sorted(names):
                full = os.path.join(base, n)
                rel = os.path.relpath(full, a.source)
                if not (n.lower().endswith('.nbt') or n.lower().endswith('.schem') or n.lower().endswith('.schematic')):
                    continue
                with open(full, 'rb') as f:
                    data = f.read()
                count, new, err = process(n, data, a.block, to)
                report(rel, count, err)
                if to and new is not None:
                    dest = os.path.join(a.output, rel)
                    os.makedirs(os.path.dirname(dest), exist_ok=True)
                    with open(dest, 'wb') as f:
                        f.write(new)
                    changed += 1
    else:
        with open(a.source, 'rb') as f:
            data = f.read()
        count, new, err = process(os.path.basename(a.source), data, a.block, to)
        report(os.path.basename(a.source), count, err)
        if to and new is not None:
            with open(a.output, 'wb') as f:
                f.write(new)
            changed += 1

    print('%s 개수 (파일별, 0개인 파일은 생략)' % a.block)
    print('\n'.join(rows) if rows else '  (없음)')
    print('합계: %d개 · 들어 있는 파일 %d개 · 읽기 실패 %d개' % (total, files, errors))
    if to:
        print('바꾼 파일 %d개 → %s (%s 로 교체). 원본은 그대로입니다.' % (changed, a.output, to))
        if os.path.isdir(a.source) and changed == 0:
            print('바꿀 파일이 없어 출력 폴더에 아무것도 쓰지 않았습니다.')
    return 1 if errors else 0


if __name__ == '__main__':
    sys.exit(main())
