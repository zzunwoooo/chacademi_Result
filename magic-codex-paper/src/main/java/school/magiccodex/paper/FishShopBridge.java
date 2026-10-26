package school.magiccodex.paper;

import java.io.File;
import java.math.BigDecimal;
import java.util.*;
import org.bukkit.*;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;
import school.magiccodex.protocol.FishShopProtocol;
import school.magiccodex.protocol.FishShopProtocol.*;
import school.magiccodex.protocol.FishingProtocol;

/**
 * 물고기 판매소 (2026-10-20, owner reference "물고기 판매소 / 물고기 매입상 에드윈").
 *
 * A shop of its own (not a ShopStore shop): it buys fish straight out of the 아공간 수조 tank. The sale tab sells every fish of
 * the checked grades except locked species (owner: 잠긴 물고기는 크기·종류 무관하고 팔리지 않게). The lock tab sets
 * the locked species (per player, shared by school and wild).
 *
 * Prices live in fish-shop.yml and can be changed at any time (/상점관리 물고기판매소 가격 …, or edit + /서버관리 새로고침 물고기판매소):
 * one fish = base x size factor, base = per-fish override or grade base (x special bonus for fish with special
 * conditions), size factor = linear from size-min (smallest of the species) to size-max (largest).
 * NPCs and the portrait work like the normal shop (Citizens NPC right click, textures/gui/dialogue/&lt;portrait&gt;.png).
 * fish-shop.yml (title, names, portrait, prices) is shared by school and wild through the player-state MariaDB
 * (SharedConfigText row "fish_shop", owner 2026-10-20: 가격·보유·잠금은 DB로 공유). Only the NPC list is per server
 * (fish-shop-npcs.yml), because Citizens ids differ between servers. Tank and locks are already in the shared DB.
 */
final class FishShopBridge implements PluginMessageListener, Listener {
    private record Session(Entity anchor, String npcName) {}

    private final MagicCodexBridge plugin;
    private final FishingBridge fishing;
    private final File file, npcFile;
    private SharedConfigText shared;
    private YamlConfiguration yaml;
    private final double[] gradeBase = {80, 400, 2000, 10000};
    private double sizeMin = .7, sizeMax = 1.5, specialBonus = 1.3;
    private final Map<String, Double> overrides = new HashMap<>();
    private String title = "물고기 판매소", role = "물고기 매입상", portrait = "", defaultName = "물고기 매입상";
    private Set<String> npcs = Set.of();
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Set<UUID> busy = new HashSet<>();
    private final Map<UUID, long[]> limits = new HashMap<>();
    private final Map<UUID,Set<Long>> completed=new HashMap<>();

