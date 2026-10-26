# 차카데미 모션 (Fabric 모드 + Paper 플러그인)

마법을 쓸 때 플레이어 모션을 재생하고, 서버 플러그인을 거쳐 다른 유저 화면에도 같은 모션이 보이게 하는 구성입니다.
0.2.0 부터 **빗자루 비행**(비행 마법)도 이 모드/플러그인이 맡습니다 → 아래 '빗자루 비행' 절.

```
chacademy-motion-mod/      Fabric 1.21.4 클라이언트 전용 모드 (playerAnimator 사용)
chacademy-motion-plugin/   Paper 1.21.4 플러그인 (모션 전달)
```

## 흐름

```
[시전자 클라]  R 키 (또는 ChacademyMotionClient.cast("wand_cast"))
   ├─ 내 화면에서 바로 모션 재생 + 12틱 뒤 파티클
   └─ chacademy:cast  [String 모션이름]  ──▶ 서버
[플러그인]     허용 목록 · 권한 · 쿨타임 검사 → MotionCastEvent (취소 가능)
   └─ chacademy:anim  [UUID 시전자][String 모션이름]  ──▶ 시전자를 보고 있는 유저들
[다른 클라]    UUID 로 플레이어를 찾아 같은 모션 + 같은 타이밍 파티클
```

- 문자열은 `VarInt(UTF-8 바이트 길이) + UTF-8`, UUID 는 `long + long` (빅엔디언). 마인크래프트 기본 코덱과 같은 형식이고, 플러그인의 `MotionCodecTest` 로 검증합니다.
- 모드가 없는 유저에게는 Bukkit 이 패킷을 보내지 않으므로 접속에 문제가 없습니다.

## 빌드

둘 다 JDK 21 이 필요합니다.

```bash
cd chacademy-motion-mod    && ./gradlew build   # build/libs/chacademy-motion-0.3.0.jar
cd chacademy-motion-plugin && ./gradlew build   # build/libs/chacademy-motion-plugin-0.3.0.jar
```

## 설치

| 위치 | 넣을 파일 |
|---|---|
| 클라이언트 `mods/` | `chacademy-motion-0.3.0.jar`, Fabric API (1.21.4), playerAnimator `2.0.5+1.21.4-fabric` |
| 서버 `plugins/` | `chacademy-motion-plugin-0.3.0.jar` |

## 테스트 방법

1. 모드를 넣은 클라이언트 두 개로 서버에 접속합니다.
2. 한 쪽에서 **R** 키를 누르면 양쪽 화면 모두에서 모션과 파티클이 보여야 합니다.
3. 서버 콘솔에서 `/테스트 모션 wand_cast <닉네임>` 을 쓰면 서버 주도로 재생됩니다 (본인 포함).
4. `config.yml` 의 `debug: true` 로 바꾸고 `/서버관리 새로고침 모션` 하면 패킷 송수신이 로그에 찍힙니다.

## 새 모션 추가

1. Blockbench(Emotecraft 플러그인)나 직접 작성한 JSON 을 `chacademy-motion-mod/src/main/resources/assets/chacademy_motion/player_animations/` 에 넣습니다. 파일 안의 `"name"` 값이 모션 이름이 됩니다.
2. 플러그인 `config.yml` 의 `allowed-animations` 에 같은 이름을 추가합니다.
3. 마법 코드에서 `ChacademyMotionClient.cast("새모션이름")` 을 부릅니다.
4. 시전 순간 이펙트가 필요하면 `CastEffects` 에 타이밍을 추가합니다.

## 다른 플러그인에서 쓰기

```java
// 마나 부족이면 다른 유저에게 모션을 보내지 않기
@EventHandler
public void onMotion(MotionCastEvent e) {
    if (!hasMana(e.getPlayer())) e.setCancelled(true);
}

// 서버에서 판정한 마법이면 서버가 직접 모션 재생 (시전자 포함)
ChacademyMotionPlugin.get().playMotion(player, "wand_cast", true);
```

## 빗자루 비행 (0.2.0)

비행 마법을 쓰면 빗자루를 소환해 타고 납니다. 펫(MCPets)과 상관없는 독자 기능이라 펫과 같이 다닐 수 있습니다.

### 구조 (서버 부담을 줄이려고 클라이언트가 움직임을 계산)

```
[비행 마법] Bridge → 마법 미몹 chaworld{type=flight} → ChacademyMotion.broomFlight(player, 200틱)
[플러그인]  탈 수 있는지 확인 → allowFlight 켜기 → chacademy:broom_ride (조작 수치, 본인에게 1번)
                                  → chacademy:broom_state (누가 어떤 빗자루, 주변 유저에게 1번)
[내 모드]   Player.travel 을 빗자루 물리로 교체 (관성·늦은 회전·약한 브레이크·균형 게이지·낙마)
            빗자루 모델은 bbmodel 을 직접 읽어서 그림 (엔티티 없음, 1인칭에서도 손잡이 보임)
[남의 모드] 그 플레이어 움직임으로 기울기·방향을 각자 계산해서 그림 (추가 패킷 없음)
[플러그인]  탄 사람만 틱마다: 낙하 거리 0, 1초 이동 거리 상한, 연습 빗자루 시간, 숙련도 기록
```

