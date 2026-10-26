"""몹 기술 → 플레이어 습득 마법 300~323 (2026-10-19, 오너: "오늘 만든 몹 스킬 중 기존과 안 겹치는 것, 해당 몹 처치 시 5% 확률로 습득").

Source of truth for these 24 spells. `tools/spells.py` imports ROUTES (MythicMobs CHA_* skill + Bridge route);
`python3 tools/mob_spells.py` writes the other files:
  magic-codex-fabric  default-spells/<order>_<id>.yml + index.txt + known-hashes.tsv
  magic-codex-paper   mana-spells.yml            (enabled: false until the VFX packs are on every client)
  portable-vfx        spell-catalog.yml           (reviewed/enabled false, phases = proposed, see docs)
  magic-discovery     discoveries.yml             (kill.mob.<family> x1, chance 0.05 per kill)
  docs/handoff/MOB_SPELLS_300_323.md              (정리표)
The looks come from the mob VFX packs (PC 차카데미 vfx_Claude\\몹스킬\\...): chatelegraph / chamobshot play them directly
(vfx=pack/system). Damage = Bridge base-damage + power-ratio x 마력 (placeholders, balance not final).
"""
import os, re, hashlib

HERE = os.path.dirname(os.path.abspath(__file__)); ROOT = os.path.dirname(HERE); REPO = os.path.dirname(ROOT)

# family key (kill.mob.<key>) -> (Korean mob name, MythicMobs CA_Wild_ prefixes)  — DiscoveryHooks.MOB_FAMILIES must match
FAMILIES = {
 'kitsune': ('키츠네', ['kitsune_']), 'phoenix': ('피닉스', ['phoenix_']), 'glacialcore': ('빙핵의 수호자', ['glacialcore']),
 'basilisk': ('바실리스크', ['basilisk_']), 'herbthief': ('약초 도둑', ['lostasset_mob3_herbthief']), 'stag': ('영록', ['stag_']),
 'warden_mage': ('봉인의 술사', ['mf_warden_mage']), 'warden_knight': ('봉인의 기사', ['mf_warden_knight']), 'warden_archer': ('봉인의 궁수', ['mf_warden_archer']),
 'phantom': ('팬텀', ['phantom_']), 'skeleton_mage': ('망각의 해골술사', ['ncr_mage_skeleton']), 'sky_serpent': ('하늘뱀', ['sky_serpent_']),
 'fairy': ('요정', ['lostasset_mob2_']), 'pegasus': ('페가수스', ['horse_pegasus']), 'hippogryph': ('히포그리프', ['hippogryph_']),
 'sabertooth': ('검치호', ['sabertooth_']), 'manticore': ('만티코어', ['manticore_']), 'golem': ('고대 골렘', ['golem_']), 'wyvern': ('와이번', ['wyvern_']),
}

E = ';hits=enemies'   # player spells: chatelegraph/chamobshot hit enemies (players only in PvP), never draw the red warning
T = lambda s: 'chatelegraph{' + s + ';warn=false' + E + '}'
SHOT = lambda s: 'chamobshot{' + s + E + '}'

