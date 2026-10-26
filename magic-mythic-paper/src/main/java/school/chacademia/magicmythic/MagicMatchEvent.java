package school.chacademia.magicmythic;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * 오픈 전 #33 (오너 결정 2026-10-25): MagicMythic 이 "이 플레이어가 지금 어떤 경기를 뛰고 있나" 를 물을 때만 부른다.
 * 경기 플러그인(ChacaKyudichi)이 경기 중인 선수에게 {@link #setKind(String)} 로 "quidditch" 또는 "duel" 을 답한다.
 * 아무도 답하지 않으면 빈 문자열 = 경기 밖 (평소 규칙). 메인 스레드 전용. 리스너는 이름으로 찾는다 (컴파일 의존 없음):
 * getPlayer() / setKind(String).
 */
public final class MagicMatchEvent extends Event {
    public static final String QUIDDITCH = "quidditch", DUEL = "duel";
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private String kind = "";

    public MagicMatchEvent(Player player) { this.player = player; }
    public Player getPlayer() { return player; }
    public void setKind(String kind) { this.kind = kind == null ? "" : kind; }
    public String getKind() { return kind; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
    static boolean listened() { return HANDLERS.getRegisteredListeners().length > 0; }

    /** 경기 종류, 경기 밖이거나 아무도 듣지 않으면 "". */
    static String ask(Player player) {
        if (player == null || !listened()) return "";
        var e = new MagicMatchEvent(player);
        org.bukkit.Bukkit.getPluginManager().callEvent(e);
        return e.getKind();
    }
}
