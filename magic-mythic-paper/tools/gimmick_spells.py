"""2026-10-20 던전 기믹 마법 239~250 (오너 기획 확정). 기믹 모델(ChacaGimmick)과 연동되는 마법만 새로 만들었다.
오너 결정: 지금 마법은 238번까지 개발된 것으로 본다(300~323 몹 습득 마법 = 기획 번호 215~238). 개발 안 된 168~299번 도감 파일은 전부 지웠고
새 마법은 239번부터 이어 붙인다. 원소: 되감기·차원 표식 = 어둠, 도관 회전 = 대지.

Source of truth for these spells. `tools/spells.py` imports routes(); `python3 tools/gimmick_spells.py` writes (in place, other entries untouched):
  magic-codex-fabric  default-spells/<order>_<id>.yml (+ index.txt, known-hashes.tsv)
  magic-codex-paper   mana-spells.yml              (enabled true, mana/cooldown)
  portable-vfx        spell-catalog.yml / spell-bindings.yml  (catalogue phase = the pack's attached `cast` system; everything else is
                      played by ChacaGimmick at the gimmick: claude:<pack>/<system>)
Gameplay = chagimmick{type=...} (GimmickMech.java) -> MagicGimmickEvent -> ChacaGimmick SpellActions.java.
Mana / cooldown / range are 임시값 (balance not final).
"""
import os, sys
HERE = os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0, HERE)
import arcana_spells as A

def _phases(pack, P):
    # attached cast follows the caster (same as feather_fall's cast phase)
    return A._phase_yaml(pack, P).replace('      - role: attached\n', '      - role: attached\n        follow-entity: true\n')

