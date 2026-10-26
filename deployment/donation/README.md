# 별풍선(SOOP) 후원 플러그인 + 클라이언트 모드 (2026-10-26, FINAL_PATCHES §43)

외부에서 받은 플러그인·모드 한 쌍입니다. 방송인 클라이언트(모드)가 SOOP 후원을 받으면 서버 플러그인이 그 방송인 플레이어의
`DonationEvent` 를 부르고, 우리 MagicCodexBridge 가 그 이벤트를 받아 보상을 줍니다 (`plugins/MagicCodexBridge/donation.yml`).

| 파일 | 무엇 | 어디에 | SHA-256 |
|---|---|---|---|
| `donation-bukkit-2.0.2-allcounts.1.jar` | 서버 플러그인 "donation" (main `bot.colla.bukkit.donation.DonationPlugin`, softdepend Skript) | **학교·야생·아레나** 서버 모두 `plugins/` | `7955a19c56d21edc080b8e886a8b68de3de7461ab04585971c73c8e14bb14b31` |
| `donation-fabric-1.21.4-2.0.2-allcounts.1.jar` | 클라이언트 모드 (Fabric 1.21.4) | 런처 `mods/` (방송인이 이 모드로 후원을 받음) | `efbf32f50e87dd8ca553d359767d8c939c1b58e79213f3705c7383de27ab7670` |

## 설정 (donation 플러그인 쪽)
- `plugins/donation/config.yml` 의 `acceptAllDonations: true` (기본값). 모든 개수를 받아야 "표에 없는 개수 → 마일리지" 가 동작합니다.
  (`acceptedDonations` 목록은 acceptAllDonations 가 true 면 쓰지 않음)
- CHZZK(치지직)는 쓰지 않습니다.

## 우리 쪽 동작 (MagicCodexBridge)
- MagicCodexBridge 가 `donation` 을 softdepend 로 두고, 그 플러그인이 켜져 있으면 후원 이벤트를 리플렉션으로 연결합니다
  (서버 시작 로그: `donation 플러그인 … 후원 이벤트 연결`, `/서버관리 상태` 의 "별풍선 후원" 줄).
- **처리한 뒤 이벤트를 취소합니다.** donation 플러그인은 이벤트가 취소되지 않으면 콘솔 명령
  `api <플레이어 계정이름> <종류 번호> <금액> <후원자 닉네임>` 을 실행하는데, 예전 Skript 의 `api` 명령이 남아 있으면 보상이 두 번 나가기 때문입니다.
  → 예전 Skript `api` 스크립트는 지워도 되고, 남아 있어도 실행되지 않습니다.
- `donation.yml` 의 `enabled: false` 로 끄면 취소하지 않으므로 예전처럼 `api` 콘솔 명령이 실행됩니다.
- 보상 표: 100 = 마법상자 1 · 1000 = 마법상자 11 · 500 = 고급마법상자 1 · 5000 = 고급마법상자 11 · 10000 = 즉사 + 모든 서버 공지 + 접속자 전원 고급마법상자 1 ·
  그 밖의 개수 = 마일리지 += 개수. 상자는 모두 우편함으로.
- 테스트: `/후원관리 <닉네임> <개수>` (OP, 이 서버 접속자만, 실제 후원과 똑같이 처리).
