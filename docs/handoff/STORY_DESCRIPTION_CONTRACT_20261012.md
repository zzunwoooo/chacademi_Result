# Story journal descriptions and editor contract — 2026-10-12

The user's six existing personal dialogue files were read, then one spoiler-light sentence was added as a top-level `description` immediately after `title`. This corrects the earlier handoff that treated the missing descriptions as a final state. Private dialogue bodies, commands, images and progress are not published here.

| ID | Existing title (unchanged) | Added description |
| --- | --- | --- |
| ch1-1 | 1-1. 깨어난 직후 | 낯선 곳에서 눈을 뜨고, 흐릿한 기억 속에서 자신이 누구인지 되짚어 보세요. |
| ch1-2 | 닉네임 설정 이후 | 엘레나 교수와 첫인사를 나누고, 차카데미아 입학을 위한 첫걸음을 내디뎌 보세요. |
| ch1-3 | 1-3. 구덩이 테스트 | 엘레나 교수의 안내에 따라 마나를 느끼고, 구덩이를 벗어나기 위한 상승기류의 원리를 배워 보세요. |
| ch1-4 | 1-4. 첫 마법 준비 | 배운 마법을 등록하는 방법을 확인하고, 첫 시전을 차근차근 준비해 보세요. |
| ch1-5 | 1-5. 구덩이 탈출 | 첫 시험을 마친 뒤 엘레나 교수와 이야기를 나누고, 학교로 향할 다음 길을 안내받으세요. |
| ch1-6 | 1-6. 선착장 | 선착장에서 선장을 만나 궁금한 점을 물으며, 차카데미아로 떠날 준비를 하세요. |

## Contract for the Claude dialogue editor

- Source: `config/chaca_dialogue/<id>/dialogue.yml`, in the personal Minecraft instance. `description` is a root-level YAML string beside `id`, `title`, `start` and `scenes`; it is not a scene line, objective, completion condition or server command.
- `DialogueParser` reads `description` into `Dialogue.description()`. Missing values default to the empty string for backward compatibility. `format: 1` does not change.
- Add a short-description editor field and preserve this value on import, edit and export. Until that editor change is made, do not drop or replace an existing `description` when round-tripping a file. This task documents the contract; it does not claim that the HTML editor was modified.
- Prefer one spoiler-light sentence of plain text. Use a properly quoted YAML string; escape quotes/newlines correctly. The journal transport currently truncates descriptions to 2,000 Java UTF-16 code units, so keep editor content within that bound rather than relying on truncation. Existing title bound is 140 code units at the journal boundary.
- Display through `StoryJournal` -> `ExternalJournal` -> `JournalClient`. Do not create a second quest/progress/reward store. Descriptions have no gameplay effects and must not change done/event/gain/affinity receipts.

## Applied files and verification

Personal instance root: `C:/Users/matil/AppData/Roaming/.zzunwoo/instances/launcher_v2-1.21.4`.

Only these six files changed:

```
config/chaca_dialogue/ch1-1/dialogue.yml
config/chaca_dialogue/ch1-2/dialogue.yml
config/chaca_dialogue/ch1-3/dialogue.yml
config/chaca_dialogue/ch1-4/dialogue.yml
config/chaca_dialogue/ch1-5/dialogue.yml
config/chaca_dialogue/ch1-6/dialogue.yml
```

Original bytes were backed up under local task-5 `story-description-backup-20261012-130943`; the private manifest contains before/after SHA256 values. Inserting only the description line preserves every other original byte, including line endings and any BOM. All36 adjacent files were hashed before/after and remained unchanged. Private originals/backups are not committed.

`StoryJournal.content` compares dialogue.yml modification time and reloads changed content. Opening the journal or its active/completed three-second refresh republishes current local descriptions; the background publication interval is15 seconds. No server restart, mod replacement, reward command or global Story reload is required for this content-only edit. If the game is closed, the next game session reads the updated file normally. Actual on-screen acceptance remains a user/game check; a file write is not a client rendering acknowledgement.
