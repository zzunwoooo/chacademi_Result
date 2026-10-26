"""2026-10-19 최종 기획안(차카데미 전체 마법 기획 238종) 168~214 중 아직 게임 기능이 없던 마법 (오너: "없는 것들 제작, 미정은 기존 체계 기준 임시값,
다른 플러그인·모드 연동이 필요한 것은 보류하고 알려 줘").

Source of truth for these spells. `tools/spells.py` imports routes() / EXTRA / PASSIVE / SKIP;
`python3 tools/arcana_spells.py` writes (in place, other entries untouched):
  magic-codex-fabric  default-spells/<order>_<id>.yml (+ index.txt, known-hashes.tsv for new files)
  magic-codex-paper   mana-spells.yml              (enabled true, mana/cooldown)
  portable-vfx        spell-catalog.yml / spell-bindings.yml  (server-events phases from the delivered VFX NOTES)
  docs/handoff/ARCANA_168_214.md                   (정리표 + 임시값 + 보류 목록)
Gameplay = chaarcana{type=...} (ArcanaMech.java), plus chahit for the plain area / curse spells and Passives for 177 / 193.
Damage = Bridge base-damage + power-ratio x 마력. Every number marked 임시 is a placeholder ("기존 체계 기준 임시값").
"""
import os, re, hashlib

HERE = os.path.dirname(os.path.abspath(__file__)); ROOT = os.path.dirname(HERE); REPO = os.path.dirname(ROOT)

A = lambda s: 'chaarcana{' + s + '}'

# ---------------------------------------------------------------- phases
def ph(trigger, effect, role, anchor, dur, delay=0, stop=None, src=None, dst=None):
    return dict(trigger=trigger, effect=effect, role=role, anchor=anchor, dur=dur, delay=delay, stop=stop or [], src=src, dst=dst)
X = lambda e: 'external:' + e
def att(trig, eff, dur, who='caster', delay=0, stop=None): return ph(trig, eff, 'attached', who, dur, delay, stop)
def sta(trig, eff, dur, at='impact', delay=0, stop=None): return ph(trig, eff, 'static', at, dur, delay, stop)
def imp(trig, eff, dur, delay=0): return ph(trig, eff, 'impact', 'impact', dur, delay)
def lnk(trig, eff, dur, src, dst, delay=0, stop=None): return ph(trig, eff, 'link', 'impact', dur, delay, stop, src, dst)

