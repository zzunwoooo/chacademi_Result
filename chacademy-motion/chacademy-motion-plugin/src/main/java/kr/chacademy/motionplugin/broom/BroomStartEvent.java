package kr.chacademy.motionplugin.broom;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.NotNull;

/** 빗자루를 타기 직전. 구역 보호 플러그인 등에서 취소하면 타지 않는다. */
public class BroomStartEvent extends PlayerEvent implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final BroomType broom;
    private final int limitTicks;
    private boolean cancelled;

    public BroomStartEvent(@NotNull Player player, @NotNull BroomType broom, int limitTicks) {
        super(player);
        this.broom = broom;
        this.limitTicks = limitTicks;
    }

    public @NotNull BroomType getBroom() {
        return broom;
    }

    /** 0 = 시간 제한 없음 (빗자루 소유), 그 외 = 연습용 빗자루 남은 틱 */
    public int getLimitTicks() {
        return limitTicks;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        cancelled = cancel;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
