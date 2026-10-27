package school.chacademia.housing;

import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import school.magiccodex.protocol.HousingProtocol;
import school.magiccodex.protocol.HousingProtocol.*;
import school.magiccodex.protocol.HousingRules.Layout;
import school.magiccodex.protocol.SchoolProtocol;

/**
 * 누가 어느 방을 보고 있는지 (이 서버), 요청 처리, 다른 서버와 구경 중 명단·방 변경 맞추기.
 * 방이 바뀌면(저장·좋아요·방명록·방문·확장) DB 의 rev 가 올라가고, 그 방을 보고 있는 모든 서버가 다음 동기화 때 다시 보낸다.
 */
final class Rooms {
    /** home = 이 창을 기숙사실 문(또는 /테스트 하우징 내방)으로 열었음 → 내 방은 편집, 남의 방에서 내 방으로 돌아갈 수 있음. */
    record Session(UUID owner, boolean edit, boolean home, long token) {}

    private final HousingPlugin plugin;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, Integer> catalogSent = new HashMap<>();
    private final Map<UUID, Long> knownRev = new HashMap<>();
    private final Map<UUID, List<Viewer>> lastViewers = new HashMap<>();
    private final Map<UUID, Person> people = new HashMap<>();
    /** 방 크기 캐시 (접속·방 읽기 때 채움, /유저관리 보기 요약용). */
    private final Map<UUID, Integer> sizes = new HashMap<>();
    private final Map<UUID, Long> lastAction = new HashMap<>(), lastPost = new HashMap<>();
    private long tokens;
    private boolean syncing;

    Rooms(HousingPlugin plugin) { this.plugin = plugin; }

    // ------------------------------------------------------------ 접속
    void join(Player p) {
        var skin = Skins.of(p);
        var catalog = plugin.catalog();
        plugin.store().touch(p.getUniqueId(), p.getName(), fallbackHouse(p), skin.hash(), skin.slim(), plugin.settings().startSize(), catalog::get, catalog.allowed(p), row -> {
            if (row != null) { people.put(p.getUniqueId(), row.person()); sizes.put(p.getUniqueId(), row.size()); }
        });
        plugin.store().heartbeat(plugin.settings().server(), List.of(p.getUniqueId()));
    }
    void quit(Player p) {
        UUID id = p.getUniqueId();
        openRequests.remove(id);sessions.remove(id); catalogSent.remove(id); people.remove(id); sizes.remove(id); lastAction.remove(id); lastPost.remove(id);
        plugin.store().offline(id, plugin.settings().server());
    }
    /** 기숙사 문 검사용 (아직 못 읽었으면 -2, 기숙사 없음 -1). */
    int house(UUID id) { var p = people.get(id); return p == null ? -2 : p.house(); }
    /** 캐시된 방 크기 (모르면 null). */
    Integer size(UUID id) { return sizes.get(id); }
    void size(UUID id, int size) { if (Bukkit.getPlayer(id) != null) sizes.put(id, size); }