# order, id, name, category, family, target, range, base, ratio, mana, cooldown, [mythic lines], note, description, research
M = [
 (300, 'fox_orbit', '여우불 공전', 'fire', 'kitsune', 'AIM', 20, 3, 0.25, 30, 8,
  [SHOT('count=3;spread=30;orbit=16;speed=0.7;homing=0.2;range=20;mult=1;ignite=40;vfx=kitsunefox/shot_normal;hitvfx=kitsunefox/hit_normal')],
  '여우불 3개가 0.8초 몸 둘레를 돌다가 조준한 적에게 휘어 날아감 (불씨 2초)',
  '여우불 세 개를 불러 잠시 몸 둘레를 돌게 한 뒤, 조준한 적을 향해 휘어 날려 보냅니다. 맞은 적은 잠깐 불탑니다.',
  '키츠네의 불은 곧장 날아가지 않는다. 한 바퀴 돌며 상대를 고른다.'),
 (301, 'curving_plumes', '곡선 불깃털', 'fire', 'phoenix', 'AIM', 20, 3, 0.25, 30, 8,
  [SHOT('count=3;spread=40;speed=0.8;homing=0.18;range=20;mult=1;ignite=40;vfx=phoenixfeather/shot_flame;hitvfx=phoenixfeather/hit_flame')],
  '좌우로 퍼졌다가 휘어 들어오는 불깃털 3발 (불씨 2초)',
  '불타는 깃털 세 개를 부채꼴로 날립니다. 깃털은 좌우로 퍼졌다가 조준한 적에게 휘어 들어갑니다.',
  '피닉스의 깃털은 바람을 타고 돌아 들어온다. 막아도 옆에서 닿는다.'),
 (302, 'crystal_orbit', '결정 공전탄', 'water', 'glacialcore', 'SELF', 12, 3, 0.2, 45, 14,
  [SHOT('count=8;spread=45;orbit=20;speed=0.7;range=12;mult=1;status=slow:30:0.3;vfx=glacialshards/shot_glacial;hitvfx=glacialshards/hit_glacial')],
  '얼음 결정 8개가 1초 몸 둘레를 돈 뒤 8방향으로 발사, 맞으면 1.5초 둔화',
  '얼음 결정 여덟 개를 몸 둘레에 띄워 돌리다가 사방으로 쏘아 보냅니다. 맞은 적은 잠시 느려집니다.',
  '빙핵의 수호자는 둘러싸이는 것을 두려워하지 않는다. 사방이 곧 과녁이다.'),
 (303, 'venom_spit', '독 뱉기', 'earth', 'basilisk', 'AIM', 18, 3, 0.2, 30, 10,
  [SHOT('count=1;speed=0.7;rise=0.35;range=18;burst=2.5;mult=1;status=poison:60;vfx=basiliskspit/shot_wild;hitvfx=basiliskspit/hit_wild')],
  '포물선 독 덩어리, 떨어진 자리 반경 2.5 피해 + 중독 3초 (웅덩이는 VFX)',
  '독 덩어리를 포물선으로 뱉습니다. 떨어진 자리 주변의 적은 피해를 입고 3초 동안 중독됩니다.',
  '바실리스크의 독은 높이 떠올랐다가 떨어진다. 엄폐물 너머까지.'),
 (304, 'smoke_veil', '연막 은신', 'earth', 'herbthief', 'SELF', 6, 1, 0.1, 35, 20,
  [T('shape=circle;at=caster;r=5;ticks=2;mult=1;status=blindness:40;vfx=smokebomb/smoke;vfxref=6;vfxlead=2'),
   'chaselfpotion{type=INVISIBILITY;duration=40;level=0} @self'],
  '주변 5블록 적 실명 2초 + 자신 2초 투명',
  '발밑에 연막탄을 터뜨립니다. 주변 적은 2초 동안 앞을 보지 못하고, 자신은 2초 동안 모습을 감춥니다.',
  '약초 도둑은 싸우지 않는다. 연기가 걷히면 이미 없다.'),
 (305, 'domain_stomp', '영역 선포', 'light', 'stag', 'SELF', 6, 5, 0.4, 40, 14,
  [T('shape=circle;at=caster;r=6;ticks=4;mult=1;up=0.9;vfx=domainseal/domain_heavenly;vfxref=10;vfxlead=4')],
  '주변 6블록 적을 위로 튕겨 올림',
  '발굽으로 땅을 구르듯 마력을 내리찍어 주변 적을 위로 튕겨 올립니다.',
  '영록이 발을 구르면 그곳은 그의 땅이 된다. 남의 발은 땅에 붙어 있지 못한다.'),
 (306, 'seal_circle', '봉인진', 'light', 'warden_mage', 'AIM', 16, 4, 0.3, 45, 16,
  [T('shape=circle;at=target;r=4;ticks=16;mult=1;status=disarm:30;vfx=sealcircle/seal;vfxref=4;vfxlead=2')],
  '조준 지점 반경 4, 0.8초 뒤 봉인 — 무장 해제 1.5초',
  '조준한 자리에 봉인진을 그립니다. 잠시 뒤 진이 닫히며 안에 있는 적의 무장을 1.5초 동안 해제합니다.',
  '봉인의 술사는 칼을 빼앗지 않는다. 칼을 쥘 이유를 잊게 한다.'),
 (307, 'seal_talismans', '봉인 부적', 'light', 'warden_mage', 'AIM', 20, 3, 0.2, 25, 7,
  [SHOT('count=3;spread=30;speed=0.8;homing=0.15;range=20;mult=1;vfx=sealtalisman/shot_seal;hitvfx=sealtalisman/hit_seal')],
  '곡선 유도 부적 3장',
  '봉인 부적 세 장을 던집니다. 부적은 빙글 돌며 휘어 조준한 적에게 붙습니다.',
  '부적은 바람을 거스르지 않는다. 돌아가더라도 반드시 닿는다.'),
 (308, 'shield_slam', '방패 내려찍기', 'light', 'warden_knight', 'SELF', 5, 5, 0.4, 30, 10,
  [T('shape=cone;at=caster;r=4.5;angle=120;ticks=4;mult=1;status=stun:10;vfx=sealshield/shield;vfxref=3;vfxlead=4')],
  '앞 부채꼴 반경 4.5 · 120° 강타 + 기절 0.5초',
  '마력의 방패를 들어 앞쪽 땅을 내리찍습니다. 앞에 있던 적은 피해를 입고 잠깐 기절합니다.',
  '봉인의 기사에게 방패는 막는 물건이 아니다. 문장을 새기는 도장이다.'),
 (309, 'chain_arrow', '사슬 화살', 'light', 'warden_archer', 'AIM', 22, 4, 0.3, 20, 6,
  [SHOT('count=1;speed=1.3;range=22;radius=0.6;mult=1;status=slow:30:0.4;vfx=chainarrow/shot_chain;hitvfx=chainarrow/hit_chain')],
  '빠른 사슬 화살, 맞으면 1.5초 둔화',
  '금빛 사슬을 감은 화살을 쏩니다. 맞은 적은 사슬에 걸려 잠시 느려집니다.',
  '봉인의 궁수는 쓰러뜨리려 쏘지 않는다. 붙잡아 두려 쏜다.'),
 (310, 'mana_drain', '마나 흡수', 'dark', 'phantom', 'AIM', 14, 3, 0.2, 40, 16,
  [T('shape=circle;at=target;r=4;ticks=12;mult=1;status=mana_burn:40;vfx=phantomdrain/drain_ender;vfxref=6;vfxlead=2')],
  '조준 지점 반경 4, 0.6초 뒤 피해 + 마나 연소 2초',
  '조준한 자리의 마력을 빨아들입니다. 그 안의 적은 피해를 입고 2초 동안 마나가 타들어 갑니다.',
  '팬텀은 피를 마시지 않는다. 마력이 흐르는 소리를 마신다.'),
 (311, 'soul_scythe', '영혼 낫', 'dark', 'phantom', 'SELF', 5, 5, 0.4, 25, 7,
  [T('shape=cone;at=caster;r=5;angle=150;ticks=4;mult=1;vfx=phantomscythe/reap_ender;vfxref=5;vfxlead=4')],
  '앞 부채꼴 반경 5 · 150° 베기',
  '영혼의 낫을 크게 휘둘러 앞쪽의 적을 베어 냅니다.',
  '낫이 지나간 자리에는 상처보다 먼저 한기가 남는다.'),
 (312, 'oblivion_orbs', '망각의 탄환', 'dark', 'skeleton_mage', 'AIM', 20, 3, 0.2, 30, 9,
  [SHOT('count=3;spread=14;speed=0.45;homing=0.08;range=20;radius=0.9;mult=1;status=slow:30:0.3;vfx=skelorb/shot;hitvfx=skelorb/hit')],
  '느리게 날아가는 탄 3발, 맞으면 1.5초 둔화',
  '망각의 기운을 담은 탄환 세 발을 천천히 날립니다. 맞은 적은 잠시 느려집니다.',
  '느린 것은 피할 수 있다. 피하는 동안 다른 것을 잊는다.'),
 (313, 'bone_grasp', '뼈의 손아귀', 'dark', 'skeleton_mage', 'AIM', 15, 3, 0.2, 40, 16,
  [T('shape=circle;at=target;r=4;ticks=12;mult=1;status=root:20;pull=1.4;vfx=bonegrasp/grasp;vfxref=6;vfxlead=2')],
  '조준 지점 반경 4, 0.6초 뒤 묶음 1초 + 시전자 쪽으로 끌어옴',
  '조준한 땅에서 뼈의 손을 솟게 합니다. 손에 붙잡힌 적은 잠시 묶이고 자신 쪽으로 끌려옵니다.',
  '망자의 손은 놓는 법을 모른다.'),
 (314, 'shadow_leap', '그림자 도약', 'dark', 'kitsune', 'ENTITY', 14, 5, 0.4, 40, 14,
  [T('shape=circle;at=target;r=2.5;ticks=4;mult=1;blink=true;vfx=specialkitsune/in_normal;vfxout=specialkitsune/out_normal;vfxref=3;vfxlead=4')],
  '대상 옆으로 순간이동해 반경 2.5 타격 (2칸 빈 공간 필요)',
  '대상 바로 옆으로 순간이동해 주변을 공격합니다. 설 자리가 없으면 이동하지 않습니다.',
  '여우는 달려가지 않는다. 눈을 깜빡이면 이미 곁에 있다.'),
 (315, 'binding_pull', '끌어오기', 'wind', 'sky_serpent', 'ENTITY', 14, 2, 0.15, 45, 18,
  [T('shape=circle;at=target;r=1.5;ticks=4;mult=1;tpme=true;status=slow:20:0.3;vfx=specialserpent/mark_jade;vfxin=specialserpent/in_jade;vfxref=3;vfxlead=4')],
  '대상을 내 앞 1.6블록으로 순간이동시키고 1초 둔화',
  '바람의 고리로 대상을 감아 자신의 바로 앞으로 끌어옵니다. 끌려온 적은 잠시 느려집니다.',
  '하늘뱀은 먹이를 쫓지 않는다. 바람을 감아 당길 뿐이다.'),
 (316, 'fairy_dust', '요정 가루', 'earth', 'fairy', 'SELF', 6, 1, 0.1, 40, 20,
  [T('shape=circle;at=caster;r=5;ticks=4;mult=1;status=sleep:40;vfx=fairydust/dust_flower;vfxref=6;vfxlead=4')],
  '주변 5블록 적 수면 2초 (피해 받으면 깸)',
  '반짝이는 요정 가루를 흩뿌립니다. 주변 적은 2초 동안 잠듭니다.',
  '요정의 장난은 해롭지 않다. 다만 깨어났을 때 모든 것이 바뀌어 있을 뿐.'),
 (317, 'moon_wind', '반달 바람날', 'wind', 'pegasus', 'SELF', 10, 4, 0.3, 25, 7,
  [T('shape=line;at=caster;length=10;width=3;ticks=4;mult=1;knock=0.8;vfx=pegasuscrescent/moon;vfxref=5;vfxlead=4')],
  '앞 직선 10 · 폭 3, 반달 바람날 2개가 밀며 지나감',
  '날개를 차듯 반달 모양 바람날 두 개를 앞으로 날려 보냅니다. 닿은 적은 밀려납니다.',
  '페가수스의 날갯짓은 달의 모양을 닮았다. 차오르기 전에 베고 지나간다.'),
 (318, 'gale_wings', '돌풍 날갯짓', 'wind', 'pegasus', 'SELF', 6, 3, 0.25, 40, 16,
  [T('shape=cone;at=caster;r=6;angle=180;ticks=4;mult=1;knock=1.6;up=0.4;status=gravity:20;vfx=galewings/gale;vfxref=10;vfxlead=4')],
  '앞 반원 반경 6 밀치기 + 중력 1초',
  '날개를 크게 내려쳐 앞쪽 반원에 돌풍을 일으킵니다. 적은 멀리 밀려나고 잠시 무거워집니다.',
  '바람이 무겁다고 느껴진다면, 그것은 날개가 너를 내려다보고 있기 때문이다.'),
 (319, 'feather_storm', '깃털 폭풍', 'wind', 'hippogryph', 'SELF', 5, 5, 0.4, 35, 12,
  [T('shape=circle;at=caster;r=5;ticks=4;mult=1;vfx=featherstorm/fstorm_common;vfxref=8;vfxlead=4')],
  '주변 5블록 깃털 칼날 폭풍',
  '날카로운 깃털 칼날을 몸 둘레로 휘몰아쳐 주변 적을 벱니다.',
  '히포그리프의 깃털은 떨어지지 않는다. 날아간다.'),
 (320, 'hunting_pounce', '사냥 도약', 'earth', 'sabertooth', 'ENTITY', 12, 5, 0.4, 35, 14,
  ['leap{velocity=130;noise=0} @target', 'delay 14', T('shape=circle;at=caster;r=3;ticks=2;mult=1;status=root:20;vfx=sabertoothpounce/pounce_arctic;vfxref=6;vfxlead=2') + ' @self'],
  '대상에게 뛰어들어 착지 반경 3 피해 + 묶음 1초',
  '대상을 향해 크게 뛰어올라 덮칩니다. 내려앉은 자리 주변의 적은 피해를 입고 잠시 묶입니다.',
  '검치호는 두 번 뛰지 않는다.'),
 (321, 'roar_leap', '포효 도약', 'wind', 'manticore', 'ENTITY', 14, 5, 0.4, 40, 16,
  ['leap{velocity=150;noise=0} @target', 'delay 16', T('shape=circle;at=caster;r=4;ticks=2;mult=1;status=fear:20;vfx=manticoreroar/roar_aether;vfxref=8;vfxlead=2') + ' @self'],
  '대상에게 뛰어들어 착지하며 포효, 반경 4 피해 + 공포 1초',
  '대상을 향해 뛰어들며 내려앉는 순간 포효합니다. 주변 적은 피해를 입고 잠시 겁에 질립니다.',
  '만티코어의 포효는 착지보다 먼저 도착한다.'),
 (322, 'gravity_slam', '중력 강타', 'earth', 'golem', 'SELF', 5, 6, 0.45, 45, 16,
  [T('shape=circle;at=caster;r=5;ticks=6;mult=1;status=gravity:40;vfx=gravityslam/gravity_arcane;vfxref=11;vfxlead=6')],
  '주변 5블록 강타 + 중력 2초',
  '두 주먹으로 땅을 내리쳐 주변 적에게 피해를 주고 2초 동안 무겁게 짓누릅니다.',
  '고대 골렘이 내리친 땅은 한동안 하늘을 잊는다.'),
 (323, 'tail_spikes', '꼬리 가시', 'earth', 'wyvern', 'AIM', 16, 2, 0.15, 35, 12,
  [T('shape=circle;at=target;r=3;ticks=8;mult=1;vfx=wyvernspikes/spikes_green;vfxref=4;vfxlead=6'), 'delay 8',
   T('shape=circle;at=target;r=3;ticks=8;mult=1;vfx=wyvernspikes/spikes_green;vfxref=4;vfxlead=6'), 'delay 8',
   T('shape=circle;at=target;r=3;ticks=8;mult=1;vfx=wyvernspikes/spikes_green;vfxref=4;vfxlead=6')],
  '조준 지점 반경 3에 0.4초 간격 가시 3번',
  '조준한 자리에 꼬리 가시를 세 번 연달아 떨어뜨립니다.',
  '와이번의 꼬리는 한 번 휘두르면 세 번 떨어진다.'),
]

