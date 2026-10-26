## ArtifactCompanions (아티팩트 소환·분신 연출, 2026-10-26)

`claude:artifactcompanions/<system>` — 아티팩트 29개 작업 그룹 D. 텍스처 12장, 고유 RGBA 약 2.8 MiB (client_compat WARN 4.6 MiB 는 재질별 중복 계산).
client_compat check OK (fix 0 changes), validate OK.

| system | role | 누가 재생 | 용도 |
|---|---|---|---|
| fire_spirit | attached, loop | 클라 (MagicCodex `ArtifactFxClient`, playAttached ENTITY 앵커) | 화염 정령의 등불: 오른쪽 어깨 뒤(시전자 기준 +0.42, 1.78, -0.38) 작은 불 정령. 레이어 위치에 어깨 오프셋이 들어 있음 |
| spirit_bolt | static, loop | 클라 | 정령 불덩이. 클라가 매 틱 `move` 로 정령 → 대상까지 옮기고 `finish` (꼬리 = world 레이어) |
| spirit_hit | static | 클라 | 불덩이 명중 |
| mirror_echo | static | 클라 | 거울 쌍둥이 따라 쓰기 명중 (위치 = 대상 발밑, 레이어는 +1.0 m) |
| shadow_rise | static | 서버 (PortableVfxService.play) | 그림자 군주의 가면: 몹이 쓰러진 자리에서 그림자가 일어남 (바닥 룬 원 + 검은 안개 기둥) |
| shadow_aura | attached, loop | 서버 (PortableVfxService.follow, 10초) | 그림자 몸에 붙는 검은 안개 |
| shadow_fade | static | 서버 | 그림자가 흩어짐 |

배포: 클라이언트 PortableVFX 효과 폴더에 이 팩을 넣어야 보인다 (없으면 효과 없이 기능만 동작).