    static final List<String> HOUSE_PERMISSIONS = List.of("arkeon", "lumina", "bestiaz", "noxer");
    /**
     * DB(codex_houses)에 기숙사가 없을 때 MagicCodexBridge 와 같은 방법으로 찾는다:
     * Bridge StatsService 의 dormitory → magiccodex.house.&lt;영문&gt; 권한. 없으면 -1.
     */
    static int fallbackHouse(Player p) {
        try {
            for (Class<?> type : Bukkit.getServicesManager().getKnownServices()) {
                if (!type.getName().equals("school.magiccodex.paper.StatsService")) continue;
                @SuppressWarnings({"rawtypes", "unchecked"}) var reg = Bukkit.getServicesManager().getRegistration((Class) type);
                if (reg == null) break;
                Object extra = type.getMethod("get", UUID.class).invoke(reg.getProvider(), p.getUniqueId());
                Object dorm = extra.getClass().getMethod("dormitory").invoke(extra);
                int i = dorm == null ? -1 : SchoolProtocol.HOUSES.indexOf(dorm.toString());
                if (i >= 0) return i;
                break;
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) { /* Bridge 없음 */ }
        for (int i = 0; i < HOUSE_PERMISSIONS.size(); i++) if (p.hasPermission("magiccodex.house." + HOUSE_PERMISSIONS.get(i))) return i;
        return -1;
    }
    Person person(Player p) {
        var known = people.get(p.getUniqueId());
        if (known != null) return known;
        var skin = Skins.of(p);
        return new Person(p.getUniqueId(), "닉네임 확인 중", -1, skin.hash(), skin.slim());
    }
    boolean searchSession(Player p){return sessions.containsKey(p.getUniqueId());}
    void catalogChanged() { catalogSent.clear(); }

    // ------------------------------------------------------------ 열기·닫기
    /** @param home 기숙사실 문·/테스트 하우징 내방 으로 열었는지 (또는 그렇게 연 창 안에서 이동했는지) */
    private final Map<UUID,Long> openRequests=new HashMap<>();
    void open(Player p,UUID owner,boolean home){
        UUID viewer=p.getUniqueId();long request=++tokens;openRequests.put(viewer,request);
        if(HousingVisitPolicy.immediate(viewer,owner,p.isOp())){openAllowed(p,owner,home);return;}
        plugin.store().isFriend(viewer,owner,friend->{if(Bukkit.getPlayer(viewer)!=p||!Objects.equals(openRequests.get(viewer),request))return;openRequests.remove(viewer);if(HousingVisitPolicy.allowed(viewer,owner,p.isOp(),friend))openAllowed(p,owner,home);else plugin.net().result(p,false,friend==null?"친구 방문 권한을 확인하지 못했어요.":"친구의 방만 방문할 수 있어요.");});
    }
    /** #51 하우징 랭킹에서 방 이동 (오너 결정 2026-10-25): 친구가 아니어도 구경 가능. 랭킹 창에서만 쓴다 — /방구경 은 여전히 친구만. */
    void openFromRanking(Player p, UUID owner) { openRequests.remove(p.getUniqueId()); openAllowed(p, owner, false); }
    private void openAllowed(Player p, UUID owner, boolean home) {
        if (!plugin.net().hasMod(p)) { ChacaLink.notify(p, "warning", "housing-nomod", "방 꾸미기를 열 수 없어요", "차카데미 클라이언트에서만 열 수 있어요", 2000); return; }
        UUID id = p.getUniqueId();
        var catalog = plugin.catalog();
        if (!Objects.equals(catalogSent.get(id), catalog.revision())) { plugin.net().send(p, catalog.message()); catalogSent.put(id, catalog.revision()); }
        // 기숙사를 아직 몰랐는데 지금은 알 수 있으면 (Bridge 가 늦게 채운 경우) 방을 읽기 전에 먼저 고쳐 둔다 (같은 DB 작업 줄이라 순서 보장).
        if (owner.equals(id) && house(id) < 0 && fallbackHouse(p) >= 0) join(p);
        var session = new Session(owner, home && owner.equals(id), home, ++tokens);
        var previous = sessions.put(id, session);
        load(p, session, true, true, previous);
    }
    void close(Player p) {
        openRequests.remove(p.getUniqueId());
        if (sessions.remove(p.getUniqueId()) != null) plugin.store().leave(p.getUniqueId());
    }
    Session session(UUID viewer) { return sessions.get(viewer); }

    /** 방을 읽어 보낸다. 창이 그 사이에 닫혔거나 다른 방으로 옮겼으면 버린다. */
    private void load(Player p, Session s, boolean open, boolean guestbook) { load(p, s, open, guestbook, null); }
    /** @param previous 방을 못 찾았을 때 되돌릴 이전 창 (친구 방으로 가다가 실패하면 보던 방에 그대로 남는다) */
    private void load(Player p, Session s, boolean open, boolean guestbook, Session previous) {
        UUID id = p.getUniqueId();
        plugin.store().load(s.owner(), id, open && !s.owner().equals(id), plugin.settings().visitOnceEver(), data -> {
            if (!p.isOnline() || sessions.get(id) != s) return;
            if (data == null) {
                if (previous != null) sessions.put(id, previous); else sessions.remove(id);
                plugin.net().result(p, false, "방 또는 등록 닉네임을 불러오지 못했어요. 다시 시도해 주세요.");
                return;
            }
            knownRev.put(s.owner(), data.row().rev());
            size(s.owner(), data.row().size());
            plugin.net().send(p, new RoomMessage(room(data, s, open, p)));
            // 스토리 "내방열기": 내 방을 DB 에서 읽어 창을 연 뒤에만 (남의 방 구경·갱신 재전송 제외)
            if (open && s.owner().equals(id)) StoryProgressEvent.fire(p, StoryProgressEvent.OWN_ROOM);
            if (guestbook) sendGuestbook(p, s);
            // #108: 방에 들어갈 때 한 번 — 주인 전시 값 (빗자루 거치대·아티팩트 진열장)
            if (open) plugin.showcases().send(p, s.owner(), () -> sessions.get(id) == s);
            var viewers = lastViewers.get(s.owner());
            if (viewers != null) sendViewers(p, s.owner(), viewers);
        });
    }
    private void sendGuestbook(Player p, Session s) {
        plugin.store().guestbook(s.owner(), HousingProtocol.MAX_GUESTBOOK, entries -> {
            if(p.isOnline()&&sessions.get(p.getUniqueId())==s){if(entries==null)plugin.net().result(p,false,"방명록 이름을 불러오지 못했어요. 다시 시도해 주세요.");else plugin.net().send(p,new Guestbook(s.owner(),entries));}
        });
    }
    private Room room(HousingStore.RoomData d, Session s, boolean open, Player viewer) {
        var row = d.row();
        int flags = (open ? HousingProtocol.F_OPEN : 0) | (s.edit() ? HousingProtocol.F_EDIT : 0) | (d.liked() ? HousingProtocol.F_LIKED : 0) | (s.home() ? HousingProtocol.F_HOME : 0);
        var next = plugin.settings().next(row.size());
        Map<Integer, Layout> layouts = new HashMap<>();
        if (s.edit()) layouts.putAll(d.layouts());
        else layouts.put(row.active(), d.layouts().getOrDefault(row.active(), Layout.EMPTY));
        Map<String, Integer> owned = s.edit() ? limit(plugin.catalog().allowed(viewer)) : Map.of();
        return new Room(flags, row.person(), row.size(), next == null ? 0 : next.getKey(), next == null ? 0 : next.getValue(),
                row.likes(), d.today(), row.visits(), row.active(), layouts, owned);
    }
    private static Map<String, Integer> limit(Map<String, Integer> owned) {
        if (owned.size() <= HousingProtocol.MAX_OWNED) return owned;
        var map = new TreeMap<String, Integer>();
        for (var e : new TreeMap<>(owned).entrySet()) { if (map.size() >= HousingProtocol.MAX_OWNED) break; map.put(e.getKey(), e.getValue()); }
        return map;
    }

    /** 이 서버에서 owner 방을 보고 있는 모든 사람에게 다시 보낸다 (방명록 포함). */
    void refresh(UUID owner) {
        for (var e : new ArrayList<>(sessions.entrySet())) {
            if (!e.getValue().owner().equals(owner)) continue;
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null) load(p, e.getValue(), false, true);
        }
    }

