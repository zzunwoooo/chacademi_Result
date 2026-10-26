"""2026-10-25 신규 마법 63종 (325~387, 기획: 프로젝트 문서 claude/magic-325-387-plan.md, VFX: PC 차카데미 vfx_Claude\\<Bundle>).
오너: "실제 스킬도 만들어 ... 필요한 추가 모드 수정이 있다면 해도 돼". 마나/쿨/피해/반경은 기획의 임시값 (밸런스 미확정).

Source of truth for these spells: the rows in tools/nova/{basic,mid,high,ult,life}.py (one list M each, same row shape as
arcana_spells.py). `tools/spells.py` imports routes() / EXTRA; `python3 tools/nova_spells.py` writes (in place, other entries untouched):
  magic-codex-fabric  default-spells/<order>_<id>.yml (+ index.txt, known-hashes.tsv)
  magic-codex-paper   mana-spells.yml
  portable-vfx        spell-catalog.yml / spell-bindings.yml  (server-events phases: one phase per VFX system the mechanic signals)
  docs/handoff/NOVA_325_387.md
Gameplay = chanova{type=<id>;pack=<bundle lower>} (NovaMech + NovaBasic/Mid/High/Ult/Life.java) or plain cha* lines.

Row: (order, id, name, category, target, range, base, ratio, mana, cooldown, [mythic lines], note(구현 요약), temp(임시값),
      description(도감), discovery(기획 발견 조건), research(None), bundle(PC 폴더 이름), holdTicks, [phases], enabled)
"""
import os, re, sys, hashlib
HERE = os.path.dirname(os.path.abspath(__file__)); ROOT = os.path.dirname(HERE); REPO = os.path.dirname(ROOT)
sys.path.insert(0, HERE)
import arcana_spells as _a
from nova.common import N, ev, cast  # noqa: F401
from nova import basic, mid, mid2, high, ult, life, life2, life3   # noqa: E402  (they import N/ev/cast from here)
M = basic.M + mid.M + mid2.M + high.M + ult.M + life.M + life2.M + life3.M
# 전투 마법 (기초·중급·상급·궁극): spells.py 의 #80 사거리 1.7배 규칙을 받는 범위
COMBAT = {r[1] for r in basic.M + mid.M + mid2.M + high.M + ult.M}
EXTRA = {}
for _m in (basic, mid, mid2, high, ult, life, life2, life3): EXTRA.update(getattr(_m, 'EXTRA', {}))

def routes():
    # utility = the MagicCodexBridge handles it (SpellUtility / LifeSpells); the Mythic skill only notes the cast
    return [(i, 'CHA_' + i, tgt, rng, base, ratio, all(l.startswith('chanote') for l in lines), lines, note) for (o, i, nm, cat, tgt, rng, base, ratio, mana, cd, lines, note, *_r) in M]

MARK = '  # ---- 325~387 신규 마법 (magic-mythic-paper/tools/nova_spells.py) ----'