# order, id, name, category, target, range, base, ratio, mana, cooldown, [mythic lines], note(구현 요약), temp(임시값 설명),
# description(도감), discovery(기획 발견 조건 or None=기존 유지), research(None=기존 유지), pack, holdTicks, [phases], enabled
M = [
 (168, 'shadow_double', '그림자 분신', 'dark', 'SELF', 12, 0, 0, 75, 25,
  [A('type=double;ticks=160;mana=75;cooldown=25') + ' @self'],
  '제자리에 분신 8초, 8초 안 재사용 시 복귀(재사용 마나 환급). 분신 유지 중 쿨 없음, 복귀/소멸 시 쿨 시작', '-',
  '시전 위치에 그림자 분신을 남깁니다. 8초 안에 다시 쓰면 분신이 있는 곳으로 돌아옵니다. 분신이 있는 동안에는 재사용 대기시간이 돌지 않습니다.',
  '도서관에서 「그림자는 두 번 선다」 책 읽기.', '밤이 깊으면 한 자리에서 예순을 세어 보라. 떠난 뒤에도 그림자는 잠시 그곳을 기억한다.',
  'shadowdouble', 200,
  [sta(X('form'), 'form', 34), sta(X('form'), 'hold', 168, delay=8), sta(X('depart'), 'depart', 18),
   sta(X('return'), 'return', 28, stop=['hold']), sta(X('expire'), 'expire', 46, stop=['hold'])], True),
 (169, 'echo_reading', '잔향 읽기', 'dark', 'SELF', 6, 0, 0, 25, 10,
  [A('type=echo;range=6') + ' @self'],
  '바라본 블록/엔티티(6칸)에 MagicEchoEvent 발생 - 퀘스트·던전 플러그인이 단서 문구를 넣음 (없으면 "남아 있는 기억이 없다")', '사거리 6, 마나 25/쿨 10 (기존 값)',
  '조사할 수 있는 물건에 남아 있는 기억을 읽어 사건의 단서를 확인합니다.', None, None,
  'echoreading', 20, [sta(X('echo_reading'), 'echo_reading', 86)], True),
 (170, 'sealing_thread', '봉인의 실', 'dark', 'SELF', 5, 0, 0, 40, 20,
  [A('type=thread;range=5;ticks=6000') + ' @self'],
  '직접 설치한 문/상자/통에 봉인(다른 사람은 못 엶), 같은 대상에 다시 쓰면 해제', '봉인 5분, 사거리 5, 마나 40/쿨 20 (기존 값). 설치자 기록은 이 플러그인 설치 이후 놓은 블록만',
  '직접 설치한 문이나 상자 하나에 봉인을 걸어 다른 사람이 열지 못하게 합니다. 같은 대상에 다시 쓰면 봉인이 풀립니다.', None, None,
  'sealingthread', 20,
  [lnk(X('thread'), 'thread', 18, 'caster-hand', 'event-point'), sta(X('wrap_chest'), 'wrap_chest', 46),
   sta(X('wrap_door_r'), 'wrap_door_r', 46), sta(X('wrap_door_l'), 'wrap_door_l', 46)], True),
 (172, 'shadow_exchange', '그림자 교환', 'dark', 'ENTITY', 15, 0, 0, 100, 30,
  [A('type=swap;range=15;hide=6')],
  '대상과 위치 교환 (0.3초 사라졌다 나타남). 남의 펫·보호구역·PvP 불가 대상 제외', '마나 100/쿨 30 (기존 값)',
  '15블록 안의 대상과 자신의 위치를 맞바꿉니다.', None, None,
  'shadowexchange', 20, [sta(X('vanish'), 'vanish', 26), sta(X('appear'), 'appear', 26)], True),
 (173, 'forbidden_contract', '금서의 계약', 'dark', 'SELF', 12, 0, 0, 0, 180,
  [A('type=contract;ratio=0.7;ticks=60;lock=300;heal=0.1') + ' @self'],
  '최대 마나 70% 지불 → 3초간 마나 회복분을 체력으로 전환 → 이어서 15초 마나 회복 금지', 'Bridge 마나 0 (메커닉이 70% 차감), 전환 비율 마나 1 → 체력 0.1, 두 효과는 연속(3초 뒤 금지 시작)',
  '최대 마나의 70%를 바쳐 3초 동안 마나 회복을 체력 회복으로 바꿉니다. 그 뒤 15초 동안 마나가 회복되지 않습니다.',
  '도서관의 관련 책 읽기 (책명 최신 확인 필요)', None,
  'forbiddencontract', 380,
  [att(X('contract'), 'contract', 52), att(X('conversion'), 'conversion', 64),
   att(X('seal_lock'), 'seal_lock', 304, stop=['conversion']), att(X('seal_break'), 'seal_break', 22, stop=['seal_lock'])], True),
 (174, 'life_siphon', '생명 갈취', 'dark', 'AIM', 12, 2, 0.15, 30, 10,
  [A('type=siphon;r=3;ticks=60;period=10;heal=0.5')],
  '조준 지점 반경 3 암흑지대 3초, 0.5초마다 피해 + 준 피해 50% 회복', '반경 3, 0.5초 주기, 피해 2+0.15, 회복 50%, 사거리 12',
  '지정한 위치에 3초 동안 암흑지대를 만들어 그 위의 적에게서 체력을 빼앗아 자신을 회복합니다.',
  '어둠 마법 기초 수업', None,
  'lifesiphon', 80,
  [sta(X('zone'), 'begin', 16), sta(X('zone'), 'sustain', 64, delay=4), sta(X('zone_end'), 'end', 20, stop=['sustain']),
   lnk(X('drain'), 'drain', 18, 'event-point', 'caster')], True),
 (175, 'drowsiness', '졸음', 'dark', 'AIM', 16, 0, 0, 100, 16,
  [A('type=drowse;speed=0.8;r=3;wait=200;root=60;orbvfx=claude:drowsiness/fly')],
  '어둠 구체가 조준 지점으로 날아가 10초 감지, 처음 들어온 적 3초 속박 후 소멸', '감지 반경 3, 대기 10초, 사거리 16, 1회 소모 (VFX 팩 Drowsiness 2026-10-25 제작, 감지 링 반경 3 고정)',
  '몸에서 출발한 어둠 구체가 지정한 자리에 감지 영역을 만듭니다. 적이 들어오면 구체가 달라붙어 3초 동안 속박합니다.',
  '잠을 자지 않고 게임 속 3일 밤을 버티기.', None,
  'drowsiness', 240,
  [sta(X('orb'), 'orb', 12), sta(X('eye'), 'eye', 200), sta(X('eye_end'), 'eye_end', 20, stop=['eye']), att(X('latch'), 'latch', 64, 'target')], True),
 (176, 'misfortune_whisper', '나약함의 저주', 'dark', 'ENTITY', 12, 0, 0, 60, 20,
  ['chahit{mult=0;status=magic_weakness:160+physical_weakness:160}'],
  '대상 8초 약화(마법 피해↓) + 물리 약화(공격 피해↓)', '감소율 = config status 공통 값 (magicWeakness / physicalWeakness), 사거리 12',
  '적 하나의 공격력과 지능을 8초 동안 낮춥니다.', '나약함 효과 상태에서 몬스터 5마리 처치', None,
  'misfortune_whisper', 180,
  [att('cast', 'misfortune_whisper_begin', 28, 'target'), att('cast', 'misfortune_whisper', 160, 'target', delay=6),
   att('cast', 'misfortune_whisper_end', 24, 'target', delay=160)], True),
 (178, 'curse_transfer', '저주의 전가', 'dark', 'ENTITY', 12, 0, 0, 90, 30,
  [A('type=spread;r=6;max=5;keep=0.7')],
  '대상의 상태이상·해로운 포션을 반경 6 적 최대 5명에게 복사 (지속 70%), 원래 대상은 유지, 연쇄 없음', '반경 6, 최대 5명, 1회 확산 (연쇄 없음), 마나 소각 제외',
  '대상에게 걸린 디버프를 주변 적에게 퍼뜨립니다. 옮겨진 디버프의 지속시간은 30% 짧아집니다.', None, None,
  'cursetransfer', 140,
  [lnk(X('spread_g1'), 'spread_g1', 20, 'event-point', 'target'), att(X('mark'), 'mark_begin', 24, 'target'),
   att(X('mark'), 'mark_hold', 110, 'target', delay=10), att(X('mark'), 'mark_end', 14, 'target', delay=110)], True),
 (179, 'discord', '마인드컨트롤', 'dark', 'ENTITY', 12, 0, 0, 110, 30,
  [A('type=discord;ticks=120;r=10')],
  '보스 제외 일반 몬스터 6초 조종: 0.5초마다 반경 10 가장 가까운 다른 몬스터를 노림, 플레이어 노림 차단', '탐색 반경 10, 사거리 12',
  '보스가 아닌 일반 몬스터 하나를 6초 동안 조종해 가까운 다른 몬스터를 공격하게 합니다.',
  '스켈레톤 화살로 다른 몬스터가 스켈레톤을 공격하는 상황 10회', None,
  'discord', 140,
  [att(X('discord_begin'), 'discord_begin', 22, 'target'), att(X('discord_begin'), 'discord_mark', 124, 'target', delay=4),
   att(X('discord_end'), 'discord_end', 14, 'target', stop=['discord_mark'])], True),
 (180, 'night_chain', '밤의 사슬', 'dark', 'AIM', 12, 0, 0, 200, 45,
  [A('type=chain;r=6;ticks=160;ratio=0.3')],
  '조준 지점 반경 6 몬스터(2마리 이상) 8초 연결: 한 마리가 받은 피해의 30%를 나머지에 전달, 재전파 없음, 수 제한 없음', '반경 6, 전달 30%, 중도 진입 없음 (시전 시 안에 있던 몬스터만)',
  '지정한 범위 안의 몬스터들을 8초 동안 사슬로 묶어, 한 마리가 받은 피해 일부를 나머지에게 전합니다.',
  '어둠 마법 실전 수업', None,
  'nightchain', 180,
  [att(X('bind'), 'bindForm', 24, 'target'), att(X('bind'), 'bindHold', 160, 'target', delay=10), att(X('bind'), 'bindFade', 30, 'target', delay=160),
   lnk(X('chain'), 'chainForm', 24, 'event-point', 'target'), lnk(X('chain'), 'chainHold', 160, 'event-point', 'target', delay=10),
   lnk(X('chain'), 'chainFade', 24, 'event-point', 'target', delay=160), lnk(X('relay'), 'relay', 18, 'event-point', 'target')], True),
 (181, 'pain_deferral', '고통 유예', 'dark', 'SELF', 12, 0, 0, 150, 45,
  [A('type=defer;ticks=120;after=100;ratio=0.5') + ' @self'],
  '6초간 받는 피해 50%를 쌓아 두고, 끝난 뒤 5초 동안 1초마다 나눠 받음', '-',
  '6초 동안 받는 피해의 절반을 미뤄 두었다가, 끝난 뒤 5초에 걸쳐 나누어 받습니다.', '피해를 누적 2,000 받기.', None,
  'paindeferral', 240,
  [att('cast', 'cast', 12)] + [att(X(f'shard_slot{i}'), f'shard_slot{i}', 224) for i in range(1, 7)]
  + [att(X(f'absorb_slot{i}'), f'absorb_slot{i}', 14, stop=[f'shard_slot{i}']) for i in range(1, 7)] + [att(X('pulse'), 'pulse', 10)], True),
 (182, 'black_harvest', '검은 수확', 'dark', 'SELF', 5, 6, 0.45, 80, 12,
  ['chahit{center=caster;r=5;max=16;status=disarm:60} @self'],
  '주변 5블록 베기 + 3초 침묵(무장해제), 처형 삭제', '피해 6+0.45, 전방위(각도 미정 → 원형), 플레이어는 PvP 규칙, 보스 포함',
  '어두운 낫을 휘둘러 주변의 적을 베고 3초 동안 침묵시킵니다.', '생명 갈취로 몬스터 100마리 처치하기.', None,
  'blackharvest', 40, [att('cast', 'cast', 38)], True),
 (183, 'ink_fingertip', '먹물 손끝', 'dark', 'SELF', 12, 0, 0, 30, 120,
  [A('type=ink;mana=30') + ' @self'],
  '손(주/보조)에 든 염료와 같은 색 염료 64개 생성, 재료 소모 없음. 염료를 안 들면 실패(마나·쿨 환급)', '"선택" = 손에 든 염료 (선택 UI 없음)',
  '손에 든 염료와 같은 색의 염료 64개를 만들어 냅니다. 다른 재료는 쓰지 않습니다.', '염료를 10번 만들기.', None,
  'inkfingertip', 20,
  [att(X('ink_' + c), 'ink_' + c, 40) for c in ['white', 'light_gray', 'gray', 'black', 'brown', 'red', 'orange', 'yellow', 'lime', 'green',
                                                 'cyan', 'light_blue', 'blue', 'purple', 'magenta', 'pink']], True),
 (184, 'shadow_nail', '그림자 못', 'dark', 'ENTITY', 20, 12, 0.8, 170, 60,
  [A('type=nail;ticks=300;dist=20')],
  '15초 저주, 시전자와 20블록 넘게 멀어지면 피해 1회 후 종료, 그냥 만료되면 피해 없음', '피해 12+0.8, 사거리 20',
  '대상에게 15초 동안 저주를 겁니다. 그동안 대상이 20블록 밖으로 멀어지면 한 번 큰 피해를 입고 저주가 끝납니다.', '1:1 결투에서 10번 이기기.', None,
  'shadownail', 320,
  [att(X('nail'), 'shadow_nail_begin', 10, 'target'), att(X('nail'), 'shadow_nail', 300, 'target', delay=2),
   att(X('nail_burst'), 'shadow_nail_burst', 26, 'target', stop=['shadow_nail']), att(X('nail_fade'), 'shadow_nail_fade', 26, 'target', stop=['shadow_nail'])], True),
 (185, 'night_lullaby', '밤의 자장가', 'dark', 'SELF', 16, 0, 0, 130, 60,
  [A('type=lullaby;count=3;ticks=200;range=16') + ' @self'],
  '팬텀 3마리 10초 소환, 0.5초마다 반경 16 적 재타겟, 햇빛에 안 탐, 드롭·경험치 없음, 주인/아군 공격 안 함', '피해 = 바닐라 팬텀, 추적 반경 16',
  '팬텀 세 마리를 불러 10초 동안 주변의 적을 쫓아 공격하게 합니다.', '팬텀 30마리 처치하기.', None,
  'nightlullaby', 220,
  [sta(X('rift'), 'rift', 40), att(X('trail'), 'trail', 200, 'target'), imp(X('hit'), 'hit', 22), sta(X('dismiss'), 'dismiss', 34, stop=['trail'])], True),
 (186, 'loosen_grasp', '손아귀 풀기', 'dark', 'ENTITY', 12, 0, 0, 60, 20,
  [A('type=loosen;ticks=80')],
  '플레이어: 선택 핫바 슬롯을 무작위로 바꿈 / 몬스터: 4초 침묵(무장해제)', '사거리 12',
  '플레이어의 손에 든 칸을 다른 칸으로 바꿔 버리거나, 몬스터를 4초 동안 침묵시킵니다.', '도서관에서 「손아귀를 푸는 법」 책 읽기.', None,
  'loosengrasp', 100,
  [att(X('hand_ripple'), 'hand_ripple', 18, 'target'), att(X('mark'), 'mark_begin', 14, 'target'), att(X('mark'), 'mark', 80, 'target', delay=2),
   att(X('mark_break'), 'mark_break', 18, 'target', stop=['mark'])], True),
 (188, 'healing_seal', '회복 봉인', 'dark', 'ENTITY', 12, 0, 0, 85, 25,
  [A('type=seal;ticks=160')],
  '대상 8초 회복봉인 (자연 회복·물약·마법 회복 차단 = 기존 회복봉인 상태 그대로)', '사거리 12',
  '대상에게 8초 동안 회복봉인을 걸어 체력을 회복하지 못하게 합니다.', '연금술로 물약 제작 누적 20회', None,
  'healingseal', 180,
  [att(X('healing_seal'), 'healing_seal_begin', 32, 'target'), att(X('healing_seal'), 'healing_seal', 160, 'target', delay=8),
   att(X('healing_seal'), 'healing_seal_end', 16, 'target', delay=160)], True),
 (189, 'shadow_scales', '어둠의 저울', 'dark', 'AIM', 12, 6, 0.45, 100, 20,
  [A('type=scales;r=4;delay=8')],
  '조준 지점 반경 4 폭발, 대상 체력 ÷ 시전자 체력 비율로 추가 피해 +1%~+50% (2배 이상 = 150%)', '보간 = 1배→2배 선형, 반경 4, 피해 6+0.45',
  '지정한 곳에 어둠 폭발을 일으킵니다. 대상의 체력이 자신보다 많을수록 더 큰 피해를 줍니다.', None, None,
  'shadowscales', 40,
  [sta(X('burst'), 'burst', 62), imp(X('hit_low'), 'hit_low', 20), imp(X('hit_mid'), 'hit_mid', 26), imp(X('hit_high'), 'hit_high', 36)], True),
 (190, 'warning_thread', '경고의 실', 'dark', 'AIM', 15, 0, 0, 200, 60,
  [A('type=cage;r=6;ticks=140')],
  '대상(또는 조준 지점) 중심 반경 6 결계 7초: 시전 때 안에 있던 적은 밖으로 못 나감 (되밀림)', '"약 6블록" = 반경 6, 사거리 15',
  '대상 주변에 결계를 쳐 7초 동안 안에 있는 적이 밖으로 나가지 못하게 합니다.',
  '도서관에서 「경계를 긋는 실」 책 읽기.', None,
  'warningthread', 160,
  [sta(X('cage'), 'begin', 44), sta(X('cage'), 'sustain', 140, delay=18), sta(X('cage_end'), 'end', 38, stop=['sustain']), imp(X('breach'), 'breach', 18)], True),
 (192, 'wound_accounting', '상처 회계', 'dark', 'ENTITY', 20, 0, 0, 80, 30,
  [A('type=wound;speed=0.7;range=20')],
  '유도 먹물 덩어리가 맞으면 무작위 상태이상 1개 (기절1초·속박1.5·무장해제2·둔화3·화상3·회복봉인4·약화5·중력3·고정3·독4·물리약화5·실명2·물약봉인5·마나소각·수면2·공포1.5)', '각 상태 지속은 임시, 사거리 20',
  '먹물 덩어리를 날려 맞은 적에게 무작위 디버프 하나를 겁니다.', '모든 디버프에 걸려보기', '겪어 본 저주는 저마다 다른 흔적을 남겼다. 이제는 그 흔적을 되돌려 줄 차례다.',
  'woundaccounting', 80,
  [att(X('gather'), 'gather', 20), imp(X('contact'), 'contact', 20), att(X('absorb'), 'absorb', 26, 'target')], True),
 (194, 'ghost_tool', '유령 도구', 'dark', 'SELF', 12, 0, 0, 100, 60,
  [A('type=ghosttool;ticks=600;reach=27') + ' @self'],
  '30초 블록 설치·파괴 사거리 +27 (block_interaction_range), 공격 사거리 그대로', '+27 (기본 4.5 → 31.5)',
  '30초 동안 멀리 있는 블록도 설치하고 부술 수 있습니다. 공격 사거리는 그대로입니다.',
  '곡괭이·도끼·삽을 각각 1개씩 내구도를 다 써서 부수기.', '손안에서 수명을 다한 것들이, 손이 닿지 않는 곳에서 다시 움직였다.',
  'ghosttool', 620,
  [att('cast', 'ghost_tool_begin', 18), att('cast', 'ghost_tool', 600, delay=4), att('cast', 'ghost_tool_end', 24, delay=600),
   sta(X('ghost_tool_break'), 'ghost_tool_break', 20), sta(X('ghost_tool_place'), 'ghost_tool_place', 12),
   sta(X('ghost_tool_swing_pickaxe'), 'ghost_tool_swing_pickaxe', 12), sta(X('ghost_tool_swing_axe'), 'ghost_tool_swing_axe', 12),
   sta(X('ghost_tool_swing_shovel'), 'ghost_tool_swing_shovel', 12)], True),
 (195, 'bell_of_fear', '공포의 종', 'dark', 'SELF', 8, 0, 0, 140, 30,
  [A('type=bell;r=8;ticks=60') + ' @self'],
  '주변 8블록 일반 몬스터 3초 공포 (파동 도달 순서대로), 플레이어·보스 제외', '반경 8, 공포 3초',
  '어두운 종을 울려 주변의 일반 몬스터를 공포에 빠뜨립니다.', '밤에 종을 10번 울리기.', '낮에는 묻히던 울림이, 어둠 속에서는 낯선 두려움을 깨웠다.',
  'belloffear', 100,
  [sta('cast', 'bell', 44, 'caster'), sta('cast', 'ripple', 26, 'caster'), att(X('fear'), 'fearApply', 18, 'target'), att(X('fear'), 'fearHold', 60, 'target', delay=2)], True),
 (196, 'eclipse_eye', '월식의 눈', 'dark', 'AIM', 30, 3, 0.2, 450, 300,
  ['chachannel{ticks=100;move=0.3;then=CHA_eclipse_eye_zone}'],
  '5초 영창(이동·피격 시 실패) → 조준 지점 반경 15 영역 10초: 안의 시전자 이동속도 +30%·마나 계속 채움, 달빛 6개가 떠돌며 접촉 피해(같은 적 1초 간격)', '이속 +30%, 달빛 6개, 피해 3+0.2, 재접촉 1초, 마나 제한 해제 = 0.5초마다 마나 가득',
  '5초 동안 영창한 뒤 넓은 어둠의 영역을 펼칩니다. 영역 안에서 자신은 빨라지고 마나가 바닥나지 않으며, 떠도는 달빛 조각이 닿는 적에게 피해를 줍니다.',
  '최대 마나 1,500 달성하기.', '어둠을 담을 그릇이 깊어지자, 달빛마저 그 안에 잠겼다.',
  'eclipseeye', 330,
  [sta('cast', 'zoneBegin', 50, 'target'), sta('cast', 'zoneSustain', 320, 'target', delay=3), sta('cast', 'chant', 106, 'target'),
   sta(X('struct_cancel'), 'chantCancel', 34, stop=['chant', 'zoneSustain']), sta(X('struct_cancel'), 'zoneEnd', 50),
   sta(X('eclipse'), 'eclipse', 42), sta(X('eclipse'), 'eclipseHold', 204),
   sta(X('eclipse_end'), 'eclipseEnd', 92, stop=['eclipseHold', 'zoneSustain', 'haste']), sta(X('eclipse_end'), 'zoneEnd', 50),
   att(X('haste'), 'haste', 200), imp(X('contact'), 'contact', 14), imp(X('shardFade'), 'shardFade', 18)], True),
 (198, 'void_maw', '공허의 입', 'dark', 'AIM', 16, 14, 0.9, 500, 100,
  ['delay 25', 'chahit{r=4;max=16}'],
  '조준 지점에서 1.25초 뒤 바닥의 입이 삼킴: 반경 4 피해', '반경 4, 지연 1.25초, 피해 14+0.9, 사거리 16',
  '바닥에서 어둠의 생물이 튀어나와 지정한 곳의 적을 삼킵니다.', '도서관에 숨겨진 「텅 빈 것의 식욕」 책 읽기.', None,
  'voidmaw', 80,
  [sta('cast', 'warnBegin', 28, 'target'), sta('cast', 'warnHold', 22, 'target', delay=4), sta('cast', 'bite', 48, 'target', delay=25)], True),
 (203, 'landing_airbrake', '빗자루 차원이동', 'wind', 'SELF', 12, 0, 0, 35, 16,
  [A('type=airbrake;dist=6') + ' @self'],
  '빗자루 탑승 중 바라보는 방향 6블록 앞으로 순간이동 (막히면 그 앞까지, 속도 유지)', '-',
  '빗자루를 탄 채로 바라보는 방향 약 6블록 앞으로 순간이동합니다.', '빗자루를 타고 누적 1,000블록 날기.', '바람이 지나간 자리에, 잠시 접힌 하늘의 틈이 남았다.',
  'landingairbrake', 20, [sta(X('depart'), 'depart', 18), sta(X('arrive'), 'arrive', 18)], True),
 (210, 'hushed_soles', '고요한 밑창', 'dark', 'SELF', 12, 0, 0, 55, 45,
  [A('type=hushed;ticks=400') + ' @self'],
  '20초간 자신의 걷기·달리기 진동(STEP)만 스컬크가 못 느낌. 착지·블록·아이템·공격 진동은 그대로', '-',
  '20초 동안 걷거나 달릴 때 생기는 스컬크 진동을 없앱니다. 착지나 블록 조작, 공격의 진동은 그대로 납니다.',
  '웅크린 채 누적 1,000블록 걷기.', '발소리를 삼킨 이끼 위에서는 바쁜 걸음도 숲의 잠을 깨우지 못했다.',
  'hushedsoles', 420, [imp(X('step'), 'step', 24)], True),
 (211, 'thorn_manners', '가시의 예절', 'earth', 'SELF', 12, 0, 0, 10, 30,
  [A('type=thorns;ticks=400') + ' @self'],
  '20초간 달콤한 열매 덤불의 감속·피해 무시 (자신만, 덤불은 그대로)', '-',
  '20초 동안 달콤한 열매 덤불을 스쳐도 느려지거나 다치지 않습니다.', '달콤한 열매 덤불에서 열매를 20번 따기.',
  '바구니에 붉은 열매가 쌓일수록, 돌아오는 길은 덜 따가웠다.',
  'thornmanners', 420, [imp(X('contact'), 'contact', 18)], True),
 (212, 'cinder_skipring', '불꽃 줄넘기', 'fire', 'SELF', 7, 0, 0, 90, 18,
  [A('type=skipring;r0=1;r1=7;ticks=40') + ' @self'],
  '발밑 불고리 반지름 1→7 (2초, 3블록/초), 고리 띠(±0.25)에 닿은 적 1회 피해, 점프(+0.4 이상)·안쪽·다른 층은 회피', '**피해량 비워 둠 (기획: 임의 수치 제안 금지)** → base 0 / ratio 0, 오너 결정 필요',
  '발밑에서 바깥으로 퍼지는 불고리를 보냅니다. 고리에 닿은 적은 피해를 입고, 뛰어넘거나 안쪽으로 파고들면 피할 수 있습니다.',
  '전투 중 적의 지상 범위 공격을 점프로 회피 20회.', '불길은 바닥을 달렸지만, 가볍게 떠오른 발끝에는 뜨거운 자국조차 남지 않았다.',
  'cinderskipring', 60, [sta('cast', 'ring', 44, 'caster'), imp(X('hit'), 'hit', 24)], True),
 (213, 'droplet_relay', '물방울 인계', 'water', 'AIM', 10, 4, 0.3, 100, 20,
  [A('type=droplet;speed=0.6;r=2.5;ticks=60')],
  '물방울이 조준 지점(10블록)으로 날아가 반경 2.5 적에게 피해 + 3초 공중부양', '반경 2.5, 피해 4+0.3',
  '몸에서 출발한 물방울이 지정한 곳까지 날아가 주변의 적을 가두고 3초 동안 띄워 올립니다.', '서로 다른 몬스터 3마리를 한 전투에서 각각 공격한 뒤 생존 5회.', None,
  'dropletrelay', 100,
  [att(X('form'), 'form', 18), imp(X('arrive'), 'arrive', 24), att(X('capture'), 'capture', 66, 'target'), att(X('release'), 'release', 32, 'target')], True),
 (214, 'folding_tideplane', '접히는 수면', 'water', 'AIM', 10, 5, 0.4, 110, 22,
  [A('type=tideplane;len=6;width=2;delay=20;turn=26')],
  '조준 지점에 6×2 물막, 1초 뒤 시전자 반대쪽으로 1.3초 동안 책장처럼 넘어감, 움직이는 막에 닿은 적 1회 피해', '넘김 1.3초 (기획 2초 → VFX 팩 기준), 접힐 방향 = 시전자→조준 방향, 피해 5+0.4',
  '바닥에 물막을 펼쳤다가 책장처럼 반대편으로 넘깁니다. 넘어가는 막에 닿은 적은 피해를 입습니다.',
  '연금술로 물 + 책을 합성하여 물로 된 책 제작', '젖은 종이는 찢어졌지만, 물로 만든 페이지는 끝없이 넘길 수 있었다.',
  'foldingtideplane', 100,
  [sta(X('sheet'), 'spread', 54), sta(X('sheet'), 'axis', 112), sta(X('sheet'), 'turn', 76), sta(X('sheet'), 'disperse', 108), imp(X('contact'), 'contact', 22)], True),
]

