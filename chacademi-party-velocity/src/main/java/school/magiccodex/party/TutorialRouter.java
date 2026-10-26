package school.magiccodex.party;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.slf4j.Logger;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 튜토리얼 서버 분산 (2026-10-25, 오픈 메모 #3). tutorial.properties:
 * <pre>
 *   tutorial-servers = tutorial            Velocity 서버 이름 (쉼표). 2026-10-26 오너: 튜토리얼 서버(시작의 섬)는 하나 → 기본 tutorial 1개.
 *                                          비우거나 1개면 아무것도 바꾸지 않는다 (분산 꺼짐, 접속은 velocity.toml 그대로). 나중에 늘리면 2개 이상 적기.
 *   balance-tolerance = 0                   처음 가려던 튜토리얼 서버가 가장 적은 곳보다 이 인원 넘게 많을 때만 옮긴다.
 *   sticky = true                           한 번 배정된 튜토리얼 서버로 계속 보낸다 (그 서버가 꺼져 있으면 다른 곳).
 * </pre>
 * 튜토리얼 대상 판정은 Velocity 에 없다: "튜토리얼 서버 무리 중 하나로 접속·이동하려는 사람"만 대상으로 본다
 * (첫 접속 서버 = velocity.toml try / forced-hosts 가 정한다). 그 사람을 무리 안에서 인원이 적은 서버로 바꾼다.
 * 튜토리얼 서버 사이 이동(이미 튜토리얼 서버에 있는 사람)은 건드리지 않는다. 꺼진 서버(10초마다 확인하는 ping 실패)는 고르지 않는다.
 * 인원 = 이 프록시가 그 서버에 붙여 둔 접속자 수 (getPlayersConnected, 비용 없음).
 */
final class TutorialRouter {
    interface Host { boolean enqueue(Runnable task); }

    private final ProxyServer proxy;
    private final Logger log;
    private final Host host;
    private final Object plugin;
    private Path file;
    private List<String> servers = List.of();
    private int tolerance;
    private boolean sticky = true;
    /** 플레이어 → 배정한 튜토리얼 서버 (sticky). 이벤트 스레드가 읽고 쓴다. 파일 저장은 작업 스레드. */
    private final Map<UUID, String> assigned = new ConcurrentHashMap<>();
    /** ping 이 실패한 서버 (10초마다 갱신). */
    private final Set<String> down = ConcurrentHashMap.newKeySet();

    TutorialRouter(ProxyServer proxy, Logger log, Host host, Object plugin) { this.proxy = proxy; this.log = log; this.host = host; this.plugin = plugin; }

    void initialize(Path directory) {
        var path = directory.resolve("tutorial.properties");
        var p = new Properties();
        try {
            if (Files.exists(path)) try (var r = Files.newBufferedReader(path)) { p.load(r); }
            else {
                p.setProperty("tutorial-servers", ""); p.setProperty("balance-tolerance", "0"); p.setProperty("sticky", "true");
                try (var w = Files.newBufferedWriter(path)) { p.store(w, "튜토리얼 서버 분산: tutorial-servers = Velocity 서버 이름 (쉼표). 1개(기본 tutorial)·빈 값이면 분산 꺼짐, 2개 이상이면 인원 분산. 고친 뒤 프록시 재시작."); }
            }
        } catch (IOException e) { log.warn("tutorial.properties 읽기 실패 — 튜토리얼 분산 꺼짐", e); return; }
        servers = parseList(p.getProperty("tutorial-servers", ""));
        try { tolerance = Math.max(0, Integer.parseInt(p.getProperty("balance-tolerance", "0").strip())); } catch (NumberFormatException e) { tolerance = 0; log.warn("tutorial.properties: balance-tolerance 는 0 이상 정수 (0 으로 씀)"); }
        sticky = !"false".equalsIgnoreCase(p.getProperty("sticky", "true").strip());
        if (!enabled()) { if (servers.size() == 1) log.info("튜토리얼 서버가 1개라 분산하지 않습니다 (" + servers.get(0) + ")"); return; }
        for (String s : servers) if (proxy.getServer(s).isEmpty()) log.warn("tutorial.properties: Velocity 에 없는 서버 " + s + " (고르지 않음)");
        file = directory.resolve("tutorial-assigned.properties");
        loadAssigned();
        proxy.getScheduler().buildTask(plugin, this::pingAll).repeat(10, TimeUnit.SECONDS).schedule();
        log.info("튜토리얼 서버 분산: " + servers + " · 허용치 " + tolerance + " · 같은 서버 유지 " + sticky);
    }

    boolean enabled() { return servers.size() >= 2; }

    static List<String> parseList(String raw) {
        var out = new ArrayList<String>();
        for (String s : raw == null ? new String[0] : raw.split(",")) { s = s.strip(); if (!s.isEmpty() && !out.contains(s)) out.add(s); }
        return List.copyOf(out);
    }

    /**
     * 갈 튜토리얼 서버. requested = 원래 가려던 곳 (무리 중 하나), counts = 서버별 인원 (꺼진 서버는 빼고 넘긴다),
     * stickyTo = 예전에 배정한 곳 (없으면 null). 고를 곳이 없으면 requested.
     */
    static String pick(List<String> group, Map<String, Integer> counts, String requested, String stickyTo, int tolerance) {
        if (stickyTo != null && counts.containsKey(stickyTo)) return stickyTo;
        String best = null;
        int min = Integer.MAX_VALUE;
        for (String s : group) {   // 같으면 목록 앞쪽
            Integer n = counts.get(s);
            if (n != null && n < min) { min = n; best = s; }
        }
        if (best == null) return requested;
        Integer mine = counts.get(requested);
        if (mine != null && mine - min <= tolerance) return requested;
        return best;
    }

    @Subscribe public void preConnect(ServerPreConnectEvent event) {
        if (!enabled() || !event.getResult().isAllowed()) return;
        RegisteredServer target = event.getResult().getServer().orElse(event.getOriginalServer());
        String name = target.getServerInfo().getName();
        if (!servers.contains(name)) return;
        RegisteredServer previous = event.getPreviousServer();
        if (previous != null && servers.contains(previous.getServerInfo().getName())) return;   // 튜토리얼 서버끼리 옮기는 것은 그대로
        UUID id = event.getPlayer().getUniqueId();
        var counts = new LinkedHashMap<String, Integer>();
        for (String s : servers) {
            if (down.contains(s)) continue;
            proxy.getServer(s).ifPresent(r -> counts.put(s, r.getPlayersConnected().size()));
        }
        String chosen = pick(servers, counts, name, sticky ? assigned.get(id) : null, tolerance);
        if (sticky && !chosen.equals(assigned.get(id))) { assigned.put(id, chosen); host.enqueue(this::saveAssigned); }
        if (chosen.equals(name)) return;
        var to = proxy.getServer(chosen);
        if (to.isEmpty()) return;
        event.setResult(ServerPreConnectEvent.ServerResult.allowed(to.get()));
        log.info("튜토리얼 분산: " + event.getPlayer().getUsername() + " " + name + " → " + chosen + " " + counts);
    }

    private void pingAll() {
        for (String s : servers) proxy.getServer(s).ifPresent(r -> r.ping().orTimeout(4, TimeUnit.SECONDS).whenComplete((ping, err) -> {
            boolean wasDown = down.contains(s);
            if (err != null || ping == null) { if (down.add(s)) log.warn("튜토리얼 서버 " + s + " 응답 없음 — 분산에서 뺍니다"); }
            else if (wasDown) { down.remove(s); log.info("튜토리얼 서버 " + s + " 다시 응답 — 분산에 넣습니다"); }
        }));
    }

    private void loadAssigned() {
        if (file == null || !Files.exists(file)) return;
        var p = new Properties();
        try (var r = Files.newBufferedReader(file)) { p.load(r); }
        catch (IOException e) { log.warn("tutorial-assigned.properties 읽기 실패 — 같은 서버 유지 기록 없이 시작", e); return; }
        for (String k : p.stringPropertyNames()) {
            try { assigned.put(UUID.fromString(k), p.getProperty(k)); } catch (IllegalArgumentException ignored) { }
        }
    }

    /** 작업 스레드에서. 임시 파일에 쓰고 바꿔 끼운다. */
    private void saveAssigned() {
        if (file == null) return;
        var p = new Properties();
        assigned.forEach((k, v) -> p.setProperty(k.toString(), v));
        try {
            var tmp = file.resolveSibling("tutorial-assigned.properties.tmp");
            try (var w = Files.newBufferedWriter(tmp)) { p.store(w, "튜토리얼 서버 배정 (자동 저장, 지우면 다시 인원 기준으로 배정)"); }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) { log.warn("tutorial-assigned.properties 저장 실패", e); }
    }
}