def write_all():
    ids = [r[1] for r in M]; assert len(ids) == len(set(ids)), 'duplicate id'
    import spell_order   # 2026-10-25 renumber: number/order come from tools/spell_order.py
    rows = sorted((spell_order.number(i, o), i, nm, cat, mana, cd, en, desc, disc, res, bundle.lower(), hold, P)
                  for (o, i, nm, cat, tgt, rng, base, ratio, mana, cd, lines, note, temp, desc, disc, res, bundle, hold, P, en) in M)
    folders = {bundle.lower(): bundle for (*_x, bundle, hold, P, en) in M}
    ds = os.path.join(REPO, 'magic-codex-fabric/src/main/resources/assets/magiccodex/default-spells')
    idx = [l for l in _a._read(os.path.join(ds, 'index.txt')).split('\n') if l.strip()]
    hashes = [l.rstrip('\r') for l in open(os.path.join(ds, 'known-hashes.tsv'), encoding='utf-8', newline='').read().split('\n') if l.strip()]
    for (o, i, nm, cat, mana, cd, en, desc, disc, res, *_r) in rows:
        fn = f'{o:03d}_{i}.yml'; path = os.path.join(ds, fn)
        old = _a._read(path) if os.path.exists(path) else ''
        icon = (re.search(r'(?m)^icon: (.*)$', old) or [None, "''"])[1]
        y = (f"id: {i}\nname: {nm}\ncategory: {cat}\ndescription: {_a._q(desc)}\nicon: {icon}\npermission: magic.learned.{i}\n"
             f"discovery:\n  description: {_a._q(disc) if disc else _a._q('아직 알려지지 않았습니다.')}\nresearch: {_a._q(res) if res else chr(39)*2}\n"
             f"cast:\n  command: 마법 {i}\n  cooldown-seconds: {cd}\n  mana-cost: {mana}\norder: {o}\n")
        _a._write_if(path, y)
        if f'{o:03d}_{i}' not in idx: idx.append(f'{o:03d}_{i}')
        h = hashlib.sha256(y.encode('utf-8')).hexdigest()
        if f'{fn}\t{h}' not in hashes: hashes.append(f'{fn}\t{h}')
    idx.sort(key=lambda l: (int(l.split('_', 1)[0]) if l.split('_', 1)[0].isdigit() else 9999, l))
    _a._write_if(os.path.join(ds, 'index.txt'), '\n'.join(idx) + '\n')
    with open(os.path.join(ds, 'known-hashes.tsv'), 'w', encoding='utf-8', newline='') as f: f.write('\r\n'.join(hashes) + '\r\n')
    _a._upsert(os.path.join(REPO, 'magic-codex-paper/src/main/resources/mana-spells.yml'), [
        (i, (lambda old, i=i, nm=nm, mana=mana, cd=cd, en=en:
             f"  {i}:\n    name: {nm}\n    enabled: {'true' if en else 'false'}\n    command: 마법 {i}\n    permission: magic.learned.{i}\n    mana-cost: {mana}\n    cooldown-seconds: {cd}\n"))
        for (o, i, nm, cat, mana, cd, en, *_r) in rows], MARK)
    def cat_entry(old, o, i, nm, mana, cd, en, pack, P):
        icon = (_a._field(old, 'icon') if old else None) or "''"
        ph_ = ("    phases:\n" + _a._phase_yaml(pack, P)) if P else "    phases: []\n"
        return (f"  {i}:\n    number: {o}\n    name: {nm}\n    permission: magic.learned.{i}\n    icon: {icon}\n    mana-cost: {mana}\n    cooldown-seconds: {cd}\n"
                f"    reviewed: {'true' if en else 'false'}\n    enabled: {'true' if en else 'false'}\n    aliases: []\n" + ph_ +
                f"    preparation-status: server-events-connected\n")
    _a._upsert(os.path.join(REPO, 'portable-vfx-runtime/paper/src/main/resources/spell-catalog.yml'), [
        (i, (lambda old, o=o, i=i, nm=nm, mana=mana, cd=cd, en=en, pack=pack, P=P: cat_entry(old, o, i, nm, mana, cd, en, pack, P)))
        for (o, i, nm, cat, mana, cd, en, desc, disc, res, pack, hold, P) in rows], MARK)
    # #80 사거리 1.7배: spells.py 가 조준 거리를 늘린 전투 마법은 이펙트 range 도 같은 배율 (d2250f1f 와 같은 방식, 30 → 51)
    import spells as _sp
    _base = {r[0]: r[3] for r in routes()}
    _scaled_ids = {s[0] for s in _sp.S if s[0] in _base and s[3] != _base[s[0]]}
    def bind_range(i):
        return int(round(30 * _sp.RANGE_SCALE)) if i in _scaled_ids else 30
    def bind_entry(old, o, i, pack, hold, P):
        ph_ = ("    phases:\n" + _a._phase_yaml(pack, P)) if P else "    phases: []\n"
        return (f"  {i}:\n    folder: {folders[pack]}\n    number: {o}\n    range: {bind_range(i)}\n    speed: 1\n    trajectory: server-events\n    max-ticks: {hold + 100}\n"
                f"    sequence:\n      sustainStart: 0\n    holdTicks: {hold}\n" + ph_ +
                "    review: nova 325~387 (tools/nova_spells.py) - phases from the delivered VFX NOTES, events sent by chanova; game QA pending\n")
    _a._upsert(os.path.join(REPO, 'portable-vfx-runtime/paper/src/main/resources/spell-bindings.yml'), [
        (i, (lambda old, o=o, i=i, pack=pack, hold=hold, P=P: bind_entry(old, o, i, pack, hold, P)))
        for (o, i, nm, cat, mana, cd, en, desc, disc, res, pack, hold, P) in rows], MARK)
    t = ['| # | id | 이름 | 대상 | 마나 / 쿨 | 피해 (base + 마력×) | 구현 | 임시값 | VFX 팩 |', '|---|---|---|---|---|---|---|---|---|']
    for (o, i, nm, cat, tgt, rng, base, ratio, mana, cd, lines, note, temp, desc, disc, res, bundle, hold, P, en) in sorted(M):
        dmg = f'{base} + {ratio}' if base or ratio else '-'
        t.append(f'| {o} | `{i}` | {nm} | {tgt} {rng} | {mana} / {cd}초 | {dmg} | {note} | {temp} | {bundle}{"" if en else " (꺼짐)"} |')
    doc = _a._read(os.path.join(HERE, 'nova_spells_doc.md')).replace('{{TABLE}}', '\n'.join(t))
    _a._write_if(os.path.join(REPO, 'docs/handoff/NOVA_325_387.md'), doc)
    print(len(M), 'nova spells written')

if __name__ == '__main__':
    write_all()