# passives (Passives.java, config.yml passives.*): equipped in a key slot. The cast key only plays the look.
PASSIVES = [
 (177, 'hungry_shadow', '굶주린 그림자', 'dark', '장착 중 몬스터 처치 시 배고픔 1칸(2) 회복',
  '마법을 등록해 두면 몬스터를 처치할 때마다 배고픔이 조금 회복됩니다.', '배고픔 2칸 이하에서 몬스터 10마리 처치하기.', None,
  'hungryshadow', [lnk('cast', 'maw', 24, 'caster-hand', 'event-point'), lnk(X('devour'), 'devour', 18, 'event-point', 'caster')]),
 (193, 'inverted_offering', '뒤집힌 공물', 'dark', '장착 중 처치한 몬스터의 드롭을 경험치로 바꿈 (아이템 1개당 0.5, 올림 - conversion_ratio 미정 임시), 플레이어·소환수 제외',
  '마법을 등록해 두면 처치한 몬스터의 전리품이 제물로 바뀌어 추가 경험치를 줍니다.', '경험치 레벨 30 달성하기.', '몸에 쌓인 경험이 깊어지자, 전리품 속에서도 같은 빛이 보였다.',
  'invertedoffering', [lnk('cast', 'offering', 16, 'event-point', 'caster'), lnk(X('offering'), 'offering', 16, 'event-point', 'caster')]),
]

