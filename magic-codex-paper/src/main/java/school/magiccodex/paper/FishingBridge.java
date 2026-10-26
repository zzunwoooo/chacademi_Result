package school.magiccodex.paper;

import java.io.File;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.*;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.util.Vector;
import school.magiccodex.protocol.FishingProtocol;
import school.magiccodex.protocol.FishingProtocol.*;

/**
 * 낚시 (2026-10-20, owner-approved plan): the panel minigame on top of the vanilla fishing rod.
 *
 * Flow: the player casts a fishing rod as usual -> when the bobber bobs in water the server measures the water
 * (FishingWater) and opens the panel (SESSION, phase WATER) -> the client sinks the bobber and reports where it stopped
 * (STOP) -> the server picks what bites (biome group, season, time, weather, depth, water size, bait) and sends BITE ->
 * the client plays teases, the hook window and the tug of war, then reports RESULT -> the server checks the timing,
 * pays the catch (inventory, else mailbox), writes the codex and the gear wear into the rod's PDC, removes the bobber
 * and puts the panel back to "ready" (phase READY). Clicking there sends RECAST: the server throws a real bobber again
 * towards the first cast's landing spot (owner 2026-10-20).
 * Vanilla bites and loot never happen for a hook in a session. Gear changes are refused while a bobber is out.
 * Everything runs on the main thread except the codex database (FishingStore).
 */
final class FishingBridge implements PluginMessageListener, Listener {
    private static final int LANDING = 0, READY = 1, SINKING = 2, BITING = 3;
    private static final String[] FILES = {"fishing.yml", "fishing-fish.yml", "fishing-gear.yml", "fishing-treasure.yml"};

    private final class Session {
        final long id = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        final UUID player;
        FishHook hook;
        Location target;          // where the first cast landed
        int state = LANDING;
        long since = System.currentTimeMillis();
        boolean ownCast, preview;
        int previewBackground;
        FishingWater water;
        float maxDepth, floorDepth;
        // the bite
        int token;
        FishingConfig.Fish fish;
        int chest = -1;
        float size, depth;
        long sentAt;
        int minimum;
        int rodSlot = -1;          // held hotbar slot when the fish was picked
        List<FishingProtocol.Attack> attacks = List.of();
        int waitMs;
        Session(UUID player) { this.player = player; }
    }

    private final MagicCodexBridge plugin;
    private FishingConfig config;
    private final FishingGear gear;
    private final FishingStore store;
    private final FishTank tank;
    private final FishMailOutbox outbox;
    private final FishingGearStore gearStore;
    private final ItemsAdderItems itemsAdder;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, long[]> limits = new HashMap<>();

