package kr.kkaegul.core.minigame;

import kr.kkaegul.core.KkaegulCore;
import kr.kkaegul.core.data.PlayerData;
import kr.kkaegul.core.util.Json;
import kr.kkaegul.core.util.Rng;
import kr.kkaegul.core.util.Txt;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.*;

/**
 * 미니게임 관리 + 일정. 게임마다 따로 돌아서 동시에 여러 게임이 열릴 수 있다 (한 사람은 한 게임만).
 * <ul>
 *   <li>위험한 초대: 30분마다 의자 수(4명)만큼, 거부 불가. 지난 판 참가자는 빼고, 새로 뽑을 사람이 모자라면 그 판은 건너뜀. 1분 전 회색 예고.</li>
 *   <li>보물찾기: 3시간마다 최대 30명, 거부 불가.</li>
 *   <li>카지노(홀덤·섯다 번갈아): 3시간마다, 보물찾기와 1시간 30분 어긋나게. 보유 다이아 50개 이상인 사람 모두에게 초대장 → 수락한 사람끼리.</li>
 *   <li>OP·kkaegul.minigame.exempt 는 어떤 초대에도 뽑히지 않음.</li>
 * </ul>
 * 일정(다음 시각)은 global.yml 에 저장해서 재시작해도 이어진다.
 */
public final class MinigameManager {
    private final KkaegulCore plugin;
    private final Map<String, Minigame> games = new LinkedHashMap<>();
    private final CardDrawService cards;
    /** 초대장 받고 이동 기다리는 사람 (게임 id 별) */
    private final Map<String, List<UUID>> pending = new HashMap<>();
    /** 지난 위험한 초대 참가자 */
    private final Set<UUID> lastInviteRound = new HashSet<>();
    private final Set<String> preNoticed = new HashSet<>();

    // 카지노 수락 받기
    private String casinoGame;
    private long casinoUntil;
    private final Set<UUID> casinoInvited = new HashSet<>();
    private final LinkedHashSet<UUID> casinoAccepted = new LinkedHashSet<>();