EXTRA = {
 'CHA_eclipse_eye_zone': ('eclipse_eye', [A('type=eclipse;r=15;ticks=200;shards=6;speed=0.3;contact=20;haste=0.3;element=dark;spell=eclipse_eye')],
                          '월식의 눈 영창 완료 후 10초 영역 (chachannel then=)'),
}

# 다른 플러그인 / 모드 / Bridge API 연동이 필요해 보류 (오너: "놔두고 말해 줘")
# 2026-10-20 오너: 개발 안 된 168번 이후 마법은 전부 버림 → 보류 목록과 도감 파일을 지웠다 (tools/gimmick_spells.py 머리말 참고)
SKIP = {}

def routes():
    return [(i, 'CHA_' + i, tgt, rng, base, ratio, False, lines, note) for (o, i, nm, cat, tgt, rng, base, ratio, mana, cd, lines, note, *_r) in M]

def passive_notes():
    return {i: note for (o, i, nm, cat, note, *_r) in PASSIVES}

# ---------------------------------------------------------------- yaml text helpers (in-place, other entries untouched)
def _read(path): return open(path, encoding='utf-8').read()
def _write_if(path, text):
    old = _read(path) if os.path.exists(path) else None
    if old != text: open(path, 'w', encoding='utf-8', newline='').write(text)

