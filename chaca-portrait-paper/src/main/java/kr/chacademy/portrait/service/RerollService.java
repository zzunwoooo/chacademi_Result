package kr.chacademy.portrait.service;

import io.papermc.paper.event.player.AsyncChatEvent;
import kr.chacademy.portrait.ChacaPortraitPlugin;
import kr.chacademy.portrait.core.PortraitSettings;
import kr.chacademy.portrait.core.PromptBuilder;
import kr.chacademy.portrait.data.PortraitStorage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import school.magiccodex.portrait.PortraitProtocol;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 다시 그리기 아이템 (ItemsAdder item:cutscene_ticket, 컷신 변경권 우클릭).
 * 흐름: 우클릭 → 입력창(HUD) 또는 채팅 입력 → 문장 검사 → 같은 칸에 아이템이 그대로 있으면 1개 차감 → 기록(PENDING) → 대기열
 * → 작업 차례가 와서 첫 유료 호출 직전에 STARTED("생성 시작") → 성공하면 DONE.
 * <p>정책(운영 결정): 시도 자체가 아이템을 쓴다.
 * 생성 시작 전의 실패(문장 거부·예산 부족·대기열 가득·스킨/설정 문제·시작 전 서버 종료)는 REFUND_DUE → 접속 중이면 바로,
 * 아니면 차감했던 서버에 다음 접속 때 돌려준다. 생성이 시작된 뒤(STARTED)의 실패(API 오류·시간 초과·안전 거부·결과 검사 실패·결과 폐기)는
 * CONSUMED로 끝나고 아이템을 돌려주지 않는다. 쿨다운·하루 횟수도 성공이 아니라 "시작된 시도" 기준이다.
 * 서버가 꺼졌다 켜지면 이 서버의 PENDING은 REFUND_DUE로, STARTED는 CONSUMED로 바꾸고 다음 접속 때 안내한다.
 * 아이템 차감 후 기록 전에 서버가 꺼지는 아주 짧은 구간은 복제 방지를 위해 돌려주지 않는다 (관리자 지급으로 처리).
 */
public final class RerollService implements Listener {

    private record Pending(String token, int slot, ItemStack snapshot, long expiresAt, boolean chat) {
    }

    private final ChacaPortraitPlugin plugin;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    /** 문장 검사 중 (중복 제출 방지). */
    private final Map<UUID, String> checking = new ConcurrentHashMap<>();
    private final Map<UUID, Long> clickGate = new ConcurrentHashMap<>();
    /** 인벤토리가 가득 차 반환을 미룬다는 안내를 마지막으로 보낸 시각 (1분에 한 번만). */
    private final Map<UUID, Long> fullNotice = new ConcurrentHashMap<>();

    /** 입력창을 열기 전 확인 결과: {마지막 시도 시각, 오늘 시도 횟수}, 예산이 남았는지. */
    private record Check(long[] usage, boolean budget) {
    }

    public RerollService(ChacaPortraitPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player p = e.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();
        PortraitSettings s = plugin.settings();
        String id = plugin.itemsAdder().idOf(hand);
        if (id == null || !id.equalsIgnoreCase(s.rerollItemId)) {
            return;
        }
        e.setCancelled(true);
        long now = System.currentTimeMillis();
        Long last = clickGate.get(p.getUniqueId());
        if (last != null && now - last < 700) {
            return;
        }
        clickGate.put(p.getUniqueId(), now);
        begin(p);
    }

