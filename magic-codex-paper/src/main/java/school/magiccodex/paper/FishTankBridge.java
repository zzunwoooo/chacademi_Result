package school.magiccodex.paper;

import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.messaging.PluginMessageListener;
import school.magiccodex.protocol.FishTankProtocol;
import school.magiccodex.protocol.FishTankProtocol.*;

/**
 * 아공간 수조 window (2026-10-20, owner spec + GPT tank art). Opened by the 아공간 수조 spell (fish_trap) or
 * /테스트 낚시 수조. Every action is decided here against the database (FishTank), never by the client:
 *
 *  아이템화  refused when the inventory has no empty slot; the row is deleted first, then the item (same tank id and
 *            caught date in its PDC) is given. Only a row that was really deleted gives an item.
 *  방생      deletes the row (the codex record stays).
 *  즉시 판매 shop price of that one fish; locked species are never sold (owner: 잠긴 물고기는 팔리지 않게); the row is
 *            deleted first, then paid; a failed payment puts the same fish back.
 *  넣기      the item is taken out of the inventory first, then inserted with its own tank id (primary key): a
 *            duplicated item is refused and stays consumed; a database error gives the item back. Capacity is
 *            checked in the database. Itemized fish are not sellable at the shop until they are back in the tank.
 */
final class FishTankBridge implements PluginMessageListener, Listener {
    private final MagicCodexBridge plugin;
    private final FishingBridge fishing;
    private final FishShopBridge shop;     // may be null (shop failed to load): no instant sale
    private final Set<UUID> open = new HashSet<>(), busy = new HashSet<>();
    private final Map<UUID, long[]> limits = new HashMap<>();
    private final Map<UUID,Set<Long>> completed=new HashMap<>();