def _entry_span(text, key):
    """(start, end) of the 2-space-indented entry `  key:` up to the next 2-space key / root key / EOF, or None."""
    m = re.search(r'(?m)^  ' + re.escape(key) + r':\n', text)
    if not m: return None
    n = re.compile(r'(?m)^(  \S[^\n]*\n|\S[^\n]*\n)').search(text, m.end())
    return (m.start(), n.start() if n else len(text))

def _q(v):
    """YAML scalar: quote when a plain scalar would break."""
    v = str(v)
    if v == '' or ': ' in v or ' #' in v or v[0] in '!&*[]{}|>%@`"\'#-?,:' or v.strip() != v:
        return '"' + v.replace('\\', '\\\\').replace('"', '\\"') + '"'
    return v

FOLDERS = {f.lower(): f for f in ['Drowsiness', 'ShadowDouble', 'EchoReading', 'SealingThread', 'ShadowExchange', 'ForbiddenContract', 'LifeSiphon', 'misfortune_whisper',
    'CurseTransfer', 'Discord', 'NightChain', 'PainDeferral', 'BlackHarvest', 'InkFingertip', 'ShadowNail', 'NightLullaby', 'LoosenGrasp', 'HealingSeal',
    'ShadowScales', 'WarningThread', 'WoundAccounting', 'GhostTool', 'BellOfFear', 'EclipseEye', 'VoidMaw', 'LandingAirbrake', 'HushedSoles', 'ThornManners',
    'CinderSkipring', 'DropletRelay', 'FoldingTideplane', 'HungryShadow', 'InvertedOffering']}

