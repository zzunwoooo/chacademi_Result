# TAB 플러그인에 한글 닉네임 넣기 (오픈 전 메모 #110)

## 넣는 방법
1. 학교·야생(·아레나) 서버 모두 `plugins/` 에 **PlaceholderAPI**, **TAB**(NEZNAMY)이 있는지 확인한다. MagicCodexBridge 는 원래 있다.
2. 서버를 한 번 켜서 `plugins/TAB/config.yml`, `groups.yml` 이 만들어지게 한다.
3. `config.yml` 에서 [config-snippet.yml](config-snippet.yml) 에 있는 세 부분만 같은 값으로 바꾼다.
   - `tablist-name-formatting`, `scoreboard-teams`, `placeholder-refresh-intervals`
   - `sorting-types` 의 `GROUPS:` 줄은 실제 LuckPerms 그룹 이름으로 바꾸거나 지운다.
4. `groups.yml` 의 `_DEFAULT_` 를 [groups.yml](groups.yml) 처럼 맞춘다.
5. 게임 안에서 `/tab reload` (OP). Tab(` 키) 목록에 한글 닉네임이 보이면 된다.
6. 확인: `/papi parse me %magiccodex_nickname%` → 내 한글 닉네임 (닉네임이 없는 계정이면 계정 이름).

## 플레이스홀더 (코드에서 확인한 것)
| 플레이스홀더 | 값 | 근거 |
|---|---|---|
| `%magiccodex_nickname%` | 저장된 한글 닉네임. **없으면 마인크래프트 계정 이름** | `NicknameBridge.placeholder()` — `stored()`가 비면 `p.getName()` |
| `%user_nickname%` | 위와 같음 (`user` 이름을 다른 플러그인이 이미 쓰고 있으면 등록되지 않음) | `NicknameBridge` 생성자 — 비어 있는 식별자만 등록 |
| `%magiccodex_account%` | 계정 이름 | `NicknameBridge.answer()` |
| `%chacademiatitle_full%` | 칭호 + 닉네임 (예: `별빛을 발견한 연구가 차카`) | `TitleBridge.placeholder()` |

- 닉네임을 정하지 않은 사람은 **계정 이름으로 떨어진다** (빈칸이 되지 않음). 코드 수정 필요 없음.
- `magiccodex` 식별자는 닉네임 확장이 갖고, 마나 값(`%magiccodex_mana_current%` 등)은 그쪽으로 넘겨받는다. 닉네임 확장이 식별자를 못 가지면 마나 확장이 등록하면서 닉네임도 대신 답한다(`ManaBridge` → `expansion.fallback(names()::placeholder)`). 어느 경우든 같은 값.
- 닉네임은 메모리 캐시(`ConcurrentHashMap`)에서 읽으므로 TAB이 비동기로 불러도 DB를 건드리지 않는다. 다른 서버에서 바꾼 닉네임은 45초 안에 반영된다.
- 칭호까지 Tab 목록에 넣고 싶으면 `customtabname: "%chacademiatitle_full%"` 로 바꾸면 된다 (오너 결정 — 기본은 닉네임만).

## 머리 위 이름표(nametag)는 TAB에 맡기지 않는다
코드 근거:
- MagicCodex 모드가 플레이어·NPC 머리 위 명찰을 직접 그린다: 서버 `NameplateBridge.push()`(1초마다 48칸 안 닉네임·기숙사·칭호 전송) → 클라이언트 `NameplateClient`.
- 모드는 Tab 목록에 있는 플레이어의 바닐라 이름표를 숨긴다(`NameplateClient.suppressVanilla` → `TamingNameMixin`이 `hasLabel`을 false로). 그래서 TAB이 팀 앞뒤 글자(`tagprefix`/`tagsuffix`)를 넣어도 **화면에 보이지 않는다**.
- 반대로 MagicCodex 명찰은 바닐라 팀의 이름표 규칙을 따른다(`NameplatePolicy.visible`: `NEVER`면 숨김, `HIDE_FOR_OTHER_TEAMS`/`HIDE_FOR_OWN_TEAM` 반영). TAB의 `invisible-nametags: true` 는 팀 규칙을 `NEVER` 로 만들어 **MagicCodex 명찰까지 사라지게 한다.**
- 저장소 안에 플레이어를 스코어보드 팀에 넣는 플러그인 코드는 없다(grep `getScoreboard`·`registerNewTeam` 0건). TAB이 팀을 독점해도 충돌할 다른 코드가 없다.

**권장**
- TAB 이름표 꾸미기는 쓰지 않는다: `groups.yml`·`users.yml` 에 `tagprefix`/`tagsuffix` 를 넣지 않는다.
- `scoreboard-teams.invisible-nametags` 는 반드시 `false`.
- TAB에서는 이름표 기능과 Tab 목록 정렬이 같은 `scoreboard-teams` 에 묶여 있다(끄면 정렬도 같이 꺼짐).
  - Tab 목록 정렬(운영자 먼저, 닉네임 순)이 필요하면 → `enabled: true` 로 두고 위처럼 꾸미기만 비운다 (기본 예시).
  - 정렬이 필요 없으면 → `enabled: false` 로 꺼도 된다. 이름표는 어차피 MagicCodex 가 그린다.
- 모드 없이 들어온 클라이언트(바닐라)는 TAB 팀 이름표가 보이지만, 꾸미기를 비워 두면 계정 이름만 나온다(바닐라와 같음).

## 오너가 정할 것
- Tab 목록에 닉네임만? 칭호까지(`%chacademiatitle_full%`)?
- 운영자를 Tab 목록에서 구분할 표시(앞 글자·색)와 LuckPerms 그룹 이름.
- 정렬을 쓸지(쓰면 `scoreboard-teams.enabled: true` 유지).

참고: TAB 위키 [Tablist name formatting](https://github.com/NEZNAMY/TAB/wiki/Feature-guide:-Tablist-name-formatting), [Nametags](https://github.com/NEZNAMY/TAB/wiki/Feature-guide:-Nametags), [Sorting](https://github.com/NEZNAMY/TAB/wiki/Feature-guide:-Sorting-players-in-tablist). 키 이름은 TAB 버전에 따라 다를 수 있으니, 처음 생성된 config.yml 의 키 이름을 기준으로 맞춘다.
