# 할로윈 하늘: Complementary 셰이더 변경분 (halloween-sky-20261031)

셰이더 파일은 **고쳐서 배포하지 않아요.** 사용자가 이미 가진 `ComplementaryUnbound_r5.5.1.zip`은 그대로 두고, 할로윈 하늘 모드가 Iris가 셰이더를 읽는 순간 메모리에서만 `halloween.diff`를 끼워 넣어요(`ShaderPatcher`, `IrisShaderSourceMixin`, `IrisShaderPropertiesMixin`). 그래서 런처의 셰이더 배포는 바꿀 게 없어요.

- `halloween.diff`: 유일한 원본. 모드 빌드 때 jar 안 `chaca_sky_shader/halloween.diff`로 들어가요. 고친 곳은 모두 `[기능:halloween-sky-20261031 시작/끝]`으로 표시돼 있어요.
- 팩 경로에 `ComplementaryUnbound_r5.5.1`이 들어 있을 때만 적용돼요. 파일 내용이 예상과 다르면 그 파일은 원본 그대로 두고 로그를 남겨요.
- 호박달 그림은 셰이더도 모드 리소스 `chaca_sky:textures/pumpkin_moon.png`를 써요(`customTexture.chacaPumpkin`). 그림은 한 곳만 바꾸면 돼요.
- 원본 zip에 적용해 봤어요. 11개 파일 모두 기대한 결과와 같았어요(줄 끝 개행 하나만 다름).

## 어떻게 보이나
- 모드가 Iris 유니폼 `chacaHalloween`(전체 정도 0~1)과 `chacaSweep`(쓸리는 경계)을 넘겨요. 두 값이 0이면 원본과 똑같아요.
- 경계는 서쪽 지평선(0)에서 동쪽 지평선(1)으로 지나가요. 경계에는 주황 빛 띠가 생기고 블룸이 걸려요.
- 바뀐 쪽은 이렇게 보여요.
  - 하늘: 남보라 → 자주 → 주황 노을, 별
  - 호박달: 셰이더 블룸이 걸리게 밝게
  - 구름: 보라 몸통에 주황 아랫면
  - 대기 안개와 물에 비친 하늘도 같이 바뀌어요.
  - 해와 달은 숨겨요.
- 장면 조명은 어스름한 보라·주황빛이 돼요.

## 고친 파일
- 새 파일: `lib/atmospherics/chacaHalloween.glsl`
- 수정: `uniforms.glsl`, `sky.glsl`, `gbuffers_skybasic.glsl`, `gbuffers_skytextured.glsl`, `unboundClouds.glsl`, `reimaginedClouds.glsl`, `mainFog.glsl`, `reflections.glsl`, `lightAndAmbientColors.glsl`, `shaders.properties`

## 다시 만들기
1. 원본 zip을 풀어요.
2. 수정본 폴더에서 고쳐요.
3. 다음 명령으로 diff를 새로 만들어요.
   ```
   diff -ruN -x "*.png" -x "*.mcmeta" 원본/shaders 수정본/shaders > halloween.diff
   ```
   diff의 `+++` 줄 경로는 `아무폴더/shaders/...` 꼴이어야 해요.
4. 모드를 다시 빌드해요.

## 확인하지 못한 것
- GLSL 검사 도구가 없어서 셰이더는 눈으로만 검토했어요.
- 게임 안 시험은 아직 안 했어요.
