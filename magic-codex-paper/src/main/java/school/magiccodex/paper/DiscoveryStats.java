package school.magiccodex.paper;

import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * 마법 발견 조건 v2 (2026-10-26, docs/plans/discovery-conditions-v2.md): 서버 통계(AuditStats, 메모 #96) 중 발견 조건에 쓰는 몇 개를
 * MagicDiscovery 카운터로도 보낸다. 통계를 이미 세는 곳(교화·연금술·낚시·물고기 판매·NPC 선물·1:1 결투·빗자루·개인 지점 이동)을
 * 그대로 다시 쓰므로 새 이벤트 리스너가 없다. {@link #keys} 는 순수 로직 (JUnit 대상).
 */
final class DiscoveryStats {
    private DiscoveryStats() {}

    /** 통계 키 → 발견 카운터 키 (없으면 빈 목록). 통계 키가 바뀌면 여기와 discoveries.yml 을 같이 고친다. */
    static List<String> keys(String stat) {
        if (stat == null) return List.of();
        return switch (stat) {
            case "교화" -> List.of("taming.success");               // 광륜 투척
            case "연금술제작" -> List.of("alchemy.crafted");         // 연금로 예열
            case "물고기판매" -> List.of("fish.sold");              // 비늘 감정
            case "보물상자" -> List.of("fishing.treasure");          // 보물 냄새
            case "낚시" -> List.of("fish.caught_fish");             // 물고기 유인·물밑 엿보기 (바닐라 낚시와 같은 키, 미니게임은 바닐라 이벤트를 취소하므로 겹치지 않음)
            case "낚시:등급1", "낚시:등급2" -> List.of("fishing.rare");           // 얼음 뱀
            case "낚시:등급3" -> List.of("fishing.rare", "fishing.legendary");  // 얼음 뱀·레비아탄
            case "낚시줄끊김" -> List.of("fishing.snapped");         // 잔잔한 물결
            case "선물" -> List.of("npc.gift");                    // 마음 읽기
            case "결투참가" -> List.of("duel.played");              // 숨 막힘
            case "결투승리" -> List.of("duel.won");                 // 그림자 못
            case "빗자루비행" -> List.of("broom.flown");            // 빗자루 차원이동·불꽃 추진 (블록)
            case "빗자루낙마" -> List.of("broom.fall");             // 순풍
            case "개인지점이동" -> List.of("travel.personal");       // 섬광 도약
            default -> List.of();
        };
    }

    /** MagicCodexBridge 가 켜질 때 한 번 (AuditLog.start). 통계는 아무 스레드에서 오므로 발견 신호는 서버 스레드로 넘긴다. */
    static void install(Plugin plugin) {
        AuditStats.forward((player, stat, amount) -> {
            var keys = keys(stat);
            if (keys.isEmpty() || amount <= 0) return;
            Runnable send = () -> { for (String k : keys) DiscoveryLink.signal(player, k, amount); };
            if (Bukkit.isPrimaryThread()) send.run();
            else if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, send);
        });
    }
}