    FishTankBridge(MagicCodexBridge plugin, FishingBridge fishing, FishShopBridge shop) {
        this.plugin = plugin; this.fishing = fishing; this.shop = shop;
        Bukkit.getMessenger().registerIncomingPluginChannel(plugin, FishTankProtocol.REQUEST, this);
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, FishTankProtocol.RESPONSE);
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }
    void close() { open.clear(); busy.clear(); }

    static boolean modded(Player p) { return p.getListeningPluginChannels().contains(FishTankProtocol.RESPONSE); }

    /** The 아공간 수조 spell: false (no mana spent) when the window cannot open. */
    boolean cast(Player p) {
        if (!modded(p)) { CmdKit.notify(p, "warning", "fish-tank-open", "아공간 수조를 열 수 없어요", "차카데미 모드를 업데이트해 주세요", 3000); return false; }
        if(!fishing.tank().loaded(p.getUniqueId())){fishing.tank().refresh(p.getUniqueId(),null);CmdKit.notify(p,"warning","fish-tank-open","수조를 읽는 중이에요","잠시 뒤 다시 열어 주세요",2000);return false;}
        if (fishing.fishingNow(p.getUniqueId())) { CmdKit.notify(p, "warning", "fish-tank-open", "낚시 중에는 아공간 수조를 열 수 없어요", "", 2000); return false; }
        open(p);
        return true;
    }
    void open(Player p) {
        if(!busy.contains(p.getUniqueId()))completed.remove(p.getUniqueId());
        open.add(p.getUniqueId());
        fishing.tank().refresh(p.getUniqueId(), () -> { if (p.isOnline() && open.contains(p.getUniqueId())) send(p, FishTankProtocol.KIND_OPEN, ""); });
    }

    // ------------------------------------------------------------------ state

    private long price(FishTank.Fish f) { return shop == null ? 0 : shop.price(f); }

    private void send(Player p,int kind,String notice){
        if(!p.isOnline()||!open.contains(p.getUniqueId()))return;
        var ids=new ArrayList<String>();
        for(int slot=0;slot<FishTankProtocol.MAX_INVENTORY;slot++){
            if(slot>35&&slot!=40)continue;var h=fishing.gear().heldFish(p.getInventory().getItem(slot));
            if(h!=null&&!h.tankId().isEmpty())ids.add(h.tankId());
        }
        fishing.tank().itemInfo(ids,items->send(p,kind,notice,items));
    }
    private void send(Player p, int kind, String notice,Map<String,FishTank.Fish> items) {
        if (!p.isOnline() || !open.contains(p.getUniqueId())) return;
        var tank = fishing.tank().tank(p.getUniqueId());
        var config = fishing.config();
        var fish = new ArrayList<Fish>(); var species = new LinkedHashMap<String, Info>();
        for (var f : tank.fish) {
            var def = config.fishById.get(f.fish());
            if (def == null || fish.size() >= FishTankProtocol.MAX_FISH) continue;
            fish.add(new Fish(f.id(), f.fish(), (float) f.size(), f.caughtAt(), price(f), tank.locks.contains(f.fish())));
            species.computeIfAbsent(def.id(), k -> info(def));
        }
        var held = new ArrayList<Held>();
        var inv = p.getInventory();
        for (int slot = 0; slot < FishTankProtocol.MAX_INVENTORY; slot++) {
            if (slot > 35 && slot != 40) continue;   // armour slots never hold fish
            var h = fishing.gear().heldFish(inv.getItem(slot));
            if (h == null) continue;
            var def = config.fishById.get(h.fish());
            if (def == null || !h.tankId().isEmpty() && !FishTankProtocol.validTankId(h.tankId())) continue;
            var row=items.get(h.tankId());
            if(!h.tankId().isEmpty()){
                if(row==null){if(!legacyMetadata(h))continue;}
                else if(!row.token().equals(h.token())||!row.fish().equals(h.fish()))continue;
            }
            if(row==null&&(!Float.isFinite(h.size())||h.size()<=0))continue;
            held.add(new Held(slot,h.tankId(),def.id(),row==null?h.size():(float)row.size(),row==null?h.caughtAt():row.caughtAt()));
            species.computeIfAbsent(def.id(), k -> info(def));
        }
        try {
            p.sendPluginMessage(plugin, FishTankProtocol.RESPONSE, FishTankProtocol.encode(new State(kind, fishing.tank().capacity(), inv.firstEmpty() < 0,
                    fish, held, List.copyOf(species.values()), cut(notice))));
        } catch (IllegalArgumentException e) { plugin.getLogger().warning("아공간 수조 패킷 실패: " + e.getMessage()); }
    }
    /** Pre-DB admin gifts already had an identity, positive size/date, and no withdrawal token. */
    private static boolean legacyMetadata(FishingGear.HeldFish held){
        return !held.tankId().isEmpty()&&FishTankProtocol.validTankId(held.tankId())&&held.token().isEmpty()
                &&Float.isFinite(held.size())&&held.size()>0&&held.caughtAt()>0;
    }
    private static Info info(FishingConfig.Fish f) {
        String d = f.description() == null ? "" : f.description();
        if (d.length() > FishTankProtocol.MAX_DESCRIPTION) d = d.substring(0, FishTankProtocol.MAX_DESCRIPTION);
        return new Info(f.id(), FishingGear.cut(f.name()), f.grade(), Math.clamp(f.shape(), 0, 255), d);
    }
    private void result(Player p, Request r, boolean ok, String notice) {
        if(!p.isOnline())return;
        try { p.sendPluginMessage(plugin, FishTankProtocol.RESPONSE, FishTankProtocol.encode(new Result(r.operation(), r.action(), ok, r.tankId(), cut(notice)))); }
        catch (IllegalArgumentException ignored) {}
    }
    private static String cut(String s) { return s == null ? "" : s.length() > FishTankProtocol.MAX_TEXT ? s.substring(0, FishTankProtocol.MAX_TEXT) : s; }

    // ------------------------------------------------------------------ requests

    @Override public void onPluginMessageReceived(String channel, Player p, byte[] bytes) {
        if (!FishTankProtocol.REQUEST.equals(channel)) return;
        long[] w = limits.computeIfAbsent(p.getUniqueId(), k -> new long[2]);
        long now = System.currentTimeMillis();
        if (now - w[0] > 1000) { w[0] = now; w[1] = 0; }
        if (++w[1] > 10) return;
        Request r;
        try { r = FishTankProtocol.request(bytes); } catch (IllegalArgumentException e) { return; }
        UUID id = p.getUniqueId();
        if (!open.contains(id)) return;
        if (r.action() == FishTankProtocol.CLOSE) { open.remove(id); return; }
        if (r.action() == FishTankProtocol.QUERY) { fishing.tank().refresh(id, () -> { if (p.isOnline()) send(p, FishTankProtocol.KIND_STATE, ""); }); return; }
        if (busy.contains(id)) { result(p, r, false, "앞의 요청을 처리하고 있어요."); return; }
        var seen=completed.computeIfAbsent(id,k->new LinkedHashSet<>());if(!seen.add(r.operation())){result(p,r,false,"이미 처리한 요청이에요.");return;}if(seen.size()>256)seen.remove(seen.iterator().next());
        busy.add(id);
        // every change is decided on a fresh read (the other server may have changed the tank)
        fishing.tank().refresh(id, () -> {
            if (!p.isOnline()) { busy.remove(id); return; }
            if(!fishing.tank().loaded(id)){done(p,r,false,"수조를 읽지 못했어요. 잠시 후 다시 시도해 주세요.");return;}
            switch (r.action()) {
                case FishTankProtocol.ITEMIZE -> itemize(p, r);
                case FishTankProtocol.RELEASE -> release(p, r);
                case FishTankProtocol.SELL -> sell(p, r);
                case FishTankProtocol.INSERT -> insert(p, r);
                default -> busy.remove(id);
            }
        });
    }

    private FishTank.Fish find(Player p, String tankId) {
        for (var f : fishing.tank().tank(p.getUniqueId()).fish) if (f.id().equals(tankId)) return f;
        return null;
    }
    private void done(Player p, Request r, boolean ok, String notice) {
        busy.remove(p.getUniqueId());
        result(p, r, ok, notice);
        send(p, FishTankProtocol.KIND_STATE, "");
    }
    private void gone(Player p, Request r) { done(p, r, false, "이미 수조에 없는 물고기예요."); }

    private void itemize(Player p, Request r) {
        var f=find(p,r.tankId());if(f==null){gone(p,r);return;}
        if(p.getInventory().firstEmpty()<0){done(p,r,false,"인벤토리에 빈 칸이 없어요.");return;}
        fishing.tank().remove(p.getUniqueId(),List.of(f),FishTankSql.Action.ITEMIZE,claimed->{
            if(claimed.isEmpty()){gone(p,r);return;}
            var actual=claimed.getFirst();ItemStack item=fishing.fishItem(actual);
            if(!p.isOnline()||p.getInventory().firstEmpty()<0||item==null){
                fishing.tank().finish(p.getUniqueId(),claimed,FishTankSql.Action.ITEMIZE,false,ok->done(p,r,false,ok?"꺼내기를 취소하고 수조에 보존했어요.":"수조 상태를 확인해야 해요. 관리자에게 알려 주세요."));return;
            }
            FishingBridge.Given given;
            try{given=fishing.deliver(p,List.of(item),"fish-tank:"+actual.id()+":"+actual.token(),actual,null);}
            catch(RuntimeException uncertain){plugin.getLogger().severe("FISH TANK delivery outcome uncertain; claim retained id="+actual.id());done(p,r,false,"지급 결과 확인이 필요해요. 관리자에게 알려 주세요.");return;}
            // 빈 칸을 확인했으므로 보통 인벤토리로 들어간다. 우편으로 갔다면 수조 기록은 우편 저장 확인 뒤 대기열이 닫는다.
            if(given==FishingBridge.Given.MAIL){done(p,r,true,"인벤토리가 가득 차서 우편으로 보냈어요.");return;}
            fishing.tank().finish(p.getUniqueId(),claimed,FishTankSql.Action.ITEMIZE,true,ok->{
                if(p.isOnline())p.playSound(p.getLocation(),Sound.ENTITY_ITEM_PICKUP,.7f,1.2f);
                done(p,r,ok,ok?"아이템으로 꺼냈어요.":"지급 기록 확인이 필요해요. 관리자에게 알려 주세요.");
            });
        });
    }

    private void release(Player p, Request r) {
        var f = find(p, r.tankId());
        if (f == null) { gone(p, r); return; }
        fishing.tank().remove(p.getUniqueId(), List.of(f), removed -> {
            if (removed.isEmpty()) { gone(p, r); return; }
            plugin.getLogger().info("FISH TANK release player=" + p.getName() + " fish=" + f.fish() + " id=" + f.id());
            p.playSound(p.getLocation(), Sound.ENTITY_FISH_SWIM, .8f, 1.1f);
            done(p, r, true, "물고기를 놓아줬어요.");
        });
    }

    private void sell(Player p,Request r){
        var f=find(p,r.tankId());if(f==null){gone(p,r);return;}
        if(shop==null||!shop.ready()){done(p,r,false,"지금은 판매할 수 없어요.");return;}
        if(fishing.tank().tank(p.getUniqueId()).locks.contains(f.fish())){done(p,r,false,"잠긴 종류라서 팔 수 없어요.");return;}
        long quoted=price(f);if(quoted!=r.expected()){done(p,r,false,"가격이 바뀌었어요. 다시 확인해 주세요.");return;}
        fishing.tank().remove(p.getUniqueId(),List.of(f),FishTankSql.Action.SELL,claimed->{
            if(claimed.isEmpty()){gone(p,r);return;}
            plugin.getLogger().info("FISH TANK PAYMENT_ATTEMPT owner="+p.getUniqueId()+" op="+r.operation()+" fish="+f.id()+" total="+quoted);
            boolean paid=false;
            if(p.isOnline())try{paid=quoted==0||shop.deposit(p,quoted);}catch(Exception uncertain){plugin.getLogger().severe("FISH TANK payment outcome uncertain; claim retained id="+f.id());done(p,r,false,"지급 결과 확인이 필요해요. 관리자에게 알려 주세요.");return;}
            plugin.getLogger().info("FISH TANK PAYMENT_RESULT owner="+p.getUniqueId()+" op="+r.operation()+" confirmed="+paid+" total="+quoted);
            boolean payment=paid;
            fishing.tank().finish(p.getUniqueId(),claimed,FishTankSql.Action.SELL,payment,ok->{
                done(p,r,ok&&payment,!ok?"판매 기록 확인이 필요해요. 관리자에게 알려 주세요.":payment?String.format("%,d G에 팔았어요.",quoted):"돈을 지급하지 못해서 물고기를 수조에 보존했어요.");
            });
        });
    }

    private void insert(Player p,Request r){
        var inv=p.getInventory();int slot=r.slot();if(slot>35&&slot!=40){done(p,r,false,"물고기를 찾지 못했어요.");return;}
        ItemStack item=inv.getItem(slot);var h=fishing.gear().heldFish(item);var def=h==null?null:fishing.config().fishById.get(h.fish());
        if(h==null||def==null||!h.tankId().equals(r.tankId())){done(p,r,false,"그 칸의 물고기가 바뀌었어요. 다시 골라 주세요.");return;}
        if(fishing.tank().full(p.getUniqueId())){done(p,r,false,"수조가 가득 찼어요.");return;}
        if(!h.tankId().isEmpty()){
            var held=h;int lookupSlot=slot;
            fishing.tank().itemInfo(List.of(h.tankId()),items->{
                var row=items.get(held.tankId());
                if(row==null){
                    if(!legacyMetadata(held)){done(p,r,false,"Invalid or already processed fish item.");return;}
                    // SQL.put remains authoritative: an existing SOLD/RELEASED/withdrawn identity is never minted again.
                    commitInsert(p,r,lookupSlot,new FishTank.Fish(held.tankId(),def.id(),Math.round(held.size()*10)/10.0,held.caughtAt(),""));
                    return;
                }
                if(!row.fish().equals(held.fish())||!row.token().equals(held.token())){done(p,r,false,"Invalid or already processed fish item.");return;}
                commitInsert(p,r,lookupSlot,new FishTank.Fish(row.id(),row.fish(),row.size(),row.caughtAt(),held.token()));
            });return;
        }
        if(!Float.isFinite(h.size())||h.size()<=0){done(p,r,false,"Fish metadata is missing.");return;}
        if(h.tankId().isEmpty()){
            int target=slot;
            if(item.getAmount()>1){target=inv.firstEmpty();if(target<0){done(p,r,false,"기존 물고기를 구분할 빈 칸 하나가 필요해요.");return;}}
            ItemStack tagged=item.asOne();var meta=tagged.getItemMeta();var data=meta.getPersistentDataContainer();
            data.set(new org.bukkit.NamespacedKey(plugin,"fishing_tank_id"),org.bukkit.persistence.PersistentDataType.STRING,UUID.randomUUID().toString());
            if(h.caughtAt()<=0)data.set(new org.bukkit.NamespacedKey(plugin,"fishing_caught"),org.bukkit.persistence.PersistentDataType.LONG,System.currentTimeMillis());
            tagged.setItemMeta(meta);if(target!=slot)item.setAmount(item.getAmount()-1);inv.setItem(target,tagged);slot=target;item=tagged;h=fishing.gear().heldFish(tagged);
        }
        var fish=new FishTank.Fish(h.tankId(),def.id(),Math.round(h.size()*10)/10.0,h.caughtAt()>0?h.caughtAt():System.currentTimeMillis(),h.token());
        commitInsert(p,r,slot,fish);
    }
    private void commitInsert(Player p,Request r,int sourceSlot,FishTank.Fish fish){
        var inv=p.getInventory();
        // Commit ownership first. An interrupted callback leaves a duplicate physical item, never two redeemable fish.
        fishing.tank().put(p.getUniqueId(),fish,put->{
            if((put==FishTank.Put.OK||put==FishTank.Put.DUPLICATE)&&p.isOnline()){
                var current=inv.getItem(sourceSlot);var held=fishing.gear().heldFish(current);
                if(held!=null&&held.tankId().equals(fish.id())&&held.token().equals(fish.token())){
                    if(current.getAmount()>1)current.setAmount(current.getAmount()-1);else inv.setItem(sourceSlot,null);
                }
            }
            switch(put){
                case OK->done(p,r,true,"수조에 넣었어요.");
                case DUPLICATE->{plugin.getLogger().warning("FISH TANK duplicate item refused player="+p.getUniqueId()+" id="+fish.id());done(p,r,false,"이미 처리된 물고기예요. (복제되거나 지난 인출의 아이템)");}
                case FULL->done(p,r,false,"수조가 가득 찼어요. 아이템은 그대로 있어요.");
                case FAILED->done(p,r,false,"저장하지 못했어요. 아이템은 그대로 있어요.");
            }
        });
    }

    @EventHandler public void inventory(org.bukkit.event.inventory.InventoryClickEvent e){if(e.getWhoClicked() instanceof Player p&&busy.contains(p.getUniqueId()))e.setCancelled(true);}
    @EventHandler public void drag(org.bukkit.event.inventory.InventoryDragEvent e){if(e.getWhoClicked() instanceof Player p&&busy.contains(p.getUniqueId()))e.setCancelled(true);}
    @EventHandler public void drop(org.bukkit.event.player.PlayerDropItemEvent e){if(busy.contains(e.getPlayer().getUniqueId()))e.setCancelled(true);}
    @EventHandler public void swap(org.bukkit.event.player.PlayerSwapHandItemsEvent e){if(busy.contains(e.getPlayer().getUniqueId()))e.setCancelled(true);}

    @EventHandler public void quit(PlayerQuitEvent e) { UUID id = e.getPlayer().getUniqueId(); open.remove(id); limits.remove(id); }
}
