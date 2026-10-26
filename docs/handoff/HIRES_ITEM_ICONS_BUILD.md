# 작업 지시: 고해상도 아이템 아이콘 빌드·검증 (magic-codex-fabric)

> **이 문서는 1차 빌드 지시 기록이다.** 현재 렌더 방식(3차: 32px 초과 아이콘을 부드럽게 그림, `OutlineMipmaps` 삭제)은 `docs/ITEM_ICONS_HIRES.md`를 따른다.

## 배경

차카데미 magiccodex Fabric UI 모드(MC 1.21.4, Java 21, yarn 1.21.4+build.8)에 기능을 추가하는 패치다. 기능 내용: 32px를 넘는 평면 아이템 텍스처를 GUI에서 "외곽선 보존 밉맵"으로 그린다.

이 변경은 Gradle 컴파일을 한 번도 거치지 않았다. 작성 환경에서 Maven/Fabric 저장소 접근이 막혀 있었기 때문이다. 다음 두 가지만 확인된 상태다.
- 렌더 방식은 WebGL2에서 같은 처리로 확인했다.
- 마인크래프트 API 이름은 yarn 1.21.4 매핑 파일과 대조했다.

**너의 일은 호스팅에서 빌드·테스트를 통과시키고 결과를 보고하는 것이다.** 기능 설계나 알고리즘은 바꾸지 않는다.

## 반드시 지킬 것 (저장소 `AGENTS.md` 원칙)

- 모든 작업은 호스팅(`ssh chacademi-host`, 계정 `chacademi-codex`)의 `C:\Chacademi\build-workspace`에서 한다. 노트북에서 Gradle을 돌리지 않는다.
- 운영 배포, `mods` 폴더 교체, 서버 재시작, DB 작업은 하지 않는다. 빌드 산출물만 만든다.
- 컴파일 수정 커밋은 브랜치 `feature/hires-item-icons`에만 한다. push·병합·다른 브랜치 반영은 사용자 확인 후에 한다.
- 인증 정보·비밀 설정을 출력하지 않는다.

## 1~2. 코드 가져오기

패치는 노트북에 있다: `C:\Users\matil\Desktop\Claude outputs\hires-item-icons\hires-item-icons.patch` (커밋 1개, `git am` 형식). 이 안내문은 같은 폴더에 있고, 적용 후에는 저장소 `docs/handoff/HIRES_ITEM_ICONS_BUILD.md`에도 있다.

1. 노트북에서 호스팅으로 이 파일 하나만 복사한다(약 40KB, 최초 최소 전송 허용 범위):
   ```powershell
   scp "C:\Users\matil\Desktop\Claude outputs\hires-item-icons\hires-item-icons.patch" chacademi-host:C:/Chacademi/build-workspace/hires-item-icons.patch
   ```
2. 호스팅에서:
   ```powershell
   cd C:\Chacademi\build-workspace
   git status            # 작업 중인 변경이 있으면 멈추고 사용자에게 보고
   (Get-FileHash .\hires-item-icons.patch -Algorithm SHA256).Hash
   git fetch origin codex/shop-ui-refine-20261005
   git switch -c feature/hires-item-icons ae37350
   git am --keep-cr .\hires-item-icons.patch
   git log --oneline -2  # 맨 위: Add high-res GUI item icons..., 그 아래: ae37350
   ```
   `ae37350`이 없거나 다른 이력 위에서 작업 중이면 최신 `codex/shop-ui-refine-20261005`에서 브랜치를 만들어 `git am`하고 그 사실을 보고한다. `git am`이 충돌하면 `git am --abort` 후 멈추고 보고한다. 해시가 다르면 전송 중 줄바꿈이 바뀐 것이니 바이너리로 다시 복사한다.