    // ------------------------------------------------------------ 요청
    void handle(Player p, Request r) {
        if (WindowlessVisit.handle(this, p, r)) return; // 정보창 "내 방 가기" 등 창 없이 온 VISIT (2026-10-27)
        UUID id = p.getUniqueId();
        Session s = sessions.get(id);
        if (r instanceof Close) { close(p); return; }
        if (s == null) return; // 창이 열려 있지 않으면 무시 (창은 서버가 연다)
        long now = System.currentTimeMillis();
        if (now - lastAction.getOrDefault(id, 0L) < 300) { plugin.net().result(p, false, "잠시 후 다시 눌러 주세요."); return; }
        lastAction.put(id, now);
        switch (r) {
            case Visit v -> {
                if (v.owner().equals(id) && s.home()) open(p, id, true);
                else open(p, v.owner(), s.home());
            }
            case Save save -> save(p, s, save);
            case Expand e -> expand(p, s, e.currentSize());
            case Like like -> {
                if (!like.owner().equals(s.owner())) return;
                if (like.owner().equals(id)) { plugin.net().result(p, false, "내 방에는 좋아요를 누를 수 없어요."); return; }
                // #78: 한 번만 (취소 없음). like.limit 이 global 이면 모든 방 통틀어 한 번.
                plugin.store().like(s.owner(), id, plugin.settings().likeGlobal(), why -> {
                    if (why == null) { plugin.net().result(p, false, "좋아요를 누르지 못했어요."); return; }
                    plugin.net().result(p, why.isEmpty(), why.isEmpty() ? "좋아요를 눌렀어요." : why);
                    if (why.isEmpty()) refresh(s.owner());
                });
            }
            case Post post -> post(p, s, post);
            case Delete d -> {
                if (!d.owner().equals(s.owner())) return;
                plugin.store().deleteEntry(s.owner(), d.entry(), id, p.isOp(), why -> {
                    plugin.net().result(p, why == null, why == null ? "방명록 글을 지웠어요." : why);
                    if (why == null) refresh(s.owner());
                });
            }
            case Friends f -> plugin.store().friends(id, HousingProtocol.MAX_FRIENDS, list -> {
                if (p.isOnline() && sessions.get(id)==s) { if(list==null)plugin.net().result(p,false,"유저 이름을 불러오지 못했어요. 다시 시도해 주세요.");else plugin.net().send(p,new FriendList(list)); }
            });
            case Close c -> close(p);
        }
    }