def _field(block, name):
    m = re.search(r'(?m)^    ' + re.escape(name) + r': (.*)$', block)
    return m.group(1) if m else None

def _upsert(path, items, marker):
    """items: [(key, body_fn(old_block_or_None) -> new block text)]. Existing entries are replaced where they are;
    new ones go into a section at the end that starts with `marker`."""
    text = _read(path)
    new = []
    for key, fn in items:
        sp = _entry_span(text, key)
        if sp:
            text = text[:sp[0]] + fn(text[sp[0]:sp[1]]) + text[sp[1]:]
        else:
            new.append(fn(None))
    head = text.split('\n' + marker + '\n')[0].rstrip('\n') + '\n'
    tail = text.split('\n' + marker + '\n')[1] if ('\n' + marker + '\n') in text else ''
    # entries already in the marker section were replaced in place above; keep them, add the new ones
    body = tail + ''.join(new)
    _write_if(path, head + (marker + '\n' + body if body.strip() else ''))

def _phase_yaml(pack, P):
    out = []
    for q in P:
        s = (f"      - role: {q['role']}\n        trigger: {q['trigger']}\n        effect: claude:{pack}/{q['effect']}\n"
             f"        anchor: {q['anchor']}\n        delay-ticks: {q['delay']}\n        duration-ticks: {q['dur']}\n")
        if q['src']: s += f"        link-source: {q['src']}\n        link-target: {q['dst']}\n"
        if q['stop']: s += "        stop-effects:\n" + ''.join(f"          - claude:{pack}/{x}\n" for x in q['stop'])
        out.append(s)
    return ''.join(out)

