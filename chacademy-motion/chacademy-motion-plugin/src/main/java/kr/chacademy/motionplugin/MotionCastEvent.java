package kr.chacademy.motionplugin;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.NotNull;

/**
 * 플레이어의 시전 모션을 다른 유저에게 보내기 직전에 호출된다.
 * 마법 플러그인에서 마나 부족, 침묵 상태 등일 때 취소하면 모션이 전달되지 않는다.
 * (시전자 본인 화면에서는 이미 재생된 상태다.)
 */
public class MotionCastEvent extends PlayerEvent implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();

    private final String animation;
    private final boolean fromClient;
    private boolean cancelled;

    public MotionCastEvent(@NotNull Player player, @NotNull String animation, boolean fromClient) {
        super(player);
        this.animation = animation;
        this.fromClient = fromClient;
    }

    /** 모션 이름 (예: wand_cast) */
    public @NotNull String getAnimation() {
        return animation;
    }

    /** true 면 모드가 보낸 요청, false 면 서버(명령어나 다른 플러그인)가 시작한 모션 */
    public boolean isFromClient() {
        return fromClient;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