    private void save(Player p, Session s, Save save) {
        if (!s.edit()) { plugin.net().result(p, false, "내 방에서만 꾸밀 수 있어요."); return; }
        plugin.store().save(s.owner(), save.slot(), save.layout(), plugin.catalog()::get, plugin.catalog().allowed(p), why -> {
            plugin.net().result(p, why == null, why == null ? "저장했어요. 방문한 친구에게는 레이아웃 " + (save.slot() + 1) + "이(가) 보여요." : why);
            if (why == null) refresh(s.owner());
        });
    }

    /** 확장 결제 진행 중인 방 (두 번 눌러 두 번 결제되지 않게). */
    private final Set<UUID> expanding = new HashSet<>();

    /**
     * 방 확장 (A03, 2026-10-21): 원장에 거래를 먼저 기록 → Vault 출금 → 한 트랜잭션으로 방 반영 → 완료.
     * 크기가 이미 바뀌었으면 입금으로 환불하고, 입금이 확인될 때만 "돌려드렸어요"라고 말한다. 결과를 모르면 uncertain 으로 두고 관리자에게 넘긴다.
     */
    private void expand(Player p, Session s, int from) {
        if (!s.edit()) { plugin.net().result(p, false, "내 방에서만 넓힐 수 있어요."); return; }
        var next = plugin.settings().next(from);
        if (next == null) { plugin.net().result(p, false, "더 넓힐 수 없어요."); return; }
        long cost = next.getValue(); int to = next.getKey();
        if (cost > 0 && !Economy.available()) { plugin.net().result(p, false, "경제 서비스 연결이 필요해요. 관리자에게 알려 주세요."); return; }
        double balance = cost > 0 ? Economy.balance(p) : 0;
        if (balance >= 0 && balance < cost) { plugin.net().result(p, false, ExpandLedger.message(ExpandLedger.CANCELLED, to, cost)); return; }
        UUID owner = s.owner();
        if (!expanding.add(owner)) { plugin.net().result(p, false, "방을 넓히는 중이에요. 잠시만 기다려 주세요."); return; }
        String tx = UUID.randomUUID().toString(), origin = plugin.settings().server();
        var log = plugin.getLogger();
        plugin.store().expandPrepare(tx, owner, from, to, cost, origin, prepared -> {
            if (!Boolean.TRUE.equals(prepared)) { expanding.remove(owner); plugin.net().result(p, false, "지금은 넓힐 수 없어요. 잠시 후 다시 시도해 주세요. (돈은 빠지지 않았어요)"); return; }
            if (!p.isOnline() || plugin.store().closing()) { expanding.remove(owner); plugin.store().expandMove(tx, ExpandLedger.PREPARED, ExpandLedger.CANCELLED, "출금 전 접속 종료", null); return; }
            var pay = Economy.withdrawResult(p, cost);
            log.info("HOUSING EXPAND tx=" + tx + " player=" + owner + " " + from + "->" + to + " cost=" + cost + " withdraw=" + pay);
            if (pay == Economy.Pay.REFUSED) {
                expanding.remove(owner);
                plugin.store().expandMove(tx, ExpandLedger.PREPARED, ExpandLedger.CANCELLED, "출금 거절", null);
                plugin.net().result(p, false, ExpandLedger.message(ExpandLedger.CANCELLED, to, cost));
                return;
            }
            if (pay == Economy.Pay.UNCERTAIN) {
                expanding.remove(owner);
                log.severe("HOUSING EXPAND tx=" + tx + " 출금 결과 모름 (Vault 예외) → uncertain, 관리자 확인 필요");
                plugin.store().expandMove(tx, ExpandLedger.PREPARED, ExpandLedger.UNCERTAIN, "출금 결과 모름", null);
                plugin.net().result(p, false, "결제 결과를 확인하지 못했어요. 다시 시도하지 말고 관리자에게 알려 주세요. (거래 " + tx.substring(0, 8) + ")");
                return;
            }
            plugin.store().expandMove(tx, ExpandLedger.PREPARED, ExpandLedger.WITHDRAWN, "출금됨", moved ->
                plugin.store().expandApply(tx, state -> finishExpand(p, owner, tx, to, cost, state)));
        });
    }