def _rows():
    """Every spell this file owns: (order, id, name, cat, mana, cd, enabled, description, discovery, research, pack, hold, phases)."""
    import spell_order   # 2026-10-25 renumber: number/order come from tools/spell_order.py
    N = spell_order.number
    r = [(N(i, o), i, nm, cat, mana, cd, en, desc, disc, res, pack, hold, P) for (o, i, nm, cat, tgt, rng, base, ratio, mana, cd, lines, note, temp, desc, disc, res, pack, hold, P, en) in M]
    r += [(N(i, o), i, nm, cat, 0, 0, True, desc, disc, res, pack, 40, P) for (o, i, nm, cat, note, desc, disc, res, pack, P) in PASSIVES]
    return sorted(r)

def write_all():
    rows = _rows()
    # ---- default-spells (fabric codex)
    ds = os.path.join(REPO, 'magic-codex-fabric/src/main/resources/assets/magiccodex/default-spells')
    idx = [l for l in _read(os.path.join(ds, 'index.txt')).split('\n') if l.strip()]
    hashes = [l.rstrip('\r') for l in open(os.path.join(ds, 'known-hashes.tsv'), encoding='utf-8', newline='').read().split('\n') if l.strip()]
    for (o, i, nm, cat, mana, cd, en, desc, disc, res, *_r) in rows:
        fn = f'{o:03d}_{i}.yml'; path = os.path.join(ds, fn)
        old = _read(path) if os.path.exists(path) else ''
        get = lambda k, d='': (re.search(r'(?m)^' + k + r': (.*)$', old) or [None, d])[1]
        icon = get('icon', "''")
        odisc = (re.search(r'(?m)^discovery:\n  description: (.*)$', old) or [None, ''])[1]
        ores = get('research', '')
        y = (f"id: {i}\nname: {nm}\ncategory: {cat}\ndescription: {_q(desc)}\nicon: {icon}\npermission: magic.learned.{i}\n"
             f"discovery:\n  description: {_q(disc) if disc else (odisc or _q('아직 알려지지 않았습니다.'))}\nresearch: {_q(res) if res else (ores or chr(39)*2)}\n"
             f"cast:\n  command: 마법 {i}\n  cooldown-seconds: {cd}\n  mana-cost: {mana}\norder: {o}\n")
        _write_if(path, y)
        if f'{o:03d}_{i}' not in idx: idx.append(f'{o:03d}_{i}')
        h = hashlib.sha256(y.encode('utf-8')).hexdigest()
        if f'{fn}\t{h}' not in hashes: hashes.append(f'{fn}\t{h}')   # history: old shipped hashes stay (DefaultSpellMigration)
    idx.sort(key=lambda l: (int(l.split('_', 1)[0]) if l.split('_', 1)[0].isdigit() else 9999, l))
    _write_if(os.path.join(ds, 'index.txt'), '\n'.join(idx) + '\n')
    with open(os.path.join(ds, 'known-hashes.tsv'), 'w', encoding='utf-8', newline='') as f: f.write('\r\n'.join(hashes) + '\r\n')
    mark = '  # ---- 168~214 최종 기획 신규 구현 (magic-mythic-paper/tools/arcana_spells.py) ----'
    # ---- Bridge mana-spells
    _upsert(os.path.join(REPO, 'magic-codex-paper/src/main/resources/mana-spells.yml'), [
        (i, (lambda old, i=i, nm=nm, mana=mana, cd=cd, en=en:
             f"  {i}:\n    name: {nm}\n    enabled: {'true' if en else 'false'}\n    command: 마법 {i}\n    permission: magic.learned.{i}\n    mana-cost: {mana}\n    cooldown-seconds: {cd}\n"))
        for (o, i, nm, cat, mana, cd, en, *_r) in rows], mark)
    # ---- PortableVFX catalog
    def cat_entry(old, o, i, nm, mana, cd, en, pack, P):
        icon = (_field(old, 'icon') if old else None) or "''"
        base = _field(old, 'baseline-icon') if old else None
        num = _field(old, 'numeric-source') if old else None
        ph_ = ("    phases:\n" + _phase_yaml(pack, P)) if P else "    phases: []\n"
        s = (f"  {i}:\n    number: {o}\n    name: {nm}\n    permission: magic.learned.{i}\n    icon: {icon}\n    mana-cost: {mana}\n    cooldown-seconds: {cd}\n"
             f"    reviewed: {'true' if en else 'false'}\n    enabled: {'true' if en else 'false'}\n    aliases: []\n" + ph_ +
             f"    preparation-status: {'server-events-connected' if en else 'vfx-pack-missing'}\n")
        if num: s += f"    numeric-source: {num}\n"
        if base: s += f"    baseline-icon: {base}\n"
        return s
    _upsert(os.path.join(REPO, 'portable-vfx-runtime/paper/src/main/resources/spell-catalog.yml'), [
        (i, (lambda old, o=o, i=i, nm=nm, mana=mana, cd=cd, en=en, pack=pack, P=P: cat_entry(old, o, i, nm, mana, cd, en, pack, P)))
        for (o, i, nm, cat, mana, cd, en, desc, disc, res, pack, hold, P) in rows], mark)
    # ---- PortableVFX bindings (server-events: the mechanics send external:* while the cast lives = holdTicks)
    def bind_entry(old, o, i, pack, hold, P):
        folder = FOLDERS.get(pack, pack)
        ph_ = ("    phases:\n" + _phase_yaml(pack, P)) if P else "    phases: []\n"
        return (f"  {i}:\n    folder: {folder}\n    number: {o}\n    range: 30\n    speed: 1\n    trajectory: server-events\n    max-ticks: {hold + 100}\n"
                f"    sequence:\n      sustainStart: 0\n    holdTicks: {hold}\n" + ph_ +
                "    review: arcana 168~214 (tools/arcana_spells.py) - phases from the delivered VFX NOTES, events sent by chaarcana; game QA pending\n")
    _upsert(os.path.join(REPO, 'portable-vfx-runtime/paper/src/main/resources/spell-bindings.yml'), [
        (i, (lambda old, o=o, i=i, pack=pack, hold=hold, P=P: bind_entry(old, o, i, pack, hold, P)))
        for (o, i, nm, cat, mana, cd, en, desc, disc, res, pack, hold, P) in rows if pack], mark)
    # ---- summary doc
    t = ['| # | id | 이름 | 대상 | 마나 / 쿨 | 피해 (base + 마력×) | 구현 | 임시값 | VFX 팩 |', '|---|---|---|---|---|---|---|---|---|']
    for (o, i, nm, cat, tgt, rng, base, ratio, mana, cd, lines, note, temp, desc, disc, res, pack, hold, P, en) in M:
        dmg = f'{base} + {ratio}' if base or ratio else '-'
        t.append(f'| {o} | `{i}` | {nm} | {tgt} {rng} | {mana} / {cd}초 | {dmg} | {note} | {temp} | {pack or "**없음**"} |')
    for (o, i, nm, cat, note, desc, disc, res, pack, P) in PASSIVES:
        t.append(f'| {o} | `{i}` | {nm} | 장착 패시브 | 0 / 0 | - | {note} | config.yml passives.{i} | {pack} |')
    sk = ['| id | 보류 이유 (필요한 연동) |', '|---|---|'] + [f'| `{k}` | {v} |' for k, v in SKIP.items()]
    doc = _read(os.path.join(HERE, 'arcana_spells_doc.md')).replace('{{TABLE}}', '\n'.join(t)).replace('{{SKIP}}', '\n'.join(sk))
    _write_if(os.path.join(REPO, 'docs/handoff/ARCANA_168_214.md'), doc)
    print(len(M), 'spells +', len(PASSIVES), 'passives written,', len(SKIP), 'skipped')

if __name__ == '__main__':
    write_all()
