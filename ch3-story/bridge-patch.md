# MagicCodexBridge 변경 (학교만 쓰임) — %chacastory_newspells%

3-8 "새로 나온 마법 10종 이상 발견" 은 신규 150종(301~450) 중 배운 수로 센다.
학교 스토리 값 조건이 PlaceholderAPI `%chacastory_newspells%` 를 읽는다.

## 1. StorySignals.java — Expansion.onRequest 의 switch 에 한 줄 (운영 소스에 [기능] 표시로)

```java
                // [기능:3장 스토리 시작] 새로 나온 마법(신규 150종) 중 배운 수 — plugins/MagicCodexBridge/story-new-spells.txt (한 줄에 마법 키 하나)
                case "newspells" -> Integer.toString(p == null ? 0 : newSpells(plugin, p));
                // [기능:3장 스토리 끝]
```

같은 Expansion 클래스(또는 StorySignals) 안에:

```java
    // [기능:3장 스토리 시작]
    private static volatile java.util.Set<String> newSpellKeys;
    static int newSpells(MagicCodexBridge plugin, Player p) {
        java.util.Set<String> keys = newSpellKeys;
        if (keys == null) {
            java.nio.file.Path f = plugin.getDataFolder().toPath().resolve("story-new-spells.txt");
            try { keys = java.nio.file.Files.readAllLines(f).stream().map(String::strip).filter(s -> !s.isEmpty() && !s.startsWith("#")).collect(java.util.stream.Collectors.toUnmodifiableSet()); }
            catch (java.io.IOException e) { keys = java.util.Set.of(); }
            newSpellKeys = keys;
        }
        int n = 0;
        for (String k : keys) if (p.hasPermission("magic.learned." + k)) n++;
        return n;
    }
    // [기능:3장 스토리 끝]
```

(`p` 는 onRequest 안에서 이미 쓰는 온라인 Player. 파일은 서버 시작 뒤 처음 읽을 때 한 번만 읽음.)

## 2. 파일
- `new-spells.txt` (이 폴더) → 학교 `plugins/MagicCodexBridge/story-new-spells.txt`
- 학교 `plugins/ChacademyStory/config.yml` `dialogue-placeholders` 는 대사에 안 쓰므로 추가 필요 없음.

## 주의
- 신규 150종(branch claude/magic-150-20261031)이 운영에 먼저 들어가야 3-8 을 채울 수 있다. 안 들어간 채 3장을 열면 3-8 에서 전원 멈춤.