    private void finishExpand(Player p, UUID owner, String tx, int to, long cost, String state) {
        var log = plugin.getLogger();
        if (ExpandLedger.APPLIED.equals(state)) {
            expanding.remove(owner);
            plugin.store().expandMove(tx, ExpandLedger.APPLIED, ExpandLedger.DONE, "넓힘", null);
            size(owner, to);
            if (p.isOnline()) plugin.net().result(p, true, ExpandLedger.message(ExpandLedger.APPLIED, to, cost));
            refresh(owner);
            return;
        }
        if (!ExpandLedger.REFUND_PENDING.equals(state)) {
            // null = DB 오류(반영됐는지 모름), 그 밖 = 이미 다른 단계: 돈을 더 움직이지 않는다.
            expanding.remove(owner);
            log.severe("HOUSING EXPAND tx=" + tx + " 반영 결과 모름 (state=" + state + ") → uncertain, 관리자 확인 필요");
            plugin.store().expandMove(tx, ExpandLedger.WITHDRAWN, ExpandLedger.UNCERTAIN, "출금됨, 반영 결과 모름", moved -> {
                if (!Boolean.TRUE.equals(moved)) plugin.store().expandMove(tx, ExpandLedger.PREPARED, ExpandLedger.UNCERTAIN, "출금됨, 반영 결과 모름", null);
            });
            if (p.isOnline()) plugin.net().result(p, false, ExpandLedger.message(ExpandLedger.UNCERTAIN, to, cost) + " (거래 " + tx.substring(0, 8) + ")");
            refresh(owner);
            return;
        }
        if (plugin.store().closing()) { expanding.remove(owner); log.severe("HOUSING EXPAND tx=" + tx + " 종료 중이라 환불하지 않음 → 다음 시작 때 uncertain"); return; }
        var refund = Economy.depositResult(p, cost);
        expanding.remove(owner);
        log.info("HOUSING EXPAND tx=" + tx + " refund=" + refund);
        if (refund == Economy.Pay.OK) {
            plugin.store().expandMove(tx, ExpandLedger.REFUND_PENDING, ExpandLedger.DONE, "환불함", null);
            if (p.isOnline()) plugin.net().result(p, false, ExpandLedger.message("refunded", to, cost));
        } else {
            log.severe("HOUSING EXPAND tx=" + tx + " 환불 실패 (" + refund + ") " + p.getName() + " " + cost + " → uncertain, 관리자 확인 필요");
            plugin.store().expandMove(tx, ExpandLedger.REFUND_PENDING, ExpandLedger.UNCERTAIN, "환불 " + (refund == Economy.Pay.REFUSED ? "거절" : "결과 모름"), null);
            if (p.isOnline()) plugin.net().result(p, false, ExpandLedger.message(ExpandLedger.UNCERTAIN, to, cost) + " (거래 " + tx.substring(0, 8) + ")");
        }
        refresh(owner);
    }

