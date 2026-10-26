"""동행 펫 (오너 2026-10-26, 메모 #81) 데이터 생성기.
Run: python3 tools/pet_companions.py  (magic-mythic-paper/ 에서)
 -> mythicmobs/Skills/chacademi_pet_skills.yml   (펫이 쓰는 원래 몹 스킬의 펫판: CHA_PET_*)
 -> ../magic-codex-paper/src/main/resources/pet-companions.yml (펫별 등급·움직임·크기·스킬/특수 능력·설명)
 -> ../deployment/operating-config/MythicMobs/Skills/chacademi_pet_skills.yml (같은 내용)

오너 규칙:
 - 등급 = 야생 몹 등급 (creature-spawns habitats.yml rarity 1/2/3, boss 는 3성). 같은 종 색 다른 애들·변색종은 같은 능력.
 - 최대 체력: 1성 +3, 2성 +6, 3성 +10. 바닐라 펫은 건드리지 않음 (이 파일에 없음 = 동행 펫 기능 없음).
 - 원래 원거리 스킬이 있던 펫: 전투 중일 때만 2성 60초 / 3성 40초마다 그 스킬 중 하나를 무작위로, 싸우는 상대에게.
   펫 고정 피해(지능 무관). 펫판은 상태이상·밀치기·끌어당기기·순간이동·돌진·소환을 모두 뺀다 (불·독 지속 피해만 남김).
 - 스킬이 없던 펫: 정해진 특수 능력 (버프만, 상태이상·피해 없음). 생활 = 장착 중 항상, 전투 = 같은 주기로 몇 초.
"""
import json, os, re, yaml
HERE = os.path.dirname(os.path.abspath(__file__)); ROOT = os.path.dirname(HERE); REPO = os.path.dirname(ROOT)

HABITATS = json.load(open(os.path.join(REPO, 'creature-spawns-paper/src/main/resources/habitats.yml'), encoding='utf-8'))['habitats']
RANGED = yaml.safe_load(open(os.path.join(REPO, 'creature-spawns-paper/src/main/resources/ranged-skills.yml'), encoding='utf-8'))['ranged-skills']
MCPETS = os.path.join(REPO, 'reference/output/custom-creatures-v1/server/plugins/MCPets/Pets/ChAcademiaCustom')  # 예전 MCPets 정의 (이름·아이콘·권한·MythicMob 을 옮겨 옴)
MOBSK = yaml.safe_load(open(os.path.join(ROOT, 'mythicmobs/Skills/chacademi_mob_skills.yml'), encoding='utf-8'))

DAMAGE_SCALE = 0.5            # 펫 피해 = 원래 몹 스킬 flat × 이 값 (올림, 최소 1)
# 몹 스펙 #109 (2026-10-26): 몹 스킬 flat 은 등급 배율로 올렸지만 펫 피해는 그대로 → '원래 flat' 은 몹 스펙 적용 전 값(baseline.json)
_BASE = os.path.join(REPO, 'docs/plans/mob-spec/baseline.json')
BASE_FLAT = {k: v['flat'] for k, v in json.load(open(_BASE, encoding='utf-8'))['skills'].items()} if os.path.exists(_BASE) else {}
# 도감 체력 (오너 2026-10-26 수정): 꺼낸 펫이 아니라 '교화해 보유한 펫'이 많을수록 최대 체력이 누적으로 오름.
# 점수 = 보유 펫 등급 점수 합 (변색종도 같은 점수, 바닐라 펫은 pets.yml 별 기준). 체력 = 최대 × (점수 / 전체 점수)^곡선, 반올림.
# 전체 점수 = 변색종을 뺀 모든 펫(커스텀 + 바닐라) 점수 합 → 이걸 다 모으면 +20.
# 기준: 보이는 것만 대충(≈10마리, 거의 1성 ≈15점) +5 · 라이트(≈25마리 ≈51점) +8 · 헤비(≈70마리 ≈195점) +13 · 올클리어 +20.
POINTS = {1: 1, 2: 3, 3: 6}
COLLECTION = {'max': 20, 'curve': 0.38}
INTERVAL = {2: 60, 3: 40}

