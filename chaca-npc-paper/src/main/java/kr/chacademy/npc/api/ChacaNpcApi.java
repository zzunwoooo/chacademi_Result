package kr.chacademy.npc.api;

import kr.chacademy.npc.ChacaNpcPlugin;
import kr.chacademy.npc.ai.OpenAiClient;
import kr.chacademy.npc.budget.BudgetService;
import kr.chacademy.npc.core.CharacterSheet;
import kr.chacademy.npc.core.Json;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 다른 플러그인용 공개 API (선택). MagicCodex 연결은 MagicCodexBridge의 NpcSocialFacade를
 * ChacaNPC가 리플렉션으로 호출하는 방식이라, 이 클래스를 다른 JAR에 포함하지 말 것.
 */
public final class ChacaNpcApi {

    private ChacaNpcApi() {
    }

    private static ChacaNpcPlugin plugin() {
        ChacaNpcPlugin p = ChacaNpcPlugin.instance();
        if (p == null) {
            throw new IllegalStateException("ChacaNPC가 아직 켜지지 않았습니다");
        }
        return p;
    }

    /**
     * 사건 기록 → 관계도를 따라 소문이 된다.
     * type: gift / heart / ending / quest_done / quest_accept / promise / nickname (heart·ending은 연애 소문)
     */
    public static void recordEvent(UUID player, String playerName, String npcId, String type, String text) {
        plugin().social().recordEvent(player, playerName, npcId, type, text);
    }

    public static CharacterSheet character(String npcId) {
        return plugin().characters().get(npcId);
    }

    /**
     * 운영자 AI 도우미 (MagicCodexBridge {@code /관리 질문}, 오픈 전 메모 #38): 지시문 + 질문 하나 → JSON 글
     * {@code {"answer": "...", "commands": [{"command": "/...", "note": "..."}]}}.
     * ChacaNPC 의 OpenAI 연결·모델을 그대로 쓴다. 오너 결정(2026-10-25): 하루 예산 한도와 상관없이 보낸다 (운영자 몇 명, 한 번에 몇 원).
     * 쓴 금액은 시스템 사용량으로 기록한다 (/서버관리 AI 예산 에 보임). 결과 future 는 HTTP 스레드에서 끝난다.
     */
    public static CompletableFuture<String> ask(String instructions, String question, int maxOutputTokens) {
        ChacaNpcPlugin p = plugin();
        var command = Json.map("type", "object",
                "properties", Json.map("command", Json.map("type", "string"), "note", Json.map("type", "string")),
                "required", List.of("command", "note"), "additionalProperties", false);
        var schema = Json.map("type", "object",
                "properties", Json.map("answer", Json.map("type", "string"),
                        "commands", Json.map("type", "array", "items", command)),
                "required", List.of("answer", "commands"), "additionalProperties", false);
        var input = OpenAiClient.messages();
        input.add(OpenAiClient.message("user", question));
        int tokens = Math.max(200, Math.min(maxOutputTokens, 4000));
        var req = new OpenAiClient.Request(instructions, input, schema, "operator_help", tokens, "chacanpc:operator-help");
        // 한도 때문에 막지 않는다: 예약이 거절되면(한도 초과) 기록 없이 그냥 보낸다
        BudgetService.Reservation reservation = p.budget().reserve(BudgetService.SYSTEM,
                p.budget().estimate(instructions.length() + question.length(), tokens));
        CompletableFuture<String> out = new CompletableFuture<>();
        p.ai().player(req, System.currentTimeMillis() + 90_000L, () -> true).whenComplete((res, ex) -> {
            if (reservation != null) p.budget().settle(reservation, res == null ? null : res.usage(), res != null && res.usageKnown());
            if (ex != null) { out.completeExceptionally(ex); return; }
            if (res == null) { out.completeExceptionally(new IllegalStateException("응답 없음")); return; }
            if (res.ok()) { out.complete(res.text()); return; }
            out.completeExceptionally(new IllegalStateException(switch (res.status()) {
                case NO_KEY -> "ChacaNPC config 에 OpenAI 키가 없어요";
                case TIMEOUT -> "응답이 너무 늦어요";
                case REJECTED -> "AI 요청이 밀려 있어요. 잠시 뒤 다시";
                default -> "AI 오류 (" + res.category() + ")";
            }));
        });
        return out;
    }

    /**
     * 마법 383 마음 읽기 (MagicCodexBridge LifeSpells 가 리플렉션으로 부름, 메인 스레드):
     * 이 엔티티가 캐릭터에 연결된 Citizens NPC 면 캐릭터 id, 아니면 null.
     */
    public static String characterOf(org.bukkit.entity.Entity entity) {
        ChacaNpcPlugin p = ChacaNpcPlugin.instance();
        if (p == null || entity == null) {
            return null;
        }
        net.citizensnpcs.api.npc.NPC npc = net.citizensnpcs.api.CitizensAPI.getNPCRegistry().getNPC(entity);
        return npc == null ? null : p.npcs().charIdFor(npc.getId());
    }

    /** 캐릭터 이름, 없으면 null (마법 383). */
    public static String characterName(String npcId) {
        ChacaNpcPlugin p = ChacaNpcPlugin.instance();
        CharacterSheet c = p == null || npcId == null ? null : p.characters().get(npcId);
        return c == null ? null : c.name();
    }

    /**
     * 마법 깨달음의 빛 (MagicCodexBridge LifeSpells 가 리플렉션으로 부름, 메인 스레드): hints.yml 의 마법 힌트
     * spell id → 목격담 재료(materials). 같은 마법에 힌트가 여럿이면 가장 막연한(difficulty 큰) 것. 없으면 빈 맵.
     */
    public static java.util.Map<String, String> hintMaterials() {
        ChacaNpcPlugin p = ChacaNpcPlugin.instance();
        var out = new java.util.HashMap<String, String>();
        if (p == null || p.content() == null) {
            return out;
        }
        var level = new java.util.HashMap<String, Integer>();
        for (var h : p.content().hints()) {
            if (h.spell() == null || h.materials() == null || h.materials().isBlank()) {
                continue;
            }
            Integer before = level.get(h.spell());
            if (before == null || h.difficulty() > before) {
                level.put(h.spell(), h.difficulty());
                out.put(h.spell(), h.materials().strip());
            }
        }
        return out;
    }

    /** 좋아하는 선물 아이템 키 (gift_likes 의 loved + liked, GiftService.keyOf 형식), 없으면 빈 목록 (마법 383). */
    public static java.util.List<String> likedGifts(String npcId) {
        ChacaNpcPlugin p = ChacaNpcPlugin.instance();
        CharacterSheet c = p == null || npcId == null ? null : p.characters().get(npcId);
        var out = new java.util.ArrayList<String>();
        if (c == null || c.giftLikes() == null) {
            return out;
        }
        for (String k : java.util.List.of("loved", "liked")) {
            var list = c.giftLikes().get(k);
            if (list != null) {
                for (String key : list) {
                    if (key != null && !key.isBlank() && !out.contains(key)) {
                        out.add(key);
                    }
                }
            }
        }
        return out;
    }
}