### 조작

| 키 | 동작 |
|---|---|
| W | 가속 (아래를 보며 W = 급강하로 속도 붙음) |
| S | 약한 브레이크 (최고 속도에서 멈추는 데 3초 정도. 빠를 때 오래 누르면 균형 깎임) |
| 마우스 | 방향. 빗자루가 늦게 따라오고 빠를수록 더 늦음. 한 번에 홱 돌리면 균형이 크게 깎임 |
| A / D | 옆으로 미끄러지기(드리프트) |
| A·A / D·D (빠르게 두 번) | 배럴롤: 옆으로 구르며 회피 (0.5초 무적, 균형 깎임, 3초 대기) |
| 스페이스 / Shift | 오르기 / 내리기 (느리면 잘 안 오르고, 너무 느리면 가라앉음) |
| 달리기 키 | 부스트 1.5초 (회전 둔해짐, 6초 대기) |
| 땅에서 Shift | 빗자루에서 내리기 |
| 공중에서 Shift 두 번 | 뛰어내리기 (낙하 피해 있음) |
| 낙마 후 스페이스 | 떨어지는 빗자루 근처(3블록)에서 다시 잡기 (약 2초 안) |

- 균형 게이지(조준점 아래)가 0 이 되면 낙마: 몸은 날던 속도 그대로 튕겨 나가고 빗자루는 혼자 날아가다 떨어집니다.
- 균형을 깎는 것: 빠를 때 급회전·급제동, 벽 충돌(빠르면 바로 낙마), 급강하 후 땅에 처박기, 피격, 비·뇌우, 고도 190 이상 바람.
- 숙련도(누적 비행 시간, 기본 3시간에 100%)가 낮을수록 균형이 더 크게 깎입니다.
- 물·용암에 닿으면 빗자루가 젖어서 내립니다.

### 서버 명령어 `/빗자루` (별칭 `/broom`)

| 명령어 | 권한 | 설명 |
|---|---|---|
| `/빗자루` | 모두 | 내 빗자루·숙련도 |
| `/빗자루 목록` | 모두 | 10종 수치 |
| `/빗자루 장착 <빗자루>` | 모두 | 가진 빗자루 장착 |
| `/유저관리 <닉네임> 빗자루 지정 <빗자루>` | OP | 다른 사람의 장착 빗자루 변경 (보유 권한이 있어야 적용) |
| `/유저관리 <닉네임> 빗자루 해제` | OP | 장착 해제 (가진 것 중 가장 좋은 빗자루, 없으면 연습용 10초) |
| `/유저관리 <닉네임> 빗자루 주기\|회수 <빗자루\|전체>` | OP | 보유 권한 주기·빼기 |
| `/테스트 빗자루 타기 [닉네임]` | OP | 마법 없이 타기/내리기 (테스트) |
| `/유저관리 <닉네임> 빗자루 숙련도 <0~100>` | OP | 숙련도 설정 |
| `/서버관리 새로고침 빗자루` | OP | brooms.yml 다시 읽기 |

빗자루 수치는 플러그인 폴더의 `brooms.yml`. 기록은 `broom-players.yml`.

### 모델 추가·교체

모드 안 `assets/chacademy_motion/brooms/<ID>.bbmodel` (10종 내장). ItemsAdder 리소스팩에 같은 경로로 넣으면
모드를 다시 배포하지 않고 모델을 바꾸거나 새 ID 를 추가할 수 있습니다 (새 ID 는 `brooms.yml` 에도 추가).
bbmodel 은 Generic(free) 형식, 텍스처 내장, `p_pilot` 본(좌석)이 있어야 합니다.

### 탑승 모션

`broom_ride`(반복), `broom_boost`(반복), `broom_wobble`(반복, 균형 25% 이하), `broom_fall`(낙마).
선회·급강하·배럴롤 기울기는 모션 위에 실시간으로 더합니다(BroomPose). 타는 중에 시전 모션이 나오면 팔·상체만 시전 모션을 씁니다.

### 보유와 장비창 (0.3.0)

- 보유 = 권한 `chacademy.broom.own.<ID>` (LuckPerms 등으로 지급), `chacademy.broom.own.*` = 전부. OP 는 전부 보유.
- 가진 빗자루는 시간 제한 없이 비행. 장착이 없으면 가진 것 중 가장 좋은 빗자루, 하나도 없으면 연습용 10초.
- MagicCodex 장비창은 `kr.chacademy.motion.api.BroomApi`(리플렉션)로 보유 목록·능력치 게이지·장착·3D 모델 렌더를 쓴다.
