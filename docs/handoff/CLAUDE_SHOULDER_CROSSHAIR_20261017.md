# Claude 인계: 어깨 시점에서 조준점 기준으로 블럭·몹 선택 (2026-10-17)

- **브랜치:** `claude/shoulder-crosshair-20261017`
- **기준 커밋:** `d931b14` (`claude/kyudichi-20261017`, 당시 최신)
- **범위:** MagicCodex 클라이언트만. 서버와 프로토콜은 변경 없음.
- **빌드와 테스트:** 미빌드, 실게임 미확인. 새 파일은 타입 검사 오류 0건이다.
- **버전:** `...-alchemy.1-shouldercross.1`

## 문제

F5 어깨 시점에서 블럭을 부수거나 때리면, 조준점이 아니라 캐릭터 정면에 있는 대상이 선택된다.

원인은 바닐라 `GameRenderer.findCrosshairTarget`이 대상을 캐릭터 눈에서 쏜 레이로 정하기 때문이다. 어깨 카메라는 머리 옆에 있어서 조준점 레이와 평행하게 어긋나 있다.

## 수정

새 파일:
- `client/ShoulderCrosshair.java`
- `mixin/ShoulderCrosshairMixin.java`: `findCrosshairTarget` HEAD에서 대체한다. `magiccodex.mixins.json`에 등록했다.

동작:
1. 어깨 시점일 때만 동작한다. 조건은 `ShoulderCamera.active()`, 3인칭, 스펙테이터 아님이다.
2. 카메라 레이를 쏜다. 시작점은 머리 옆을 지나는 지점이라 캐릭터 뒤 블럭은 잡히지 않는다.
3. 조준점에 걸린 블럭이나 엔티티를 고른다.
4. 고른 대상을 서버와 같은 기준(눈 위치)으로 다시 검사한다.
   - **리치:** 바닐라 `ensureTargetInRange`와 같고, 엔티티는 `canInteractWithEntity`를 쓴다. 리치는 늘어나지 않는다.
   - **가림:** 눈에서 보면 다른 블럭이 가로막는 경우에는 그 가리는 블럭을 선택한다. 서버 판정과 일치시키기 위해서다.
5. 1인칭과 F5 앞 시점은 바닐라 그대로다.

블럭 부수기, 설치, 공격, 우클릭 상호작용, 블럭 테두리가 모두 이 결과를 따른다.

## 주의

이 브랜치(kyudichi, party-ui 포함)에서 `PartyScreen.java` 153·217행이 컴파일되지 않는다. 오류는 `int`/`Object` → `Piece`이며, 이번 작업과 무관한 기존 오류다. 빌드 전에 확인이 필요하다.