    public MinigameManager(KkaegulCore plugin) {
        this.plugin = plugin;
        this.cards = new CardDrawService(plugin);
        register(new PeperoGame(plugin));
        register(new DangerousInviteGame(plugin));
        register(new TreasureHuntGame(plugin));
        register(new kr.kkaegul.core.casino.holdem.PokerGame(plugin));
        register(new kr.kkaegul.core.casino.seotda.SeotdaGame(plugin));
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L * 10, 20L * 5);
    }

    private void register(Minigame g) { games.put(g.id, g); }

    public Map<String, Minigame> games() { return games; }

    public CardDrawService cards() { return cards; }

    public Minigame game(String id) { return games.get(id); }

    /** 진행 중인 게임 하나 (예전 호환용, 아무거나) */
    public Minigame current() {
        for (Minigame g : games.values()) if (g.running()) return g;
        return null;
    }

    public List<Minigame> running() {
        List<Minigame> out = new ArrayList<>();
        for (Minigame g : games.values()) if (g.running()) out.add(g);
        return out;
    }

    public Minigame gameOf(Player p) {
        for (Minigame g : games.values()) if (g.has(p)) return g;
        return null;
    }

    /** 게임 중이거나 초대받아 이동 대기 중 */
    public boolean busy(Player p) {
        if (gameOf(p) != null) return true;
        for (List<UUID> l : pending.values()) if (l.contains(p.getUniqueId())) return true;
        return false;
    }

    public boolean protects(Block b) {
        for (Minigame g : games.values()) if (g.running() && g.protects(b)) return true;
        return false;
    }

    // ───────── 일정 ─────────
    private long every(String key, long def) { return Math.max(1, plugin.getConfig().getLong("minigame.schedule." + key + ".every-minutes", def)) * 60_000L; }
    private long offset(String key, long def) { return Math.max(0, plugin.getConfig().getLong("minigame.schedule." + key + ".offset-minutes", def)) * 60_000L; }
    private boolean on(String key) { return plugin.getConfig().getBoolean("minigame.schedule." + key + ".enabled", true); }

    private static final String[] KEYS = {"invite", "treasure", "casino"};
    private static long defEvery(String k) { return k.equals("invite") ? 30 : 180; }
    private static long defOffset(String k) { return k.equals("casino") ? 90 : 0; }

    public long next(String key) {
        Long n = plugin.global().sched.get(key);
        if (n == null || n <= 0) {
            long off = offset(key, defOffset(key));
            long first = System.currentTimeMillis() + (off > 0 ? off : every(key, defEvery(key)));
            plugin.global().sched.put(key, first);
            plugin.global().dirty = true;
            return first;
        }
        return n;
    }

    private void tick() {
        if (!plugin.getConfig().getBoolean("minigame.auto.enabled", true)) return;
        long now = System.currentTimeMillis();
        boolean dayOk = plugin.clock().serverDay() >= plugin.getConfig().getInt("minigame.auto.from-day", 1);
        if (casinoGame != null && now >= casinoUntil) closeCasinoInvite();
        for (String k : KEYS) {
            long n = next(k);
            if (!on(k) || !dayOk) {   // 꺼져 있거나 오픈 전: 일정만 밀어 둠
                if (now >= n) bump(k, n, now);
                continue;
            }
            if (k.equals("invite") && now >= n - 60_000L && now < n && preNoticed.add(k + n)) {
                int cap = games.get("invite").capacity();
                plugin.announce("&71분 뒤 깨굴이의 위험한 초대장이 " + (cap == Integer.MAX_VALUE ? "누군가" : cap + "명") + "에게 날아갑니다…", false);
            }
            if (now < n) continue;
            bump(k, n, now);
            String r = fire(k);
            plugin.getLogger().info("[일정] " + k + " → " + r);
        }
    }

    private void bump(String k, long n, long now) {
        long ev = every(k, defEvery(k));
        while (n <= now) n += ev;
        plugin.global().sched.put(k, n);
        plugin.global().dirty = true;
    }

    private String fire(String key) {
        return switch (key) {
            case "invite" -> start("invite", null);
            case "treasure" -> start("treasure", null);
            case "casino" -> {
                String g = plugin.global().casinoTurn % 2 == 0 ? "poker" : "seotda";
                plugin.global().casinoTurn++;
                plugin.global().dirty = true;
                Minigame m = games.get(g);
                if (m == null || !m.enabled()) { g = g.equals("poker") ? "seotda" : "poker"; }
                yield openCasino(g);
            }
            default -> "?";
        };
    }

    /** /깨굴 게임 일정 */
    public List<String> scheduleLines() {
        SimpleDateFormat f = new SimpleDateFormat("MM/dd HH:mm");
        f.setTimeZone(TimeZone.getTimeZone("Asia/Seoul"));
        List<String> out = new ArrayList<>();
        String[] names = {"위험한 초대", "보물찾기", "카지노(" + (plugin.global().casinoTurn % 2 == 0 ? "홀덤" : "섯다") + ")"};
        for (int i = 0; i < KEYS.length; i++)
            out.add("&f" + names[i] + " &7다음 " + f.format(new Date(next(KEYS[i]))) + " · " + (every(KEYS[i], defEvery(KEYS[i])) / 60_000) + "분마다" + (on(KEYS[i]) ? "" : " &c(꺼짐)"));
        return out;
    }

    /** 다음 시각을 지금부터 N분 뒤로 */
    public void setNext(String key, long minutes) {
        plugin.global().sched.put(key, System.currentTimeMillis() + minutes * 60_000L);
        plugin.global().dirty = true;
    }

    // ───────── 시작 ─────────
    /** 랜덤 (관리자 명령) */
    public String startRandom() {
        List<Minigame> cand = new ArrayList<>();
        for (Minigame g : games.values()) {
            if (!g.enabled() || g.readiness() != null || g.running()) continue;
            if (g.timed()) continue;
            if (g instanceof PeperoGame && !plugin.clock().isPeperoSeason()) continue;
            cand.add(g);
        }
        Minigame g = Rng.pick(cand);
        if (g == null) return "열 수 있는 게임이 없어요 (위치 설정 확인: /kg game check)";
        return start(g.id, null);
    }

    /** chosen == null 이면 랜덤 초대. 카지노(포커·섯다)는 chosen 이 없으면 수락형 초대장 */
    public String start(String id, List<Player> chosen) {
        Minigame g = games.get(id);
        if (g == null) return "게임 없음: " + id;
        if (g.running() || pending.containsKey(id)) return "이미 진행 중(또는 초대 중): " + g.label();
        if (!g.enabled() && chosen == null) return g.label() + " 꺼져 있음";
        String why = g.readiness();
        if (why != null) return g.label() + " 준비 안 됨: " + why;
        if (g.timed() && chosen == null) return openCasino(id);
        List<Player> picked = chosen != null ? chosen : pickPlayers(g);
        final List<Player> list = picked.size() > g.capacity() ? new ArrayList<>(picked.subList(0, g.capacity())) : picked;
        if (chosen == null && g instanceof DangerousInviteGame && list.size() < Math.min(g.capacity(), plugin.getConfig().getInt("minigame.games.invite.invite-max", 4)))
            return g.label() + " 건너뜀: 새로 뽑을 사람이 부족 (" + list.size() + "명)";
        if (list.size() < g.minPlayers()) return g.label() + " 인원 부족 (" + list.size() + "/" + g.minPlayers() + ")";
        if (g instanceof DangerousInviteGame) { lastInviteRound.clear(); for (Player p : list) lastInviteRound.add(p.getUniqueId()); }

        if (g.timed()) {   // 카지노는 이동 없이 바로 창으로
            g.begin(list);
            return "시작: " + g.label() + " (" + list.size() + "명)";
        }
        List<UUID> ids = new ArrayList<>();
        for (Player p : list) ids.add(p.getUniqueId());
        pending.put(id, ids);
        StringBuilder names = new StringBuilder();
        for (Player p : list) names.append(plugin.displayName(p)).append(", ");
        String who = names.length() > 2 ? names.substring(0, names.length() - 2) : "";
        if (list.size() > 8) who = plugin.displayName(list.get(0)) + " 외 " + (list.size() - 1) + "명";
        plugin.announce("&f[깨굴이의 위험한 초대] " + g.label() + " ← " + who, true);
        for (Player p : list) {
            plugin.ui().fx(p, "invite_letter", Json.obj().put("title", "깨굴이의 위험한 초대").put("to", plugin.displayName(p)).put("place", g.label()));
            plugin.ui().center(p, "&f초대장이 도착했습니다", "&7" + g.label() + " (거부 불가)");
            plugin.wasd().cancel(p);
        }
        long delay = plugin.getConfig().getLong("minigame.invite-delay-ticks", 100);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            pending.remove(id);
            List<Player> still = new ArrayList<>();
            for (Player p : list) if (p.isOnline()) still.add(p);
            if (still.size() < g.minPlayers()) { plugin.announce("&7초대된 인원이 부족해 " + g.label() + "이(가) 취소됐어요.", false); return; }
            g.begin(still);
        }, delay);
        return "시작: " + g.label() + " (" + list.size() + "명)";
    }

    /** 초대 받을 수 있는 사람인지 (공통) */
    public boolean invitable(Player p) {
        if (p.isOp() || p.hasPermission("kkaegul.minigame.exempt")) return false;
        PlayerData d = plugin.data().get(p);
        if (d.inJail || plugin.mine().isInMine(p)) return false;
        return !busy(p);
    }

    private List<Player> pickPlayers(Minigame g) {
        List<Player> pool = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!invitable(p) || !g.eligible(p)) continue;
            if (g instanceof DangerousInviteGame && lastInviteRound.contains(p.getUniqueId())) continue;   // 지난 판 사람은 빼기
            pool.add(p);
        }
        Collections.shuffle(pool);
        int min = plugin.getConfig().getInt("minigame.games." + g.id + ".invite-min", plugin.getConfig().getInt("minigame.invite-min", 2));
        int max = plugin.getConfig().getInt("minigame.games." + g.id + ".invite-max", plugin.getConfig().getInt("minigame.invite-max", 6));
        max = Math.min(max, g.capacity());
        min = Math.min(min, max);
        int n = Math.min(pool.size(), Rng.range(min, max));
        if (g instanceof PeperoGame && n % 2 == 1 && n > 1 && n < pool.size()) n++;
        return new ArrayList<>(pool.subList(0, n));
    }

    // ───────── 카지노 수락형 초대 ─────────
    private int acceptSeconds() { return Math.max(15, plugin.getConfig().getInt("minigame.casino.accept-seconds", 60)); }

    public String openCasino(String id) {
        Minigame g = games.get(id);
        if (g == null) return "게임 없음: " + id;
        if (casinoGame != null) return "이미 카지노 초대 중";
        if (g.running()) return g.label() + " 이미 진행 중";
        List<Player> pool = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) if (invitable(p) && g.eligible(p)) pool.add(p);
        if (pool.size() < g.minPlayers()) return g.label() + " 초대 건너뜀: 받을 수 있는 사람 " + pool.size() + "명";
        casinoGame = id;
        casinoUntil = System.currentTimeMillis() + acceptSeconds() * 1000L;
        casinoInvited.clear();
        casinoAccepted.clear();
        int min = plugin.getConfig().getInt("minigame.casino.min-held", 50);
        int mins = g.durationMinutes();
        plugin.announce("&f[깨굴 카지노] " + g.label() + " 초대장이 왔어요! &7(다이아 " + min + "개 이상 · " + acceptSeconds() + "초 안에 수락)", true);
        for (Player p : pool) {
            casinoInvited.add(p.getUniqueId());
            plugin.ui().fx(p, "invite_letter", Json.obj().put("title", "깨굴 카지노 초대").put("to", plugin.displayName(p)).put("place", g.label()));
            p.playSound(p.getLocation(), "minecraft:block.note_block.chime", 1f, 1.2f);
            plugin.chat().invite(p, "&f{e:system} &f" + g.label() + " 초대장이 도착했어요 &7(판돈 1다이아 · 가진 다이아 전부 칩 · 최대 " + mins + "분) ",
                    "&a[수락]", "/수락", "&7눌러서 " + g.label() + " 참가");
        }
        return g.label() + " 초대장 " + pool.size() + "명";
    }

    /** /수락 */
    public void accept(Player p) {
        if (casinoGame == null || !casinoInvited.contains(p.getUniqueId())) { plugin.ui().error(p, "&c받은 초대장이 없어요."); return; }
        Minigame g = games.get(casinoGame);
        if (casinoAccepted.contains(p.getUniqueId())) { p.sendMessage(Txt.color(Txt.notice("&7이미 수락했어요. 곧 시작해요!"))); return; }
        if (gameOf(p) != null) { plugin.ui().error(p, "&c다른 게임 중이라 참가할 수 없어요."); return; }
        if (g != null && !g.eligible(p)) { plugin.ui().error(p, "&c보유 다이아가 " + plugin.getConfig().getInt("minigame.casino.min-held", 50) + "개 이상이어야 해요."); return; }
        casinoAccepted.add(p.getUniqueId());
        plugin.ui().successSound(p);
        long left = Math.max(0, (casinoUntil - System.currentTimeMillis()) / 1000);
        p.sendMessage(Txt.color(Txt.notice("&f수락했어요! " + left + "초 뒤 " + (g == null ? "" : g.label()) + " 테이블이 열려요.")));
    }

    private void closeCasinoInvite() {
        String id = casinoGame;
        casinoGame = null;
        Minigame g = games.get(id);
        List<Player> list = new ArrayList<>();
        for (UUID u : casinoAccepted) {
            Player p = Bukkit.getPlayer(u);
            if (p != null && p.isOnline() && gameOf(p) == null && (g == null || g.eligible(p))) list.add(p);
        }
        casinoInvited.clear();
        casinoAccepted.clear();
        if (g == null) return;
        if (list.size() < g.minPlayers()) {
            plugin.announce("&7[깨굴 카지노] 수락한 사람이 부족해서 " + g.label() + "이(가) 열리지 않았어요.", false);
            for (Player p : list) plugin.ui().error(p, "&c수락한 사람이 부족해서 테이블이 열리지 않았어요.");
            return;
        }
        g.begin(list);
    }

    public void onEnded(Minigame g) { pending.remove(g.id); }

    public void stop() {
        for (Minigame g : games.values()) if (g.running()) g.end();
        pending.clear();
        casinoGame = null;
    }

    public void stop(String id) {
        Minigame g = games.get(id);
        if (g != null && g.running()) g.end();
        pending.remove(id);
        if (id.equals(casinoGame)) casinoGame = null;
    }
}