    private void begin(Player p) {
        PortraitSettings s = plugin.settings();
        UUID uuid = p.getUniqueId();
        // 유료 호출 없이 알 수 있는 문제(설정·키·레퍼런스·단가)는 아이템을 건드리기 전에 걸러낸다
        if (plugin.service().staticProblem() != null) {
            p.sendMessage(s.message("disabled"));
            return;
        }
        if (!p.hasPermission("chacaportrait.reroll")) {
            return;
        }
        if (plugin.service().busy(uuid) || checking.containsKey(uuid)) {
            p.sendMessage(s.message("reroll-busy"));
            return;
        }
        if (plugin.service().waitingCount() >= s.maxWaiting) {
            p.sendMessage(s.message("reroll-busy"));
            return;
        }
        if (plugin.service().paused()) {
            p.sendMessage(s.message("reroll-unavailable")); // OpenAI 장애로 쉬는 중
            return;
        }
        if (kr.chacademy.portrait.skin.SkinFetcher.refOf(p).url() == null) {
            p.sendMessage(s.message("reroll-no-skin"));
            return;
        }
        int slot = p.getInventory().getHeldItemSlot();
        ItemStack snapshot = p.getInventory().getItem(slot);
        if (snapshot == null) {
            return;
        }
        ItemStack one = snapshot.clone();
        one.setAmount(1);
        String day = LocalDate.now(PortraitService.ZONE).toString();
        plugin.service().then(plugin.db().call(() ->
                new Check(plugin.storage().rerollUsage(uuid, day), plugin.service().budgetAvailable(s))), check -> {
            if (!p.isOnline()) {
                return;
            }
            long[] usage = check.usage();
            long wait = usage[0] + s.rerollCooldownSeconds * 1000L - System.currentTimeMillis();
            if (wait > 0) {
                p.sendMessage(s.message("reroll-cooldown", "seconds", (wait + 999) / 1000));
                return;
            }
            if (s.rerollDailyLimit > 0 && usage[1] >= s.rerollDailyLimit) {
                p.sendMessage(s.message("reroll-daily"));
                return;
            }
            if (!check.budget()) {
                p.sendMessage(s.message("budget")); // 아이템은 건드리지 않았다
                return;
            }
            String token = PortraitService.newId();
            boolean hud = plugin.channel().capable(p);
            pending.put(uuid, new Pending(token, slot, one, System.currentTimeMillis() + s.promptTimeoutSeconds * 1000L, !hud));
            if (hud) {
                plugin.channel().openPrompt(p, token, "원하는 표정·포즈·분위기를 적어 주세요. 비워 두면 기본 그림이에요.");
            } else {
                p.sendMessage(s.message("reroll-prompt-chat"));
            }
            // 실패해도 아이템이 사라진다는 안내 (입력창에도 같은 문구가 보인다)
            p.sendMessage(s.message("reroll-notice"));
        }, ex -> p.sendMessage(s.message("disabled")));
    }

    /** HUD 입력창 응답 (메인 스레드). */
    public void answer(Player p, String token, String text, boolean proceed) {
        Pending pd = pending.get(p.getUniqueId());
        if (pd == null || !pd.token().equals(token)) {
            return;
        }
        handle(p, pd, text, proceed);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        Pending pd = pending.get(e.getPlayer().getUniqueId());
        if (pd == null || !pd.chat()) {
            return;
        }
        e.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(e.message()).strip();
        Player p = e.getPlayer();
        plugin.main(() -> {
            Pending now = pending.get(p.getUniqueId());
            if (now == null || !now.token().equals(pd.token())) {
                return;
            }
            if (text.equals("취소")) {
                handle(p, now, "", false);
            } else {
                handle(p, now, text.equals("기본") ? "" : text, true);
            }
        });
    }

