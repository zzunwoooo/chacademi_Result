# Claude 2026-10-19 — 몹 예고 장판(원형·직선·부채꼴) + 첫 몹 스킬 "봉인의 술사 봉인진" (빌드 안 함)

브랜치 `claude/mob-telegraph-20261019` (기반 `claude/status-hud-polish-20261019` = c5a2ccf + HUD v4). 노트북에서 Gradle 미실행 — 순수 Java 테스트만 로컬 확인.

## 무엇
- 적 범위 공격 전 **빨간 예고 장판**: 반투명 빨강 채움 + 밝은 빨강 외곽선(+옅은 글로우). 모양 3종 = 원형 / 직선 / 부채꼴 (사장님 레퍼런스 시트).
- 서버가 보낸 숫자 그대로 그리고, 예고가 끝나면 **같은 숫자로 판정** (`TelegraphProtocol.inside`) → 보이는 범위 = 맞는 범위.
- 첫 스킬: 봉인의 술사(CA_Wild_mf_warden_mage, 커스텀 미몹) `CHA_MOB_seal_circle` — 대상 발밑 반경 4 원형, 예고 1.2초 → 봉인 발동, 피해 4 + 무장해제 1.5초. 이로치(`_shiny`)는 `CHA_MOB_seal_circle_shiny` (VFX만 루비색).
  VFX 팩 `SealCircle`: `claude:sealcircle/seal` (청록·상아, 몹 외형색) / `claude:sealcircle/seal_ruby` (이로치 루비·금). 같은 텍스처, 고유 RGBA 2.8 MiB.
  (처음 만든 블레이즈 BlazePillar 팩은 사장님 지시로 미사용 — 바닐라 기반 몹이라서.)

## 파일
- protocol `TelegraphProtocol` — 채널 `magiccodex:telegraph`, v1 SHOW/CLEAR, `inside()` 판정(여유 = 대상 몸 절반 폭).
- magic-mythic-paper
  - `TelegraphMech` = `chatelegraph{shape;at;r;length;width;angle;ticks;flat|mult;element;ignite;status;hits;height;vfx;vfxlead;vfxref;tint;cancel}` (MagicMythicPlugin 스위치 등록).
  - `Telegraphs` = 송신(64블록, `telegraph.range`)·타이머·시전자 사망 시 취소(CLEAR)·지면 높이 스냅. config `telegraph:` 섹션 추가.
  - `ReactionVfx.effect(id, at, scale, yaw, ticks, tint)` 추가 (기존 반응 VFX 재생도 이걸 거침, 동작 동일). `Reactions.vfx()` 접근자.
  - `TelegraphCommand`: `/마법미몹 예고 <원형|직선|부채꼴|전부|화염기둥> [초]` — 피해 없음, 보이기만. 봉인진/봉인진이로치 = 예고 1.2초 + SealCircle VFX.
  - `mythicmobs/Skills/chacademi_mob_skills.yml` (새 파일, 손 관리) — 봉인의 술사 스킬 2개. 몹에 붙이는 줄은 파일 맨 위 주석.
- magic-codex-fabric: `TelegraphClient`(수신·렌더, AFTER_ENTITIES, aim 텍스처 band/glow 재사용), `TelegraphGeometry`(순수 수학), MagicCodexClient 초기화 1줄.
- 테스트: `TelegraphProtocolTest`, `TelegraphGeometryTest` (fabric), `TelegraphMechTest` (mythic).