# 날아다니는 펫 / 물 펫 (나머지는 땅)
FLY = re.compile(r'^(owl|lil_bird|butterfly|lostasset_mob2_.*fairy|lostasset_mob2_flowersprite|phoenix_.*|hippogryph_.*|wyvern_.*|dragon_.*|horse_pegasus|sky_serpent_.*|wolfhawk_.*|phantom_.*|bulb_.*)$')
# 보이는 크기 (블록, 모델의 가장 긴 변): 등급이 높을수록 조금 크게
SIZE = {1: 0.55, 2: 0.7, 3: 0.85}

# 특수 능력 (스킬이 없던 2·3성). kind: passive(장착 중 항상) / combat(전투 중 주기 발동, seconds 동안)
# effect 키는 서버 PetCompanions 가 아는 것만: speed(+비율) knockback(1=안 밀림) max_mana(+수치) mana_regen(+비율)
#   haste(성급함 단계) dolphin(물속 이동) fishing(+비율, 희귀 물고기) night_vision water_breathing climate_immune fire_resist
#   jump(점프 강화 단계) fall(낙하 피해 감소 비율, 1=없음) intelligence(+비율) resistance(저항 단계) absorb(흡수 하트 칸)
#   cleanse(해로운 효과 제거) regen(재생 단계) heal(즉시 체력) mana(즉시 마나)
SPECIAL = {
 # ---- 2성
 'elk':            ('passive', None, {'speed': 0.10}, '이동속도 +10%'),
 'timber_wolf':    ('combat', 10, {'intelligence': 0.15}, '무리 울음: 10초간 지능 +15%'),
 'lostasset_mob1_wolf': ('combat', 10, {'intelligence': 0.15}, '무리 울음: 10초간 지능 +15%'),
 'buffalo':        ('combat', 8, {'resistance': 1, 'knockback': 1}, '버티기: 8초간 저항 I + 밀려나지 않음'),
 'buffalo_alpha':  ('combat', 8, {'resistance': 1, 'knockback': 1}, '버티기: 8초간 저항 I + 밀려나지 않음'),
 'alpha_boar':     ('passive', None, {'knockback': 1}, '밀려나지 않음'),
 'lostasset_mob3_mossyboar': ('passive', None, {'knockback': 1}, '밀려나지 않음'),
 'bulb_amethyst':  ('passive', None, {'max_mana': 80}, '최대 마나 +80'),
 'bulb_crimsonspore': ('passive', None, {'mana_regen': 0.20}, '마나 자연 회복 +20%'),
 'bulb_warpedfungus': ('passive', None, {'haste': 1}, '채집 속도 증가 (성급함 I)'),
 'piranha':        ('passive', None, {'dolphin': 1}, '물속 이동 빠름'),
 'swordfish':      ('passive', None, {'fishing': 0.10}, '낚시 운 증가 (희귀 물고기 +10%)'),
 'jellyfish_pink': ('passive', None, {'night_vision': 1}, '야간 투시'),
 'jellyfish_yellow': ('passive', None, {'night_vision': 1}, '야간 투시'),
 'seahorse_pink':  ('passive', None, {'water_breathing': 1}, '물속 숨'),
 'seahorse_yellow': ('passive', None, {'water_breathing': 1}, '물속 숨'),
 'baby_yeti':      ('passive', None, {'climate_immune': 1}, '서버 날씨·기후 영향 받지 않음 (좋은 효과·나쁜 효과 모두)'),
 'frost_ferret':   ('passive', None, {'climate_immune': 1}, '서버 날씨·기후 영향 받지 않음 (좋은 효과·나쁜 효과 모두)'),
 'gangster_snowman': ('combat', 0, {'absorb': 3}, '얼음 방패: 흡수 하트 3칸'),
 'lizard_aqua':    ('combat', 5, {'cleanse': 1, 'regen': 1}, '탈피: 해로운 효과 제거 + 5초간 재생'),
 'lizard_lava':    ('passive', None, {'fire_resist': 1}, '화염 저항'),
 'horse_glacial':  ('passive', None, {'speed': 0.15}, '이동속도 +15%'),
 'horse_magma':    ('passive', None, {'speed': 0.15}, '이동속도 +15%'),
 'horse_spartan':  ('passive', None, {'speed': 0.15}, '이동속도 +15%'),
 'frog_green':     ('passive', None, {'jump': 1, 'fall': 0.5}, '점프 강화 + 낙하 피해 50% 감소'),
 'frog_permafrost': ('passive', None, {'jump': 1, 'fall': 0.5}, '점프 강화 + 낙하 피해 50% 감소'),
 'bear':           ('combat', 0, {'absorb': 4}, '곰의 가호: 흡수 하트 4칸'),
 'bear_black':     ('combat', 0, {'absorb': 4}, '곰의 가호: 흡수 하트 4칸'),
 'bear_polar':     ('combat', 0, {'absorb': 4}, '곰의 가호: 흡수 하트 4칸'),
 'raptor_red':     ('combat', 8, {'speed': 0.20, 'intelligence': 0.15}, '사냥 본능: 8초간 신속 + 지능 +15%'),
 'crocodile':      ('combat', 8, {'resistance': 1}, '단단한 비늘: 8초간 저항 I'),
 # ---- 3성
 'nocsy_cat_fallen': ('combat', 10, {'heal': 6, 'mana': 60, 'intelligence': 0.15}, '달빛 축복: 체력 6·마나 60 회복 + 10초간 지능 +15%'),
 'frog_royal':     ('passive', None, {'jump': 2, 'fall': 1}, '점프 강화 II + 낙하 피해 없음'),
 'mf_warden_tank': ('combat', 8, {'absorb': 6, 'resistance': 1}, '철벽: 흡수 하트 6칸 + 8초간 저항 I'),
 'raptor_nightmare': ('combat', 10, {'speed': 0.40, 'intelligence': 0.15}, '악몽의 질주: 10초간 신속 II + 지능 +15%'),
 'raptor_undead':  ('combat', 10, {'speed': 0.40, 'intelligence': 0.15}, '악몽의 질주: 10초간 신속 II + 지능 +15%'),
}