    private void post(Player p, Session s, Post post) {
        if (!post.owner().equals(s.owner())) return;
        String text = HousingProtocol.cleanText(post.text());
        if (text.isEmpty()) { plugin.net().result(p, false, "한마디를 적어 주세요."); return; }
        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis(), wait = plugin.settings().guestbookCooldownMs() - (now - lastPost.getOrDefault(id, 0L));
        if (wait > 0) { plugin.net().result(p, false, "방명록은 " + ((wait + 999) / 1000) + "초 뒤에 다시 쓸 수 있어요."); return; }
        lastPost.put(id, now);
        plugin.store().post(s.owner(), person(p), text, plugin.settings().guestbookKeep(), ok -> {
            plugin.net().result(p, ok != null, ok != null ? "방명록을 남겼어요." : "방명록을 남기지 못했어요.");
            if (ok != null) refresh(s.owner());
        });
    }

    // ------------------------------------------------------------ 다른 서버와 맞추기
    void sync() {
        if (syncing) return;
        var local = new ArrayList<HousingStore.Presence>();
        var owners = new HashSet<UUID>();
        for (var e : sessions.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null) continue;
            local.add(new HousingStore.Presence(e.getKey(), e.getValue().owner(), person(p).name()));
            owners.add(e.getValue().owner());
        }
        lastViewers.keySet().retainAll(owners); knownRev.keySet().retainAll(owners);
        syncing = true;
        plugin.store().sync(plugin.settings().server(), local, owners, result -> {
            syncing = false;
            if (result == null) return;
            for (UUID owner : owners) {
                var viewers = result.viewers().getOrDefault(owner, List.of());
                if (!viewers.equals(lastViewers.get(owner))) {
                    lastViewers.put(owner, viewers);
                    for (var e : sessions.entrySet()) {
                        if (!e.getValue().owner().equals(owner)) continue;
                        Player p = Bukkit.getPlayer(e.getKey());
                        if (p != null) sendViewers(p, owner, viewers);
                    }
                }
                Long rev = result.revs().get(owner);
                if (rev != null && !rev.equals(knownRev.get(owner))) { knownRev.put(owner, rev); refresh(owner); }
            }
        });
    }
    private void sendViewers(Player p, UUID owner, List<Viewer> all) {
        var shown = all.size() > HousingProtocol.MAX_VIEWERS_SHOWN ? all.subList(0, HousingProtocol.MAX_VIEWERS_SHOWN) : all;
        plugin.net().send(p, new Viewers(owner, all.size(), shown));
    }
    void heartbeat() {
        var ids = new ArrayList<UUID>();
        for (var p : Bukkit.getOnlinePlayers()) ids.add(p.getUniqueId());
        plugin.store().heartbeat(plugin.settings().server(), ids);
    }
    void shutdown() {
        for (var id : new ArrayList<>(sessions.keySet())) plugin.store().leave(id);
        sessions.clear();
    }
    int open() { return sessions.size(); }
}