    private void handle(Player p, Pending pd, String text, boolean proceed) {
        PortraitSettings s = plugin.settings();
        UUID uuid = p.getUniqueId();
        if (System.currentTimeMillis() > pd.expiresAt()) {
            pending.remove(uuid, pd);
            p.sendMessage(s.message("reroll-cancelled"));
            return;
        }
        if (!proceed) {
            pending.remove(uuid, pd);
            p.sendMessage(s.message("reroll-cancelled"));
            return;
        }
        String request = text == null ? "" : text.strip();
        if (!PortraitProtocol.validPrompt(request)) {
            p.sendMessage(s.message("reroll-rejected"));
            return; // 입력은 유지 (다시 보낼 수 있음)
        }
        if (checking.putIfAbsent(uuid, pd.token()) != null) {
            return;
        }
        pending.remove(uuid, pd);
        if (request.isEmpty() || !s.moderationEnabled) {
            take(p, pd, request);
            return;
        }
        String clean = PromptBuilder.clean(request, PortraitProtocol.MAX_PROMPT_CHARS);
        // 문장 검사는 플러그인 보조 실행기에서 (공용 ForkJoin 풀을 쓰지 않는다). 결과: true 통과 / false 거부 / null 검사 서버 장애.
        java.util.concurrent.CompletableFuture<Boolean> check;
        try {
            check = java.util.concurrent.CompletableFuture.<Boolean>supplyAsync(() -> {
                try {
                    return plugin.ai().flagged(s, clean) ? Boolean.FALSE : Boolean.TRUE;
                } catch (Exception ex) {
                    plugin.getLogger().info("[ChacaPortrait] 문장 검사를 하지 못함 (검사 서버 장애) → 잠시 후 다시 시도 안내: " + ex.getMessage());
                    return null;
                }
            }, plugin.service().aux());
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            check = java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        plugin.service().then(check.orTimeout(30, TimeUnit.SECONDS), ok -> {
            if (ok == null) {
                unavailable(p, pd);
                return;
            }
            if (!ok) {
                rejected(p, pd);
                return;
            }
            take(p, pd, clean);
        }, ex -> unavailable(p, pd));
    }

    /** 문장이 거부됨: 같은 토큰으로 다시 입력받는다 (아이템은 그대로). */
    private void rejected(Player p, Pending pd) {
        UUID uuid = p.getUniqueId();
        checking.remove(uuid);
        if (!p.isOnline()) {
            return;
        }
        PortraitSettings s = plugin.settings();
        p.sendMessage(s.message("reroll-rejected"));
        if (System.currentTimeMillis() <= pd.expiresAt()) {
            pending.put(uuid, pd);
            if (!pd.chat()) {
                plugin.channel().openPrompt(p, pd.token(), "그 요청은 쓸 수 없어요. 다른 문장으로 적어 주세요.");
            }
        }
    }

    /** 문장 검사 서버 장애: 내용 거부와 구분해 "잠시 후 다시" 안내. 아이템은 아직 차감 전이라 그대로다. */
    private void unavailable(Player p, Pending pd) {
        UUID uuid = p.getUniqueId();
        checking.remove(uuid);
        if (!p.isOnline()) {
            return;
        }
        PortraitSettings s = plugin.settings();
        p.sendMessage(s.message("reroll-unavailable"));
        if (System.currentTimeMillis() <= pd.expiresAt()) {
            pending.put(uuid, pd);
            if (!pd.chat()) {
                plugin.channel().openPrompt(p, pd.token(), "지금은 요청을 확인할 수 없어요. 잠시 후 다시 보내 주세요.");
            }
        }
    }

    /** Durable DB intent precedes the inventory debit. Bukkit changes stay on main. */
    private void take(Player p, Pending pd, String request) {
        UUID uuid = p.getUniqueId();
        if (!current(p) || plugin.service().busy(uuid)) { checking.remove(uuid, pd.token()); return; }
        PortraitService.Job job = PortraitService.jobFor(PortraitService.Kind.REROLL, p, request, pd.token(), List.of(), null);
        if (job.skin().url() == null) { checking.remove(uuid, pd.token()); return; }
        byte[] item = pd.snapshot().serializeAsBytes();
        String server = plugin.settings().serverId;
        PortraitSettings settings = plugin.settings();
        plugin.service().then(plugin.db().call(() -> {
            // 입력하는 동안 예산이 바닥났을 수 있다 → 차감 전에 한 번 더 확인
            if (!plugin.service().budgetAvailable(settings)) {
                return false;
            }
            plugin.storage().insertReroll(pd.token(), uuid, server, item, request);
            return true;
        }), recorded -> {
            if (!Boolean.TRUE.equals(recorded)) {
                checking.remove(uuid, pd.token());
                if (current(p)) p.sendMessage(plugin.settings().message("budget"));
                return;
            }
            if (!current(p)) { checking.remove(uuid, pd.token()); return; }
            try {
                if (!kr.chacademy.portrait.core.InventoryReceipt.debit(new Account(p, pd.token(), pd.slot(), pd.snapshot()))) {
                    checking.remove(uuid, pd.token());
                    plugin.db().call(() -> plugin.storage().moveReroll(pd.token(), PortraitStorage.R_PREPARED, PortraitStorage.R_CANCELLED));
                    p.sendMessage(plugin.settings().message("reroll-cancelled"));
                    return;
                }
            } catch (RuntimeException error) {
                // Keep PREPARED: after an ambiguous save only the next loaded player-data
                // snapshot can determine whether the debit was persisted. Do not mint a refund.
                p.sendMessage("§c아이템 저장을 확인하지 못했습니다. 재접속하면 기록을 확인합니다.");
                plugin.getLogger().warning("[ChacaPortrait] Debit save uncertain; recover on next login: " + pd.token());
                return; // keep checking gate until quit
            }
            plugin.service().then(plugin.db().call(() ->
                    plugin.storage().moveReroll(pd.token(), PortraitStorage.R_PREPARED, PortraitStorage.R_PENDING)), promoted -> {
                checking.remove(uuid, pd.token());
                if (!Boolean.TRUE.equals(promoted) || !plugin.service().submit(job, null)) {
                    failed(uuid, pd.token(), "대기열에 넣지 못함 (가득 참·종료 중·이미 진행 중)", false, false);
                    return;
                }
                // 작업이 실제로 대기열에 들어간 뒤에만 안내한다. 앞에 대기가 있으면 순서와 대략의 시간을 알려 준다.
                if (current(p)) {
                    int ahead = plugin.service().ahead(uuid);
                    p.sendMessage(ahead < 0 ? plugin.settings().message("started")
                            : plugin.settings().message("reroll-queued", "position", ahead + 1,
                                    "minutes", plugin.service().roughMinutes(ahead)));
                }
            }, error -> { checking.remove(uuid, pd.token()); failed(uuid, pd.token(), "기록 전환 실패", false, false); });
        }, error -> {
            // No inventory mutation has happened yet. An uncertain committed intent
            // is cancelled/recovered from the player's absent receipt on next login.
            checking.remove(uuid, pd.token());
            if (current(p)) p.sendMessage("§c요청을 기록하지 못했습니다. 아이템은 사용하지 않았습니다.");
        });
    }

    private boolean current(Player p) {
        return p.isOnline() && Bukkit.getPlayer(p.getUniqueId()) == p;
    }

    /**
     * 생성 시작 전 실패 → 반환 대상으로 (유료 호출이 나가지 않은 것이 확실할 때만 부른다).
     * @param budget 예산 부족으로 실패했는지 (플레이어에게 예산 안내를 먼저 보냄)
     * @param alsoStarted "생성 시작" 기록을 시도했지만 결과를 확인하지 못한 경우: STARTED로 남았더라도 유료 호출은 없었으므로 반환한다
     */
    public void failed(UUID uuid, String token, String error, boolean budget, boolean alsoStarted) {
        plugin.service().then(plugin.db().call(() -> {
            boolean prepared = plugin.storage().moveReroll(token, PortraitStorage.R_PREPARED, PortraitStorage.R_REFUND, error);
            boolean pendingRow = plugin.storage().moveReroll(token, PortraitStorage.R_PENDING, PortraitStorage.R_REFUND, error);
            boolean startedRow = alsoStarted && plugin.storage().moveReroll(token, PortraitStorage.R_STARTED, PortraitStorage.R_REFUND, error);
            return prepared || pendingRow || startedRow;
        }), moved -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                if (budget && Boolean.TRUE.equals(moved)) p.sendMessage(plugin.settings().message("budget"));
                deliverRefunds(p, true);
            }
        }, error2 -> plugin.getLogger().warning("[ChacaPortrait] Refund remains recoverable on restart: " + token));
    }

    /**
     * 생성 시작 후 실패 → 소모 처리 (STARTED→CONSUMED, 반환 없음). 접속 중이면 바로 안내하고, 아니면 다음 접속 때 안내한다.
     * 기록이 이미 다른 상태면 아무것도 하지 않는다 (한 번만 성공).
     */
    public void consumed(UUID uuid, String token, String error) {
        plugin.service().then(plugin.db().call(() -> plugin.storage().consumeReroll(token, error)), moved -> {
            if (!Boolean.TRUE.equals(moved)) {
                return;
            }
            plugin.getLogger().info("[ChacaPortrait] 다시 그리기 소모 처리 (반환 없음) " + uuid + " " + token + ": " + error);
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                p.sendMessage(plugin.settings().message("reroll-consumed"));
                plugin.db().call(() -> {
                    plugin.storage().markConsumedNotified(token);
                    return null;
                });
            }
        }, error2 -> plugin.getLogger().warning("[ChacaPortrait] 소모 처리 기록 실패 — 다음 시작 때 정리됩니다: " + token));
    }

    /** 접속 시: 접속해 있지 않을 때(또는 서버 재시작으로) 소모 처리된 다시 그리기가 있으면 한 번 안내한다. */
    public void notifyConsumed(Player p) {
        UUID uuid = p.getUniqueId();
        plugin.service().then(plugin.db().call(() -> plugin.storage().takeConsumedNotices(uuid)), count -> {
            if (count != null && count > 0 && current(p)) {
                p.sendMessage(plugin.settings().message("reroll-consumed"));
            }
        }, error -> plugin.getLogger().warning("[ChacaPortrait] 소모 안내 확인 실패: " + error.getMessage()));
    }

    private final java.util.Set<String> delivering = ConcurrentHashMap.newKeySet();
    private final java.util.Set<UUID> retryRefunds = ConcurrentHashMap.newKeySet();

    /** Only the server that debited the player owns the refund. Receipts are retained. */
    public void deliverRefunds(Player p, boolean failureMessage) {
        UUID uuid = p.getUniqueId();
        if (!current(p) || plugin.generationBlocked()) return;
        if (checking.containsKey(uuid)) {
            retryRefunds.add(uuid); // 차감 처리 중 — 끝난 뒤 sweep()에서 다시 확인 (관리자 반환·직전 실패 반환이 재접속까지 밀리지 않게)
            return;
        }
        plugin.service().then(plugin.db().call(() -> plugin.storage().refundsDue(uuid, plugin.settings().serverId)), rows -> {
            if (!current(p)) return;
            retryRefunds.remove(uuid); // 아래에서 다시 시도가 필요한 경우(인벤토리 가득·저장 실패)에만 다시 넣는다
            for (PortraitStorage.Reroll row : rows) {
                if (!delivering.add(row.token())) continue;
                Account account = new Account(p, row.token(), -1, ItemStack.deserializeBytes(row.item()));
                String marker = account.marker();
                if (PortraitStorage.R_PREPARED.equals(row.state())) {
                    // A prepared row with no saved debit receipt consumed nothing.
                    String next = marker == null ? PortraitStorage.R_CANCELLED : PortraitStorage.R_REFUND;
                    plugin.service().then(plugin.db().call(() ->
                            plugin.storage().moveReroll(row.token(), PortraitStorage.R_PREPARED, next)), moved -> {
                        delivering.remove(row.token());
                        if (current(p) && Boolean.TRUE.equals(moved) && PortraitStorage.R_REFUND.equals(next)) deliverRefunds(p, failureMessage);
                    }, error -> delivering.remove(row.token()));
                    continue;
                }
                try {
                    if (!kr.chacademy.portrait.core.InventoryReceipt.refund(account)) {
                        if (marker != null) retryRefunds.add(uuid);
                        else plugin.getLogger().warning("[ChacaPortrait] Missing debit receipt; manual audit required: " + row.token());
                        delivering.remove(row.token());
                        continue;
                    }
                } catch (RuntimeException error) {
                    retryRefunds.add(uuid);
                    delivering.remove(row.token());
                    plugin.getLogger().warning("[ChacaPortrait] Refund save deferred: " + row.token());
                    continue;
                }
                // Persisted receipt + inventory now agree. Even if this DB write fails,
                // the retained receipt prevents a second item on next login.
                plugin.service().then(plugin.db().call(() ->
                        plugin.storage().moveReroll(row.token(), PortraitStorage.R_REFUND, PortraitStorage.R_REFUNDED)), moved -> {
                    delivering.remove(row.token());
                    retryRefunds.remove(uuid);
                    if (current(p) && Boolean.TRUE.equals(moved)) p.sendMessage(plugin.settings().message(failureMessage ? "reroll-failed" : "refund"));
                }, error -> { delivering.remove(row.token()); retryRefunds.add(uuid); });
            }
        }, error -> plugin.getLogger().warning("[ChacaPortrait] Refund lookup deferred until next login."));
    }

    /** Receipt and inventory are part of one player-data save, never a separate file. */
    private final class Account implements kr.chacademy.portrait.core.InventoryReceipt.Account {
        private final Player player;
        private final org.bukkit.NamespacedKey key;
        private final int slot;
        private final ItemStack item;
        private record Snapshot(ItemStack[] contents, String receipt) {}
        Account(Player p, String token, int slot, ItemStack item) {
            this.player=p; this.key=new org.bukkit.NamespacedKey(plugin,"receipt_"+token);
            this.slot=slot; this.item=item.clone(); this.item.setAmount(1);
        }
        public String marker() { return player.getPersistentDataContainer().get(key, org.bukkit.persistence.PersistentDataType.STRING); }
        public void marker(String value) {
            if (value == null) player.getPersistentDataContainer().remove(key);
            else player.getPersistentDataContainer().set(key, org.bukkit.persistence.PersistentDataType.STRING, value);
        }
        public Object snapshot() {
            ItemStack[] items=player.getInventory().getStorageContents();
            for(int i=0;i<items.length;i++) if(items[i]!=null) items[i]=items[i].clone();
            return new Snapshot(items,marker());
        }
        public void restore(Object value) {
            Snapshot s=(Snapshot)value; player.getInventory().setStorageContents(s.contents()); marker(s.receipt());
        }
        public boolean take() {
            if(!current(player) || slot<0) return false;
            ItemStack current=player.getInventory().getItem(slot);
            if(current==null || !current.isSimilar(item) || current.getAmount()<1) return false;
            ItemStack after=current.clone(); after.setAmount(current.getAmount()-1);
            player.getInventory().setItem(slot,after.getAmount()==0?null:after); return true;
        }
        public boolean give() {
            if(!current(player)) return false;
            ItemStack[] items=player.getInventory().getStorageContents();
            for(int i=0;i<items.length;i++) if(items[i]==null || items[i].getType().isAir()) {
                player.getInventory().setItem(i,item.clone()); return true;
            }
            // 빈 칸이 생길 때까지 5초마다 다시 시도하지만, 안내는 1분에 한 번만
            long now=System.currentTimeMillis(); Long last=fullNotice.get(player.getUniqueId());
            if(last==null || now-last>=60_000L) {
                fullNotice.put(player.getUniqueId(),now);
                player.sendMessage("§e일러스트 아이템 반환 대기 중: 인벤토리에 빈 칸을 만들어 주세요.");
            }
            return false;
        }
        public void save() {
            if(!Bukkit.isPrimaryThread() || !current(player)) throw new IllegalStateException("Player session changed");
            player.saveData();
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        pending.remove(e.getPlayer().getUniqueId());
        checking.remove(e.getPlayer().getUniqueId());
        retryRefunds.remove(e.getPlayer().getUniqueId());
        clickGate.remove(e.getPlayer().getUniqueId());
        fullNotice.remove(e.getPlayer().getUniqueId());
    }

    /** 만료된 입력 대기 정리 (주기 호출). */
    public void sweep() {
        for (UUID id : retryRefunds) { Player p=Bukkit.getPlayer(id); if(p!=null) deliverRefunds(p, false); }
        long now = System.currentTimeMillis();
        pending.entrySet().removeIf(en -> {
            if (now <= en.getValue().expiresAt()) {
                return false;
            }
            Player p = Bukkit.getPlayer(en.getKey());
            if (p != null) {
                p.sendMessage(plugin.settings().message("reroll-cancelled"));
                if (!en.getValue().chat()) {
                    plugin.channel().closePrompt(p);
                }
            }
            return true;
        });
    }
}