    FishShopBridge(MagicCodexBridge plugin, FishingBridge fishing) throws Exception {
        this.plugin = plugin; this.fishing = fishing;
        file = new File(plugin.getDataFolder(), "fish-shop.yml");
        npcFile = new File(plugin.getDataFolder(), "fish-shop-npcs.yml");
        if (!file.exists()) plugin.saveResource("fish-shop.yml", false);
        // NPC assignments use the existing server-scoped ShopStore; no separate list is seeded.
        reload();
        shared = new SharedConfigText(plugin, "fish_shop", "물고기 판매소 설정", localText(), this::applyText);
        Bukkit.getMessenger().registerIncomingPluginChannel(plugin, FishShopProtocol.REQUEST, this);
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, FishShopProtocol.RESPONSE);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        CmdKit.register(plugin, "/상점관리 물고기판매소", this::commands);
        Bukkit.getServicesManager().register(FishShopBridge.class,this,plugin,org.bukkit.plugin.ServicePriority.Normal);
    }

    void close() { Bukkit.getServicesManager().unregister(FishShopBridge.class,this);sessions.clear(); if (shared != null) shared.close(); }

    String localText() {
        try { return java.nio.file.Files.readString(file.toPath()); } catch (Exception e) { return ""; }
    }
    /** Another server published a new fish-shop.yml: write it here and load it. Returns an error or null. */
    String applyText(String text) {
        String before = localText();
        try { java.nio.file.Files.writeString(file.toPath(), text); reload(); return null; }
        catch (Exception e) {
            try { java.nio.file.Files.writeString(file.toPath(), before); reload(); } catch (Exception ignored) {}
            return e.getMessage();
        }
    }

    // ------------------------------------------------------------------ config

    void reload() throws Exception {
        var y = new YamlConfiguration();
        y.load(file);
        double[] base = gradeBase.clone();
        for (int g = 0; g < 4; g++) {
            double v = y.getDouble("prices.grade." + FishingProtocol.GRADE_LABELS[g], base[g]);
            if (!Double.isFinite(v) || v < 0 || v > 1e12) throw new IllegalArgumentException("prices.grade." + FishingProtocol.GRADE_LABELS[g] + " 는 0 이상");
            base[g] = v;
        }
        double min = y.getDouble("prices.size-min", .7), max = y.getDouble("prices.size-max", 1.5), bonus = y.getDouble("prices.special-bonus", 1.3);
        if (!Double.isFinite(min) || !Double.isFinite(max) || !Double.isFinite(bonus) || min < 0 || max < min || bonus < 0) throw new IllegalArgumentException("prices.size-min ≤ size-max 이어야 함");
        var over = new HashMap<String, Double>();
        var fs = y.getConfigurationSection("prices.fish");
        if (fs != null) for (String id : fs.getKeys(false)) {
            if (!fishing.config().fishById.containsKey(id)) throw new IllegalArgumentException("prices.fish." + id + ": 없는 물고기");
            double value=fs.getDouble(id);if(!Double.isFinite(value)||value<0||value>1e12)throw new IllegalArgumentException("invalid fish price: "+id);over.put(id,value);
        }
        for(var fish:fishing.config().fish){double value=over.getOrDefault(fish.id(),base[fish.grade()]*(special(fish)?bonus:1));FishShopPricing.validate(value,min,max);}
        // commit
        yaml = y; System.arraycopy(base, 0, gradeBase, 0, 4); sizeMin = min; sizeMax = max; specialBonus = bonus;
        overrides.clear(); overrides.putAll(over);
        title = cut(y.getString("title", "물고기 판매소")); role = cut(y.getString("role", "물고기 매입상"));
        defaultName = cut(y.getString("npc-name", "물고기 매입상"));
        portrait = y.getString("portrait", "");
        if (!portrait.matches("[a-z0-9_/-]{0,120}") || portrait.contains("..")) portrait = "";
        var local = new YamlConfiguration();
        if (npcFile.exists()) local.load(npcFile);
        npcs = Set.copyOf(local.getStringList("npcs"));
    }

    /** Saves fish-shop.yml here, then shares it with the other server; on a sharing failure the old text comes back. */
    private void save(CommandSender sender, String done) throws Exception {
        String before = localText();
        try{yaml.save(file);reload();}catch(Exception failed){applyText(before);throw failed;}
        if (shared == null) { CmdKit.ok(sender, done); return; }
        String attempted=localText();
        shared.publish(attempted, "command:" + sender.getName(), failed -> {
            if (failed == null) CmdKit.ok(sender, done + (shared.shared() ? " (학교·야생 같이 적용)" : " (공유 DB 없음: 이 서버만)"));
            else { if(localText().equals(attempted))applyText(before); CmdKit.fail(sender, "다른 서버와 공유하지 못해서 되돌렸어요", new IllegalStateException(failed)); }
        });
    }

    static boolean special(FishingConfig.Fish f) {
        return !f.weather().equals("전체") || !f.moon().isEmpty() || f.underground() || f.yMin() != null || f.yMax() != null;
    }
    double base(FishingConfig.Fish f) {
        Double o = overrides.get(f.id());
        if (o != null) return o;
        return gradeBase[f.grade()] * (special(f) ? specialBonus : 1);
    }
    boolean ready() { return economy() != null; }
    long price(FishTank.Fish fish) {
        var f = fishing.config().fishById.get(fish.fish());
        if (f == null) return 0;
        return FishShopProtocol.price(base(f), fish.size(), f.minSize(), f.maxSize(), sizeMin, sizeMax);
    }

    // ------------------------------------------------------------------ opening

    private static boolean modded(Player p) { return p.getListeningPluginChannels().contains(FishShopProtocol.RESPONSE); }
    private static boolean near(Player p, Entity anchor) {
        return anchor == null || anchor.isValid() && p.getWorld() == anchor.getWorld() && p.getLocation().distanceSquared(anchor.getLocation()) <= 64;
    }

    void open(Player p, Entity anchor, String npcName) {
        if (!modded(p)) { CmdKit.notify(p, "warning", "fish-shop-mod", "물고기 판매소를 열 수 없어요", "차카데미 모드를 업데이트해 주세요", 3000); return; }
        if(!busy.contains(p.getUniqueId()))completed.remove(p.getUniqueId());
        sessions.put(p.getUniqueId(), new Session(anchor, npcName == null || npcName.isBlank() ? defaultName : cut(npcName)));
        // the lock tab lists only the species this player has caught (codex), so the codex is loaded too
        fishing.store().load(p.getUniqueId(), () -> fishing.tank().refresh(p.getUniqueId(), () -> { if (p.isOnline() && sessions.containsKey(p.getUniqueId())) send(p, FishShopProtocol.KIND_OPEN, ""); }));
    }

    private List<FishTank.Fish> sellable(Player p, int mask) {
        var tank = fishing.tank().tank(p.getUniqueId());
        var out = new ArrayList<FishTank.Fish>();
        for (var f : tank.fish) {
            var def = fishing.config().fishById.get(f.fish());
            if (def == null || tank.locks.contains(f.fish()) || (mask >> def.grade() & 1) == 0) continue;
            out.add(f);
        }
        return out;
    }

    private void send(Player p, int kind, String notice) {
        var s = sessions.get(p.getUniqueId());
        if (s == null || !p.isOnline()) return;
        var tank = fishing.tank().tank(p.getUniqueId());
        var grades = new ArrayList<Grade>();
        for (int g = 0; g < 4; g++) {
            int count = 0; long total = 0; String sample = ""; long best = -1;
            for (var f : sellable(p, 1 << g)) {
                long v = price(f); count++; total += v;
                if (v > best) { best = v; sample = f.fish(); }
            }
            grades.add(new Grade(count, total, sample));
        }
        var species = new ArrayList<Species>();
        if (kind == FishShopProtocol.KIND_OPEN) {
            // owner 2026-10-20: fish not caught yet are not shown in the lock list
            var book = fishing.store().book(p.getUniqueId());
            synchronized (book) {
                for (var f : fishing.config().fish) if (book.rows.containsKey(f.id())) species.add(new Species(f.id(), FishingGear.cut(f.name()), f.grade()));
            }
        }
        var locks = new ArrayList<String>();
        for (String l : tank.locks) if (FishingProtocol.validId(l) && locks.size() < FishShopProtocol.MAX_SPECIES) locks.add(l);
        try {
            p.sendPluginMessage(plugin, FishShopProtocol.RESPONSE, FishShopProtocol.encode(new State(kind, title, role, s.npcName(), portrait, balance(p),
                    tank.fish.size(), fishing.tank().capacity(), grades, locks, species, cut(notice))));
        } catch (IllegalArgumentException e) { plugin.getLogger().warning("물고기 판매소 패킷 실패: " + e.getMessage()); }
    }

    private void result(Player p, long op, boolean ok, int sold, long total, String notice) {
        if(!p.isOnline())return;
        try { p.sendPluginMessage(plugin, FishShopProtocol.RESPONSE, FishShopProtocol.encode(new Result(op, ok, sold, total, cut(notice)))); }
        catch (IllegalArgumentException ignored) {}
    }

    // ------------------------------------------------------------------ requests

    @Override public void onPluginMessageReceived(String channel, Player p, byte[] bytes) {
        if (!FishShopProtocol.REQUEST.equals(channel)) return;
        long[] w = limits.computeIfAbsent(p.getUniqueId(), k -> new long[2]);
        long now = System.currentTimeMillis();
        if (now - w[0] > 1000) { w[0] = now; w[1] = 0; }
        if (++w[1] > 10) return;
        Request r;
        try { r = FishShopProtocol.request(bytes); } catch (IllegalArgumentException e) { return; }
        var s = sessions.get(p.getUniqueId());
        if (s == null || !p.isOnline()) return;
        if (r.action() == FishShopProtocol.CLOSE) { sessions.remove(p.getUniqueId()); return; }
        if (!near(p, s.anchor())) { sessions.remove(p.getUniqueId()); return; }
        switch (r.action()) {
            case FishShopProtocol.QUERY -> fishing.tank().refresh(p.getUniqueId(), () -> { if (p.isOnline()) send(p, FishShopProtocol.KIND_STATE, ""); });
            case FishShopProtocol.LOCKS -> {
                var set = new HashSet<String>();
                for (String id : r.locks()) if (fishing.config().fishById.containsKey(id)) set.add(id);
                fishing.tank().locks(p.getUniqueId(), set, ok->send(p,FishShopProtocol.KIND_STATE,ok?"잠금을 저장했어요. ("+set.size()+"종)":"잠금을 저장하지 못했어요. 다시 시도해 주세요."));
            }
            case FishShopProtocol.SELL -> sell(p, r);
            default -> {}
        }
    }

    private void sell(Player p,Request r){
        UUID id=p.getUniqueId();if(busy.contains(id))return;
        if(economy()==null){result(p,r.operation(),false,0,0,"경제 서비스 연결이 필요해요.");return;}
        var seen=completed.computeIfAbsent(id,k->new LinkedHashSet<>());if(!seen.add(r.operation())){result(p,r.operation(),false,0,0,"이미 처리한 요청이에요.");return;}if(seen.size()>256)seen.remove(seen.iterator().next());
        busy.add(id);
        fishing.tank().refresh(id,()->{
            if(!p.isOnline()){busy.remove(id);return;}
            if(!fishing.tank().loaded(id)){busy.remove(id);result(p,r.operation(),false,0,0,"수조를 읽지 못했어요.");return;}
            var list=sellable(p,r.mask());var quote=new HashMap<String,Long>();long sum=0;
            try{for(var f:list){long value=price(f);sum=Math.addExact(sum,value);quote.put(f.id(),value);}}
            catch(ArithmeticException invalid){busy.remove(id);result(p,r.operation(),false,0,0,"판매 금액 범위를 확인해야 해요.");return;}
            if(list.isEmpty()||sum!=r.expected()){busy.remove(id);result(p,r.operation(),false,0,0,"판매 대상이나 금액이 바뀌었어요. 다시 확인해 주세요.");send(p,FishShopProtocol.KIND_STATE,"");return;}
            final long accepted=sum;
            fishing.tank().remove(id,list,FishTankSql.Action.SELL,claimed->{
                if(claimed.isEmpty()){busy.remove(id);result(p,r.operation(),false,0,0,"잠금이나 수조 내용이 바뀌었어요. 다시 확인해 주세요.");fishing.tank().refresh(id,()->send(p,FishShopProtocol.KIND_STATE,""));return;}
                plugin.getLogger().info("FISH SHOP PAYMENT_ATTEMPT owner="+id+" op="+r.operation()+" total="+accepted+" fish="+claimed.stream().map(FishTank.Fish::id).toList());
                boolean paid=false;
                if(p.isOnline())try{paid=accepted==0||deposit(p,accepted);}catch(Exception uncertain){plugin.getLogger().severe("FISH SHOP payment outcome uncertain; claims retained player="+id+" op="+r.operation());busy.remove(id);result(p,r.operation(),false,0,0,"지급 결과 확인이 필요해요. 관리자에게 알려 주세요.");return;}
                plugin.getLogger().info("FISH SHOP PAYMENT_RESULT owner="+id+" op="+r.operation()+" confirmed="+paid+" total="+accepted);
                boolean payment=paid;
                fishing.tank().finish(id,claimed,FishTankSql.Action.SELL,payment,ok->{
                    if(ok&&payment)Bukkit.getScheduler().runTask(plugin,()->StoryProgressEvent.fire(p,StoryProgressEvent.FISH_SOLD));
                    if(ok&&payment){AuditStats.add(id,"물고기판매",claimed.size());var kinds=new java.util.TreeMap<String,Integer>();for(var f:claimed)kinds.merge(f.fish(),1,Integer::sum);
                        AuditLog.log(id,AuditLog.상점,"물고기 판매 · "+claimed.size()+"마리 · "+accepted+" G","방식","물고기판매","마리",claimed.size(),"합계",accepted,"종류",kinds.toString(),"개체",claimed.stream().map(FishTank.Fish::id).toList().toString(),"요청",r.operation());}
                    busy.remove(id);result(p,r.operation(),ok&&payment,ok&&payment?claimed.size():0,ok&&payment?accepted:0,
                        !ok?"판매 기록 확인이 필요해요. 관리자에게 알려 주세요.":payment?claimed.size()+"마리를 "+String.format("%,d",accepted)+" G에 팔았어요.":"돈을 지급하지 못해서 물고기를 수조에 보존했어요.");
                    send(p,FishShopProtocol.KIND_STATE,"");
                });
            });
        });
    }

    @EventHandler public void quit(PlayerQuitEvent e) { sessions.remove(e.getPlayer().getUniqueId()); limits.remove(e.getPlayer().getUniqueId()); }

    // ------------------------------------------------------------------ economy (Vault through reflection, like ShopBridge)

    private record Economy(Class<?> type, Object provider) {}
    @SuppressWarnings({"rawtypes", "unchecked"})
    private Economy economy() {
        for (Class<?> type : Bukkit.getServicesManager().getKnownServices())
            if (type.getName().equals("net.milkbowl.vault.economy.Economy")) {
                var r = Bukkit.getServicesManager().getRegistration((Class) type);
                if (r != null) return new Economy(type, r.getProvider());
            }
        return null;
    }
    String balance(Player p) {
        try {
            var e = economy();
            if (e == null) return "";
            double v = ((Number) e.type.getMethod("getBalance", OfflinePlayer.class).invoke(e.provider, p)).doubleValue();
            return BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
        } catch (Exception ex) { return ""; }
    }
    boolean deposit(Player p, long amount) throws Exception {
        var e = economy();
        if (e == null) return false;
        Object result = e.type.getMethod("depositPlayer", OfflinePlayer.class, double.class).invoke(e.provider, p, (double) amount);
        return (boolean) result.getClass().getMethod("transactionSuccess").invoke(result);
    }

    // ------------------------------------------------------------------ Citizens NPC

    @SuppressWarnings("unchecked")
    private void citizens() {
        var dep = Bukkit.getPluginManager().getPlugin("Citizens");
        if (dep == null) return;
        try {
            var loader = dep.getClass().getClassLoader();
            Class<? extends Event> type = (Class<? extends Event>) Class.forName("net.citizensnpcs.api.event.NPCRightClickEvent", true, loader);
            Bukkit.getPluginManager().registerEvent(type, this, EventPriority.HIGH, (l, event) -> {
                try {
                    var npc = event.getClass().getMethod("getNPC").invoke(event);
                    int npcId = ((Number) npc.getClass().getMethod("getId").invoke(npc)).intValue();
                    if (!npcs.contains("citizens:" + npcId)) return;
                    if (plugin.dialogueBridge() != null && plugin.dialogueBridge().claimed(npcId)) return;
                    var p = (Player) event.getClass().getMethod("getClicker").invoke(event);
                    var anchor = (Entity) npc.getClass().getMethod("getEntity").invoke(npc);
                    String name = String.valueOf(npc.getClass().getMethod("getName").invoke(npc));
                    if (event instanceof Cancellable c) c.setCancelled(true);
                    open(p, anchor, ChatColor.stripColor(name));
                } catch (Exception e) { plugin.getLogger().warning("물고기 판매소 NPC 이벤트를 처리하지 못했습니다: " + e.getClass().getSimpleName()); }
            }, plugin, true);
        } catch (Exception e) { plugin.getLogger().warning("물고기 판매소 Citizens 연결 실패"); }
    }

    // ------------------------------------------------------------------ 명령 (2026-10-20): /상점관리 물고기판매소 …

    private Map<String, String> fishNames() { var out = new LinkedHashMap<String, String>(); for (var f : fishing.config().fish) out.put(f.id(), f.name()); return out; }

    private void commands() {
        var gradeOrFish = (java.util.function.Supplier<Collection<String>>) () -> { var all = new ArrayList<String>(List.of(FishingProtocol.GRADE_LABELS)); all.addAll(AdminCommandRules.catalogChoices(fishNames())); return all; };
        var root = CmdKit.literal("물고기판매소")
                .then(CmdKit.literal("열기").executes(CmdKit.run(ctx -> adminOpen(CmdKit.self(ctx)))).then(CmdKit.player("닉네임").executes(CmdKit.run(ctx -> adminOpen(ChacaApi.resolveOnline(ctx, "닉네임"))))))
                .then(CmdKit.literal("가격").then(CmdKit.word("등급또는물고기", gradeOrFish)
                        .then(CmdKit.literal("기본값").executes(CmdKit.run(ctx -> price(ctx, null))))
                        .then(CmdKit.argument("값", DoubleArgumentType.doubleArg(0, 1e12)).executes(CmdKit.run(ctx -> price(ctx, DoubleArgumentType.getDouble(ctx, "값")))))))
                .then(CmdKit.literal("크기보정").then(CmdKit.argument("최소", DoubleArgumentType.doubleArg(0, 1e12)).then(CmdKit.argument("최대", DoubleArgumentType.doubleArg(0, 1e12)).executes(CmdKit.run(ctx -> {
                    double min = DoubleArgumentType.getDouble(ctx, "최소"), max = DoubleArgumentType.getDouble(ctx, "최대");
                    if (max < min) throw ChacaApi.error("최대 배율은 최소 배율 이상이어야 해요");
                    yaml.set("prices.size-min", min); yaml.set("prices.size-max", max); saveChecked(CmdKit.sender(ctx), "크기 보정을 바꿨어요 (×" + AdminCommandRules.number(min) + " ~ ×" + AdminCommandRules.number(max) + ")");
                })))))
                .then(CmdKit.literal("조건보정").then(CmdKit.argument("배율", DoubleArgumentType.doubleArg(0, 1e12)).executes(CmdKit.run(ctx -> {
                    double v = DoubleArgumentType.getDouble(ctx, "배율");
                    yaml.set("prices.special-bonus", v); saveChecked(CmdKit.sender(ctx), "조건 보정을 ×" + AdminCommandRules.number(v) + "로 바꿨어요");
                }))))
                .then(CmdKit.literal("이름").then(CmdKit.argument("이름", com.mojang.brigadier.arguments.StringArgumentType.greedyString()).executes(CmdKit.run(ctx -> {
                    String name = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "이름").strip();
                    if (name.isEmpty() || name.length() > FishShopProtocol.MAX_TEXT) throw ChacaApi.error("이름은 1~" + FishShopProtocol.MAX_TEXT + "자예요");
                    yaml.set("npc-name", name); saveChecked(CmdKit.sender(ctx), "판매소 NPC 이름을 " + name + "(으)로 바꿨어요");
                }))))
                .then(CmdKit.literal("초상").then(CmdKit.word("초상", () -> List.of(portrait.isEmpty() ? "elena-neutral" : portrait)).executes(CmdKit.run(ctx -> {
                    String id = CmdKit.text(ctx, "초상");
                    if (!id.matches("[a-z0-9_/-]{1,120}") || id.contains("..")) throw ChacaApi.error("초상 ID는 영문 소문자·숫자·_·-·/ 만 써요 (textures/gui/dialogue/<초상>.png)");
                    yaml.set("portrait", id); saveChecked(CmdKit.sender(ctx), "판매소 초상을 " + id + "(으)로 바꿨어요");
                }))))
                .then(CmdKit.literal("가격표").executes(CmdKit.run(ctx -> CmdKit.lines(CmdKit.sender(ctx), priceLines(null))))
                        .then(CmdKit.word("물고기", () -> AdminCommandRules.catalogChoices(fishNames())).executes(CmdKit.run(ctx -> CmdKit.lines(CmdKit.sender(ctx), priceLines(CmdKit.pick(fishNames(), CmdKit.text(ctx, "물고기"), "물고기")))))));
        ChacaApi.branch(plugin, ChacaApi.상점관리, root, "물고기 판매소 열기·가격·보정·이름·초상·가격표", "물고기판매소 가격표");
        ChacaApi.reload(plugin, "물고기판매소", "fish-shop.yml (물고기 판매소 가격, 학교·야생 공유)", () -> {
            String before = localText();
            reload();
            String attempted = localText();
            shared.publish(attempted, "reload", failed -> {
                if (failed == null) return;
                if (localText().equals(attempted)) applyText(before);
                plugin.getLogger().warning("물고기 판매소: 다른 서버와 공유하지 못해서 되돌렸습니다: " + failed);
            });
        });
    }

    /** 설정 저장. 설정 오류(검사 실패)는 콘솔에 남기고 유저에게는 짧은 문구. */
    private void saveChecked(CommandSender sender, String done) throws CommandSyntaxException {
        try { save(sender, done); }
        catch (IllegalArgumentException invalid) { plugin.getLogger().warning("물고기 판매소 설정 거절: " + invalid.getMessage()); throw ChacaApi.error("그 값으로는 가격을 계산할 수 없어요 (자세한 내용은 콘솔)"); }
        catch (Exception error) { throw new IllegalStateException("fish-shop.yml save failed", error); }
    }

    private void price(CommandContext<CommandSourceStack> ctx, Double value) throws CommandSyntaxException {
        String input = CmdKit.text(ctx, "등급또는물고기");
        int g = FishingProtocol.gradeIndex(input);
        if (g >= 0 && Arrays.asList(FishingProtocol.GRADE_LABELS).contains(input)) {
            if (value == null) throw ChacaApi.error("등급 가격은 숫자로 정해 주세요 (기본값은 물고기별 가격에만)");
            yaml.set("prices.grade." + input, value);
            saveChecked(CmdKit.sender(ctx), input + " 등급 기본 가격을 " + AdminCommandRules.number(value) + " G로 바꿨어요");
            return;
        }
        String id = CmdKit.pick(fishNames(), input, "물고기");
        yaml.set("prices.fish." + id, value);
        saveChecked(CmdKit.sender(ctx), fishNames().get(id) + " 가격을 " + (value == null ? "등급 기본값으로" : AdminCommandRules.number(value) + " G로") + " 바꿨어요");
    }

    /** /상점관리 물고기판매소 열기, /테스트 낚시 판매소: NPC 없이 창을 연다. */
    void adminOpen(Player target) throws CommandSyntaxException {
        if (!modded(target)) throw ChacaApi.error(plugin.names().name(target) + " 님은 차카데미 모드가 없어 판매소 창을 열 수 없어요");
        open(target, null, null);
    }

    /** 가격표 (fishId null = 등급 요약). */
    List<String> priceLines(String fishId) {
        var out = new ArrayList<String>();
        if (fishId != null) {
            var f = fishing.config().fishById.get(fishId);
            out.add("── " + f.name() + " (" + FishingProtocol.GRADE_LABELS[f.grade()] + ") 가격 ──");
            out.add("기본 " + Math.round(base(f)) + " G" + (overrides.containsKey(f.id()) ? " (물고기별 가격)" : special(f) ? " (조건 보정 포함)" : ""));
            out.add(FishShopProtocol.price(base(f), f.minSize(), f.minSize(), f.maxSize(), sizeMin, sizeMax) + " G (" + f.minSize() + "cm) ~ "
                    + FishShopProtocol.price(base(f), f.maxSize(), f.minSize(), f.maxSize(), sizeMin, sizeMax) + " G (" + f.maxSize() + "cm)");
            return out;
        }
        out.add("── 물고기 판매소 가격표 ──");
        for (int g = 0; g < 4; g++) out.add(FishingProtocol.GRADE_LABELS[g] + " 기본 " + Math.round(gradeBase[g]) + " G");
        out.add("크기 보정 ×" + AdminCommandRules.number(sizeMin) + " ~ ×" + AdminCommandRules.number(sizeMax) + " · 조건 보정 ×" + AdminCommandRules.number(specialBonus) + " · 물고기별 가격 " + overrides.size() + "종");
        return out;
    }

    private static String cut(String s) { return s == null ? "" : s.length() > FishShopProtocol.MAX_TEXT ? s.substring(0, FishShopProtocol.MAX_TEXT) : s; }
}
