"""368~373 궁극 (rows: see tools/nova_spells.py). Java: NovaUlt.java.
Chants (368 / 369 / 371) = chachannel{...;then=CHA_<id>_x} + EXTRA skill. A broken chant: ChannelMech sends external:struct_cancel
(the rows stop the chant visuals on it); completion sends external:laser_fire (unused: the then-skill's chanova emits the next phase)."""
from nova.common import N, ev, cast, ph, X

def cancel(effect, stop, role='attached', anchor='caster', dur=1):
    """Broken chant (external:struct_cancel from chachannel): stop the chant systems. `effect` is what this phase plays
    (a 1-tick restart of a stopped chant system when the bundle has no cancel visual)."""
    return ph(X('struct_cancel'), effect, role, anchor, dur, 0, stop)

M = [
 (368, 'flame_emperor', '염제 강림', 'fire', 'SELF', 6, 18, 1.2, 450, 600,
  ['chachannel{ticks=60;move=0.3;then=CHA_flame_emperor_x}'],
  '3초 영창(이동·피격 시 실패) → 8초 화신: 걸은 자리에 화염길(밟은 적 화상 3초, 점마다 약 1.7초, 실제 불 없음), 화신 중 이 마법 키 = 대형 화염탄(최대 4발, 반경 2.5 폭발 + 화상, 마나·쿨 없음), '
  '끝날 때 반경 6 폭발(끝 + 0.35초) + 화상. 쿨 600초는 화신이 끝날 때부터',
  '영창 3초, 화신 8초, 화염탄 4발 (발당 피해 x0.5, 반경 2.5, 탄속 1.3, 사거리 32, 0.3초 간격), 끝 폭발 18+1.2 반경 6, 화상 3초',
  '3초 동안 영창한 뒤 8초 동안 화염의 화신이 됩니다. 지나간 자리에 불길이 남아 적을 태우고, 마법 키를 다시 누를 때마다 커다란 화염탄을 던집니다(최대 4발). 화신이 끝나면 주변이 크게 폭발합니다.',
  '화염 마법 30종 습득하기.', None,
  'FlameEmperor', 290,
  [cast('flame_emperor_chant', 60), cancel('flame_emperor_chant', ['flame_emperor_chant']),
   ev('flame_emperor', 'attached', 175, 'caster'),
   ev('flame_emperor_end', 'attached', 50, 'caster', stop=['flame_emperor']),
   ev('flame_emperor_fireball_hit', 'impact', 20)], True),
 (369, 'leviathan_rise', '레비아탄', 'water', 'AIM', 24, 20, 1.3, 600, 900,
  [N('leviathan_rise_mark', 'LeviathanRise', 'aim=area:10'), 'chachannel{ticks=100;move=0.3;then=CHA_leviathan_rise_x}'],
  '5초 영창(이동·피격 시 실패) → 조준 지점 반경 10: 바다뱀이 솟아 삼킴(+0.4초, 피해 x0.35 + 둔화 3초) → 내려찍음(+1.4초, 피해 + 기절 1초)',
  '반경 10, 삼킴 피해 x0.35, 내려찍기 20+1.3, 둔화 3초, 기절 1초',
  '5초 동안 영창하면 조준한 곳의 물에서 거대한 바다뱀이 솟아올라 주변의 적을 삼킨 뒤 내려찍습니다. 삼켜진 적은 느려지고, 마지막 일격에 기절합니다.',
  '물 마법 30종 습득 + 전설 물고기 1마리 낚기.', None,
  'LeviathanRise', 230,
  [cast('casterChant', 100), ev('zoneChant', 'static', 100), cancel('drain', ['zoneChant', 'casterChant'], role='static', anchor='impact', dur=30),
   ev('strike', 'static', 90), ev('drain', 'static', 30, stop=['strike']), ev('struck', 'impact', 20)], True),
 (370, 'heaven_cyclone', '하늘의 눈', 'wind', 'SELF', 8, 18, 1.2, 450, 900,
  [N('heaven_cyclone', 'HeavenCyclone', 'r=8;lift=4;ticks=200')],
  '시전 위치 반경 8 대폭풍 10초: 안의 적을 1.5초에 걸쳐 4블록 띄워 붙잡음(속도로 유지, 플레이어는 중력 = 비행·빗자루 불가, 보스는 안 뜸), '
  '끝 + 0.4초에 한꺼번에 내리꽂고 끝 + 0.55초 피해',
  '반경 8, 10초, 띄우기 4블록, 내리꽂기 18+1.2',
  '주변에 거대한 폭풍을 10초 동안 일으킵니다. 폭풍 안의 적은 천천히 떠올라 날지 못한 채 붙잡히고, 폭풍이 끝날 때 한꺼번에 땅으로 내리꽂힙니다.',
  '바람 마법 30종 습득하기.', None,
  'HeavenCyclone', 270,
  [cast('begin', 30, role='static'), ev('sustain', 'static', 210), ev('end', 'static', 40, stop=['sustain']),
   ev('lift', 'attached', 220, 'target'), ev('slam', 'impact', 20, stop=['lift'])], True),
 (371, 'abyss_gate', '심연의 문', 'dark', 'AIM', 24, 22, 1.4, 600, 900,
  [N('abyss_gate_mark', 'AbyssGate', 'aim=area:8'), 'chachannel{ticks=60;move=0.3;then=CHA_abyss_gate_x}'],
  '3초 영창(이동·피격 시 실패) → 조준 지점에 문 8초: 반경 8 적을 문 쪽으로 끌어당김(보스 30%), 닫힐 때(끝 + 0.45초) 큰 피해 + 수면 1초',
  '반경 8, 열림 8초, 끌기 0.16/틱, 피해 22+1.4, 수면 1초',
  '3초 동안 영창하면 조준한 곳에 거대한 심연의 문이 8초 동안 열립니다. 주변의 적은 문 쪽으로 끌려가고, 문이 닫힐 때 큰 피해를 입고 잠듭니다.',
  '어둠 마법 30종 습득하기.', None,
  'AbyssGate', 290,
  [cast('chant', 60), ev('mark', 'static', 70), cancel('chant', ['chant', 'mark']),
   ev('begin', 'static', 30, stop=['mark']), ev('sustain', 'static', 160), ev('end', 'static', 46, stop=['sustain']),
   ev('pull', 'link', 175, src='target-chest', dst='event-point'), ev('sleep', 'impact', 24, stop=['pull'])], True),
 (372, 'aurora_veil', '오로라', 'light', 'SELF', 10, 4, 0.3, 450, 900,
  [N('aurora_veil', 'AuroraVeil', 'r=10;ticks=240')],
  '지원형: 시전 위치 반경 10 오로라 12초. 안의 아군(본인 + 파티원) 2초마다 회복(시전 + 2.3초부터 5회) + 안에 있는 동안 새 디버프 면역, 안의 적 약화(나가면 1.5초 뒤 풀림)',
  '반경 10, 12초, 회복 4+0.3 x 5회, 면역은 영역 안에서만',
  '하늘에 오로라를 12초 동안 펼칩니다. 오로라 아래의 자신과 파티원은 꾸준히 회복하고 새로운 해로운 효과를 받지 않으며, 그 아래의 적은 약해집니다.',
  '빛 마법 30종 습득하기.', None,
  'AuroraVeil', 300,
  [cast('aurora_veil_begin', 34, role='static'), ev('aurora_veil', 'static', 240),
   ev('aurora_veil_end', 'static', 40, stop=['aurora_veil', 'aurora_veil_ally', 'aurora_veil_weaken']),
   ev('aurora_veil_ally', 'attached', 240, 'target'), ev('aurora_veil_weaken', 'attached', 240, 'target')], True),
 (373, 'titan_stride', '타이탄의 발걸음', 'earth', 'AIM', 13, 16, 1.1, 450, 900,
  [N('titan_stride', 'TitanStride', 'aim=line:13:7')],
  '바라보는 방향 3.5 / 7 / 11블록에 거인의 발이 차례로 내려찍음(각 1초 예고): +1.0초·+1.8초 반경 2.2 피해 x0.6, +2.8초 반경 3.5 피해 + 기절 1초',
  '발 3번, 반경 2.2 / 2.2 / 3.5, 피해 x0.6 / x0.6 / 16+1.1, 기절 1초',
  '거인의 발이 바라보는 방향을 따라 세 번 땅을 내려찍습니다. 발이 떨어질 자리는 1초 전에 표시되고, 마지막 발에 맞은 적은 기절합니다.',
  '대지 마법 35종 습득하기.', None,
  'TitanStride', 130,
  [cast('titan_stride', 72, role='static'), ev('titan_stride_step_l', 'impact', 44), ev('titan_stride_step_r', 'impact', 44),
   ev('titan_stride_final', 'impact', 52)], True),
]
EXTRA = {
 'CHA_flame_emperor_x': ('flame_emperor', [N('flame_emperor', 'FlameEmperor', 'ticks=160;shots=4;r=6;cooldown=600')], '염제 강림 영창 완료 후 8초 화신 (chachannel then=)'),
 'CHA_leviathan_rise_x': ('leviathan_rise', [N('leviathan_rise', 'LeviathanRise', 'r=10')], '레비아탄 영창 완료 후 바다뱀 (chachannel then=)'),
 'CHA_abyss_gate_x': ('abyss_gate', [N('abyss_gate', 'AbyssGate', 'r=8;ticks=160')], '심연의 문 영창 완료 후 문 8초 (chachannel then=)'),
}