# order, id, name, category, type, range, mana, cooldown, pack folder, cast-phase ticks, description, discovery, research, note
M = [
 (239, 'thawing_heat', '해빙의 열기', 'fire', 'thaw', 12, 40, 8, 'ThawingHeat', 60,
  '얼음 봉인을 바라보고 3초 동안 열기를 불어넣어 녹입니다. 그사이 피해를 받으면 다시 얼어붙습니다.',
  '화염 실습실에서 「얼음을 녹이는 열」 책 읽기.', '얼음은 한 번에 녹지 않는다. 손바닥의 온기가 끝까지 머물러야 한다.',
  '얼음 봉인 3초 채널링 → thaw 트리거, 시전자 피해 시 취소'),
 (240, 'steam_ignition', '증기 점화', 'fire', 'steam', 12, 30, 6, 'SteamIgnition', 16,
  '증기 밸브에 불을 넣어 압력을 채웁니다. 압력이 찬 밸브는 연결된 장치에 마력 신호를 보냅니다.',
  '화염 실습실에서 「증기 기관 입문」 책 읽기.', '불과 물은 서로를 밀어내지만, 그 사이에 갇힌 힘은 무엇이든 움직인다.',
  '증기 밸브 steam 트리거 (밸브 노드 mana: out 이면 마력 신호)'),
 (241, 'water_level', '수위 조절', 'water', 'level', 12, 20, 1.5, 'WaterLevel', 16,
  '수위 기둥의 물을 한 칸 올립니다. 웅크린 채 쓰면 한 칸 내립니다.',
  '양동이로 물을 30번 붓기.', '물은 높은 곳을 싫어하지만, 부탁하면 한 칸쯤은 올라가 준다.',
  '수위 기둥 레벨 0~3 (+1 / 웅크리면 -1)'),
 (242, 'stone_weight', '무게추', 'earth', 'weight', 12, 30, 5, 'StoneWeight', 16,
  '바라보는 압력판 위에 마법 무게추를 떨어뜨려 누릅니다. 무게추는 작동한 뒤 부서져 사라집니다.',
  '장치 실습실에서 「무게와 균형」 책 읽기.', '사람 대신 돌이 서 있어도, 바닥은 그 차이를 모른다.',
  '대지 압력판 weight 트리거 (보통 hold 짧게 + mana: out)'),
 (243, 'vine_awaken', '덩굴 깨우기', 'earth', 'grow', 12, 40, 10, 'VineAwaken', 16,
  '씨앗 화분을 깨워 덩굴을 키웁니다. 자란 덩굴을 타고 높은 곳으로 오를 수 있습니다.',
  '뼛가루로 묘목을 나무로 5번 키우기.', '흙 속의 씨앗은 언제나 깨어날 준비가 되어 있다. 누군가 불러 주기만 하면.',
  '씨앗 화분 grow 트리거 → 노드 climb 설정만큼 위로 덩굴(타고 오르는 블록)'),
 (244, 'light_beam', '빛줄기', 'light', 'beam', 32, 50, 6, 'LightBeam', 60,
  '3초 동안 곧은 빛줄기를 내뿜습니다. 거울에서 꺾이고, 프리즘을 지나면 그 색으로 물듭니다. 쓰는 동안에는 움직일 수 없습니다.',
  '도서관에서 「곧은 빛에 관한 소론」 책 읽기.', '빛은 곧게 나아간다. 꺾이는 것은 빛이 아니라 우리가 놓아 둔 거울이다.',
  '3초 채널링(이동 불가) 빛줄기, 거울 반사·프리즘 색 변경, 수신 수정 light 입력'),
 (245, 'mirror_tune', '반사 조율', 'light', 'tune', 20, 15, 1, 'MirrorTune', 14,
  '멀리 있는 반사 거울이나 회전 다이얼을 90도 돌립니다.',
  '도서관에서 「거울과 빛의 길」 책 읽기.', '한 칸 돌리면 다른 길이 열린다. 네 번 돌리면 처음으로 돌아온다.',
  '회전 반사 거울·회전 다이얼 위치 +1 (0~3)'),
 (246, 'prism_tint', '프리즘 물들이기', 'light', 'tint', 20, 15, 1, 'PrismTint', 14,
  '프리즘의 색을 바꿉니다. 프리즘을 지나는 빛줄기는 그 색으로 물듭니다. 웅크린 채 쓰면 반대 순서로 바뀝니다.',
  '도서관에서 「프리즘 연구」 책 읽기.', '흰빛 안에는 모든 색이 접혀 있다. 프리즘은 그중 하나를 골라 펼칠 뿐이다.',
  '프리즘 색 흰색→빨강→노랑→초록→파랑→보라 순환 (웅크리면 반대)'),
 (247, 'statue_wake', '석상 깨우기', 'dark', 'wake', 16, 20, 2, 'StatueWake', 16,
  '잠든 석상을 잠시 깨워 시선을 오른쪽으로 90도 돌리게 합니다.',
  '대강당에서 「석상 관리 일지」 책 읽기.', '석상은 잠들어 있을 뿐, 무엇을 바라봐야 하는지는 기억하고 있다.',
  '잠든 석상 방향 +90° (노드 facing 조건)'),
 (248, 'rewind', '되감기', 'dark', 'rewind', 16, 30, 5, 'Rewind', 16,
  '바라보는 장치를 바로 직전 상태로 되돌립니다. 1분 이내에 바뀐 것만 되돌릴 수 있습니다.',
  '도서관에서 「되돌리는 장치에 관하여」 책 읽기.', '시간을 되돌리는 것은 아니다. 장치가 방금 전의 모습을 기억해 낼 뿐이다.',
  '마지막 변화(방향·위치·수위·색·레버) 1단계 되돌리기, 60초 이내'),
 (249, 'conduit_turn', '도관 회전', 'earth', 'conduit', 16, 10, 1, 'ConduitTurn', 14,
  '설치된 마력 도관을 90도 돌려 마력이 흐르는 길을 바꿉니다.',
  '장치 실습실에서 「마력 도관 설계」 책 읽기.', '마력은 길을 가리지 않는다. 길을 고르는 것은 도관을 놓는 손이다.',
  '마력 도관 방향 +90° → 마력 신호 경로 재계산'),
 (250, 'dimension_mark', '차원 표식', 'dark', 'mark', 24, 40, 5, 'DimensionMark', 16,
  '텔레포트 게이트에 표식을 새깁니다. 다른 게이트에 한 번 더 쓰면 두 게이트가 2분 동안 이어집니다.',
  '엔더 진주로 순간 이동 10번 하기.', '두 문 사이의 거리는 접을 수 있다. 양쪽에 같은 이름을 새겨 두기만 하면.',
  '첫 시전 = 표식(30초), 두 번째 = 다른 게이트와 연결(120초, 걸어 들어가면 이동)'),
]

def routes():
    return [(i, 'CHA_' + i, 'SELF', rng, 0, 0, False, [f'chagimmick{{type={t};range={rng};mana={mana}}} @self'], note)
            for (o, i, nm, cat, t, rng, mana, cd, pack, ct, desc, disc, res, note) in M]

def _rows():
    import spell_order
    rows = []
    for (o, i, nm, cat, t, rng, mana, cd, pack, ct, desc, disc, res, note) in M:
        o = spell_order.number(i, o)   # 2026-10-25 renumber: number/order come from tools/spell_order.py
        P = [A.att('cast', 'cast', ct)]
        rows.append((o, i, nm, cat, mana, cd, True, desc, disc, res, pack.lower(), ct + 20, P))
    return rows

