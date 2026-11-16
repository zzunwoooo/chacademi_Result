package com.diamondserver.core;

import bot.colla.donation.event.DonationEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * 후원 받기: donation 플러그인(SOOP 별풍선 · 치지직 치즈)이 알려 주는 후원을 이벤트 줄에 세운다.
 * 후원을 받은 스트리머(그 플레이어)가 이벤트 대상이 된다.
 * donation 플러그인이 없으면 이 클래스는 쓰이지 않는다 (그때는 /후원테스트 로만 이벤트가 나간다).
 */
final class DonationHook implements Listener {
    private final DiamondPlugin pl;

    DonationHook(DiamondPlugin pl) { this.pl = pl; }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDonation(DonationEvent e) {
        if (!pl.getConfig().getBoolean("donation.enabled", true)) return;
        e.setCancelled(true);                        // donation 플러그인의 기본 동작(콘솔 명령 실행)은 막는다
        if (e.isTestDonation() && !pl.getConfig().getBoolean("donation.allow-test", true)) return;
        int won = Math.max(1, pl.getConfig().getInt("donation.chzzk-won-per-unit", 100));
        int count = e.getType().getPlatform() == DonationEvent.Platform.CHZZK ? e.getAmount() / won : e.getAmount();
        Player target = e.getPlayer();
        if (!pl.events.enqueue(target.getUniqueId(), target.getName(), e.getNickname(), count))
            pl.getLogger().info("등록되지 않은 후원 개수라 넘김: " + count + "개 (" + e.getNickname() + " → " + target.getName() + ")");
    }
}
