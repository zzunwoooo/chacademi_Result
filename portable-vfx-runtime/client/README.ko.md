# Claude 클라이언트 런타임

Minecraft 1.21.4 / Fabric용 Claude vfx.json 렌더러입니다. 일반 마법 선택은 서버(MagicCodex)가 담당합니다. 클라이언트 전용 점검·미리보기 명령은 `/이펙트도구` 아래에만 있습니다.

## 점검 명령

- `/이펙트도구 상태`: 렌더러·활성 효과·리소스 사전 준비 상태
- `/이펙트도구 목록`: 등록된 효과 ID
- `/이펙트도구 설정오류`: 설정 로딩 오류
- `/이펙트도구 활성`, `정지 <인스턴스>`, `전부정지`: 로컬 효과 관리
- `/이펙트도구 새로고침`: F3+T와 같은 리소스 재로딩
- `/이펙트도구 재생 <효과>`, `여기재생 <효과>`, `좌표재생 <효과> <x> <y> <z>`: 로컬 배치
- `/이펙트도구 증명재생 <효과>`: seed 0으로 고정한 비교용 미리보기
- `/이펙트도구 따라가기`, `발사`, `지면발사`, `폭발`, `충돌`, `마무리`: 부착·이동·단계 전환 점검

이 명령은 서버 권한이나 게임플레이를 부여하지 않습니다. 이전 `/pvfxclient`, Unity/Effekseer 불덩이 테스트는 제거했습니다. Claude 효과의 HDR/bloom은 원본 vfx.json의 설정을 기반으로 사용합니다.

## 전체 마법 bloom 강도

`config/portablevfx/bloom.properties`의 `strength=0.65`가 모든 Claude 효과의 원본 bloom intensity에 곱해집니다. 기본값은 기존 대비 35% 낮은 bloom 기여도입니다. 중심 재질·발광 입력, 원본 색상·threshold·scatter는 바꾸지 않습니다. 화면 밝기는 톤매핑 때문에 정확히 35% 감소하는 것은 아닙니다.

- `0`: 번짐 bloom만 끄기
- `0.65`: 기본 완화값
- `1`: 원본 bloom 강도 복원
- 허용 범위: 0~1, 파일 수정 후 Minecraft 재시작

파일이 없으면 최초 실행 시 생성됩니다. Complementary/Iris의 월드 bloom 및 다른 모드 효과에는 적용하지 않습니다.

## 공개 API

`dev.portablevfx.client.PortableVfxClient.api()`로 얻습니다. 모든 호출은 Minecraft 클라이언트 스레드에서 수행합니다. 다른 스레드에서는 `MinecraftClient.getInstance().execute(...)`를 사용합니다.

- `play(effectId, position, scale, durationTicks)`: 수락되면 Optional UUID. durationTicks=0은 원본 기본값을 사용합니다.
- `play(PlayEffect)`: 프로토콜과 같은 검증을 거치는 요청
- `playAttached(...)`: 이미 추적 중인 엔티티의 명시적 앵커에 부착
- `move`, `basis`, `width`, `orient`, `impact`, `finish`: 위치·방향·폭·단계 수명 관리
- `stop(UUID)`, `clear()`, `list()`, `activeInstances()`, `status()`

Claude의 uniform scale은 1입니다. effectWidth는 별도의 1회성 폭 입력입니다. tint/opacity 임의 변경은 지원하지 않으며, 원본 효과의 색과 알파를 유지합니다. 요청 수락은 GPU 출력 성공을 보장하는 ACK가 아닙니다.

월드 변경과 clear는 인스턴스만 정리하고 준비된 자산을 재사용합니다. 리소스 reload와 종료에서 CPU/GPU 자산을 해제합니다. retained world depth, GL 상태 복구, GL 3.3 컨텍스트 확보를 유지합니다.
