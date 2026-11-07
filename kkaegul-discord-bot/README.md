# 깨굴 디스코드 보이스 봇

포커·섯다 미니게임에서 테이블이 정해지면, **같은 테이블 사람끼리 테이블별 음성 채널로 자동 이동**시키고,
테이블이 끝나면 **원래 있던 채널로 돌려보낸 뒤 임시 채널을 지웁니다.**

## 1. 봇 만들기 (한 번)
1. https://discord.com/developers/applications → New Application → Bot → **Reset Token** 으로 토큰 복사
2. OAuth2 → URL Generator: scopes `bot`, `applications.commands` / 권한 **Manage Channels(채널 관리)**, **Move Members(멤버 이동)**, **Connect(연결)**, **View Channels(채널 보기)**
3. 만든 주소로 디스코드 서버에 초대
4. 디스코드 설정 → 고급 → 개발자 모드 켜기 → 서버 이름 우클릭 "ID 복사"(guildId), 카테고리 우클릭 "ID 복사"(categoryId)

## 2. 실행 (마크 서버와 같은 PC)
1. Node.js 18 이상 설치
2. 이 폴더에서 `config.example.json` 을 복사해 `config.json` 으로 만들고 값 채우기 (token, guildId, categoryId, secret)
3. `npm install` 한 번 → `npm start` (또는 `start-bot.bat` 더블클릭)

## 3. 마크 서버 설정
`plugins/KkaegulCore/config.yml`
```yaml
discord:
  enabled: true
  bot-url: "http://127.0.0.1:8787"
  secret: "config.json 의 secret 과 같은 값"
```
`/깨굴 리로드`

## 4. 유저 연동 (한 번)
게임에서 `/디코연동` → 6자리 코드 → 디스코드에서 `/연동 123456`
(`/연동해제` 로 풀기). 연동된 유저가 **음성 채널에 들어가 있을 때만** 옮길 수 있어요 (디스코드 규칙).

- 봇 이름은 `config.json` 의 `botName`(기본 "운빨깨굴랜드"), 프로필 사진은 이 폴더의 `avatar.png`(런처 아이콘)로 켤 때 자동 적용돼요.
- 토큰·secret 은 남에게 보여 주지 마세요. `links.json` 에 연동 목록이 저장됩니다.