변경 파일:
- 추가: `magic-codex-fabric/src/main/java/school/magiccodex/client/OutlineMipmaps.java` (순수 Java, 외곽선 보존 밉맵)
- 추가: `.../client/ItemIconTextures.java` (아이템 모델 해석, 비동기 디코드, GPU 업로드, GUI 그리기)
- 추가: `.../mixin/ItemIconMixin.java` (`DrawContext.drawItem` 7인자 private 오버로드 HEAD, `require = 0`)
- 추가: `src/main/resources/assets/magiccodex/shaders/core/item_icon.fsh`, `item_icon.json`
- 추가: `src/test/java/school/magiccodex/client/OutlineMipmapsTest.java`
- 수정: `UiResources.java` (`initialize()`에서 `ItemIconTextures.initialize()`, `reset()`에서 `ItemIconTextures.reset()`)
- 수정: `src/main/resources/magiccodex.mixins.json` (`"ItemIconMixin"` 추가)
- 문서: `docs/ITEM_ICONS_HIRES.md`, `README.md` 하단 한 단락

## 3. 빌드·테스트

```powershell
.\magic-codex-fabric\gradlew.bat -p magic-codex-fabric test remapJar
```

- 기대: 새 테스트 없음(OutlineMipmaps 삭제). 기존 테스트에 새 실패가 없어야 함.
- 기존 알려진 실패 1건(외부 spells fixture 부재)은 이 패치와 무관하다. 그 외 새 실패가 있으면 원인을 적는다.
- 산출물: `magic-codex-fabric/build/libs/magic-codex-ui-*.jar`. 파일명과 SHA256을 보고한다.

## 4. 컴파일 오류가 나면 — 허용되는 수정 범위

**동작을 바꾸지 않는 최소 수정만** 한다. 예상 지점과 고치는 방법:

| 증상 | 수정 |
| --- | --- |
| `m.translate(x, y, 150)` 모호한 호출 | `m.translate((float) x, (float) y, 150f)` |
| `JsonArray.isEmpty()` 없음 | `.size() == 0` |
| `RenderLayer` 생성자 접근 불가 | 기존 `HudTextureCache.HudLayer`와 같은 방식이므로 그쪽 선언을 그대로 따른다 |
| `ctx.drawTexture(...)` 오버로드 불일치 | `HudTextureCache.render()`의 호출 형태(13인자, `Function<Identifier,RenderLayer>`)와 똑같이 맞춘다 |
| `ResourceManager.getResource` 반환형 | `Optional<Resource>`가 아니면 `getResourceOrThrow` + try/catch로 바꾸되 "없으면 바닐라" 의미는 유지 |
| `GL14.GL_TEXTURE_LOD_BIAS` 관련 | `org.lwjgl.opengl.GL14` import 확인. 상수는 `0x8501` |

바꾸면 안 되는 것:
- `OutlineMipmaps`의 계산 방식: 덮임 40%, 내부 색만 평균, 외곽선 1텍셀 재도색
- 필터 설정: 축소 `LINEAR_MIPMAP_LINEAR` / 확대 `NEAREST` / LOD bias `-0.5`
- 적용 조건: 단일 `layer0`, `item/generated` 계열, tints·애니메이션·glint 제외
- 믹스인의 `require = 0`

설계를 바꿔야만 해결되는 문제면 멈추고 보고한다.

## 5. 실게임 확인 (사용자 요청이 있을 때만)

노트북에서 게임 실행·런처 교체는 사용자가 요청할 때만 한다. 요청을 받았다면:

- 128x128 텍스처를 쓰는 평면 아이템(예: `mana_core`)을 인벤토리·핫바에 두고 GUI 배율 2/3/4에서 본다. 외곽선이 끊기지 않고 그라데이션이 부드러운지 확인한다.
- 슬롯 하이라이트, 개수 숫자, 내구도 바가 아이콘 위에 정상으로 나오는지 본다.
- 인챈트된 아이템, 16px 바닐라 아이템이 예전과 같은지 본다.
- 손에 든 아이템과 바닥 아이템은 바닐라 그대로인지 본다.
- 로그에 `High-res item icons active (...)`가 한 번 찍히는지 본다. 안 찍히면 믹스인 주입 실패(require=0이라 조용히 바닐라로 남음)이니 `drawItem` 서명을 확인한다.

## 6. 보고 형식

1. 가져온 커밋과 기준 커밋
2. 빌드·테스트 결과: 통과/실패 수, 새 실패 목록
3. 컴파일 수정을 했다면 파일·줄·이유 (diff 요약)
4. 산출 JAR 경로·SHA256
5. 수정 커밋을 만들었다면 그 해시 (push 안 함)
6. 실게임 확인 여부와 결과, 미확인 항목
