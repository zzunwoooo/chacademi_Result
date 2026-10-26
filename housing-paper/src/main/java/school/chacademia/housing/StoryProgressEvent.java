package school.chacademia.housing;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * 스토리 단계(ChacademyStory)의 "신호" 조건용 성공 알림. 각 기능이 저장·지급이 확정된 뒤 메인 스레드에서만 부른다.
 * ChacademyStory 는 이 클래스를 리플렉션으로 듣는다 (getPlayer / getKey / getAmount). 실패·취소·재전송에는 부르지 않는다.
 */
public final class StoryProgressEvent extends Event {
    public static final String OWN_ROOM = "내방열기";
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final String key;
    private final int amount;

    public StoryProgressEvent(Player player, String key, int amount) {
        this.player = player;
        this.key = key;
        this.amount = amount;
    }

    public Player getPlayer() { return player; }
    public String getKey() { return key; }
    public Integer getAmount() { return amount; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }

    /** 접속 중인 유저에게만, 다른 플러그인의 오류가 기능 결과를 깨지 않게. */
    static void fire(Player player, String key) {
        if (player == null || !player.isOnline() || !Bukkit.isPrimaryThread()) return;
        try { Bukkit.getPluginManager().callEvent(new StoryProgressEvent(player, key, 1)); }
        catch (RuntimeException error) { Bukkit.getLogger().warning("[스토리 신호] " + key + " 전달 실패: " + error); }
    }
}
