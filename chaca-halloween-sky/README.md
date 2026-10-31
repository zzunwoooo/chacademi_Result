# 할로윈 하늘 (halloween-sky-20261031)

오너 요청 2026-10-31: 학교 서버에서 켜기/끄기로, 하늘이 한쪽 끝부터 "촤아악" 쓸려 할로윈 하늘이 되고 호박달이 뜨는 연출.
셰이더를 켠 사람도 보여야 하고, 셰이더 하늘처럼 빛 번짐·실사 느낌.

## 구성
| 폴더 | 무엇 | 어디로 |
|---|---|---|
| `mod/` | 새 클라 모드 `chaca-halloween-sky-0.1.0.jar` (id `chaca_sky`, 통째로 새 모드라 주입 없음) | 런처 `mods/` |
| `shader/halloween.diff` | Complementary Unbound r5.5.1 변경분. 모드 jar 에 들어가 셰이더 읽을 때 메모리에서만 끼움 (셰이더 파일 배포·교체 없음) | 없음 (모드에 포함) |
| `server/halloween-sky.sk` | `/할로윈하늘 켜기·끄기` + 켜진 동안 들어온 사람도 쓸리는 연출부터 | 학교 서버 `plugins/Skript/scripts/` → `/sk reload halloween-sky` |
| `art/pumpkin_moon.png` | 호박달 그림 (지금은 임시 그림. 오너가 줄 그림으로 교체) | 모드 `assets/chaca_sky/textures/` (셰이더도 이걸 씀) |

## 동작
- 신호 = 소리. 서버가 `/playsound chaca_sky:halloween_on|halloween_off|halloween_now` 를 보내면 모드가 가로채서(재생 안 함) 하늘을 바꾼다. 그래서 **서버 재시작·플러그인 없이** Skript 만으로 켜고 끈다.
- 켜기: 서쪽 끝(-x)에서 동쪽 끝으로 7초 동안 쓸림. 앞줄에 주황 빛 띠. 끄기: 거꾸로 돌아감. `now` = 즉시 켜짐 (지금은 안 씀. 접속자도 오너 요청으로 쓸리는 연출부터 봄).
- 다른 서버로 옮기거나 나가면 원래 하늘 (클라가 접속 때마다 초기화). 학교 Skript 만 신호를 보내니 학교에서만 보인다.
- 셰이더 끈 사람: 모드가 바닐라 하늘 위에 할로윈 반구·별·호박달·달무리를 덧그림 (가산 합성으로 빛 번짐 흉내). 세상 밝기는 저녁 정도로, 안개·구름은 보랏빛.
- 셰이더 켠 사람: 모드가 Iris 에 `chacaHalloween`(0..1 분위기), `chacaSweep`(쓸림 앞줄) 값을 넘기고, 모드가 끼운 Complementary 변경분이 그 값으로 하늘·호박달·조명을 바꾼다. 값이 0 이면 원본과 똑같이 동작.
- 쓸림 식 (모드·셰이더 공통): 방향 d 의 t=(d.x+1)/2, 할로윈 비율 = 1-smoothstep(f-0.06, f+0.06, t), 호박달 방향 normalize(0.75, 0.45, 0.35).

## 호박달 그림 바꾸기
256×256 투명 PNG 로 맞춰 `art/pumpkin_moon.png` 와 `mod/src/main/resources/assets/chaca_sky/textures/pumpkin_moon.png` 를 바꾸고 모드만 다시 빌드 (셰이더도 같은 그림을 씀).

## 확인한 것
- 모드: `gradle --offline build -x test` 성공 (믹스인 대상 이름은 refmap 으로 확인).
- 셰이더 변경분: 실제 원본 zip 에 끼워 11개 파일 모두 의도한 결과와 같음 (GLSL 은 검사 도구가 없어 눈으로만 검토).
- 게임 안 시험은 아직 안 함.