# 펫판에서 빼는 것: 이동·소환 메커닉 줄, 사람을 옮기는 스킬
DROP_LINES = ('state{', 'lunge{', 'leap{', 'summon{')
SKIP_SKILL = ('blink=true', 'tpme=true', 'summon{')
DROP_PARAMS = ('knock', 'up', 'pull', 'blink', 'tpme', 'vfxout', 'vfxin', 'spell')
KEEP_STATUS = ('burn', 'poison')

def pet_skill_lines(name):
    lines = MOBSK[name]['Skills']
    if any(s in l for l in lines for s in SKIP_SKILL): return None
    out = []; dmg = 0; fi = 0; base = BASE_FLAT.get(name)
    for l in lines:
        if l.startswith(DROP_LINES): continue
        if l.startswith('delay'):
            out.append(l); continue
        m = re.match(r'^(\w+)\{(.*)\}\s*(@\S+)?$', l)
        if not m or m.group(1) not in ('chatelegraph', 'chamobshot'): return None
        params = []
        for kv in m.group(2).split(';'):
            if '=' not in kv: continue
            k, v = kv.split('=', 1)
            if k in DROP_PARAMS or k == 'hits' or k == 'mult' or k == 'warn': continue
            if k == 'status':
                keep = [s for s in v.split(',') if s.split(':')[0] in KEEP_STATUS]
                if not keep: continue
                v = ','.join(keep)
            if k == 'flat':
                orig = base[fi] if base and fi < len(base) else float(v); fi += 1
                n = max(1, -(-orig * DAMAGE_SCALE // 1)); dmg += n; v = str(int(n))
            params.append(f'{k}={v}')
        params += ['mult=0', 'hits=enemies', 'warn=false', 'spell=pet_skill']
        out.append(f"{m.group(1)}{{{';'.join(params)}}} @trigger")
    while out and out[-1].startswith('delay'): out.pop()
    return (out, int(dmg)) if out else None

class _Dumper(yaml.SafeDumper):
    pass
# 여러 줄 설명은 | 블록으로 (운영 overlay 검사 도구의 작은 YAML 파서가 읽을 수 있게)
_Dumper.add_representer(str, lambda d, v: d.represent_scalar('tag:yaml.org,2002:str', v, style='|' if '\n' in v else None))

def main():
    skills_out = ['# 차카데미 동행 펫 스킬 (자동 생성: magic-mythic-paper/tools/pet_companions.py) - 손으로 고치지 마세요',
                  '# 원래 몹 스킬(chacademi_mob_skills.yml)의 펫판: 상태이상·밀치기·이동·소환 없음, 예고 장판 없음, 펫 고정 피해(flat).',
                  '# MagicCodexBridge PetCompanions 가 전투 중 주인(플레이어)을 시전자로, 싸우는 상대를 대상으로 시전합니다.', '']
    pets = {}; made = {}
    for key, h in HABITATS.items():
        stars = 3 if h['rarity'] == 'boss' else int(h['rarity'])
        motion = 'swim' if h.get('medium') == 'water' else ('fly' if FLY.match(key) else 'ground')
        base = {'name': h['name'], 'stars': stars, 'motion': motion, 'size': SIZE[stars]}
        for shiny in (False, True):
            pid = 'ca_' + key + ('_shiny' if shiny else '')
            entry = dict(base)
            if shiny: entry['name'] = h['name'] + ' (변색종)'
            src = yaml.safe_load(open(os.path.join(MCPETS, pid + '.yml'), encoding='utf-8'))
            assert src['Id'] == pid, pid
            entry['permission'] = src['Permission']
            entry['mythic'] = src['MythicMob']
            icon = src.get('Icon') or {}
            entry['icon'] = {'material': icon.get('Material', 'AMETHYST_SHARD'), 'name': icon.get('Name', '§f' + entry['name']),
                             'lore': [str(x).rstrip() for x in icon.get('Description', [])]}
            info = [f'{stars}성 · 교화하면 도감 체력 점수 +{POINTS[stars]}']
            if stars >= 2:
                mob = 'CA_Wild_' + key + ('_shiny' if shiny else '')
                ranged = RANGED.get(mob, [])
                usable = []
                for s in ranged:
                    if s not in MOBSK: continue
                    pet_name = 'CHA_PET_' + s[len('CHA_MOB_'):]
                    if pet_name not in made:
                        r = pet_skill_lines(s)
                        if r is None: made[pet_name] = None; continue
                        lines, dmg = r; made[pet_name] = dmg
                        skills_out += [f'# {h["name"]} - {s} 의 펫판 (피해 {dmg})', f'{pet_name}:', '  Cooldown: 0', '  Skills:'] + [f'  - {l}' for l in lines] + ['']
                    if made[pet_name] is not None: usable.append(pet_name)
                if usable:
                    entry['skills'] = usable
                    names = [MOBSK_title(s) for s in usable]
                    info.append(f'전투 중 {INTERVAL[stars]}초마다 스킬: ' + ' / '.join(names))
                    info.append(f'피해 {max(made[s] for s in usable)} (펫 고정, 지능 무관)')
                elif key in SPECIAL:
                    kind, secs, eff, text = SPECIAL[key]
                    entry['special'] = {'kind': kind, 'effects': eff, **({'seconds': secs} if kind == 'combat' and secs else {})}
                    info.append(('장착 중: ' if kind == 'passive' else f'전투 중 {INTERVAL[stars]}초마다 ') + text)
                else:
                    raise SystemExit(f'2·3성인데 스킬도 특수 능력도 없음: {key}')
            entry['info'] = '\n'.join(info)
            pets[pid] = entry
    doc = {
        'collection-health': {'points': {str(k): v for k, v in POINTS.items()}, **COLLECTION},
        'interval-seconds': {str(k): v for k, v in INTERVAL.items()}, 'combat-seconds': 8, 'skill-range': 24,
        'blocked-matches': ['quidditch', 'duel'],
        'pets': pets,
    }
    head = ('# 동행 펫 (오너 2026-10-26, 메모 #81) - 자동 생성: magic-mythic-paper/tools/pet_companions.py\n'
            '# 여기 있는 펫은 MCPets 몹을 소환하지 않고 클라가 작게 그립니다 (모든 사람에게 보임). 없는 펫(바닐라)은 예전 그대로.\n'
            '# collection-health: 도감 체력 — 교화해 보유한 펫 등급 점수(points) 합이 많을수록 최대 체력 증가. 체력 = max × (보유 점수 / 전체 점수)^curve (전체 = 변색종 뺀 모든 펫)\n'
            '# interval-seconds: 전투 중 스킬·특수 능력 주기 / combat-seconds: 마지막으로 때리거나 맞은 뒤 이만큼 = 전투 중\n'
            '# blocked-matches: 이 경기 중에는 펫을 꺼낼 수 없음 (꺼내 둔 펫은 경기 시작 때 들어감)\n'
            '# pets.<펫ID>: stars · motion(ground/fly/swim) · size(보이는 크기, 블록) · skills(CHA_PET_*, 무작위 1개) 또는 special · info(펫 창 설명)\n'
            '#   permission(보유 권한) · mythic(예전 MCPets MythicMob, 변신 가면용) · icon(펫 창 아이콘: material·name·lore) — MCPets 없이 동작 (2026-10-26)\n')
    for out in ('magic-codex-paper/src/main/resources/pet-companions.yml', 'deployment/operating-config/MagicCodexBridge/pet-companions.yml'):
        with open(os.path.join(REPO, out), 'w', encoding='utf-8') as f:
            f.write(head); yaml.dump(doc, f, Dumper=_Dumper, allow_unicode=True, sort_keys=False, width=200)
    # 서버 파일 교체 때 지울 MCPets 정의 (동행 펫으로 옮겨 감)
    removal = {'reason': '동행 펫 전환 (오너 2026-10-26, 메모 #81): 커스텀 펫은 MCPets 없이 MagicCodexBridge pet-companions.yml 로 동작',
               'servers': ['school', 'wild', 'arena'],
               'delete': sorted(f'plugins/MCPets/Pets/ChAcademiaCustom/{pid}.yml' for pid in pets),
               'keep': ['plugins/MCPets/Pets/ChAcademiaTaming (바닐라 펫)', 'plugins/MCPets/Pets/ChAcademiaShiny (바닐라 변색종 펫)',
                        'MythicMobs CA_Pet_* 몹 정의 (변신 가면이 씀)', '플레이어 보유 권한 chacademia.pet.* (그대로 이어서 씀)']}
    with open(os.path.join(REPO, 'deployment/mcpets-companion-removal.json'), 'w', encoding='utf-8') as f:
        json.dump(removal, f, ensure_ascii=False, indent=1); f.write('\n')
    text = '\n'.join(skills_out) + '\n'
    for p in (os.path.join(ROOT, 'mythicmobs/Skills/chacademi_pet_skills.yml'), os.path.join(REPO, 'deployment/operating-config/MythicMobs/Skills/chacademi_pet_skills.yml')):
        open(p, 'w', encoding='utf-8').write(text)
    print(len(pets), 'pets,', sum(1 for v in made.values() if v is not None), 'pet skills,', sum(1 for v in made.values() if v is None), 'skipped skills')

# 원래 몹 스킬의 한글 이름 (펫 창 설명용)
TITLES = {
 'aim_shot': '조준 사격', 'bone_arrows': '뼈 화살', 'bone_grasp': '뼈의 손아귀', 'chain_arrow': '사슬 화살', 'dash_slash': '돌진 베기',
 'domain_seal': '영역 선포', 'dragon_breath': '드래곤 브레스', 'earth_stomp': '대지 짓밟기', 'fairy_orbs': '요정 구슬',
 'feather_barrage': '깃털 탄막', 'feather_storm': '깃털 폭풍', 'fire_feathers': '불깃털', 'fox_charm': '현혹', 'foxfire_orbit': '여우불 공전',
 'frost_field': '서리 진', 'frost_laser': '빙핵 레이저', 'gale_wings': '돌풍 날갯짓', 'glide_dive': '활강 습격', 'gravity_slam': '중력 강타',
 'ground_quake': '땅 울림', 'horn_ray': '뿔 광선', 'ice_shards': '얼음 파편', 'light_horns': '빛의 뿔', 'moon_wind': '달바람',
 'oblivion_orbs': '망각의 탄환', 'poison_flask': '독 플라스크', 'pounce': '사냥 도약', 'roar_leap': '포효 도약', 'root_bind': '뿌리 묶기',
 'rune_boulder': '룬 바위', 'seal_charge': '봉인 돌격', 'seal_circle': '봉인진', 'seal_talismans': '봉인 부적', 'serpent_breath': '하늘뱀 브레스',
 'soul_drain': '혼 흡수', 'spear_throw': '창 던지기', 'spike_volley': '가시 탄막', 'tail_spikes': '꼬리 가시', 'tail_sweep': '꼬리 휩쓸기',
 'venom_spit': '독 뱉기', 'vine_whip': '덩굴 채찍', 'wind_blade': '바람 칼날',
}
def MOBSK_title(pet_name):
    rest = pet_name[len('CHA_PET_'):]
    for k in sorted(TITLES, key=len, reverse=True):
        if rest == k or rest.startswith(k + '_'): return TITLES[k]
    raise SystemExit('스킬 한글 이름 없음: ' + pet_name)

if __name__ == '__main__':
    main()