def write_all():
    for r in M: A.FOLDERS[r[8].lower()] = r[8]
    _write(_rows())

def _write(rows):
    import re, hashlib
    REPO = A.REPO
    ds = os.path.join(REPO, 'magic-codex-fabric/src/main/resources/assets/magiccodex/default-spells')
    idx = [l for l in A._read(os.path.join(ds, 'index.txt')).split('\n') if l.strip()]
    hashes = [l.rstrip('\r') for l in open(os.path.join(ds, 'known-hashes.tsv'), encoding='utf-8', newline='').read().split('\n') if l.strip()]
    for (o, i, nm, cat, mana, cd, en, desc, disc, res, pack, hold, P) in rows:
        fn = f'{o:03d}_{i}.yml'; path = os.path.join(ds, fn)
        y = (f"id: {i}\nname: {nm}\ncategory: {cat}\ndescription: {A._q(desc)}\nicon: ''\npermission: magic.learned.{i}\n"
             f"discovery:\n  description: {A._q(disc)}\nresearch: {A._q(res)}\n"
             f"cast:\n  command: 마법 {i}\n  cooldown-seconds: {cd}\n  mana-cost: {mana}\norder: {o}\n")
        A._write_if(path, y)
        if f'{o:03d}_{i}' not in idx: idx.append(f'{o:03d}_{i}')
        h = hashlib.sha256(y.encode('utf-8')).hexdigest()
        if f'{fn}\t{h}' not in hashes: hashes.append(f'{fn}\t{h}')
    idx.sort(key=lambda l: (int(l.split('_', 1)[0]) if l.split('_', 1)[0].isdigit() else 9999, l))
    A._write_if(os.path.join(ds, 'index.txt'), '\n'.join(idx) + '\n')
    with open(os.path.join(ds, 'known-hashes.tsv'), 'w', encoding='utf-8', newline='') as f: f.write('\r\n'.join(hashes) + '\r\n')
    mark = '  # ---- 239~250 던전 기믹 마법 (magic-mythic-paper/tools/gimmick_spells.py) ----'
    A._upsert(os.path.join(REPO, 'magic-codex-paper/src/main/resources/mana-spells.yml'), [
        (i, (lambda old, i=i, nm=nm, mana=mana, cd=cd:
             f"  {i}:\n    name: {nm}\n    enabled: true\n    command: 마법 {i}\n    permission: magic.learned.{i}\n    mana-cost: {mana}\n    cooldown-seconds: {cd}\n"))
        for (o, i, nm, cat, mana, cd, *_r) in rows], mark)
    def cat_entry(old, o, i, nm, mana, cd, pack, P):
        icon = (A._field(old, 'icon') if old else None) or "''"
        return (f"  {i}:\n    number: {o}\n    name: {nm}\n    permission: magic.learned.{i}\n    icon: {icon}\n    mana-cost: {mana}\n    cooldown-seconds: {cd}\n"
                f"    reviewed: true\n    enabled: true\n    aliases: []\n    phases:\n" + _phases(pack, P) + "    preparation-status: server-events-connected\n")
    A._upsert(os.path.join(REPO, 'portable-vfx-runtime/paper/src/main/resources/spell-catalog.yml'), [
        (i, (lambda old, o=o, i=i, nm=nm, mana=mana, cd=cd, pack=pack, P=P: cat_entry(old, o, i, nm, mana, cd, pack, P)))
        for (o, i, nm, cat, mana, cd, en, desc, disc, res, pack, hold, P) in rows], mark)
    def bind_entry(old, o, i, pack, hold, P):
        return (f"  {i}:\n    folder: {A.FOLDERS.get(pack, pack)}\n    number: {o}\n    range: 30\n    speed: 1\n    trajectory: server-events\n    max-ticks: {hold + 100}\n"
                f"    sequence:\n      sustainStart: 0\n    holdTicks: {hold}\n    phases:\n" + _phases(pack, P) +
                "    review: gimmick 239~250 (tools/gimmick_spells.py) - catalogue plays only the pack's attached cast; ChacaGimmick plays the rest at the gimmick\n")
    A._upsert(os.path.join(REPO, 'portable-vfx-runtime/paper/src/main/resources/spell-bindings.yml'), [
        (i, (lambda old, o=o, i=i, pack=pack, hold=hold, P=P: bind_entry(old, o, i, pack, hold, P)))
        for (o, i, nm, cat, mana, cd, en, desc, disc, res, pack, hold, P) in rows], mark)
    print(len(rows), 'gimmick spells written')

if __name__ == '__main__':
    write_all()