def obj(word):
    """word + 을/를 by the last syllable's final consonant."""
    c = ord(word[-1]) - 0xAC00
    return word + ('을' if 0 <= c < 11172 and c % 28 else '를')

def routes():
    """(id, skill, target, range, base, ratio, utility, lines, note) rows for spells.py S."""
    return [(i, 'CHA_' + i, tgt, rng, base, ratio, False, lines, note) for (o, i, nm, cat, fam, tgt, rng, base, ratio, mana, cd, lines, note, desc, res) in M]

def _write_if(path, text):
    old = open(path, encoding='utf-8').read() if os.path.exists(path) else None
    if old != text: open(path, 'w', encoding='utf-8').write(text)

def _section(path, marker, body):
    """Replace (or append) the block that starts with `marker` and runs to the end of the file."""
    s = open(path, encoding='utf-8').read()
    s = s.split('\n' + marker)[0].rstrip('\n') + '\n' + marker + '\n' + body
    open(path, 'w', encoding='utf-8').write(s)

def write_all():
    ds = os.path.join(REPO, 'magic-codex-fabric/src/main/resources/assets/magiccodex/default-spells')
    idx = open(os.path.join(ds, 'index.txt'), encoding='utf-8').read().split('\n')
    import spell_order   # 2026-10-25 renumber: number/order come from tools/spell_order.py
    mine = {r[1] for r in M}
    idx = [l for l in idx if l.strip() and l.split('_', 1)[1] not in mine]
    hashes = [l.rstrip('\r') for l in open(os.path.join(ds, 'known-hashes.tsv'), encoding='utf-8', newline='').read().split('\n') if l.strip()]
    for (o, i, nm, cat, fam, tgt, rng, base, ratio, mana, cd, lines, note, desc, res) in M:
        o = spell_order.number(i, o)
        mob = FAMILIES[fam][0]
        y = (f"id: {i}\nname: {nm}\ncategory: {cat}\ndescription: {desc}\nicon: ''\npermission: magic.learned.{i}\n"
             f"discovery:\n  description: {obj(mob)} 처치하면 5% 확률로 습득합니다.\nresearch: {res}\n"
             f"cast:\n  command: 마법 {i}\n  cooldown-seconds: {cd}\n  mana-cost: {mana}\norder: {o}\n")
        fn = f'{o:03d}_{i}.yml'; _write_if(os.path.join(ds, fn), y)
        idx.append(f'{o:03d}_{i}')
        h = hashlib.sha256(y.encode('utf-8')).hexdigest()
        hashes = [l for l in hashes if not l.startswith(fn + '\t')] + [f'{fn}\t{h}']
    _write_if(os.path.join(ds, 'index.txt'), '\n'.join(idx) + '\n')
    with open(os.path.join(ds, 'known-hashes.tsv'), 'w', encoding='utf-8', newline='') as f: f.write('\r\n'.join(hashes) + '\r\n')   # file is CRLF
    mark = '  # ---- 300~323 몹 기술 습득 마법 (magic-mythic-paper/tools/mob_spells.py) ----'
    # Bridge mana-spells: enabled false until the mob VFX packs are on every client (catalog mode refuses spells without ready VFX)
    _section(os.path.join(REPO, 'magic-codex-paper/src/main/resources/mana-spells.yml'), mark, ''.join(
        f"  {i}:\n    name: {nm}\n    enabled: false\n    command: 마법 {i}\n    permission: magic.learned.{i}\n    mana-cost: {mana}\n    cooldown-seconds: {cd}\n"
        for (o, i, nm, cat, fam, tgt, rng, base, ratio, mana, cd, *_r) in M))
    # PortableVFX catalog: the gameplay mechanics play the VFX themselves, so the catalog phase is only the cast flash at the caster
    cat_body = ''
    for (o, i, nm, cat, fam, tgt, rng, base, ratio, mana, cd, lines, *_r) in M:
        v = re.search(r'vfx=([a-z0-9_]+)/', ' '.join(lines)).group(1)
        cat_body += (f"  {i}:\n    number: {o}\n    name: {nm}\n    permission: magic.learned.{i}\n    icon: ''\n    mana-cost: {mana}\n    cooldown-seconds: {cd}\n"
                     f"    reviewed: false\n    enabled: false\n    aliases: []\n    phases: []\n    preparation-status: mob-skill-vfx-direct\n"
                     f"    mob-vfx-pack: {v}\n")
    _section(os.path.join(REPO, 'portable-vfx-runtime/paper/src/main/resources/spell-catalog.yml'), mark, cat_body)
    disc = ''
    for (o, i, nm, cat, fam, *_r) in M:
        mob = FAMILIES[fam][0]
        disc += (f'  {i}:\n    name: "{nm}"\n    permission: "magic.learned.{i}"\n    icon: "magiccodex:textures/spells/mob_skill/{i}.png"\n'
                 f'    category: "{cat}"\n    condition-text: "{obj(mob)} 처치하면 5% 확률로 습득합니다."\n    requirements:\n      "kill.mob.{fam}": 1\n'
                 f'    chance: 0.05\n    prerequisites: []\n')
    _section(os.path.join(REPO, 'magic-discovery-paper/src/main/resources/discoveries.yml'), mark, disc)
    # summary doc
    rows = ['| # | id | 이름 | 원소 | 습득 (처치 시 5%) | 대상 | 마나 / 쿨 | 피해 (base + 마력×) | 효과 | 원본 몹 기술 VFX |', '|---|---|---|---|---|---|---|---|---|---|']
    for (o, i, nm, cat, fam, tgt, rng, base, ratio, mana, cd, lines, note, desc, res) in M:
        v = sorted(set(re.findall(r'vfx(?:out|in)?=([a-z0-9_]+/[a-z0-9_]+)', ' '.join(lines))))
        rows.append(f'| {o} | `{i}` | {nm} | {cat} | {FAMILIES[fam][0]} (`kill.mob.{fam}`) | {tgt} {rng} | {mana} / {cd}초 | {base} + {ratio} | {note} | {", ".join("`"+x+"`" for x in v)} |')
    doc = open(os.path.join(HERE, 'mob_spells_doc.md'), encoding='utf-8').read().replace('{{TABLE}}', '\n'.join(rows))
    _write_if(os.path.join(REPO, 'docs/handoff/MOB_SPELLS_300_323.md'), doc)
    print(len(M), 'mob spells written')

if __name__ == '__main__':
    write_all()
