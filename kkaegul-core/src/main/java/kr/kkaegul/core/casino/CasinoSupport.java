package kr.kkaegul.core.casino;

import kr.kkaegul.core.KkaegulCore;
import kr.kkaegul.core.util.Json;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** 포커·섯다 미니게임 공용 도우미: 테이블 나누기, 다이아 입출금, 화면 전송 */
public final class CasinoSupport {
    private CasinoSupport() {}

    public static final List<String> AVATARS = List.of("bear", "rabbit", "fox", "raccoon", "cat", "squirrel", "deer", "panda");

    /**
     * n명을 3인 테이블 위주로 고르게 나눔. 2명 테이블을 섞어서 혼자 남는 사람이 없게.
     * 예) 2→[2] 3→[3] 4→[2,2] 5→[3,2] 6→[3,3] 7→[3,2,2] 8→[3,3,2] 10→[3,3,2,2]
     * 1명이면 빈 목록 (게임 불가).
     */
    public static List<Integer> split(int n) {
        List<Integer> out = new ArrayList<>();
        if (n < 2) return out;
        int threes = n / 3, rem = n % 3;
        if (rem == 1) { threes -= 1; out.add(2); out.add(2); }       // 3+1 → 2+2
        else if (rem == 2) out.add(2);
        for (int i = 0; i < threes; i++) out.add(0, 3);
        return out;
    }

    public static long available(KkaegulCore plugin, Player p) { return plugin.submit().available(p); }

    /** 다이아 가져오기. 실제로 가져온 개수 */
    public static long take(KkaegulCore plugin, Player p, long n) {
        if (n <= 0) return 0;
        long got = plugin.submit().takeDiamonds(p, n);
        plugin.hud().refresh(p);
        return got;
    }

    /** 다이아 돌려주기 (접속 중이 아니면 데이터에 바로) */
    public static void give(KkaegulCore plugin, UUID u, long n) {
        if (n <= 0 || u == null) return;
        Player p = Bukkit.getPlayer(u);
        if (p != null) plugin.wallet().deposit(plugin.data().get(p), n, p);
        else { var d = plugin.data().get(u); d.held += n; d.markDirty(); }
    }

    /** 엘시 형식 상태 JSON 을 kkaegul:ui 로 감싸서 보냄 */
    public static void send(KkaegulCore plugin, Player p, String game, String stateJson) {
        plugin.ui().send(p, Json.obj().put("type", "casino").put("game", game).put("state", stateJson));
    }

    public static void close(KkaegulCore plugin, Player p, String game) {
        send(plugin, p, game, "{\"type\":\"close\"}");
    }

    public static String num(long v) { return String.format(Locale.US, "%,d", v); }

    public static String left(long ms) {
        long m = Math.max(0, ms) / 60_000L;
        if (m >= 60) return (m / 60) + "시간 " + (m % 60) + "분";
        if (m >= 1) return m + "분";
        return Math.max(0, ms) / 1000 + "초";
    }
}
