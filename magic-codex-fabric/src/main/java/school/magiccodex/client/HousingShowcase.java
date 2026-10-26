package school.magiccodex.client;

import java.util.*;
import school.magiccodex.protocol.ArtifactProtocol.Entry;
import school.magiccodex.protocol.HousingProtocol;
import school.magiccodex.protocol.KyudichiQueueProtocol;

/**
 * #108 (2026-10-26) 지금 보고 있는 방 주인의 전시 값 (빗자루 거치대·아티팩트 진열장·큐디치 트로피장). 클라이언트 스레드.
 * <ul>
 *   <li>빗자루·아티팩트: 내 방이면 내 값(BroomUiLink·ArtifactClient), 남의 방이면 방에 들어갈 때 서버가 한 번 보낸 SHOWCASE.</li>
 *   <li>큐디치 티어: 트로피장을 처음 그릴 때 한 번 프록시 전적(PROFILE, StatsRanks 와 같은 요청)을 묻는다. 답은 이 클래스가 가로챈다.</li>
 * </ul>
 * 매 틱·매 프레임 요청 없음 (주인이 바뀔 때만 다시 묻는다).
 */
final class HousingShowcase {
    private HousingShowcase() {}

    private static HousingProtocol.Showcase data;
    private static UUID tierFor; private static String tierQuery; private static boolean tierAnswered;
    private static KyudichiQueueProtocol.Profile tierProfile;
    private static boolean artifactsAsked;

    /** 방 주인이 바뀌거나 창을 닫으면 (HousingClient). */
    static void reset() { data = null; tierFor = null; tierQuery = null; tierAnswered = false; tierProfile = null; artifactsAsked = false; }
    static void accept(HousingProtocol.Showcase s) { data = s; }

    // ---------------------------------------------------------------- 빗자루
    /** 주인 장착 빗자루 id ("" = 없음·모름). */
    static String broom(UUID owner, boolean self) {
        if (self) return BroomUiLink.equipped();
        return data != null && data.owner().equals(owner) ? data.broom() : "";
    }
    static String broomName(UUID owner, boolean self, String id) {
        if (id.isEmpty()) return "";
        if (self) return BroomUiLink.text("name", id);
        return data != null && data.owner().equals(owner) && !data.broomName().isEmpty() ? data.broomName() : id;
    }

    // ---------------------------------------------------------------- 아티팩트
    /** 주인 장착 아티팩트 id. */
    static List<String> artifacts(UUID owner, boolean self) {
        if (self) return ArtifactClient.equipped();
        return data != null && data.owner().equals(owner) ? data.artifacts() : List.of();
    }
    /** 도감 목록(아이콘·이름)이 아직 없으면 방마다 한 번 받아 둔다. */
    static Entry entry(String id) {
        var catalog = ArtifactClient.catalog();
        if (catalog.isEmpty() && !artifactsAsked) { artifactsAsked = true; ArtifactClient.query(); }
        for (var e : catalog) if (e.id().equals(id)) return e;
        return null;
    }

    // ---------------------------------------------------------------- 큐디치 티어
    /** 트로피장을 그릴 때: 이 주인 것을 아직 안 물었으면 한 번 묻는다. 내 방이면 "" (나). */
    static void askTier(UUID owner, String nick, boolean self) {
        if (owner.equals(tierFor)) return;
        tierFor = owner; tierAnswered = false; tierProfile = null;
        tierQuery = self ? "" : nick == null ? "" : nick.strip();
        if (!self && tierQuery.isEmpty()) { tierQuery = null; tierAnswered = true; return; }
        KyudichiQueueClient.send(new KyudichiQueueProtocol.ProfileRequest(KyudichiQueueProtocol.MODE_KYUDICHI, tierQuery));
    }
    /** KyudichiQueueClient.receive: 트로피장이 물은 답이면 가져가고 true. */
    static boolean take(KyudichiQueueProtocol.ProfileReply reply) {
        if (tierQuery == null || reply.mode() != KyudichiQueueProtocol.MODE_KYUDICHI || !tierQuery.equals(reply.query())) return false;
        tierQuery = null; tierAnswered = true; tierProfile = reply.profile(); return true;
    }
    /** 티어 0~8, 기록 없음·아직 모름 = -1. */
    static int tier(UUID owner) { return owner.equals(tierFor) && tierAnswered && tierProfile != null ? Math.clamp(tierProfile.tier(), 0, 8) : -1; }
    /** 이름표 문구용: 답을 받았는데 기록이 없으면 true. */
    static boolean noRecord(UUID owner) { return owner.equals(tierFor) && tierAnswered && tierProfile == null; }
}
