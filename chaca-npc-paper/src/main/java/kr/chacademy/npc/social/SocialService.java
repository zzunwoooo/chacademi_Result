package kr.chacademy.npc.social;

import kr.chacademy.npc.ChacaNpcPlugin;
import kr.chacademy.npc.ai.OpenAiClient;
import kr.chacademy.npc.budget.BudgetService;
import kr.chacademy.npc.config.Settings;
import kr.chacademy.npc.core.BudgetMath;
import kr.chacademy.npc.core.CharacterSheet;
import kr.chacademy.npc.core.Json;
import kr.chacademy.npc.core.ReplyParser;
import kr.chacademy.npc.core.TextFilter;
import kr.chacademy.npc.core.TextSanitizer;
import kr.chacademy.npc.data.Storage;
import net.citizensnpcs.api.npc.NPC;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * 사건 기록 → 관계도를 따라 소문 퍼뜨리기, 그리고 NPC끼리 짧은 잡담 연출.
 */
public final class SocialService {

    private final ChacaNpcPlugin plugin;
    private final Random random = new Random();
    private final Map<String, Long> lastAmbientByPlace = new HashMap<>();

    public SocialService(ChacaNpcPlugin plugin) {
        this.plugin = plugin;
    }

    /** 사건 기록. Bridge도 ChacaNpcApi.recordEvent로 선물·하트 이벤트 등을 넣을 수 있다. */
    public void recordEvent(UUID player, String playerName, String npcId, String type, String text) {
        if (player == null || npcId == null || npcId.isBlank() || type == null || type.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        String pid = player.toString();
        String kind = type.trim();
        if (kind.length() > 32) {
            kind = kind.substring(0, 32);
        }
        // 저장 전에 정리: 제어문자·색 코드·URL 제거, 길이 제한. 자유 글이 섞인 사건(약속·별명·모르는 종류)은 금지어 검사도 한다.
        // 걸리거나 비면 원문 대신 서버가 만든 문장(이름·원문 없음)을 저장한다 — 사건 자체(종류)는 남긴다.
        CharacterSheet c = plugin.characters().get(npcId);
        String canned = TextSanitizer.publicRumor(kind, c == null ? null : c.name());
        String clean = TextSanitizer.clean(text, EVENT_TEXT_MAX);
        if (clean == null || (!TextSanitizer.isStructuredType(kind) && !plugin.content().filter().isPublicSafe(clean))) {
            clean = canned;
        }
        String name = TextSanitizer.clean(playerName, 32);
        String finalKind = kind;
        String finalText = clean;
        plugin.database().run(() -> plugin.storage().addEvent(pid, name == null ? "학생" : name, npcId, finalKind, finalText, now));
    }

    /** 사건 글 최대 길이(코드포인트). */
    private static final int EVENT_TEXT_MAX = 200;

    // ------------------------------------------------------------ 소문 퍼뜨리기 (10분마다)

    public void propagate() {
        Settings st = plugin.settings();
        Map<String, CharacterSheet> chars = new HashMap<>();
        for (CharacterSheet c : plugin.characters().all()) {
            chars.put(c.id(), c);
        }
        plugin.database().run(() -> {
            Storage storage = plugin.storage();
            for (Storage.EventRow e : storage.unpropagatedEvents(200)) {
                CharacterSheet source = chars.get(e.npc());
                Set<String> told = new HashSet<>();
                if (source != null && source.relations() != null) {
                    for (Map.Entry<String, CharacterSheet.Relation> rel : source.relations().entrySet()) {
                        String other = rel.getKey();
                        if (chars.containsKey(other) && !other.equals(e.npc()) && random.nextDouble() < rel.getValue().gossip()) {
                            told.add(other);
                        }
                    }
                }
                // 연애 사건은 공략 가능한 다른 NPC들에게도 전해진다 (질투 여부는 대화 때 호감도로 판단)
                if ("heart".equals(e.type()) || "ending".equals(e.type())) {
                    for (CharacterSheet other : chars.values()) {
                        if (!other.id().equals(e.npc()) && other.romanceable()) {
                            told.add(other.id());
                        }
                    }
                }
                Map<String, Long> heardAt = new LinkedHashMap<>();
                for (String npc : told) {
                    long delayH = st.rumorDelayMinHours
                            + (st.rumorDelayMaxHours > st.rumorDelayMinHours
                            ? random.nextInt(st.rumorDelayMaxHours - st.rumorDelayMinHours + 1) : 0);
                    heardAt.put(npc, e.createdAt() + delayH * 3_600_000L + random.nextInt(3_600_000));
                }
                try {
                    // 소문 줄 추가 + "퍼뜨림" 표시를 한 트랜잭션으로 (중간에 실패해도 다음 번에 소문이 두 번 생기지 않는다)
                    storage.propagateEvent(e, heardAt);
                } catch (RuntimeException ex) {
                    // 사건 하나가 실패해도 나머지는 계속 퍼뜨린다 (이 사건은 다음 주기에 다시 시도)
                    plugin.getLogger().warning("[ChacaNPC] 소문 퍼뜨리기 실패 (사건 #" + e.id() + "): " + ex.getMessage());
                }
            }
        });
    }

    /** MagicCodex 고정 대화의 하트 이벤트·엔딩·호칭 설정을 사건으로 기록한다. */
    public void onMagicCodexEvent(Map<String, String> ev) {
        CharacterSheet c = plugin.characters().get(ev.get("npc"));
        String player = ev.get("player");
        String name = ev.getOrDefault("playerName", "학생");
        if (c == null || player == null) {
            return;
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(player);
        } catch (IllegalArgumentException ex) {
            return;
        }
        String type = ev.getOrDefault("type", "");
        String value = ev.getOrDefault("value", "");
        String text = switch (type) {
            case "heart" -> name + "이(가) " + c.name() + "와(과) 특별한 시간을 보냄";
            case "ending" -> name + "이(가) " + c.name() + "의 특별한 사람이 됨";
            case "nickname" -> c.name() + "는 " + name + "을(를) '" + value + "'(이)라고 부른대";
            default -> null;
        };
        if (text != null) {
            recordEvent(uuid, name, c.id(), type, text);
        }
    }

    // ------------------------------------------------------------ NPC끼리 잡담 (주기적으로)

    public void ambientTick() {
        Settings st = plugin.settings();
        if (!st.ambientEnabled || !plugin.budget().isAiEnabled()
                || plugin.budget().status() != BudgetMath.Status.NORMAL) {
            return;
        }
        long now = System.currentTimeMillis();
        // 장소별로 지금 그 장소에 있는 NPC 모으기
        Map<String, List<CharacterSheet>> byPlace = new HashMap<>();
        for (String id : plugin.npcs().linkedCharacters()) {
            CharacterSheet c = plugin.characters().get(id);
            if (c == null || !c.hasSchedule()) {
                continue;
            }
            int slot = plugin.npcs().currentSlot(c);
            if (slot < 0) {
                continue;
            }
            Integer nid = plugin.npcs().npcIdFor(id);
            if (nid == null || plugin.dialogue().isTalkingWith(nid)) {
                continue;
            }
            byPlace.computeIfAbsent(c.schedule().get(slot).place(), k -> new ArrayList<>()).add(c);
        }
        for (Map.Entry<String, List<CharacterSheet>> e : byPlace.entrySet()) {
            Long last = lastAmbientByPlace.get(e.getKey());
            if (last != null && now - last < st.ambientIntervalMinutes * 60_000L) {
                continue;
            }
            List<CharacterSheet[]> pairs = new ArrayList<>();
            List<CharacterSheet> list = e.getValue();
            for (int i = 0; i < list.size(); i++) {
                for (int j = i + 1; j < list.size(); j++) {
                    CharacterSheet a = list.get(i);
                    CharacterSheet b = list.get(j);
                    if (a.relations().containsKey(b.id()) || b.relations().containsKey(a.id())) {
                        pairs.add(new CharacterSheet[]{a, b});
                    }
                }
            }
            if (pairs.isEmpty()) {
                continue;
            }
            CharacterSheet[] pair = pairs.get(random.nextInt(pairs.size()));
            NPC na = plugin.npcs().npcFor(pair[0].id());
            if (na == null || !na.isSpawned() || na.getEntity() == null) {
                continue;
            }
            Location loc = na.getEntity().getLocation();
            List<Player> near = new ArrayList<>();
            for (Player p : loc.getWorld().getPlayers()) {
                if (p.getLocation().distanceSquared(loc) <= st.ambientPlayerRadius * st.ambientPlayerRadius) {
                    near.add(p);
                }
            }
            if (near.isEmpty()) {
                continue;
            }
            lastAmbientByPlace.put(e.getKey(), now);
            startAmbient(pair[0], pair[1], e.getKey(), loc, near);
        }
    }

    private void startAmbient(CharacterSheet a, CharacterSheet b, String placeName, Location loc, List<Player> near) {
        Settings st = plugin.settings();
        Map<String, String> names = new HashMap<>();
        Set<String> pids = new HashSet<>();
        for (Player p : near) {
            pids.add(p.getUniqueId().toString());
        }
        long now = System.currentTimeMillis();
        // 공개 잡담(근처 모두가 봄)에는 소문의 "종류"만 쓴다: 원문·플레이어 이름은 DB에서 꺼내지도 않는다.
        // social.public-rumors: false 면 잡담에 소문을 아예 넣지 않는다.
        boolean withRumors = st.publicRumors;
        plugin.database().async(() -> {
            List<Storage.PublicRumor> r = new ArrayList<>();
            if (withRumors) {
                r.addAll(plugin.storage().publicRumorsHeardBy(a.id(), pids, now, 1));
                r.addAll(plugin.storage().publicRumorsHeardBy(b.id(), pids, now, 1));
            }
            return r;
        }).thenAccept(rumors -> plugin.sync(() -> {
            var place = plugin.places().get(placeName);
            StringBuilder ctx = new StringBuilder();
            ctx.append("[장소] ").append(place != null ? place.label() : placeName).append('\n');
            ctx.append("[시간] ").append(plugin.npcs().partOfDay(a)).append('\n');
            ctx.append(describe(a)).append('\n').append(describe(b)).append('\n');
            CharacterSheet.Relation rel = a.relations().get(b.id());
            if (rel != null) {
                ctx.append("[관계] ").append(a.name()).append("에게 ").append(b.name()).append("는 ").append(rel.type()).append('\n');
            }
            if (rumors != null && !rumors.isEmpty()) {
                ctx.append("[둘이 아는 학교 소문 — 가볍게 하나만 섞어도 됨]\n");
                Set<String> seen = new HashSet<>();
                for (Storage.PublicRumor rv : rumors) {
                    // 서버가 만든 문장: 사건 종류 + NPC 이름만 (플레이어 이름·AI/플레이어가 쓴 글 없음)
                    CharacterSheet src = rv.sourceNpc() == null ? null : plugin.characters().get(rv.sourceNpc());
                    String line = TextSanitizer.publicRumor(rv.type(), src == null ? null : src.name());
                    if (seen.add(line)) {
                        ctx.append("- ").append(line).append('\n');
                    }
                }
            }
            names.put(a.id(), a.name());
            names.put(b.id(), b.name());
            String instructions = plugin.content().ambientPrompt()
                    .replace("{world_lore}", st.worldLore == null ? "" : st.worldLore.trim());
            List<Map<String, Object>> input = OpenAiClient.messages();
            input.add(OpenAiClient.message("user", ctx.toString()
                    + "\nspeaker 값은 \"" + a.id() + "\" 또는 \"" + b.id() + "\"만 쓴다."));
            Map<String, Object> lineSchema = Json.map(
                    "type", "object",
                    "properties", Json.map(
                            "speaker", Json.map("type", "string"),
                            "text", Json.map("type", "string")),
                    "required", List.of("speaker", "text"),
                    "additionalProperties", false);
            Map<String, Object> schema = Json.map(
                    "type", "object",
                    "properties", Json.map("lines", Json.map("type", "array", "items", lineSchema)),
                    "required", List.of("lines"),
                    "additionalProperties", false);
            OpenAiClient.Request req = new OpenAiClient.Request(instructions, input, schema, "ambient_chat",
                    st.maxOutputTokens, "chacanpc:ambient");
            BudgetService.Reservation reservation = plugin.budget().reserve(BudgetService.SYSTEM,
                    plugin.budget().estimate(instructions.length() + ctx.length() + 40, st.maxOutputTokens));
            if (reservation == null) {
                return;
            }
            // 백그라운드 요청: 플레이어 대화가 붐비면 받지 않는다 (그때는 이번 잡담을 건너뜀)
            plugin.ai().background(req).whenComplete((res, ex) -> {
                plugin.budget().settle(reservation, res == null ? null : res.usage(), res != null && res.usageKnown());
                plugin.sync(() -> {
                    if (res != null && res.ok()) {
                        showAmbient(res.text(), names, loc);
                    }
                });
            });
        }));
    }

    private static String describe(CharacterSheet c) {
        StringBuilder sb = new StringBuilder("[" + c.id() + "] " + c.name());
        if (c.persona() != null) {
            if (c.persona().role() != null) {
                sb.append(" / ").append(c.persona().role());
            }
            if (c.persona().speech() != null) {
                sb.append(" / 말투: ").append(c.persona().speech());
            }
        }
        return sb.toString();
    }

    private void showAmbient(String json, Map<String, String> names, Location loc) {
        List<Object> lines;
        try {
            lines = Json.arr(Json.parseObject(json).get("lines"));
        } catch (RuntimeException ex) {
            return;
        }
        if (lines == null) {
            return;
        }
        TextFilter filter = plugin.content().filter();
        // 잡담은 근처 모든 플레이어에게 보인다: 접속 중인 플레이어 이름이 들어간 줄도 내보내지 않는다
        List<String> playerNames = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            playerNames.add(online.getName());
            // 한글 닉네임이 들어간 줄도 막는다 (#94: 유저에게는 닉네임이 보이는 이름)
            String nick = plugin.link().playerName(online);
            if (!nick.equals(online.getName())) playerNames.add(nick);
        }
        List<String[]> ok = new ArrayList<>();
        for (Object o : lines) {
            Map<String, Object> m = Json.obj(o);
            String speaker = Json.str(m, "speaker");
            String text = Json.str(m, "text");
            if (speaker == null || text == null || !names.containsKey(speaker) || ok.size() >= 4) {
                continue;
            }
            // 정리(제어문자·색 코드·URL 제거, 길이 제한) → 대사 검사 + 공개용 금지어 검사
            String clean = ReplyParser.cleanLine(TextSanitizer.strip(text), plugin.settings().lineMaxLength);
            if (clean == null) {
                continue;
            }
            if (filter.checkOutput(clean) != TextFilter.Verdict.OK || !filter.isPublicSafe(clean)
                    || TextSanitizer.mentionsAny(clean, playerNames)) {
                return; // 하나라도 이상하면 전부 버린다
            }
            ok.add(new String[]{names.get(speaker), clean});
        }
        int delay = 0;
        for (String[] item : ok) {
            String name = item[0];
            String clean = item[1];
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                double r2 = plugin.settings().ambientPlayerRadius * plugin.settings().ambientPlayerRadius;
                for (Player p : loc.getWorld().getPlayers()) {
                    if (p.getLocation().distanceSquared(loc) <= r2) {
                        p.sendMessage(Component.text("[" + name + "] ", NamedTextColor.GRAY)
                                .append(Component.text(clean, NamedTextColor.GRAY)));
                    }
                }
            }, delay);
            delay += 60; // 3초 간격
        }
    }
}
