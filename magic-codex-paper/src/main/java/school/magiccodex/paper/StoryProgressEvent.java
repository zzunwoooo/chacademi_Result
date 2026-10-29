package school.magiccodex.paper;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * 스토리 단계(ChacademyStory)의 "신호" 조건용 성공 알림. 각 기능이 저장·지급이 확정된 뒤 메인 스레드에서만 부른다.
 * ChacademyStory 는 이 클래스를 리플렉션으로 듣는다 (getPlayer / getKey / getAmount). 실패·취소·재전송에는 부르지 않는다.
 * 학교가 아닌 서버의 신호는 {@link StorySignals} 가 학교로 전달해 relayed=true 로 다시 발사한다 (2026-10-29).
 */
public final class StoryProgressEvent extends Event {
    public static final String TAMING = "교화성공", FISH = "낚시성공", ALCHEMY = "연금술성공", ASCEND = "승급성공";
    /** 2026-10-25 (1장 재기획): 물고기 판매소에서 돈을 받고 판매 기록까지 끝남 · 지도 이동 창을 엶 · 공개 웨이포인트를 처음 등록함. */
    public static final String FISH_SOLD = "물고기판매", MAP_OPEN = "지도열기", WAYPOINT = "웨이포인트등록";
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final String key;
    private final int amount;
    private final boolean relayed;

    public StoryProgressEvent(Player player, String key, int amount) { this(player, key, amount, false); }

    /** relayed = 다른 서버에서 전달받은 신호 (다시 전달하지 않음). */
    public StoryProgressEvent(Player player, String key, int amount, boolean relayed) {
        this.player = player;
        this.key = key;
        this.amount = amount;
        this.relayed = relayed;
    }

    public Player getPlayer() { return player; }
    public String getKey() { return key; }
    public Integer getAmount() { return amount; }
    public boolean relayed() { return relayed; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }

    /** 접속 중인 유저에게만, 다른 플러그인의 오류가 기능 결과를 깨지 않게. */
    static void fire(Player player, String key) {
        if (player == null || !player.isOnline() || !Bukkit.isPrimaryThread()) return;
        try { Bukkit.getPluginManager().callEvent(new StoryProgressEvent(player, key, 1)); }
        catch (RuntimeException error) { Bukkit.getLogger().warning("[스토리 신호] " + key + " 전달 실패: " + error); }
    }
}