## GPT 할 일
1. 빌드: protocol → magic-mythic-paper → magic-codex-fabric. 테스트 통과 확인.
2. VFX 팩: 사장님 PC `C:\Users\matil\Desktop\차카데미 vfx_Claude\SealCircle\SealCircle.zip` → PortableVFX 팩 폴더 `sealcircle` (static 2시스템 seal / seal_ruby, areaRadius reference 4). client_compat check OK.
3. MythicMobs: `chacademi_mob_skills.yml` 를 Skills 폴더에, `chacademia_custom.yml` 의 `CA_Wild_mf_warden_mage` / `_shiny` Skills 에 파일 맨 위 주석의 줄 추가 → `/mm reload`.
4. 확인: `/마법미몹 예고 전부` (세 모양), `/마법미몹 예고 봉인진`, `/마법미몹 예고 봉인진이로치`, 서바이벌로 봉인의 술사 앞에서 장판 안/밖 판정.
5. 시전 모션 `seal_cast` (1.6초, 1.12초 내려찍기·1.2초 발동): 사장님 PC `C:\Users\matil\Desktop\차카데미 vfx_Claude\몹스킬\봉인의 술사 - 봉인진\model\ca_mf_warden_mage(_s).bbmodel` 로 ModelEngine blueprints 의 기존 파일 교체 → `/meg reload` + 리소스팩 재생성. 기존 애니(idle/walk/attack/tentacle/death/ca_hurt)는 그대로.
6. 한 폴더 모음: `...\몹스킬\봉인의 술사 - 봉인진\` (model / mythicmobs / vfx / preview / source + README.md). 몹 파일은 Skills 에 한 줄만 추가(참고본 mobs_mf_warden_mage.yml).

## 주의
- 서버 부담: 예고 1개당 패킷 1번(~60바이트, 64블록 안 플레이어만) + 지연 작업 1개 + 판정 때 엔티티 검색 1번. 매 틱 도는 작업 없음(시전자 사망은 사망 이벤트로 취소). 그리는 건 전부 클라이언트.
- 몹 스킬은 기본 `hits=players` (플레이어만 맞음). 펫/몹도 맞게 하려면 `hits=enemies`.
- WorldGuard `magic` 금지 구역에선 시전자·대상 모두 막힘(기존 규칙 그대로).
- 클라이언트 렌더: 레이어 버퍼는 레이어마다 한 번만 가져와 끝까지 씀 (중간에 다른 레이어를 가져오면 앞 버퍼가 닫힘).
- 평평한 판 하나로 그림(시작 높이). 계단·언덕에선 일부가 땅에 묻히거나 뜰 수 있음 — 필요하면 다음에 지형 따라 나누기.
- 몹 마나는 아직 없음(사장님 "마나는 일단 놔둬") — 쿨다운만.

## 추가 (2026-10-19 오후) — 고위 몹 2호: 빙핵의 수호자 "빙핵 레이저" (직선 예고 첫 사용)
- 스킬 `CHA_MOB_frost_laser` / `_shiny` (chacademi_mob_skills.yml): 시전자 앞 직선 길이 16 · 폭 2, 예고 1.2초 → 관통 광선, 피해 8 + 둔화 2초(40%). 쿨 12초.
- VFX 팩 `FrostLaser` → 폴더 `frostlaser`: `laser` (흰·라벤더·보라, 모델 색) / `laser_obsidian` (이로치 흑요·은백·금). static, 시전자 yaw 기준 +Z. client_compat OK, 고유 RGBA 2.6 MiB.
- 모션 `frost_laser` (2.0초): `ca_glacialcore(_s).bbmodel` 에 추가. 기존 skill4 의 날개 자세·봉인 문양 뼈를 재사용, 모델 레이저 뼈는 숨김(광선은 VFX).
- 모음 폴더: 사장님 PC `차카데미 vfx_Claude\몹스킬\빙핵의 수호자 - 빙핵 레이저\` (README 표대로 설치).
- 테스트: `/마법미몹 예고 빙핵레이저`, `/마법미몹 예고 빙핵레이저이로치` (내 발에서 바라보는 방향, 피해 없음).
- 주의: VFX 발사점은 발 위 2.4 m · 앞 1.6 m (모델 문양 위치). ModelEngine 에서 모델 크기를 바꿨다면 어긋날 수 있음.

## 추가 — 몹 스킬 1차 묶음 (계열당 대표 1개, 색만 다른 몹은 같은 스킬·VFX 색만 다름)
| 계열 | 스킬 id (`CHA_MOB_<스킬>_<몹>`) | 예고 | 효과 | VFX 팩 / 시스템 | 모션 |
|---|---|---|---|---|---|
| 오우거 3종 | ground_quake | 원형 시전자 r10 · 1.2초 | 피해 7 + 기절 0.8초 | `ogrequake` / quake_{club,axe,hammer}[_s] | ground_quake (attack2 늘림) |
| 고대 골렘 4종 | gravity_slam | 원형 시전자 r11 · 1.2초 (vfxlead 3) | 피해 8 + 중력 2초 | `gravityslam` / gravity_{arcane,corrupted,magma,royal}[_s] | gravity_slam (새로) |
| 드래곤 4종 | dragon_breath | 부채꼴 r16 · 60° · 1.2초 | 1.5초 구름 브레스, 0.3초마다 피해 4 ×5 + 사막 독 / 빙하 둔화 / 화산 화상 / 스컬크 실명 | `dragonbreath` / breath_{desert,glacial,nether,sculk}[_s] | dragon_breath (새로) |
| 서리 기사 | frost_field | 원형 시전자 r8 · 1.0초 | 피해 5 + 속박 1초 | `frostfield` / frost_field[_s] | frost_field (새로) |
| 터스커 3종 | earth_stomp | 원형 시전자 r10 · 1.2초 | 피해 6 + 기절 0.8초 | `earthstomp` / stomp_{demonic,dreamlight,forest}[_s] | earth_stomp (새로) |
- 스킬 yml: `chacademi_mob_skills.yml` 의 "자동 생성" 구간 (사장님 PC 각 폴더 mythicmobs/ 에도 같은 파일). 몹마다 Skills 에 한 줄 (mobs_*.yml 참고본).
- 모델: 사장님 PC `차카데미 vfx_Claude\몹스킬\<계열> - <스킬>\model\` (기본·이로치 모두). blueprints 같은 이름 교체.
- 시험 명령 추가: `/마법미몹 예고 효과 <팩/시스템> <원형|원형내발|직선|부채꼴> <크기> [각도·폭] [틱] [리드틱]` (피해 없음).
- 주의: 팩 이름 `earthquake` 는 기존 마법 팩과 겹쳐서 오우거 팩은 `ogrequake` 로 함.

## 추가 — 범위 확대 + 브레스 다시 만듦 + 몹 스킬 2차 묶음
사장님: "범위 너무 작아 … 브레스면 구름같은걸 쏴야지". 1차 표는 위에서 새 범위로 고침. VFX 팩도 새 범위에 맞게 다시 빌드(같은 이름 덮어쓰기).
- 봉인진 r4 → **r7** (vfxref 4 그대로 → VFX 1.75배), 빙핵 레이저 16×2 → **24×3** (vfxref 16 → 1.5배). 팩은 그대로.
- 드래곤 브레스 VFX: 직선 줄기·불꽃 튀김 없앰 → 부채꼴 안을 굴러 나가는 구름 덩어리(dust 시트, 가산 빛 구름 + 알파 구름 몸체 + 연기 + 바닥 구름). 하늘뱀 브레스도 같은 틀.

| 계열 | 스킬 id | 예고 | 효과 | VFX 팩 / 시스템 | 모션 |
|---|---|---|---|---|---|
| 영록 4종 | domain_seal | 원형 시전자 r10 · 1.0초 | 피해 6 + 위로 튕김(up 0.9) | `domainseal` / domain_{heavenly,demonic,rune,void}[_s] | domain_stomp 1.7초 |
| 하늘뱀 4종 | serpent_breath | 부채꼴 r12 · 55° · 1.0초 | 1.2초 구름 브레스, 피해 3 ×4 + 비취 둔화 / 마그마 화상 / 태양 실명 / 영체 약화 | `serpentbreath` / sbreath_{jade,magma,solar,spectral}[_s] | serpent_breath 2.6초 |
| 피닉스 4종 | feather_barrage | 부채꼴 r14 · 55° · 0.8초 | 깃털 5발, 피해 7 + 화상 / 뇌광 기절 | `featherbarrage` / feather_{flame,darkflame,light,thunder}[_s] | feather_barrage 1.4초 |
| 페가수스 | gale_wings | 반원(부채꼴 180°) r10 · 1.0초 | 피해 4 + 바깥으로 밀침(knock 1.6, up 0.4) + 중력 1초 | `galewings` / gale[_s] | gale_wings 1.7초 |
| 히포그리프 4종 | feather_storm | 원형 시전자 r8 · 1.0초 | 피해 6 | `featherstorm` / fstorm_{common,crystal,fallen,royal}[_s] | feather_storm 1.7초 |

- 코드: `chatelegraph` 에 `pulses`/`period` (같은 장판에 지연 판정 추가, 매 틱 작업 없음 — 판정마다 runTaskLater 1개, 시전자 죽으면 건너뜀), `knock`/`up`/`pull` (원형·부채꼴 = 중심에서 바깥, 직선 = 앞 방향). 시험 명령 `효과 ... [배율]` 인자 추가.
- 파일: 사장님 PC `차카데미 vfx_Claude\몹스킬\<계열> - <스킬>\` 10개 계열 모두 새로 덮어씀 (model / mythicmobs / vfx / preview / source / README). 팩만 따로: `차카데미 vfx_Claude\<Pack>\`.
- 주의: client_compat WARN 의 RGBA 수치(6~20 MiB)는 같은 텍스처를 재질마다 세는 툴 문제. 공용 lib/tex 를 같이 쓰는 팩들이라 고유 텍스처 실측 팩당 0.7~2.6 MiB.
- 주의: 브레스 pulses 는 같은 위치·방향 장판으로 판정(시전자가 돌아도 장판은 고정 — 예고와 같음).

## 추가 — 몹 스킬 3차 묶음 (범위기만, 유도·텔포 계열은 나중에 따로)
| 계열 | 스킬 id | 예고 | 효과 | VFX 팩 / 시스템 | 모션 |
|---|---|---|---|---|---|
| 키츠네 3종 | fox_charm | 원형 대상 r7 · 1.0초 | 피해 4 + 공포 1.5초 | `kitsunecharm` / charm_{normal,demonic,inferno}[_s] | charm_cast 1.6초 |
| 유니콘 | horn_ray | 직선 22×3 · 1.0초 | 피해 7 + 약화 2초 | `unicornray` / ray[_s] | horn_ray 1.9초 |
| 바실리스크 3종 | tail_sweep | 반원(부채꼴 180°) r9 · 1.0초 | 피해 6 + 밀침 (눈보라 둔화) | `basilisksweep` / sweep_{obsidian,snowstorm,wild}[_s] | tail_sweep 1.7초 |
| 와이번 4종 | tail_spikes | 원형 대상 r4 · 1.0초 × 3번 (delay 8틱) | 한 번에 피해 4 + 공허 실명 / 녹빛 독 / 업화 화상 / 불사 약화 | `wyvernspikes` / spikes_{ender,green,inferno,undead}[_s] | tail_spikes 2.2초 |
| 만티코어 4종 | roar_leap | MythicMobs `leap{velocity=150}` + 원형 대상 r8 · 1.0초 | 피해 7 + 공포 1초 (맹독 독 추가) | `manticoreroar` / roar_{aether,dusk,soul,venom}[_s] | roar_leap 1.8초 |

- 주의: 와이번 VFX 는 착탄 T0 = 0.3초 → `vfxlead=6` (다른 팩은 2). 3번 모두 그때 대상 위치에 새로 깔림.
- 주의: 만티코어 leap 는 MythicMobs 기본 mechanic. 몹이 못 뛰는 지형이면 예고·판정만 그 자리에서 남음(정상).
- 주의: 같은 스킬에 status 가 둘이면 `status=fear:20,poison:40` 처럼 한 줄로 합침(skills.py 가 처리).
- 파일: 사장님 PC `차카데미 vfx_Claude\몹스킬\<계열> - <스킬>\` 5개 새 폴더 (preview 에 `vfx_preview_전체색.html` = 모든 색 변종). 팩만 따로 `차카데미 vfx_Claude\<Pack>\`.
- 최신 `chacademi_mob_skills.yml` 은 3차 폴더와 이 레포에 있음 (1·2차 폴더의 사본은 그 계열 스킬만 같고 3차 스킬은 없음 → 서버엔 레포 파일 하나만 넣으면 됨).

## 추가 — 몹 스킬 4차 묶음 (범위기)
| 계열 | 스킬 id | 예고 | 효과 | VFX 팩 / 시스템 | 모션 |
|---|---|---|---|---|---|
| 팬텀 2종 | soul_drain | 원형 대상 r6 · 1.0초 | 피해 4 + 마나번 2초 | `phantomdrain` / drain_{ender,ghost}[_s] | soul_drain 2.0초 |
| 검치호 3종 | pounce | `leap{velocity=130}` + 원형 대상 r6 · 1.0초 | 피해 7 + 속박 1초 | `sabertoothpounce` / pounce_{arctic,midnight,savanna}[_s] | pounce 1.6초 |
| 울프호크 4종 | glide_dive | 직선 18×4 · 1.0초 + 끝에 `lunge` | 피해 6 + 살짝 밀침 (먹구름 기절 0.5초) | `wolfhawkglide` / glide_{dire,fallen,fullmoon,stormcloud}[_s] | glide_dive 1.9초 |
| 약초 수호골렘 | root_bind | 원형 대상 r6 · 1.0초 | 피해 4 + 속박 1.5초 | `rootbind` / roots[_s] | root_bind 1.7초 |
| 약초 도둑 | smoke_bomb | 원형 시전자 r6 · 0.8초 | 피해 2 + 실명 1.5초, 끝에 자기 투명 2초 (potion) | `smokebomb` / smoke[_s] | smoke_bomb 1.2초 |

- 주의: 약초 골렘·도둑 모델은 ca_attack 대신 `attack` 애니를 기준으로 새 애니를 만듦(뼈 이름 ra/la/b, r_a/l_a).
- 주의: 울프호크 lunge·도둑 투명은 예고가 끝날 때(`delay 18` / `delay 16`) 실행 — MythicMobs 기본 mechanic.

## 추가 — 몹 스킬 5차 묶음 (범위기, 마지막)
| 계열 | 스킬 id | 예고 | 효과 | VFX 팩 / 시스템 | 모션 |
|---|---|---|---|---|---|
| 망각의 해골술사 | bone_grasp | 원형 대상 r6 · 0.8초 | 피해 3 + 술사 쪽으로 끌어옴(pull 1.4) + 속박 0.5초 | `bonegrasp` / grasp[_s] | bone_grasp 1.4초 |
| 요정 4종 | fairy_dust | 원형 시전자 r6 · 1.0초 | 피해 2 + 수면 1.5초 (포자 요정 2초) | `fairydust` / dust_{flower,lake,leaf,mushroom}[_s] | fairy_dust 1.5초 |
| 유적 궁수 | aim_shot | 직선 24×1.5 · 2.0초 | 피해 8 | `ruinstrike` / strike_arrow[_s] | aim_shot 2.4초 |
| 유적 창병 | spear_throw | 직선 16×2 · 1.0초 | 피해 6 + 둔화 1.5초 | `ruinstrike` / strike_spear[_s] | spear_throw 1.4초 |
| 유적 검병 | dash_slash | 직선 10×3 · 0.8초 + 끝에 lunge | 피해 6 | `ruinstrike` / strike_slash[_s] | dash_slash 1.3초 |
| 봉인의 기사 | seal_charge | 직선 15×3 · 1.0초 + 끝에 lunge | 피해 7 + 기절 0.8초 | `sealcharge` / charge[_s] | seal_charge 1.8초 |

- 요정 4종: 문서상 범위기는 포자 요정 "수면 포자"뿐이라, 색만 다른 몹 규칙대로 4종 공통 스킬로 하고 가루 모양·색만 다르게 함.
- 유적 궁수 "조준 중 맞으면 취소"는 아직 없음(예고는 시전자 사망 때만 취소). 필요하면 다음에 피격 취소 추가.
- 같은 스킬에 같은 status 가 두 번이면 몹별 값이 이김(skills.py). 사장님 PC 의 모든 몹스킬 폴더 `chacademi_mob_skills.yml` 을 최신(스킬 138개)으로 맞춤.
- 남은 것(나중에): 유도·순간이동 계열 — 키츠네 여우불/순보, 와이번 낚아채기, 만티코어 독침, 요정 구슬/장난 등. 몹용 날아가는 VFX(투사체 추적)가 필요.

## 추가 — 6차: 짧은 근접 기본기 (사장님: "범위 위주라 짧게 찌르는 기본 공격 느낌도 필요")
| 계열 | 스킬 id | 예고 | 효과 | VFX 팩 / 시스템 | 모션 |
|---|---|---|---|---|---|
| 랩터 3종 | bite | 직선 5×2 · 0.4초 + 짧은 lunge | 피해 4 | `beastbite` / bite_{nightmare,red,undead}[_s] | bite 0.8초 |
| 악어 | croc_bite | 직선 4×2.5 · 0.5초 | 피해 6 + 속박 0.6초 | `beastbite` / bite_croc[_s] | croc_bite 0.9초 |
| 곰 3종 | swipe | 부채꼴 4.5 · 110° · 0.5초 | 피해 6 + 살짝 밀침 (북극곰 둔화) | `bearswipe` / swipe_{brown,black,polar}[_s] | swipe 1.0초 |
| 봉인의 궁수 | chain_shot | 직선 18×1.5 · 0.6초 | 피해 5 + 궁수 쪽으로 끌어옴(pull 0.9) | `wardenarts` / warden_chain[_s] | chain_shot 1.0초 |
| 봉인의 거벽 | shield_bash | 부채꼴 4.5 · 120° · 0.5초 | 피해 5 + 크게 밀침, 자기 저항 5초 | `wardenarts` / warden_bash[_s] | shield_bash 0.9초 |

- 기본기는 `~onTimer:40 0.5` (2초마다 50%), 쿨 3~6초. 예고가 짧아(0.4~0.6초) 피할 수는 있지만 근접에서 꾸준히 위협.
- 거벽 "거벽 진형"(정면 피해 -60%)은 정면 판정 기능이 없어서 지금은 MythicMobs `potion RESISTANCE` 5초로 대신함.
- 랩터·곰·악어는 문서에 없는 몹 — 사장님 요청(기본 공격형)으로 공격적인 야생 몹을 골라 추가함. 스킬 152개.

## 기본기 · 특수기 (2026-10-19 오후, 사장님: "모든 몹이 기본 공격 느낌의 기술 / 특수기는 레어 몹만 / 몹별로 다르게 / 이 퀄리티로")

### 새 서버 코드 (빌드·테스트 필요 — GPT)
- `MobShotMech` (`chamobshot`, MagicMythicPlugin 등록) — 몹 가벼운 투사체. 예고 없음(보고 피하는 기술). 파라미터: count≤8 spread≤360 interval speed range radius homing rise(중력 0.04) fromy/fromz burst orbit flat/element/status/knock/up, `vfx`=projectile 역할 시스템(`GameplayVisual.cast(null, id, …)` 로 서버가 움직임), `hitvfx`=맞은/떨어진 자리(`ReactionVfx.effect`). **hit 시스템은 impact 역할**로 만들어져 있음 → `ReactionVfx.effect` 가 impact 역할도 재생하는지 확인, 안 되면 클라 쪽 static 취급 또는 팩의 role 을 static 으로 바꿔 재생(레이어는 그대로, 로컬 +Y=위).
- `GameplayVisual.cast(UUID owner, …)` — owner null 허용(몹).
- `TelegraphMech`: `blink=true`(+`vfxout`) 몹이 예고 자리 옆으로 순간이동 후 판정, `tpme=true`(+`vfxin`) 맞은 대상을 몹 앞 1.6블록으로 끌어옴. 둘 다 2칸 빈 공간 확인.
- 소환수 `mythicmobs/Mobs/chacademi_mob_minions.yml`: `CHA_MINION_wolf`(WOLF + ca_lostasset_mob1_wolf) / `CHA_MINION_skeleton`(HUSK + ca_ncr_warrior_skeleton), 10초 뒤 remove. 모델 id·Type 확인.

### yml
- `chacademi_mob_skills.yml` 끝 "자동 생성 2" 구간 (scratchpad ms/basics.py): 기본기 156개(`CHA_MOB_<기본기>_<몹>`), 특수기 38개.
- 몹 항목 줄: 큰 기술 `~onTimer:300 0.35` (예전 100 → 덜 쓰게), 기본기 `~onTimer:40 0.6`, 특수기 `~onTimer:240 0.3`. 몹별 참고본 = PC `몹스킬\<폴더>\mythicmobs\mobs_추가할줄.yml`. 랩터·악어·곰·봉인궁수·거벽은 예전 6차 bite/swipe/chain_shot/shield_bash 가 새 기본기로 교체(예전 스킬은 yml 에 남아 있지만 몹에 안 붙임).
- 모션: `state{s=attack}` 기본, 오우거 attack1 · 서리기사 slash · 해골술사/유적궁수/유적검병 attack2 · 빙핵 skill5. 모델에 그 이름이 없으면 모션 없이 기술만 나감 — GPT 가 bbmodel 애니 이름 확인.
- 콤보(서리기사 2연·히포그리프 2연·검치호 3연·유적검병 3연): 예고를 여러 번, VFX 는 첫 번째에만(VFX 하나가 콤보 전체를 그림).

### VFX 팩 (PC `차카데미 vfx_Claude\몹스킬\<폴더>\기본기\` + 루트 `<Pack>\`)
| 팩 | 몹 | 방식 |
|---|---|---|
| OgreSwing | 오우거 | 부채꼴 160° 휘두르기 |
| GolemBoulder | 골렘 | 룬 바위 포물선 투척 + 착탄 |
| DragonTail | 드래곤 | 꼬리 360° 회전 |
| FrostCross | 서리기사 | X자 2연 베기 |
| TuskerUpper | 터스커 | 엄니 올려치기 |
| StagHorn | 영록 | 빛 뿔 3갈래 투사체 |
| SerpentCoil | 하늘뱀 | 나선 휘감기 (+ SpecialSerpent 끌어오기) |
| PhoenixFeather | 피닉스 | 곡선 유도 불깃털 3발 |
| PegasusCrescent | 페가수스 | 날아가는 반달 바람날 2개 |
| HippoClaw | 히포그리프 | 발톱 세 줄 2연 |
| KitsuneFox | 키츠네 | 여우불 공전 후 유도 (+ SpecialKitsune 순간이동) |
| UnicornStar | 유니콘 | 대상 발밑 별 낙하 |
| BasiliskSpit | 바실리스크 | 독 포물선 + 웅덩이 |
| WyvernSpike | 와이번 | 가시 3연사 포물선 (+ SpecialWyvern 끌어오기) |
| ManticoreClaw | 만티코어 | 2연 할퀴기 + 포효 |
| PhantomScythe | 팬텀 | 영혼 낫 (+ SpecialPhantom 순간이동) |
| SaberClaw | 검치호 | 3연 할퀴기 |
| WolfhawkBlade | 울프호크 | 회전 바람칼날 (+ SpecialWolfhawk 늑대 소환) |
| HerbWhip | 약초골렘 | 덩굴 채찍 |
| HerbPotion | 약초도둑 | 독약병 + 독 웅덩이 |
| SkelOrb | 해골술사 | 망각탄 3발 (+ SpecialSkelmage 뼈병사 소환) |
| FairyOrb | 요정 | 곡선 유도 구슬 2발 |
| RuinArrow / RuinSpin / RuinTriple | 유적 궁수/창병/검병 | 뼈화살 2연사 / 창 회전 / 3연 베기 |
| SealShield | 봉인기사 | 방패 내려찍기 문양 |
| SealTalisman | 봉인술사 | 부적 3장 곡선 (+ SpecialSeal 끌어오기) |
| GlacialShards | 빙핵의 수호자 | 결정 공전 → 8방향 |
| BeastJaw | 랩터·악어 | 물어뜯기 |
| BearMaul | 곰 | 양앞발 내려찍기 |
| ChainArrow / ShieldBash | 봉인궁수 / 거벽 | 사슬 화살 / 방패 돌진 |
- 모두 client_compat check OK. **텍스처 주의:** 붓질 획 시트(T_SlashStroke_Sheet + T_InkStroke_Sheet, 각 1024×512)가 팩마다 들어가 고유 RGBA 2.9~7.2 MiB (목표 4 MiB 초과). 클라 1 GiB 예산이 빠듯하면 두 시트를 512×256 으로 줄이거나 공용 팩 하나로 묶는 작업 필요 — 알려주면 Claude 가 함.

### 기본기 예고 없음 (2026-10-19 15:40, 사장님: "기본기는 위험 예고 하지 말고, 범위형 큰 기술만 예고")
- `chatelegraph` 새 파라미터 `warn=false`: 빨간 장판을 안 보내고(패킷 없음) 같은 모양으로 `ticks` 뒤 판정만 함. `Telegraphs.start(..., warn, ...)` 오버로드, 그려지지 않은 건 clear 패킷도 안 보냄.
- 기본기 근접형 전부 `warn=false;ticks=8` (0.4초 뒤 판정, 휘두르는 모션에 맞춤). 유니콘 별 낙하만 ticks=14 (VFX 가 별가루 모으는 0.7초). 투사체 기본기(chamobshot)는 원래 예고 없음.
- 처음 만든 큰 기술(범위기)과 특수기는 예고 그대로.

## 이 브랜치에 같이 들어간 이후 변경 (2026-10-19 밤) — 배포할 때 같이 확인
| 커밋 | 내용 | 설명 문서 |
|---|---|---|
| `a31fb9e`, `c7925ef` | 몹 스킬을 플레이어 습득 마법 300~323번으로 (그 몹 처치 시 5% 발견) — default-spells, mana-spells, discoveries, spell-runtime, chacademi_spells, spell-catalog | `magic-mythic-paper/tools/mob_spells_doc.md`, 프로젝트 문서 `claude/mob-spells-300-323.md` |
| `72294d5` (merge `194aea1`) | 대상 체력바: 머리 방향 대신 **화면 십자선(에임) 기준**으로 몹 선택. 새 C2S 채널 `magiccodex:target_aim` → 서버 Bridge + 클라 모드 같이 배포 | `docs/handoff/CLAUDE_TARGET_HUD_AIM_20261019.md` |
- 이 브랜치는 체력바 브랜치 `claude/status-hud-polish-20261019` 를 포함 (따로 배포할 필요 없음, `claude/target-hud-aim-20261019` 도 이미 합쳐짐).
- 알려진 문제(미수정): 체력바에서 플레이어 닉네임이 한글로 안 나온다는 제보. 서버는 `DisplayNames`(저장된 닉네임 → PlaceholderAPI `display-name-placeholder` → 계정 이름) 순으로 보냄 → 그 서버에서 닉네임 저장소/플레이스홀더가 비어 있는지 먼저 확인. 글자가 □ 로 깨지면 `hud_bold` 글꼴 한글 문제.

## 대상 지정 마법이 ModelEngine 모델 몹에게 안 써지던 문제 (2026-10-19 밤, 오너 제보) — 코드 있음, 빌드·실게임 안 함
- **원인 1 (클라):** `AimTargeting.targetable` 이 투명한 엔티티를 빼는데, ModelEngine 몹의 본체는 클라에 투명으로 보냄 → 조준 미리보기에서 대상이 안 잡혀 "사거리 안의 대상을 조준해 주세요". 모델 히트박스(Interaction 엔티티)도 대상으로 안 봄.
  - 수정: 투명이어도 플레이어가 아니면 대상 가능(옅은 은신 플레이어는 계속 불가). 조준선이 Interaction 히트박스에 닿으면 그걸 탄 몹 → 없으면 그 안에 있는 몹으로 바꿈(`modelBase`).
- **원인 2 (서버):** `SpellRuntime.prepare` 의 바닐라 광선이 작은 본체 상자만 알거나, 모델 히트박스(Interaction/Display)에 맞으면 몹이 아닌 엔티티가 대상이 됨.
  - 수정: ENTITY/AIR_ENTITY/BURNING_ENTITY 마법에서 맞은 게 LivingEntity 가 아니면 교화·체력바와 같은 광선 `TamingBridge.target(p, range)`(서브 히트박스·히트박스 엔티티 → 원래 몹)으로 다시 고름. `MagicCodexBridge.tamingBridge()` 게터 추가.
- 범위·조준점(AIM) 마법은 그대로.
- GPT 확인: 모델 몹(예: 고대 골렘, 드래곤)에게 대상 지정 마법(만년빙옥·화염 채찍·발화 등) 시전, 일반 몹·플레이어 대상도 예전처럼 되는지.