    FishingBridge(MagicCodexBridge plugin) throws Exception {
        this.plugin = plugin;
        for (String f : FILES) if (!new File(plugin.getDataFolder(), f).exists()) plugin.saveResource(f, false);
        config = FishingConfig.load(plugin.getDataFolder());
        itemsAdder = new ItemsAdderItems(plugin.getLogger());
        gearStore = new FishingGearStore(plugin);
        gear = new FishingGear(plugin, config, itemsAdder, gearStore);
        store = new FishingStore(plugin);
        tank = new FishTank(plugin);
        outbox = new FishMailOutbox(plugin.getDataFolder().toPath().resolve("fish-mail-outbox"));
        tank.capacity(config.tankCapacity);
        Bukkit.getMessenger().registerIncomingPluginChannel(plugin, FishingProtocol.REQUEST, this);
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, FishingProtocol.RESPONSE);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        CmdKit.register(plugin, "/낚시도감·/유저관리 낚시·/아이템관리 낚시장비·물고기·/테스트 낚시", this::commands);
        for (Player p : Bukkit.getOnlinePlayers()) { store.load(p.getUniqueId(), null); tank.refresh(p.getUniqueId(), null); preloadGear(p); }
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 2, 2);
        Bukkit.getScheduler().runTask(plugin, this::recoverOutbox);   // 다른 플러그인(우편)까지 켜진 뒤
        plugin.getLogger().info("낚시: 물고기 " + config.fish.size() + "종, 장비 " + config.gear.stream().mapToInt(Map::size).sum() + "종");
    }

    private FishTankBridge tankWindow;
    void tankWindow(FishTankBridge w) { tankWindow = w; }
    FishTank tank() { return tank; }
    /** A line is in the water (cast, sinking or a fish on it): the 아공간 수조 window waits until it is done. */
    boolean fishingNow(UUID player) {
        var s = sessions.get(player);
        return s != null && blocksTank(s.preview,s.state);
    }
    static boolean blocksTank(boolean preview,int state){return !preview&&state!=READY;}
    FishingConfig config() { return config; }
    FishingGear gear() { return gear; }

    /** A fish item (taken out of the tank) for this tank row, or null when the species no longer exists. */
    ItemStack fishItem(FishTank.Fish f) {
        var def = config.fishById.get(f.fish());
        return def == null ? null : gear.fish(def, (float) f.size(), f.id(), f.caughtAt(), f.token());
    }

    void close() {
        for (var s : List.copyOf(sessions.values())) { removeHook(s); Player p = Bukkit.getPlayer(s.player); if (p != null) send(p, new Close(s.id, "")); }
        sessions.clear();
        store.close();
        tank.close();
        gearStore.close();
    }
    FishingStore store() { return store; }

    /** Gear rows of everything the player carries (owner: durability lives in the DB), so casting never waits. */
    private void preloadGear(Player p) {
        var items = new ArrayList<ItemStack>();
        for (ItemStack it : p.getInventory().getContents()) if (it != null && gear.kindOf(it) >= 0) items.add(it);
        gear.load(items, null);
    }

    // ------------------------------------------------------------------ vanilla rod

    @EventHandler(priority = EventPriority.HIGH)
    public void fish(PlayerFishEvent e) {
        Player p = e.getPlayer();
        Session s = sessions.get(p.getUniqueId());
        switch (e.getState()) {
            case FISHING -> {
                // only refusals here; the session starts at MONITOR (after other plugins had their say)
                if (!config.enabled || e.isCancelled() || !modded(p)) return;
                ItemStack rod = rodOf(p);
                if (rod == null) return;
                if (!gear.ready(rod)) {
                    e.setCancelled(true);
                    gear.load(List.of(rod), null);
                    CmdKit.notify(p, "warning", "fishing-cast", "아직 던질 수 없어요", "낚싯대 정보를 불러오는 중이에요. 잠시 뒤 다시 던져 주세요.", 2000);
                    return;
                }
                var l = gear.read(rod);
                if (!l.rod().fallback() && l.rod().value() <= 0) {
                    e.setCancelled(true);
                    CmdKit.notify(p, "warning", "fishing-cast", "낚싯대가 고장 났어요", "수리한 뒤 다시 던져 주세요.", 2000);
                    return;
                }
                if (s != null && !s.preview && (s.state == SINKING || s.state == BITING)) { e.setCancelled(true); return; }
                if (tank.full(p.getUniqueId())) {
                    // owner 2026-10-20: a full tank blocks fishing
                    e.setCancelled(true);
                    CmdKit.notify(p, "warning", "fishing-cast", "아공간 수조가 가득 찼어요 (" + tank.count(p.getUniqueId()) + "/" + tank.capacity() + ")", "물고기를 팔거나 꺼낸 뒤 낚시해 주세요.", 2000);
                }
            }
            case BITE, CAUGHT_FISH, LURED -> {
                if (s != null && s.hook != null && s.hook.equals(e.getHook())) { e.setCancelled(true); e.setExpToDrop(0); }
            }
            default -> {
                // REEL_IN, IN_GROUND, FAILED_ATTEMPT, CAUGHT_ENTITY: the player pulled the bobber back by hand
                if (s != null && s.hook != null && s.hook.equals(e.getHook())) {
                    if (s.state == BITING) penalty(p, s);
                    if (s.state == SINKING || s.state == BITING) { end(p, s, ""); }
                    else if (s.state == LANDING) { if (s.ownCast) ready(p, s, ""); else sessions.remove(p.getUniqueId()); }
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void cast(PlayerFishEvent e) {
        if (e.getState() != PlayerFishEvent.State.FISHING || !config.enabled) return;
        Player p = e.getPlayer();
        if (!modded(p) || rodOf(p) == null) return;     // no MagicCodex mod: plain vanilla fishing
        Session s = sessions.get(p.getUniqueId());
        if (s != null && s.preview) { sessions.remove(p.getUniqueId()); s = null; }
        if (s != null && (s.state == SINKING || s.state == BITING)) return;
        if (s == null) { s = new Session(p.getUniqueId()); sessions.put(p.getUniqueId(), s); }
        s.hook = e.getHook(); s.state = LANDING; s.since = System.currentTimeMillis();
        if (!s.ownCast) s.target = null;
        quiet(s.hook);
    }

    /** The MagicCodex client listens on the fishing channel (players without it fish the vanilla way). */
    private static boolean modded(Player p) { return p.getListeningPluginChannels().contains(FishingProtocol.RESPONSE); }

    /** Vanilla must never bite this hook (the minigame decides). */
    private static void quiet(FishHook hook) {
        try {
            hook.setApplyLure(false);
            hook.setMaxWaitTime(20 * 60 * 60); hook.setMinWaitTime(20 * 60 * 60 - 1); hook.setWaitTime(20 * 60 * 60 - 1);
        } catch (RuntimeException | LinkageError ignored) {}
    }

    /** The rod in the main hand (owner set: the gear window follows the held rod; an offhand rod fishes the vanilla way). */
    private ItemStack rodOf(Player p) {
        ItemStack main = p.getInventory().getItemInMainHand();
        return FishingGear.isRod(main) ? main : null;
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (var s : List.copyOf(sessions.values())) {
            Player p = Bukkit.getPlayer(s.player);
            if (p == null) { removeHook(s); sessions.remove(s.player); continue; }
            if (s.preview) continue;
            switch (s.state) {
                case LANDING -> {
                    if (s.hook == null || !s.hook.isValid()) { if (s.ownCast) ready(p, s, "찌가 물에 닿지 않았어요."); else sessions.remove(s.player); continue; }
                    boolean bobbing;
                    try { bobbing = s.hook.getState() == FishHook.HookState.BOBBING; } catch (LinkageError err) { bobbing = s.hook.isInWater(); }
                    if (bobbing || (s.hook.isInWater() && s.hook.getVelocity().lengthSquared() < .01)) land(p, s);
                    else if (now - s.since > 8000) { removeHook(s); if (s.ownCast) ready(p, s, "찌가 물에 닿지 않았어요."); else sessions.remove(s.player); }
                }
                case SINKING, BITING -> {
                    if (s.hook == null || !s.hook.isValid()) { end(p, s, "찌가 사라져서 낚시를 멈췄어요."); continue; }
                    if (!s.hook.getWorld().equals(p.getWorld()) || s.hook.getLocation().distanceSquared(p.getLocation()) > 48 * 48) {
                        end(p, s, config.message("far", "찌가 너무 멀어져서 낚시를 멈췄어요.")); continue;
                    }
                    if (s.state == BITING && now - s.sentAt > 5 * 60_000) { penalty(p, s); end(p, s, ""); }
                    if (s.state == SINKING && now - s.since > 90_000) end(p, s, "");
                }
                default -> {}
            }
        }
    }

    private void land(Player p, Session s) {
        var water = FishingWater.measure(s.hook.getLocation(), config);
        if (water == null) return;   // still dropping into it
        quiet(s.hook);
        s.water = water;
        if (s.target == null) s.target = s.hook.getLocation().clone();
        var l = gear.read(rodOf(p));
        float cap = config.depthCap(water.depth());
        // the bobber may go to 85% of the line: the last 15% is the room a hooked fish can pull before the line runs out
        float reach = l.line().gear().a() * .85f;
        s.maxDepth = Math.min(FishingProtocol.MAX_DEPTH, cap <= 0 ? reach : Math.min(cap, reach));
        s.floorDepth = cap <= 0 ? FishingProtocol.MAX_DEPTH : Math.min(FishingProtocol.MAX_DEPTH, cap);
        s.state = SINKING; s.since = System.currentTimeMillis();
        send(p, new FishingProtocol.Session(s.id, FishingProtocol.PHASE_WATER, water.background(), s.maxDepth, s.floorDepth, config.waterClassOf(water.area()),
                Math.min(water.area(), 1_000_000), tank.count(p.getUniqueId()), tank.capacity(), gear.protocol(l), ""));
    }

    private void ready(Player p, Session s, String notice) {
        s.state = READY; s.ownCast = false; s.hook = null; s.since = System.currentTimeMillis();
        var l = gear.read(rodOf(p));
        int bg = s.preview ? s.previewBackground : s.water == null ? 0 : s.water.background();
        send(p, new FishingProtocol.Session(s.id, FishingProtocol.PHASE_READY, bg, s.maxDepth, Math.max(s.maxDepth, s.floorDepth), s.water == null ? 0 : config.waterClassOf(s.water.area()),
                s.water == null ? 0 : Math.min(s.water.area(), 1_000_000), tank.count(p.getUniqueId()), tank.capacity(), gear.protocol(l), cut(notice)));
    }

    private void end(Player p, Session s, String notice) {
        removeHook(s);
        sessions.remove(s.player);
        send(p, new Close(s.id, cut(notice)));
    }

    private static void removeHook(Session s) {
        if (s.hook != null && s.hook.isValid()) s.hook.remove();
        s.hook = null;
    }

    @EventHandler public void quit(PlayerQuitEvent e) {
        var s = sessions.remove(e.getPlayer().getUniqueId());
        if (s != null) removeHook(s);
        limits.remove(e.getPlayer().getUniqueId());
        store.forget(e.getPlayer().getUniqueId());
        tank.forget(e.getPlayer().getUniqueId());
    }
    @EventHandler public void join(PlayerJoinEvent e) { store.load(e.getPlayer().getUniqueId(), null); tank.refresh(e.getPlayer().getUniqueId(), null); preloadGear(e.getPlayer()); resolveOutbox(e.getPlayer()); }
    @EventHandler public void changedWorld(PlayerChangedWorldEvent e) {
        var s = sessions.get(e.getPlayer().getUniqueId());
        if (s != null && !s.preview) end(e.getPlayer(), s, "");
    }

    // ------------------------------------------------------------------ requests

    @Override public void onPluginMessageReceived(String channel, Player p, byte[] bytes) {
        if (!FishingProtocol.REQUEST.equals(channel)) return;
        long[] w = limits.computeIfAbsent(p.getUniqueId(), k -> new long[2]);
        long now = System.currentTimeMillis();
        if (now - w[0] > 1000) { w[0] = now; w[1] = 0; }
        if (++w[1] > 20) return;
        Request r;
        try { r = FishingProtocol.request(bytes); } catch (IllegalArgumentException e) { return; }
        Session s = sessions.get(p.getUniqueId());
        boolean ours = s != null && s.id == r.session();
        switch (r.action()) {
            case FishingProtocol.STOP -> { if (ours && s.state == SINKING) bite(p, s, Math.min(r.depth(), s.maxDepth)); }
            case FishingProtocol.RESULT -> { if (ours && s.state == BITING && r.token() == s.token) result(p, s, r.outcome(), r.heldMask()); }
            case FishingProtocol.RECAST -> { if (ours && s.state == READY) recast(p, s); }
            case FishingProtocol.CANCEL -> { if (ours) { if (s.state == BITING) penalty(p, s); removeHook(s); sessions.remove(p.getUniqueId()); } }
            case FishingProtocol.GEAR_OPEN -> sendGear(p, "");
            case FishingProtocol.GEAR_EQUIP -> equip(p, r.kind(), r.slot());
            case FishingProtocol.CODEX_OPEN -> openCodex(p);
            case FishingProtocol.CODEX_CLAIM -> claim(p, r.tier());
            default -> {}
        }
    }

    // ------------------------------------------------------------------ the bite

    private int season() {
        var c = plugin.climateService();
        return c == null ? -1 : c.season().ordinal();
    }

    private boolean timeMatches(String time, long t) {
        if (time.equals("전체")) return true;
        int[] r = config.times.get(time);
        if (r == null) return true;
        return r[0] <= r[1] ? t >= r[0] && t < r[1] : t >= r[0] || t < r[1];
    }

    private boolean eligible(FishingConfig.Fish f, Session s, World world, int season, float depth) {
        if (depth < f.minDepth() || depth > f.maxDepth()) return false;
        if (s.preview) return true;
        var w = s.water;
        int wc = config.waterClassOf(w.area());
        if (wc < f.waterMin() || wc > f.waterMax()) return false;
        if (!f.biomes().isEmpty() && Collections.disjoint(f.biomes(), w.groups())) return false;
        if (!f.seasons().isEmpty() && season >= 0 && !f.seasons().contains(ClimateService.Season.values()[season].label)) return false;
        if (!timeMatches(f.time(), world.getTime())) return false;
        switch (f.weather()) {
            case "맑음" -> { if (world.hasStorm()) return false; }
            case "비" -> { if (!world.hasStorm()) return false; }
            case "뇌우" -> { if (!world.isThundering()) return false; }
            default -> {}
        }
        if (f.underground() && !w.underground()) return false;
        if (f.yMin() != null && w.surfaceY() < f.yMin()) return false;
        if (f.yMax() != null && w.surfaceY() > f.yMax()) return false;
        if (!f.moon().isEmpty()) {
            long phase = (world.getFullTime() / 24000) % 8;
            boolean night = timeMatches("밤", world.getTime());
            if (!night || (f.moon().equals("full") && phase != 0) || (f.moon().equals("new") && phase != 4)) return false;
        }
        return true;
    }

    /** 377 물밑 엿보기 (LifeSpells): surfaceY, a one-line summary and the chat lines; null = no water there. */
    record Glimpse(int surfaceY, String summary, List<String> lines) {}

    /**
     * The water at this point (same measurement as a cast) and the fish whose PLACE rules fit it (water size, biome group,
     * underground, height, a depth the bobber can reach). Time, weather, season and moon are not checked (they change).
     * Fish not in this player's codex are shown as ???.
     */
    Glimpse glimpse(Player p, Location at) {
        var w = FishingWater.measure(at, config);
        if (w == null) return null;
        int wc = config.waterClassOf(w.area());
        float cap = config.depthCap(w.depth());
        float reach = cap <= 0 ? FishingProtocol.MAX_DEPTH : Math.min(cap, FishingProtocol.MAX_DEPTH);
        var book = store.book(p.getUniqueId());
        if (!book.loaded) store.load(p.getUniqueId(), null);
        var byGrade = new ArrayList<List<String>>();
        for (int g = 0; g < 4; g++) byGrade.add(new ArrayList<>());
        synchronized (book) {
            for (var f : config.fish) {
                if (f.minDepth() > reach || wc < f.waterMin() || wc > f.waterMax()) continue;
                if (!f.biomes().isEmpty() && Collections.disjoint(f.biomes(), w.groups())) continue;
                if (f.underground() && !w.underground()) continue;
                if (f.yMin() != null && w.surfaceY() < f.yMin()) continue;
                if (f.yMax() != null && w.surfaceY() > f.yMax()) continue;
                byGrade.get(f.grade()).add(book.loaded && book.rows.containsKey(f.id()) ? f.name() : "???");
            }
        }
        String size = FishingConfig.WATER[wc] + (w.area() >= Integer.MAX_VALUE / 4 ? " (아주 넓음)" : " (" + w.area() + "칸)");
        String place = w.groups().isEmpty() ? w.biome().replace("minecraft:", "") : String.join("·", new TreeSet<>(w.groups()));
        String depth = w.depth() >= 64 ? "64블록 넘게" : w.depth() + "블록";
        var lines = new ArrayList<String>();
        lines.add("── 물밑 엿보기 ──");
        lines.add(" 깊이 " + depth + " · 넓이 " + size + " · " + place + (w.underground() ? " · 지하" : ""));
        int total = 0;
        for (int g = 0; g < 4; g++) {
            var names = byGrade.get(g);
            total += names.size();
            if (names.isEmpty()) continue;
            var shown = names.size() > 8 ? names.subList(0, 8) : names;
            lines.add(" " + FishingProtocol.GRADE_LABELS[g] + " " + names.size() + "종: " + String.join(", ", shown) + (names.size() > 8 ? " 외 " + (names.size() - 8) + "종" : ""));
        }
        if (total == 0) lines.add(" 이 물에서 낚이는 물고기가 없어요");
        else lines.add(" (시간·날씨·계절에 따라 낚이는 물고기가 달라요)");
        return new Glimpse(w.surfaceY(), "깊이 " + depth + " · " + FishingConfig.WATER[wc] + " · 물고기 " + total + "종", lines);
    }

    private void bite(Player p, Session s, float depth) {
        var rnd = ThreadLocalRandom.current();
        var l = gear.read(rodOf(p));
        var bait = l.bait().gear();
        World world = s.preview ? p.getWorld() : s.hook.getWorld();
        int zone = config.zone(depth);
        s.depth = depth; s.fish = null; s.chest = -1;
        double chestChance = config.treasureChance + bait.effect("treasure", 0);
        if (!s.preview && LifeSpells.active(p.getUniqueId(), "treasure_scent")) chestChance *= 2;   // 379 보물 냄새 (LifeSpells)
        int shape, power, pattern;
        float size;
        if (rnd.nextDouble() < chestChance) {
            s.chest = pickGrade(config.treasureRarity[zone], new boolean[]{true, true, true, true}, 1);
            shape = FishingProtocol.SHAPE_CHEST; power = 3 + s.chest; pattern = FishingProtocol.CALM; size = .35f + .1f * s.chest; s.size = 0;
        } else {
            int season = season();
            var pool = new ArrayList<FishingConfig.Fish>();
            for (var f : config.fish) if (eligible(f, s, world, season, depth)) pool.add(f);
            if (pool.isEmpty()) for (var f : config.fish) if (f.biomes().isEmpty() && f.grade() == 0 && depth >= f.minDepth() && depth <= f.maxDepth()) pool.add(f);
            if (pool.isEmpty()) pool.add(config.fish.getFirst());
            boolean[] has = new boolean[4];
            for (var f : pool) has[f.grade()] = true;
            float bonus = (1 + bait.a() / 100f) * (float) plugin.petFishing(p);   // 동행 펫 passive fishing
            int grade = pickGrade(config.rarity[zone], has, bonus);
            double total = 0;
            var weights = new double[pool.size()];
            for (int i = 0; i < pool.size(); i++) {
                var f = pool.get(i);
                if (f.grade() != grade) continue;
                double wgt = 1;
                if (f.time().equals("밤")) wgt *= bait.effect("night_fish", 1);
                if (f.magic()) wgt *= bait.effect("magic_fish", 1);
                if (f.minDepth() >= 50) wgt *= bait.effect("deep_fish", 1);
                weights[i] = wgt; total += wgt;
            }
            double pick = rnd.nextDouble() * total;
            for (int i = 0; i < pool.size(); i++) {
                if (weights[i] <= 0) continue;
                pick -= weights[i];
                if (pick <= 0) { s.fish = pool.get(i); break; }
            }
            if (s.fish == null) s.fish = pool.getLast();
            var f = s.fish;
            float u = (float) Math.pow(rnd.nextDouble(), 1.6);
            s.size = Math.round((f.minSize() + (f.maxSize() - f.minSize()) * u) * 10) / 10f;
            size = (float) Math.clamp((Math.log(s.size) - Math.log(3)) / (Math.log(1200) - Math.log(3)), 0, 1);
            shape = f.shape(); power = f.power(); pattern = f.pattern();
        }
        float wait = config.waitMin + (config.waitMax - config.waitMin) * rnd.nextFloat();
        boolean ocean = s.water != null && s.water.groups().stream().anyMatch(config.oceanGroups::contains);
        float reduction = Math.clamp(bait.b(), 0, 90);
        if ((bait.has("fresh_wait") && ocean) || (bait.has("sea_wait") && !ocean)) reduction = 0;   // wrong water: no help
        wait *= 1 - reduction / 100f;
        if (timeMatches("밤", world.getTime())) wait *= l.flt().gear().effect("night_wait", 1);
        int teases = config.teaseMin + rnd.nextInt(config.teaseMax - config.teaseMin + 1);
        int grade = l.flt().gear().has("grade_glow") ? (s.fish != null ? s.fish.grade() : s.chest) : -1;
        int window = Math.clamp(Math.round(l.rod().gear().b() * 1000), 100, 5000);
        s.token = rnd.nextInt(1, Integer.MAX_VALUE);
        // hook gauge (owner: faster and harder for better fish; 일반 stays as it was): needle time and zone by grade
        int realGrade = s.fish != null ? s.fish.grade() : Math.max(0, s.chest);
        float[] speed = {1f, 1.25f, 1.5f, 2f}, zones = {.22f, .18f, .15f, .12f};
        int sweep = Math.clamp(Math.round(l.rod().gear().b() / .22f / speed[realGrade] * 1000), 500, 20000);
        // strain (owner: low-grade gear deep down almost always fails): how far into what this line can reach, by gear grade
        float reach = l.line().gear().a() * .85f;
        float into = (float) Math.clamp((depth - .55 * reach) / Math.max(.1, .45 * reach), 0, 1);
        float gearGrade = (l.rod().gear().grade() + l.line().gear().grade()) / 2f;
        float strain = Math.clamp(1 + .9f * into * (1 - gearGrade / 3f * .85f), .5f, 5f);
        // 378 잔잔한 물결 (LifeSpells): this bite's strain x0.7 (the client's tension rise, fish pull and line heat all scale with it).
        if (!s.preview && LifeSpells.take(p.getUniqueId(), "calm_ripple")) {
            strain = Math.max(.5f, strain * .7f);
            CmdKit.notify(p, "system", "life-calm_ripple", "잔잔한 물결", "이번 낚시는 줄 장력이 천천히 올라요", 0);
        }
        // fish attacks during the reel (extra wear on the line / rod)
        var attacks = new ArrayList<FishingProtocol.Attack>();
        if (s.fish != null) {
            int n = switch (realGrade) { case 0 -> rnd.nextFloat() < .3f ? 1 : 0; case 1 -> rnd.nextFloat() < .6f ? 1 : 0; case 2 -> 1 + rnd.nextInt(2); default -> 2 + rnd.nextInt(3); };
            float reel = FishingProtocol.reelSpeed(l.rod().gear().a()) * .75f * .5f;
            int span = (int) Math.clamp(depth / Math.max(.3f, reel) * 1000 * .9f, 3000, 120000);
            int at = 1500;
            for (int i = 0; i < n; i++) {
                at += 1500 + rnd.nextInt(Math.max(1, span / Math.max(1, n)));
                int damage = new int[]{2, 3, 4, 6}[realGrade] + power / 3;
                attacks.add(new FishingProtocol.Attack(Math.min(at, 600000), rnd.nextBoolean() ? FishingProtocol.ATTACK_LINE : FishingProtocol.ATTACK_ROD, damage));
            }
        }
        s.attacks = List.copyOf(attacks);
        s.waitMs = Math.round(Math.min(wait, 59) * 1000);
        var b = new Bite(s.id, s.token, shape, size, grade, s.waitMs, teases, window, power, pattern, rnd.nextLong(), depth,
                s.fish != null && s.fish.magic(), sweep, zones[realGrade], strain, attacks);
        // C09 (2026-10-21): the fish is fixed here on the server; the bite frame no longer carries a legendary flag (v3), and the
        // catch must take at least as long as the fastest honest minigame for this fish (difficulty, depth, rod reel bonuses).
        var rodGear = l.rod().gear();
        float reelBonus = Math.max(1, rodGear.effect("sea_reel", 1)) * Math.max(1, rodGear.effect("magic_reel", 1));
        s.minimum = FishingProtocol.minimumCatchMillis(b, rodGear.a(), reelBonus);
        s.sentAt = System.currentTimeMillis();
        s.rodSlot = p.getInventory().getHeldItemSlot();
        if (send(p, b)) s.state = BITING; else end(p, s, "낚시 정보를 보내지 못했어요.");
    }

    private static int pickGrade(double[] weights, boolean[] allowed, float bonus) {
        double total = 0; double[] w = new double[4];
        for (int g = 0; g < 4; g++) if (allowed[g]) { w[g] = weights[g] * (g > 0 ? bonus : 1); total += w[g]; }
        if (total <= 0) { for (int g = 0; g < 4; g++) if (allowed[g]) return g; return 0; }
        double pick = ThreadLocalRandom.current().nextDouble() * total;
        for (int g = 0; g < 4; g++) { pick -= w[g]; if (w[g] > 0 && pick <= 0) return g; }
        for (int g = 3; g >= 0; g--) if (allowed[g]) return g;
        return 0;
    }

    // ------------------------------------------------------------------ result

    private void result(Player p, Session s, int outcome, int heldMask) {
        long elapsed = System.currentTimeMillis() - s.sentAt;
        if (outcome == FishingProtocol.CAUGHT && elapsed < s.minimum) {
            plugin.getLogger().warning("FISHING too fast player=" + p.getName() + " session=" + s.id + " fish=" + (s.fish != null ? s.fish.id() : "chest" + s.chest) + " elapsed=" + elapsed + "ms min=" + s.minimum + "ms");
            outcome = FishingProtocol.REFUSED;
        }
        if (s.preview) s.rodSlot = p.getInventory().getHeldItemSlot();
        if (outcome != FishingProtocol.REFUSED && !heldRod(p, s)) outcome = FishingProtocol.REFUSED;   // rod swapped after the bite
        var notes = new ArrayList<String>();
        // attacks that happened before the reported end wear the gear (doubled when the player kept winding)
        int lineHit = 0, rodHit = 0;
        boolean tugged = outcome == FishingProtocol.CAUGHT || outcome == FishingProtocol.SNAPPED || outcome == FishingProtocol.ESCAPED;
        if (tugged && !s.preview) for (int i = 0; i < s.attacks.size(); i++) {
            var a = s.attacks.get(i);
            if (elapsed + 1500 < s.waitMs + a.atMs()) continue;
            int d = a.damage() * ((heldMask >> i & 1) != 0 ? 2 : 1);
            if (a.kind() == FishingProtocol.ATTACK_LINE) lineHit += d; else rodHit += d;
        }
        var l = wearAndBait(p, s, outcome, notes, lineHit, rodHit);
        String fishId = "", name = ""; int grade = 0, shape = 0; float cm = 0; boolean record = false, fresh = false, mailed = false;
        var rewards = new ArrayList<Reward>();
        int shown = outcome;
        if (outcome == FishingProtocol.CAUGHT) {
            var items = new ArrayList<ItemStack>();
            if (s.chest >= 0) {
                shown = FishingProtocol.TREASURE; grade = s.chest; shape = FishingProtocol.SHAPE_CHEST; name = FishingProtocol.GRADE_LABELS[grade] + " 보물상자";
                var t = config.treasure[grade];
                var rnd = ThreadLocalRandom.current();
                int sum = t.rewards().stream().mapToInt(FishingConfig.Reward::weight).sum();
                for (int i = 0; i < t.rolls() && sum > 0; i++) {
                    int pick = rnd.nextInt(sum);
                    for (var rw : t.rewards()) {
                        pick -= rw.weight();
                        if (pick < 0) { var it = item(rw.item(), rnd.nextInt(rw.min(), rw.max() + 1)); if (it != null) items.add(it); break; }
                    }
                }
                if (!s.preview) { var r = store.record(p.getUniqueId(), "treasure_" + grade, 0); fresh = r[0]; AuditStats.add(p.getUniqueId(), "보물상자", 1);
                    if (grade >= 1) { var got = new StringBuilder(); for (var it : items) got.append(got.length() == 0 ? "" : ", ").append(AuditLog.item(it));
                        AuditLog.log(p, AuditLog.낚시, "보물상자 · " + name + " · " + AuditText.clip(got.toString(), 150), "종류", "보물상자", "등급", grade, "아이템", got.toString(), "위치", AuditLog.where(p.getLocation())); } }
            } else {
                var f = s.fish;
                fishId = f.id(); name = f.name(); grade = f.grade(); shape = f.shape(); cm = s.size;
                // owner 2026-10-20: fish go into the 아공간 수조 tank, not the inventory
                if (!s.preview) {
                    var r = store.record(p.getUniqueId(), f.id(), cm); fresh = r[0]; record = r[1];
                    AuditStats.add(p.getUniqueId(), "낚시", 1); AuditStats.add(p.getUniqueId(), "낚시:등급" + grade, 1);
                    if (grade >= 1) AuditLog.log(p, AuditLog.낚시, "낚시 · " + FishingProtocol.GRADE_LABELS[grade] + " " + name + " " + String.format("%.1f", cm) + "cm" + (fresh ? " (첫 발견)" : "") + (record ? " (최고 기록)" : ""),
                            "물고기", f.id(), "이름", name, "등급", grade, "크기", String.format("%.2f", cm), "첫발견", fresh ? "예" : "아니오", "위치", AuditLog.where(p.getLocation()));
                    // 낚시 칭호 (메모 #21): title-awards.yml fishing 규칙 (기본은 비어 있음 = 아무것도 안 함)
                    var titles = plugin.titleService(); if (titles != null) titles.fish(p.getUniqueId(), f.id(), cm);
                    tank.add(p.getUniqueId(),f.id(),cm,(saved,put)->{
                        // 스토리 "낚시성공": 물고기가 수조 DB 에 저장된 뒤에만 (보물상자·미리보기·보류 저장 제외)
                        if(put==FishTank.Put.OK)StoryProgressEvent.fire(p,StoryProgressEvent.FISH);
                        if(put!=FishTank.Put.OK&&p.isOnline())CmdKit.notify(p,"warning","fishing-kept","잡은 물고기는 안전하게 보존했어요","수조 공간과 저장소가 준비되면 자동으로 수조에 들어가요.",3000);
                    });
                } else fresh = true;
            }
            for (ItemStack it : merge(items)) rewards.add(new Reward(FishingGear.cut(AlchemyBridge.displayName(it)), it.getAmount(), preview(it)));
            if (rewards.size() > FishingProtocol.MAX_REWARDS) rewards.subList(FishingProtocol.MAX_REWARDS, rewards.size()).clear();
            if (!s.preview) mailed = give(p, items, "fishing:" + s.id + ":" + s.token);
            if (mailed) notes.add(config.message("mailed", "인벤토리가 가득 차서 우편으로 보냈어요."));
            if (grade == 3) p.getWorld().playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, .8f, 1.2f);
        } else if (outcome == FishingProtocol.REFUSED) notes.add("결과를 확인하지 못했어요. 다시 시도해 주세요.");
        else if (outcome == FishingProtocol.SNAPPED && !s.preview) AuditStats.add(p.getUniqueId(), "낚시줄끊김", 1); // 마법 발견 v2 잔잔한 물결
        removeHook(s);
        s.state = READY; s.ownCast = false; s.since = System.currentTimeMillis();
        send(p, new FishingProtocol.Result(s.id, shown, fishId, FishingGear.cut(name), grade, shape, cm, record, fresh, rewards, mailed,
                gear.protocol(l), tank.count(p.getUniqueId()), tank.capacity(), cut(String.join(" ", notes))));
    }

    private boolean heldRod(Player p, Session s) {
        return p.getInventory().getHeldItemSlot() == s.rodSlot && FishingGear.isRod(p.getInventory().getItemInMainHand());
    }

    /** Cancelling / reeling in by hand after the bite costs the same as losing the fish (no free re-rolls). */
    private void penalty(Player p, Session s) {
        if (s.preview || !heldRod(p, s)) return;
        var notes = new ArrayList<String>();
        wearAndBait(p, s, FishingProtocol.ESCAPED, notes, 0, 0);
        if (!notes.isEmpty()) CmdKit.notify(p, "warning", "fishing-penalty", "낚시를 멈췄어요", String.join(" ", notes), 1500);
    }

    /** Wear and bait use for one try, written into the held rod. Returns the loadout after it. */
    private FishingGear.Loadout wearAndBait(Player p, Session s, int outcome, List<String> notes, int lineHit, int rodHit) {
        ItemStack rod = rodOf(p);
        var l = gear.read(rod);
        if (s.preview || rod == null || outcome == FishingProtocol.REFUSED) return l;
        boolean tug = outcome == FishingProtocol.CAUGHT || outcome == FishingProtocol.SNAPPED || outcome == FishingProtocol.ESCAPED;
        // C09 (2026-10-21): scaring the fish off after the bite also uses the bait, so re-rolling bites is never free
        boolean baitUsed = tug || outcome == FishingProtocol.MISSED || outcome == FishingProtocol.SPOOKED;
        var returned = new ArrayList<ItemStack>();
        var rodPart = wear(l.rod(), tug ? 1 + rodHit : 0);
        var flt = wear(l.flt(), 1);
        var line = wear(l.line(), tug ? 1 + lineHit + (outcome == FishingProtocol.SNAPPED ? config.snapDamage : 0) : 0);
        var bait = l.bait();
        if (baitUsed && !bait.fallback()) bait = bait.value() - 1 <= 0 ? new FishingGear.Part(config.fallback[3], -1, true) : new FishingGear.Part(bait.gear(), bait.value() - 1, false);
        if (!rodPart.fallback() && rodPart.value() <= 0) notes.add(rodPart.gear().name() + "이(가) 고장 났어요.");
        if (!flt.fallback() && flt.value() <= 0) { returned.add(gear.create(flt.gear(), 0, 1)); notes.add(flt.gear().name() + "이(가) 고장 나서 빠졌어요."); flt = new FishingGear.Part(config.fallback[1], -1, true); }
        if (!line.fallback() && line.value() <= 0) { returned.add(gear.create(line.gear(), 0, 1)); notes.add(line.gear().name() + "이(가) 고장 나서 빠졌어요."); line = new FishingGear.Part(config.fallback[2], -1, true); }
        if (!l.bait().fallback() && bait.fallback()) notes.add("미끼를 다 썼어요.");
        var next = new FishingGear.Loadout(rodPart, flt, line, bait);
        if (!(l.rod().fallback() && next.flt().fallback() && next.line().fallback() && next.bait().fallback())) gear.write(rod, next);
        if (!returned.isEmpty()) give(p, returned, "fishing-gear:" + s.id + ":" + s.token);
        return next;
    }

    private static FishingGear.Part wear(FishingGear.Part p, int amount) {
        if (p.fallback() || amount <= 0) return p;
        return new FishingGear.Part(p.gear(), Math.max(0, p.value() - amount), false);
    }

    private ItemStack item(String key, int amount) {
        if (key.startsWith("minecraft:") || !key.contains(":")) {
            Material m = Material.matchMaterial(key);
            return m == null || !m.isItem() ? null : new ItemStack(m, Math.clamp(amount, 1, m.getMaxStackSize()));
        }
        return itemsAdder.create(key, amount);
    }
    private static List<ItemStack> merge(List<ItemStack> items) {
        var out = new ArrayList<ItemStack>();
        outer:
        for (ItemStack it : items) {
            for (ItemStack o : out) if (o.isSimilar(it) && o.getAmount() + it.getAmount() <= o.getMaxStackSize()) { o.setAmount(o.getAmount() + it.getAmount()); continue outer; }
            out.add(it.clone());
        }
        return out;
    }
    private static byte[] preview(ItemStack item) {
        try {
            ItemStack one = item.clone(); one.setAmount(1);
            byte[] b = one.serializeAsBytes();
            if (b.length <= FishingProtocol.MAX_PREVIEW) return b;
            b = new ItemStack(item.getType()).serializeAsBytes();
            return b.length <= FishingProtocol.MAX_PREVIEW ? b : new byte[0];
        } catch (RuntimeException e) { return new byte[0]; }
    }

    // ------------------------------------------------------------------ 보상 우편 대기열 (A01 안 B, 2026-10-21)

    enum Given { INVENTORY, MAIL, DROPPED }
    private static final String OUTBOX_TITLE = "낚시 보상", OUTBOX_BODY = "인벤토리가 가득 차서 낚시 보상을 우편으로 보내 드립니다.";
    /** 대기 중인 레코드 (source -> 레코드). 메인 스레드 전용. */
    private final Map<String, FishMailOutbox.Entry> outboxIndex = new LinkedHashMap<>();
    private final Set<String> outboxSending = new HashSet<>();
    private final Map<String, Integer> outboxRetries = new HashMap<>();
    private final List<java.nio.file.Path> outboxBroken = new ArrayList<>();

    /** Inventory first, the rest by durable mail. Returns true when something went to the mailbox. */
    boolean give(Player p, List<ItemStack> items, String source) { return deliver(p, items, source, null, null) == Given.MAIL; }

    /**
     * 인벤토리에 먼저 넣고, 남는 것은 우편 대기열 파일에 먼저 남긴 뒤 같은 틱에 영수증(PDC)과 saveData 를 하고 우편으로 보낸다.
     * 우편 DB 저장이 확인돼야 대기열에서 지운다. 실패·종료 중이면 레코드를 남겨 두고 다시 보낸다(시작·접속·1분 뒤 최대 3번).
     * 같은 source 로 다시 보내도 우편 source_key 가 UNIQUE 라 우편은 한 통만 생긴다.
     * tankClaim: 수조 꺼내기(TAKING)라면 우편 저장이 확인된 뒤에만 수조 기록을 ITEM 으로 닫는다.
     * mailed: 이번에 보낸 우편이 모두 저장됐는지(true) 아닌지(false, 레코드는 남음). MAIL 일 때만 불린다. 메인 스레드.
     */
    Given deliver(Player p, List<ItemStack> items, String source, FishTank.Fish tankClaim, java.util.function.Consumer<Boolean> mailed) {
        // 공통 지급 칸 판정·배치 (RewardDelivery, 2026-10-26): 핫바 + 잠금 해제된 지금 쪽 27칸만, 기존 우편 수령과 같은 순서.
        var next = RewardDelivery.copy(p.getInventory().getStorageContents());
        var leftover = new ArrayList<ItemStack>(RewardDelivery.place(next, RewardDelivery.usable(plugin, p), items.stream().map(ItemStack::clone).toList(), false));
        p.getInventory().setStorageContents(next);
        if (leftover.isEmpty()) return Given.INVENTORY;
        String receipt = receiptName(source);
        var entries = new ArrayList<FishMailOutbox.Entry>();
        try {
            if (outbox == null) throw new java.io.IOException("outbox unavailable");
            for (int part = 0; part * 9 < leftover.size(); part++) {
                var bytes = new ArrayList<byte[]>();
                for (ItemStack it : leftover.subList(part * 9, Math.min(leftover.size(), part * 9 + 9))) bytes.add(it.serializeAsBytes());
                entries.add(outbox.save(source + ":" + part, p.getUniqueId(), receipt, OUTBOX_TITLE, OUTBOX_BODY, bytes,
                        tankClaim == null ? "" : tankClaim.id(), tankClaim == null ? "" : tankClaim.token()));
            }
        } catch (java.io.IOException | RuntimeException e) {
            // 대기열에 남기지 못했다: 우편 없이 발밑에 떨어뜨린다 (사라지지 않게). 이미 쓴 레코드는 지운다.
            for (var en : entries) try { outbox.acknowledge(en); } catch (java.io.IOException ignored) {}
            plugin.getLogger().severe("FISHING OUTBOX write failed player=" + p.getUniqueId() + " source=" + source + " (" + e + "); dropped at feet: " + leftover);
            for (ItemStack it : leftover) p.getWorld().dropItemNaturally(p.getLocation(), it);
            CmdKit.notify(p, "warning", "fishing-drop", "보상을 발밑에 떨어뜨렸어요", "우편함에 넣지 못했어요. 바로 주워 주세요.", 0);
            return Given.DROPPED;
        }
        // 인벤토리 변화와 영수증을 같은 플레이어 데이터 스냅샷에 저장한다. 영수증이 디스크에 없으면 이 지급은 일어나지 않은 것이다.
        p.getPersistentDataContainer().set(receiptKey(receipt), org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
        try { p.saveData(); } catch (RuntimeException e) { plugin.getLogger().warning("FISHING OUTBOX saveData failed player=" + p.getUniqueId() + " source=" + source + " (" + e + "); 다음 저장 때 함께 저장됨"); }
        int[] left = {entries.size()}; boolean[] all = {true};
        for (var en : entries) {
            outboxIndex.put(en.source(), en);
            sendOutbox(en, ok -> { all[0] &= ok; if (--left[0] == 0 && mailed != null) mailed.accept(all[0]); });
        }
        plugin.getLogger().info("FISHING OUTBOX queued player=" + p.getUniqueId() + " source=" + source + " parts=" + entries.size() + (tankClaim == null ? "" : " tank=" + tankClaim.id()));
        return Given.MAIL;
    }

    static String receiptName(String source) { return "fish_mail_" + UUID.nameUUIDFromBytes(source.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString().replace("-", ""); }
    private NamespacedKey receiptKey(String receipt) { return new NamespacedKey(plugin, receipt); }
    private static java.nio.file.Path playerFile(UUID id) { return Bukkit.getWorlds().getFirst().getWorldFolder().toPath().resolve("playerdata").resolve(id + ".dat"); }

    /** 한 레코드를 우편으로 보낸다(메인 스레드). done 은 메인 스레드에서 한 번 불린다(종료 중이면 불리지 않을 수 있음). */
    private void sendOutbox(FishMailOutbox.Entry e, java.util.function.Consumer<Boolean> done) {
        java.util.function.Consumer<Boolean> finish = done == null ? ok -> {} : done;
        if (!outboxIndex.containsKey(e.source()) || !outboxSending.add(e.source())) { finish.accept(false); return; }
        var service = Bukkit.getServicesManager().load(MailService.class);
        var stacks = new ArrayList<ItemStack>();
        try { for (byte[] b : e.items()) stacks.add(ItemStack.deserializeBytes(b)); }
        catch (RuntimeException bad) { outboxSending.remove(e.source()); plugin.getLogger().severe("FISHING OUTBOX unreadable item; kept for admin source=" + e.source() + " (" + bad + ")"); finish.accept(false); return; }
        java.util.concurrent.CompletableFuture<UUID> sent;
        try {
            if (service == null) throw new IllegalStateException("mail service unavailable");
            sent = service.sendSystem(e.source(), e.recipient(), e.title(), e.body(), stacks);
        } catch (RuntimeException failed) { outboxSending.remove(e.source()); outboxFailed(e, failed); finish.accept(false); return; }
        sent.whenComplete((id, error) -> {
            // 우편 DB 저장이 확인된 경우에만 레코드를 지운다. 종료 중이라 메인으로 못 돌아가도 지우기는 끝낸다(다음 시작 때 다시 보내지 않게).
            boolean acked = false;
            if (error == null) try { outbox.acknowledge(e); acked = true; } catch (java.io.IOException io) { plugin.getLogger().warning("FISHING OUTBOX acknowledge failed source=" + e.source() + " (" + io + "); 다시 보내도 같은 우편으로 처리됨"); }
            boolean removed = acked;
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                outboxSending.remove(e.source());
                if (error != null) { outboxFailed(e, error); finish.accept(false); return; }
                if (removed) { outboxIndex.remove(e.source()); outboxRetries.remove(e.source()); }
                plugin.getLogger().info("FISHING OUTBOX delivered player=" + e.recipient() + " source=" + e.source() + " mail=" + id);
                Player online = Bukkit.getPlayer(e.recipient());
                if (online != null && outboxIndex.values().stream().noneMatch(x -> x.receipt().equals(e.receipt()))) online.getPersistentDataContainer().remove(receiptKey(e.receipt()));
                if (!e.tankFish().isEmpty()) {
                    tank.finish(e.recipient(), List.of(new FishTank.Fish(e.tankFish(), "", 0, 0, e.tankToken())), FishTankSql.Action.ITEMIZE, true, ok -> {
                        if (ok) plugin.getLogger().info("FISHING OUTBOX tank itemized player=" + e.recipient() + " fish=" + e.tankFish());
                        else plugin.getLogger().severe("FISHING OUTBOX tank finish failed player=" + e.recipient() + " fish=" + e.tankFish() + " (우편은 저장됨; /유저관리 <닉네임> 낚시 수조 멈춘처리 로 확인)");
                        if (Bukkit.getPlayer(e.recipient()) == null) tank.forget(e.recipient());
                    });
                }
                finish.accept(true);
            });
        });
    }

    private void outboxFailed(FishMailOutbox.Entry e, Throwable error) {
        int n = outboxRetries.merge(e.source(), 1, Integer::sum);
        plugin.getLogger().warning("FISHING OUTBOX send failed (kept) player=" + e.recipient() + " source=" + e.source() + " try=" + n + " (" + error + ")");
        Player online = Bukkit.getPlayer(e.recipient());
        if (online != null && n == 1) CmdKit.notify(online, "warning", "fishing-mail-wait", "낚시 보상 우편이 늦어지고 있어요", "보상은 안전하게 보관 중이고 자동으로 다시 보내요.", 3000);
        if (n <= 3 && plugin.isEnabled()) Bukkit.getScheduler().runTaskLater(plugin, () -> { var cur = outboxIndex.get(e.source()); if (cur != null) sendOutbox(cur, null); }, 1200L);
    }

    /** 시작: 접속 중인 사람은 접속 규칙으로, 아닌 사람은 디스크 영수증이 있을 때만 다시 보낸다(없으면 접속 때 판정). */
    private void recoverOutbox() {
        if (outbox == null) return;
        outboxBroken.clear();
        try { for (var e : outbox.pending(outboxBroken)) outboxIndex.putIfAbsent(e.source(), e); }
        catch (java.io.IOException e) { plugin.getLogger().severe("FISHING OUTBOX read failed (" + e + "); records kept"); return; }
        if (!outboxBroken.isEmpty()) plugin.getLogger().severe("FISHING OUTBOX 읽을 수 없는 레코드 " + outboxBroken.size() + "개 (자동 처리 안 함, 관리자 확인 필요): " + outboxBroken);
        if (!outboxIndex.isEmpty()) plugin.getLogger().warning("FISHING OUTBOX 대기 중인 낚시 보상 우편 " + outboxIndex.size() + "건 다시 확인");
        for (var e : List.copyOf(outboxIndex.values())) {
            if (Bukkit.getPlayer(e.recipient()) != null) continue;
            boolean saved;
            try { saved = DeliveryReceipt.saved(playerFile(e.recipient()), receiptKey(e.receipt()).toString()); }
            catch (java.io.IOException | RuntimeException x) { plugin.getLogger().warning("FISHING OUTBOX receipt check failed source=" + e.source() + " (" + x + "); 접속 때 다시 확인"); continue; }
            if (saved) sendOutbox(e, null);
        }
        for (Player p : Bukkit.getOnlinePlayers()) resolveOutbox(p);
    }

    /** 접속(이 서버): 영수증이 플레이어 데이터에 있으면 다시 보내고, 없으면 지급 전에 멈춘 것이므로 레코드를 버린다. 남은 영수증은 지운다. */
    private void resolveOutbox(Player p) {
        if (outbox == null) return;
        var pdc = p.getPersistentDataContainer();
        for (var e : List.copyOf(outboxIndex.values())) {
            if (!e.recipient().equals(p.getUniqueId()) || outboxSending.contains(e.source())) continue;
            if (pdc.has(receiptKey(e.receipt()), org.bukkit.persistence.PersistentDataType.BYTE)) { sendOutbox(e, null); continue; }
            try { outbox.acknowledge(e); outboxIndex.remove(e.source()); plugin.getLogger().warning("FISHING OUTBOX discarded (영수증 없음: 지급 전에 멈춤) player=" + p.getUniqueId() + " source=" + e.source()); }
            catch (java.io.IOException x) { plugin.getLogger().warning("FISHING OUTBOX discard failed source=" + e.source() + " (" + x + ")"); }
        }
        String ns = plugin.getName().toLowerCase(Locale.ROOT);
        for (var key : List.copyOf(pdc.getKeys()))
            if (key.getNamespace().equals(ns) && key.getKey().startsWith("fish_mail_") && outboxIndex.values().stream().noneMatch(x -> x.receipt().equals(key.getKey()))) pdc.remove(key);
    }

    /** 관리자 확인용: 이 플레이어의 대기 중인 보상 우편 줄. */
    private List<String> outboxLines(UUID id) {
        var out = new ArrayList<String>();
        for (var e : outboxIndex.values()) if (e.recipient().equals(id))
            out.add(e.source() + " · " + QuestBridge.timeText(e.created()) + " · 아이템 " + e.items().size() + "칸" + (e.tankFish().isEmpty() ? "" : " · 수조 꺼내기") + (outboxSending.contains(e.source()) ? " · 보내는 중" : " · 재시도 " + outboxRetries.getOrDefault(e.source(), 0) + "회"));
        return out;
    }

    // ------------------------------------------------------------------ recast

    private void recast(Player p, Session s) {
        if (s.preview) {
            s.state = SINKING; s.since = System.currentTimeMillis();
            send(p, new FishingProtocol.Session(s.id, FishingProtocol.PHASE_WATER, s.previewBackground, s.maxDepth, s.floorDepth, 3, 4096,
                    tank.count(p.getUniqueId()), tank.capacity(), gear.protocol(gear.read(rodOf(p))), ""));
            return;
        }
        ItemStack main = p.getInventory().getItemInMainHand();
        if (!FishingGear.isRod(main)) { ready(p, s, "낚싯대를 손에 들어 주세요."); return; }
        var l = gear.read(main);
        if (!l.rod().fallback() && l.rod().value() <= 0) { ready(p, s, "낚싯대가 고장 났어요."); return; }
        Location target = s.target;
        if (target == null || !p.getWorld().equals(target.getWorld()) || target.distanceSquared(p.getLocation()) > 30 * 30) {
            end(p, s, "처음 던진 곳이 너무 멀어요. 다시 던져 주세요."); return;
        }
        FishHook hook;
        try { hook = p.launchProjectile(FishHook.class); } catch (RuntimeException | LinkageError e) {
            end(p, s, "찌를 던지지 못했어요. 다시 던져 주세요."); return;
        }
        hook.setVelocity(aim(hook.getLocation(), target.clone().add(0, .2, 0)));
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_FISHING_BOBBER_THROW, .6f, .5f);
        p.swingMainHand();
        s.hook = hook; s.ownCast = true; s.state = LANDING; s.since = System.currentTimeMillis();
        quiet(hook);
    }

    /** Initial velocity so a fishing bobber (drag 0.92, gravity 0.03 per tick) lands near `to`. */
    static Vector aim(Location from, Location to) {
        double dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ(), dy = to.getY() - from.getY();
        double dist = Math.sqrt(dx * dx + dz * dz);
        int n = (int) Math.clamp(Math.round(dist * 1.4 + 8), 8, 40);
        double drag = .92, sum = (1 - Math.pow(drag, n)) / (1 - drag);
        double h = dist / sum;
        // y after n ticks = A*vy - B
        double y0 = 0, v0 = 0, y1 = 0, v1 = 1;
        for (int i = 0; i < n; i++) { y0 += v0; v0 = v0 * drag - .03; y1 += v1; v1 = v1 * drag - .03; }
        double a = y1 - y0, vy = (dy - y0) / a;
        var v = new Vector(dist < 1e-6 ? 0 : dx / dist * h, vy, dist < 1e-6 ? 0 : dz / dist * h);
        if (v.length() > 2.5) v.multiply(2.5 / v.length());
        return v;
    }

    // ------------------------------------------------------------------ gear window

    private boolean locked(Player p) {
        var s = sessions.get(p.getUniqueId());
        return s != null && !s.preview && s.state != READY;
    }

    /** Loads the database rows of the held rod and the gear in the inventory, then sends the window. */
    private void sendGear(Player p, String notice) {
        var items = new ArrayList<ItemStack>();
        for (ItemStack it : p.getInventory().getStorageContents()) if (it != null && gear.kindOf(it) >= 0) items.add(it);
        gear.load(items, () -> { if (p.isOnline()) sendGearNow(p, notice); });
    }
    private void sendGearNow(Player p, String notice) {
        ItemStack rod = rodOf(p);
        var l = gear.read(rod);
        var owned = new ArrayList<Owned>();
        var inv = p.getInventory();
        int held = inv.getHeldItemSlot();
        var contents = inv.getStorageContents();
        for (int slot = 0; slot < contents.length && owned.size() < FishingProtocol.MAX_OWNED; slot++) {
            ItemStack it = contents[slot];
            if (slot == held && FishingGear.isRod(inv.getItemInMainHand())) continue;
            int k = gear.kindOf(it);
            if (k < 0) continue;
            var g = gear.gearOf(it);
            if (g == null) continue;
            int d = k == 3 ? -1 : (gear.plainRod(it) ? -1 : gear.durabilityOf(it));
            int max = k == 3 || gear.plainRod(it) ? -1 : g.durability();
            owned.add(new Owned(k, slot, g.id(), FishingGear.cut(g.name()), g.grade(), d, max, it.getAmount(), cutText(FishingGear.stats(g)), cutText(g.specialText())));
        }
        var stats = new ArrayList<String>();
        for (int k = 0; k < 4; k++) stats.add(cutText(FishingGear.stats(l.part(k).gear())));
        send(p, new GearList(gear.protocol(l), locked(p) || rod == null, owned, stats, cut(rod == null ? "낚싯대를 손에 들어 주세요." : notice)));
    }

    private void equip(Player p, int kind, int slot) {
        if (locked(p)) { sendGear(p, config.message("no-gear-change", "낚시 중에는 장비를 바꿀 수 없어요.")); return; }
        var inv = p.getInventory();
        ItemStack rod = inv.getItemInMainHand();
        if (!FishingGear.isRod(rod)) { sendGear(p, "낚싯대를 손에 들어 주세요."); return; }
        var contents = inv.getStorageContents();
        if (slot >= contents.length || slot == inv.getHeldItemSlot()) { sendGear(p, ""); return; }
        ItemStack chosen = contents[slot];
        if (gear.kindOf(chosen) != kind) { sendGear(p, "그 장비를 찾지 못했어요."); return; }
        var g = gear.gearOf(chosen);
        if (g == null) { sendGear(p, ""); return; }
        if (!gear.ready(chosen) || !gear.ready(rod)) { sendGear(p, "장비 정보를 불러오는 중이에요. 다시 골라 주세요."); return; }
        if (kind != 3 && !gear.plainRod(chosen) && gear.durabilityOf(chosen) == 0) { sendGear(p, config.message("broken", "고장 난 장비는 장착할 수 없어요.")); return; }
        if (kind == FishingProtocol.ROD) {
            // swap the held rod with the chosen one
            inv.setItem(slot, rod);
            inv.setItemInMainHand(chosen);
        } else {
            var l = gear.read(rod);
            var old = l.part(kind);
            int value = kind == 3 ? chosen.getAmount() : gear.durabilityOf(chosen);
            var part = new FishingGear.Part(g, value < 0 ? g.durability() : value, false);
            var next = switch (kind) {
                case 1 -> new FishingGear.Loadout(l.rod(), part, l.line(), l.bait());
                case 2 -> new FishingGear.Loadout(l.rod(), l.flt(), part, l.bait());
                default -> new FishingGear.Loadout(l.rod(), l.flt(), l.line(), part);
            };
            if (l.rod().fallback()) next = new FishingGear.Loadout(new FishingGear.Part(config.fallback[0], config.fallback[0].durability(), false), next.flt(), next.line(), next.bait());
            inv.setItem(slot, null);
            gear.write(rod, next);
            inv.setItemInMainHand(rod);
            if (!old.fallback()) give(p, List.of(gear.create(old.gear(), old.value(), Math.max(1, old.value()))), "fishing-unequip:" + UUID.randomUUID());
        }
        p.playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_GENERIC, .7f, 1.2f);
        sendGear(p, "");
        var s = sessions.get(p.getUniqueId());
        if (s != null && s.state == READY) ready(p, s, "");
    }

    // ------------------------------------------------------------------ codex

    private void openCodex(Player p) {
        var book = store.book(p.getUniqueId());
        if (!book.loaded) { store.load(p.getUniqueId(), () -> { if (p.isOnline()) sendCodex(p, ""); }); return; }
        sendCodex(p, "");
    }

    private void sendCodex(Player p, String notice) {
        var book = store.book(p.getUniqueId());
        var entries = new ArrayList<Entry>();
        int caught = 0;
        int[] chests = new int[4];
        synchronized (book) {
            for (var f : config.fish) {
                var rec = book.rows.get(f.id());
                boolean got = rec != null;
                if (got) caught++;
                entries.add(new Entry(f.id(), got ? FishingGear.cut(f.name()) : "???", f.grade(), f.shape(), got, got ? (float) rec.best() : 0,
                        got ? rec.count() : 0, cutText(got ? f.description() : "아직 잡지 못한 물고기예요."), cutText(seasonText(f)),
                        cutText(got ? placeText(f) : "???"), cutText(depthText(f)), cutText(got ? timeText(f) : "???")));
            }
            for (int g = 0; g < 4; g++) { var r = book.rows.get("treasure_" + g); chests[g] = r == null ? 0 : r.count(); }
            int reached = FishingProtocol.tiersReached(caught, config.fish.size());
            int claimed = 0, available = 0;
            for (int t = 0; t < 4; t++) { if (book.claimed.contains(t)) claimed |= 1 << t; else if (t < reached) available |= 1 << t; }
            send(p, new Codex(entries, chests, claimed, available, tierRewards(), cut(notice)));
        }
    }

    /** What each completion tier gives, for the codex tooltip: items as previews, command rewards by their display text. */
    private List<List<Reward>> tierRewards() {
        var out = new ArrayList<List<Reward>>();
        for (int t = 0; t < 4; t++) {
            var list = new ArrayList<Reward>();
            var reward = config.codexRewards[t];
            for (String spec : reward.items()) {
                int at = spec.lastIndexOf(':');
                String key = spec; int amount = 1;
                if (at > 0) { try { amount = Integer.parseInt(spec.substring(at + 1)); key = spec.substring(0, at); } catch (NumberFormatException ignored) {} }
                var it = item(key, amount);
                if (it != null && list.size() < FishingProtocol.MAX_REWARDS) list.add(new Reward(FishingGear.cut(AlchemyBridge.displayName(it)), amount, preview(it)));
            }
            for (String text : reward.display()) if (!text.isBlank() && list.size() < FishingProtocol.MAX_REWARDS) list.add(new Reward(FishingGear.cut(text), 0, new byte[0]));
            out.add(list);
        }
        return out;
    }

    private static String seasonText(FishingConfig.Fish f) {
        return f.seasons().isEmpty() ? "사계절" : String.join("·", f.seasons().stream().sorted(Comparator.comparingInt(FishingBridge::seasonOrder)).toList());
    }
    private static int seasonOrder(String s) { return switch (s) { case "봄" -> 0; case "여름" -> 1; case "가을" -> 2; default -> 3; }; }
    private static String placeText(FishingConfig.Fish f) {
        String where = f.biomes().isEmpty() ? "어디서나" : String.join("·", f.biomes());
        String water = f.waterMin() == f.waterMax() ? FishingConfig.WATER[f.waterMin()] + "만" : f.waterMin() == 0 && f.waterMax() == 3 ? "" :
                f.waterMax() == 3 ? FishingConfig.WATER[f.waterMin()] + " 이상" : FishingConfig.WATER[f.waterMax()] + " 이하";
        String extra = f.underground() ? " · 지하" : "";
        if (f.yMin() != null) extra += " · 높은 곳";
        if (f.yMax() != null) extra += " · 깊은 땅속";
        return where + (water.isEmpty() ? "" : " · " + water) + extra;
    }
    private static String depthText(FishingConfig.Fish f) {
        return f.maxDepth() >= 999 ? FishingGear.num(f.minDepth()) + "m 이상" : FishingGear.num(f.minDepth()) + "~" + FishingGear.num(f.maxDepth()) + "m";
    }
    private static String timeText(FishingConfig.Fish f) {
        String t = f.time().equals("전체") ? "언제나" : f.time();
        if (!f.weather().equals("전체")) t += " · " + f.weather();
        if (f.moon().equals("full")) t += " · 보름달";
        if (f.moon().equals("new")) t += " · 그믐달";
        return t;
    }

    private void claim(Player p, int tier) {
        var book = store.book(p.getUniqueId());
        int caught;
        synchronized (book) { caught = (int) config.fish.stream().filter(f -> book.rows.containsKey(f.id())).count(); }
        if (tier >= FishingProtocol.tiersReached(caught, config.fish.size())) { sendCodex(p, "아직 받을 수 없는 보상이에요."); return; }
        store.claim(p.getUniqueId(), tier, ok -> {
            if (!p.isOnline()) return;
            if (!ok) { sendCodex(p, "이미 받은 보상이에요."); return; }
            var reward = config.codexRewards[tier];
            for (String cmd : reward.commands())
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("{player}", p.getName()).replace("{uuid}", p.getUniqueId().toString()));
            var items = new ArrayList<ItemStack>();
            for (String spec : reward.items()) {
                int at = spec.lastIndexOf(':');
                String key = spec; int amount = 1;
                if (at > 0) { try { amount = Integer.parseInt(spec.substring(at + 1)); key = spec.substring(0, at); } catch (NumberFormatException ignored) {} }
                var it = item(key, amount);
                if (it != null) items.add(it);
            }
            if (!items.isEmpty()) give(p, items, "fishing-codex:" + p.getUniqueId() + ":" + tier);
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, .8f, 1.1f);
            sendCodex(p, "도감 " + new int[]{25, 50, 75, 100}[tier] + "% 보상을 받았어요!");
        });
    }

    // ------------------------------------------------------------------ commands

    private static final List<String> BACKGROUNDS = List.of("민물", "바다", "얼음", "동굴");

    private Map<String, String> fishNames() { var out = new LinkedHashMap<String, String>(); for (var f : config.fish) out.put(f.id(), f.name()); return out; }
    private Map<String, String> gearNames() { var out = new LinkedHashMap<String, String>(); for (var m : config.gear) for (var g : m.values()) out.put(g.id(), g.name()); return out; }
    private FishingConfig.Gear gearById(String id) { for (int k = 0; k < 4; k++) { var g = config.gear(k, id); if (g != null) return g; } return null; }

    private void commands() {
        ChacaApi.playerCommand(plugin, CmdKit.literal("낚시도감").executes(CmdKit.run(ctx -> openCodexFor(CmdKit.sender(ctx), CmdKit.self(ctx)))), "낚시 도감 열기", List.of());
        // /유저관리 <닉네임> 낚시
        var number = (java.util.function.Supplier<com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, Integer>>) () -> CmdKit.argument("번호", IntegerArgumentType.integer(1, 10000));
        var user = CmdKit.literal("낚시")
                .then(CmdKit.confirm(CmdKit.literal("도감초기화"), ctx -> {
                    UUID id = ChacaApi.target(ctx);
                    store.reset(id);
                    plugin.getLogger().info("FISHING ADMIN codex reset admin=" + CmdKit.sender(ctx).getName() + " player=" + id);
                    CmdKit.ok(CmdKit.sender(ctx), CmdKit.name(id) + " 님 낚시 도감을 초기화했어요");
                }))
                .then(CmdKit.literal("도감열기").executes(CmdKit.run(ctx -> openCodexFor(CmdKit.sender(ctx), ChacaApi.targetOnline(ctx)))))
                .then(CmdKit.literal("수조")
                        .then(CmdKit.literal("보기").executes(CmdKit.run(this::showTank)))
                        .then(CmdKit.literal("빼기").then(number.get().executes(CmdKit.run(ctx -> takeFish(ctx, false)))))
                        .then(CmdKit.literal("돌려주기").then(number.get().executes(CmdKit.run(ctx -> takeFish(ctx, true)))))
                        .then(CmdKit.literal("잠금해제").then(CmdKit.word("물고기", () -> AdminCommandRules.catalogChoices(fishNames())).executes(CmdKit.run(this::unlock))))
                        // 판매(SELLING)·꺼내기(TAKING) 도중 멈춘 물고기 보기·정리 (2026-10-21)
                        .then(CmdKit.literal("멈춘처리").executes(CmdKit.run(this::showStuck))
                                .then(number.get()
                                        .then(CmdKit.confirm(CmdKit.literal("지급완료"), ctx -> resolveStuck(ctx, true)))
                                        .then(CmdKit.confirm(CmdKit.literal("수조로"), ctx -> resolveStuck(ctx, false))))))
                .then(CmdKit.literal("우편대기").executes(CmdKit.run(this::showOutbox))
                        .then(CmdKit.literal("다시보내기").executes(CmdKit.run(this::resendOutbox))));
        ChacaApi.branch(plugin, ChacaApi.유저관리, user, "낚시 도감·아공간 수조 보기와 고치기·멈춘 판매/꺼내기 정리·보상 우편 대기", "낚시 수조 보기");
        // /아이템관리 낚시장비 · 물고기
        var gearArg = CmdKit.word("장비", () -> AdminCommandRules.catalogChoices(gearNames()))
                .executes(CmdKit.run(ctx -> giveGear(ctx, 1, CmdKit.self(ctx))))
                .then(CmdKit.argument("개수", IntegerArgumentType.integer(1, 64)).executes(CmdKit.run(ctx -> giveGear(ctx, IntegerArgumentType.getInteger(ctx, "개수"), CmdKit.self(ctx))))
                        .then(CmdKit.player("닉네임").executes(CmdKit.run(ctx -> giveGear(ctx, IntegerArgumentType.getInteger(ctx, "개수"), ChacaApi.resolveOnline(ctx, "닉네임"))))));
        ChacaApi.branch(plugin, ChacaApi.아이템관리, CmdKit.literal("낚시장비").then(gearArg), "낚시 장비(낚싯대·찌·줄·미끼) 주기", "낚시장비 ");
        var fishArg = CmdKit.word("물고기", () -> AdminCommandRules.catalogChoices(fishNames()))
                .executes(CmdKit.run(ctx -> giveFish(ctx, -1, CmdKit.self(ctx))))
                .then(CmdKit.argument("크기cm", DoubleArgumentType.doubleArg(0.1, 100000)).executes(CmdKit.run(ctx -> giveFish(ctx, DoubleArgumentType.getDouble(ctx, "크기cm"), CmdKit.self(ctx))))
                        .then(CmdKit.player("닉네임").executes(CmdKit.run(ctx -> giveFish(ctx, DoubleArgumentType.getDouble(ctx, "크기cm"), ChacaApi.resolveOnline(ctx, "닉네임"))))));
        ChacaApi.branch(plugin, ChacaApi.아이템관리, CmdKit.literal("물고기").then(fishArg), "물고기 아이템 주기 (수조에 넣을 수 있음)", "물고기 ");
        // /테스트 낚시
        var preview = CmdKit.literal("미리보기").executes(CmdKit.run(ctx -> preview(CmdKit.self(ctx), 0)));
        for (int i = 0; i < BACKGROUNDS.size(); i++) { int bg = i; preview.then(CmdKit.literal(BACKGROUNDS.get(i)).executes(CmdKit.run(ctx -> preview(CmdKit.self(ctx), bg)))); }
        var test = CmdKit.literal("낚시").then(preview)
                .then(CmdKit.literal("수조").executes(CmdKit.run(ctx -> openTank(CmdKit.self(ctx)))).then(CmdKit.player("닉네임").executes(CmdKit.run(ctx -> openTank(ChacaApi.resolveOnline(ctx, "닉네임"))))))
                .then(CmdKit.literal("도감").executes(CmdKit.run(ctx -> openCodexFor(CmdKit.sender(ctx), CmdKit.self(ctx)))).then(CmdKit.player("닉네임").executes(CmdKit.run(ctx -> openCodexFor(CmdKit.sender(ctx), ChacaApi.resolveOnline(ctx, "닉네임"))))))
                .then(CmdKit.literal("판매소").executes(CmdKit.run(ctx -> fishShop().adminOpen(CmdKit.self(ctx)))).then(CmdKit.player("닉네임").executes(CmdKit.run(ctx -> fishShop().adminOpen(ChacaApi.resolveOnline(ctx, "닉네임"))))))
                .then(CmdKit.literal("가격표").executes(CmdKit.run(ctx -> CmdKit.lines(CmdKit.sender(ctx), fishShop().priceLines(null))))
                        .then(CmdKit.word("물고기", () -> AdminCommandRules.catalogChoices(fishNames())).executes(CmdKit.run(ctx -> CmdKit.lines(CmdKit.sender(ctx), fishShop().priceLines(CmdKit.pick(fishNames(), CmdKit.text(ctx, "물고기"), "물고기")))))));
        ChacaApi.branch(plugin, ChacaApi.테스트, test, "낚시 미니게임·수조·도감·물고기 판매소 미리보기", "낚시 미리보기");
        ChacaApi.reload(plugin, "낚시", "fishing*.yml (물고기·장비·보물)", () -> {
            var next = FishingConfig.load(plugin.getDataFolder());
            config = next; gear.config(next); tank.capacity(next.tankCapacity);
            plugin.getLogger().info("낚시 설정을 다시 읽었습니다. (물고기 " + next.fish.size() + "종)");
        });
    }

    private FishShopBridge fishShop() throws CommandSyntaxException {
        var shop = Bukkit.getServicesManager().load(FishShopBridge.class);
        if (shop == null) throw ChacaApi.error("물고기 판매소가 준비되지 않았어요 (fish-shop.yml 확인)");
        return shop;
    }

    private void openCodexFor(CommandSender sender, Player target) throws CommandSyntaxException {
        if (!modded(target)) {
            if (sender == target) { CmdKit.notify(target, "warning", "fishing-mod", "낚시 도감을 열 수 없어요", "차카데미 모드를 업데이트해 주세요", 3000); return; }
            throw ChacaApi.error(plugin.names().name(target) + " 님은 차카데미 모드가 없어 도감을 열 수 없어요");
        }
        openCodex(target);
    }

    private void openTank(Player target) throws CommandSyntaxException {
        if (tankWindow == null) throw ChacaApi.error("아공간 수조가 준비되지 않았어요");
        if (!FishTankBridge.modded(target)) throw ChacaApi.error(plugin.names().name(target) + " 님은 차카데미 모드가 없어 수조 창을 열 수 없어요");
        tankWindow.open(target);
    }

    private void preview(Player p, int bg) {
        var old = sessions.remove(p.getUniqueId());
        if (old != null) removeHook(old);
        var s = new Session(p.getUniqueId());
        s.preview = true; s.previewBackground = bg; s.state = SINKING;
        s.maxDepth = Math.min(FishingProtocol.MAX_DEPTH, gear.read(rodOf(p)).line().gear().a() * .85f);
        s.floorDepth = FishingProtocol.MAX_DEPTH;
        sessions.put(p.getUniqueId(), s);
        send(p, new FishingProtocol.Session(s.id, FishingProtocol.PHASE_WATER, bg, s.maxDepth, s.floorDepth, 3, 4096, tank.count(p.getUniqueId()), tank.capacity(),
                gear.protocol(gear.read(rodOf(p))), "미리보기: 보상·내구도·도감 기록이 없어요."));
    }

    private void giveGear(CommandContext<CommandSourceStack> ctx, int n, Player target) throws CommandSyntaxException {
        var g = gearById(CmdKit.pick(gearNames(), CmdKit.text(ctx, "장비"), "낚시 장비"));
        var items = new ArrayList<ItemStack>();
        if (g.kind() == 3) items.add(gear.create(g, -1, Math.clamp(n, 1, 64)));
        else for (int i = 0; i < Math.clamp(n, 1, 36); i++) items.add(gear.create(g, -1, 1));
        boolean mailed = give(target, items, "fishing-admin:" + UUID.randomUUID());
        CmdKit.ok(CmdKit.sender(ctx), plugin.names().name(target) + " 님에게 " + g.name() + (n > 1 ? " " + n + "개" : "") + "를 주었어요" + (mailed ? " (일부 우편)" : ""));
    }

    private void giveFish(CommandContext<CommandSourceStack> ctx, double size, Player t) throws CommandSyntaxException {
        var f = config.fishById.get(CmdKit.pick(fishNames(), CmdKit.text(ctx, "물고기"), "물고기"));
        float cm = size > 0 ? (float) size : f.maxSize();
        var sender = CmdKit.sender(ctx);
        var row = new FishTank.Fish(UUID.randomUUID().toString(), f.id(), cm, System.currentTimeMillis());
        tank.registerItem(row, (saved, registered) -> {
            if (!registered || saved == null || saved.token() == null || saved.token().isBlank()) { CmdKit.fail(sender, "물고기 저장소에 등록하지 못해 주지 않았어요", null); return; }
            if (!t.isOnline()) { CmdKit.userFail(sender, "등록하는 동안 대상이 나가서 주지 못했어요"); return; }
            try {
                give(t, List.of(gear.fish(f, cm, saved.id(), saved.caughtAt(), saved.token())), "fishing-admin:" + saved.id());
                CmdKit.ok(sender, plugin.names().name(t) + " 님에게 " + f.name() + " (" + cm + "cm)를 주었어요");
            } catch (RuntimeException error) {
                CmdKit.fail(sender, "물고기는 등록됐지만 전달을 확인하지 못했어요. 다시 주기 전에 인벤토리·우편을 확인해 주세요", error);
            }
        });
    }

    /** 수조 줄 (번호는 잡은 시각 순서, 1부터). */
    private String row(int index, FishTank.Fish f, Set<String> locks) {
        var def = config.fishById.get(f.fish());
        return index + ". " + (def == null ? f.fish() : def.name()) + " " + AdminCommandRules.number(Math.round(f.size() * 10) / 10.0) + "cm · " + QuestBridge.timeText(f.caughtAt()) + (locks.contains(f.fish()) ? " · 잠김" : "");
    }

    private void showTank(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        UUID id = ChacaApi.target(ctx);
        var sender = CmdKit.sender(ctx);
        String who = CmdKit.name(id);
        tank.refresh(id, () -> {
            if (!tank.loaded(id)) { CmdKit.fail(sender, "수조를 읽지 못했어요", null); return; }
            var t = tank.tank(id);
            var out = new ArrayList<String>();
            out.add("── " + who + " 아공간 수조 " + t.fish.size() + "/" + tank.capacity() + " ──");
            for (int i = 0; i < t.fish.size() && i < 200; i++) out.add(row(i + 1, t.fish.get(i), t.locks));
            if (t.fish.size() > 200) out.add("… (" + (t.fish.size() - 200) + "마리 더)");
            var locked = t.locks.stream().map(l -> fishNames().getOrDefault(l, l)).sorted().toList();
            out.add("잠긴 종류: " + (locked.isEmpty() ? "없음" : String.join(", ", locked)));
            if (Bukkit.getPlayer(id) == null) tank.forget(id);
            tank.stuck(id, rows -> {
                if (rows == null) out.add("판매·꺼내기 도중 멈춘 물고기: 읽지 못함");
                else if (!rows.isEmpty()) out.add("판매·꺼내기 도중 멈춘 물고기 " + rows.size() + "마리 (수조 칸 차지) → 낚시 수조 멈춘처리");
                CmdKit.lines(sender, out);
            });
        });
    }

    /** giveBack=false: 수조에서 빼기(방생), true: 아이템으로 인벤토리(가득 차면 우편)에 돌려주기. */
    private void takeFish(CommandContext<CommandSourceStack> ctx, boolean giveBack) throws CommandSyntaxException {
        UUID id = ChacaApi.target(ctx);
        Player p = giveBack ? ChacaApi.targetOnline(ctx) : Bukkit.getPlayer(id);
        int number = IntegerArgumentType.getInteger(ctx, "번호");
        var sender = CmdKit.sender(ctx);
        String who = CmdKit.name(id);
        String admin = sender.getName();
        tank.refresh(id, () -> {
            var list = tank.tank(id).fish;
            if (!tank.loaded(id)) { CmdKit.fail(sender, "수조를 읽지 못했어요", null); return; }
            if (number > list.size()) { CmdKit.userFail(sender, who + " 님 수조에 " + number + "번 물고기가 없어요 (수조 보기로 번호 확인)"); return; }
            var f = list.get(number - 1);
            String label = row(number, f, Set.of()).substring(String.valueOf(number).length() + 2);
            if (!giveBack) {
                tank.remove(id, List.of(f), removed -> {
                    if (removed.isEmpty()) { CmdKit.userFail(sender, "그 물고기는 방금 수조에서 빠졌어요. 다시 확인해 주세요"); return; }
                    plugin.getLogger().info("FISH TANK ADMIN release admin=" + admin + " player=" + id + " fish=" + f.fish() + " id=" + f.id());
                    if (p == null) tank.forget(id);
                    CmdKit.ok(sender, who + " 님 수조에서 " + label + "을(를) 뺐어요");
                });
                return;
            }
            tank.remove(id, List.of(f), FishTankSql.Action.ITEMIZE, claimed -> {
                if (claimed.isEmpty()) { CmdKit.userFail(sender, "그 물고기는 방금 수조에서 빠졌어요. 다시 확인해 주세요"); return; }
                var actual = claimed.getFirst();
                ItemStack item = fishItem(actual);
                if (item == null || !p.isOnline()) {
                    tank.finish(id, claimed, FishTankSql.Action.ITEMIZE, false, ok -> { if (ok) CmdKit.userFail(sender, "돌려주지 못해 수조에 그대로 두었어요"); else CmdKit.fail(sender, "수조 상태 확인이 필요해요", null); });
                    return;
                }
                Given given;
                try {
                    // 우편으로 가면 수조 기록(ITEM)은 우편 저장이 확인된 뒤 대기열이 닫는다. 여기서 먼저 완료로 적지 않는다.
                    given = deliver(p, List.of(item), "fish-tank-admin:" + actual.id() + ":" + actual.token(), actual, ok -> {
                        plugin.getLogger().info("FISH TANK ADMIN itemize-mail admin=" + admin + " player=" + id + " fish=" + actual.fish() + " id=" + actual.id() + " mailed=" + ok);
                        if (ok) CmdKit.ok(sender, who + " 님에게 " + label + "을(를) 우편으로 돌려주었어요");
                        else CmdKit.userFail(sender, "우편 저장이 늦어지고 있어요. 보상 대기열에 남아 자동으로 다시 보내요 (수조 기록은 우편 확인 뒤 닫힘)");
                    });
                }
                catch (RuntimeException uncertain) { CmdKit.fail(sender, "전달 결과 확인이 필요해요 (수조 기록은 보류)", uncertain); return; }
                if (given == Given.MAIL) return;
                tank.finish(id, claimed, FishTankSql.Action.ITEMIZE, true, ok -> {
                    plugin.getLogger().info("FISH TANK ADMIN itemize admin=" + admin + " player=" + id + " fish=" + actual.fish() + " id=" + actual.id() + " ok=" + ok);
                    if (ok) CmdKit.ok(sender, who + " 님에게 " + label + "을(를) 아이템으로 돌려주었어요");
                    else CmdKit.fail(sender, "지급 기록 확인이 필요해요", null);
                });
            });
        });
    }

    /** 관리자가 방금 본 멈춘 목록 (관리자 이름|대상 → 목록). */
    private final Map<String, List<FishTankSql.Stuck>> stuckListed = new HashMap<>();
    private boolean pendingMailFor(String fishId) { return outboxIndex.values().stream().anyMatch(e -> e.tankFish().equals(fishId)); }

    private void showStuck(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        UUID id = ChacaApi.target(ctx);
        var sender = CmdKit.sender(ctx);
        String who = CmdKit.name(id);
        tank.stuck(id, rows -> {
            if (rows == null) { CmdKit.fail(sender, "수조 기록을 읽지 못했어요", null); return; }
            stuckListed.put(sender.getName() + "|" + id, rows);
            var out = new ArrayList<String>();
            out.add("── " + who + " 판매·꺼내기 도중 멈춘 물고기 " + rows.size() + "마리 (수조 칸 차지) ──");
            for (int i = 0; i < rows.size(); i++) {
                var r = rows.get(i);
                out.add(row(i + 1, r.fish(), Set.of()) + " · " + (r.action() == FishTankSql.Action.SELL ? "판매 중" : "꺼내는 중") + (pendingMailFor(r.fish().id()) ? " · 보상 우편 대기열에 있음" : ""));
            }
            if (rows.isEmpty()) out.add("(없음)");
            else out.add("판매 중: 돈이 들어갔으면(콘솔 PAYMENT_RESULT confirmed=true) 지급완료, 아니면 수조로 · 꺼내는 중: 아이템을 받았으면 지급완료, 아니면 수조로 — 멈춘처리 <번호> 지급완료|수조로 확인");
            CmdKit.lines(sender, out);
        });
    }

    private void resolveStuck(CommandContext<CommandSourceStack> ctx, boolean paid) throws CommandSyntaxException {
        UUID id = ChacaApi.target(ctx);
        var sender = CmdKit.sender(ctx);
        int number = IntegerArgumentType.getInteger(ctx, "번호");
        var rows = stuckListed.get(sender.getName() + "|" + id);
        if (rows == null) throw ChacaApi.error("먼저 낚시 수조 멈춘처리 로 목록을 확인해 주세요");
        if (number > rows.size()) throw ChacaApi.error("그 번호가 없어요 (멈춘처리 목록으로 확인)");
        var st = rows.get(number - 1);
        if (!paid && st.action() == FishTankSql.Action.ITEMIZE && pendingMailFor(st.fish().id()))
            throw ChacaApi.error("이 물고기는 보상 우편 대기열에 있어요. 우편이 저장되면 자동으로 정리돼요 (수조로 되돌리면 복제돼요)");
        String admin = sender.getName(), who = CmdKit.name(id);
        String was = st.action() == FishTankSql.Action.SELL ? "SELLING" : "TAKING";
        String to = paid ? (st.action() == FishTankSql.Action.SELL ? "SOLD" : "ITEM") : "TANK";
        tank.finish(id, List.of(st.fish()), st.action(), paid, ok -> {
            plugin.getLogger().warning("FISH TANK ADMIN resolve admin=" + admin + " player=" + id + " fish=" + st.fish().fish() + " id=" + st.fish().id() + " " + was + "->" + to + " ok=" + ok);
            stuckListed.remove(admin + "|" + id);
            if (Bukkit.getPlayer(id) == null) tank.forget(id);
            if (ok) CmdKit.ok(sender, who + " 님 " + number + "번 물고기를 " + (paid ? "지급 완료로 닫았어요" : "수조로 되돌렸어요"));
            else CmdKit.userFail(sender, "이미 처리됐거나 상태가 바뀌었어요. 멈춘처리 목록을 다시 확인해 주세요");
        });
    }

    private void showOutbox(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        UUID id = ChacaApi.target(ctx);
        var lines = outboxLines(id);
        var out = new ArrayList<String>();
        out.add("── " + CmdKit.name(id) + " 낚시 보상 우편 대기 " + lines.size() + "건 ──");
        out.addAll(lines);
        if (lines.isEmpty()) out.add("(대기 없음)");
        else out.add("서버 시작·접속·1분 뒤 자동으로 다시 보내요 (같은 우편은 두 번 생기지 않음). 지금 보내기: 낚시 우편대기 다시보내기");
        if (!outboxBroken.isEmpty()) out.add("읽을 수 없는 대기 파일 " + outboxBroken.size() + "개 (서버 전체, 자동 처리 안 함 — 콘솔 FISHING OUTBOX 로그 확인)");
        CmdKit.lines(CmdKit.sender(ctx), out);
    }

    private void resendOutbox(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        UUID id = ChacaApi.target(ctx);
        var sender = CmdKit.sender(ctx);
        Player online = Bukkit.getPlayer(id);
        int before = outboxLines(id).size();
        if (before == 0) throw ChacaApi.error("대기 중인 낚시 보상 우편이 없어요");
        if (online != null) resolveOutbox(online);
        else for (var e : List.copyOf(outboxIndex.values())) {
            if (!e.recipient().equals(id)) continue;
            try { if (DeliveryReceipt.saved(playerFile(id), receiptKey(e.receipt()).toString())) sendOutbox(e, null); }
            catch (java.io.IOException | RuntimeException x) { plugin.getLogger().warning("FISHING OUTBOX receipt check failed source=" + e.source() + " (" + x + ")"); }
        }
        plugin.getLogger().info("FISHING OUTBOX ADMIN resend admin=" + sender.getName() + " player=" + id + " pending=" + before);
        CmdKit.ok(sender, CmdKit.name(id) + " 님 대기 중인 보상 우편 " + before + "건을 다시 보냈어요. 결과는 우편대기로 확인해 주세요");
    }

    private void unlock(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        UUID id = ChacaApi.target(ctx);
        String fish = CmdKit.pick(fishNames(), CmdKit.text(ctx, "물고기"), "물고기");
        var sender = CmdKit.sender(ctx);
        String who = CmdKit.name(id);
        tank.refresh(id, () -> {
            if (!tank.loaded(id)) { CmdKit.fail(sender, "수조를 읽지 못했어요", null); return; }
            var locks = new HashSet<>(tank.tank(id).locks);
            if (!locks.remove(fish)) { CmdKit.userFail(sender, who + " 님은 " + fishNames().get(fish) + "을(를) 잠그지 않았어요"); return; }
            tank.locks(id, locks, ok -> {
                if (Bukkit.getPlayer(id) == null) tank.forget(id);
                if (ok) CmdKit.ok(sender, who + " 님 " + fishNames().get(fish) + " 잠금을 풀었어요"); else CmdKit.fail(sender, "잠금을 저장하지 못했어요", null);
            });
        });
    }

    // ------------------------------------------------------------------ io

    private boolean send(Player p, Frame frame) {
        try { p.sendPluginMessage(plugin, FishingProtocol.RESPONSE, FishingProtocol.encode(frame)); return true; }
        catch (IllegalArgumentException e) { plugin.getLogger().warning("낚시 패킷 만들기 실패: " + e.getMessage()); return false; }
    }
    private static String cut(String s) { return s == null ? "" : s.length() > FishingProtocol.MAX_NOTICE ? s.substring(0, FishingProtocol.MAX_NOTICE) : s; }
    private static String cutText(String s) { return s == null ? "" : s.length() > FishingProtocol.MAX_TEXT ? s.substring(0, FishingProtocol.MAX_TEXT) : s; }
}
