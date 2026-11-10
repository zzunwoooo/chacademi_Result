package kr.kkaegul.core;

import kr.kkaegul.core.api.ActionRunner;
import kr.kkaegul.core.api.ApiService;
import kr.kkaegul.core.box.BoxService;
import kr.kkaegul.core.command.Actions;
import kr.kkaegul.core.command.Commands;
import kr.kkaegul.core.data.DataStore;
import kr.kkaegul.core.data.GlobalData;
import kr.kkaegul.core.diamond.DiamondService;
import kr.kkaegul.core.diamond.RouletteService;
import kr.kkaegul.core.diamond.WalletService;
import kr.kkaegul.core.economy.EconomyService;
import kr.kkaegul.core.fever.FeverService;
import kr.kkaegul.core.gacha.GachaService;
import kr.kkaegul.core.harass.HarassService;
import kr.kkaegul.core.harass.QuizService;
import kr.kkaegul.core.harass.WasdService;
import kr.kkaegul.core.item.ItemUseListener;
import kr.kkaegul.core.item.Items;
import kr.kkaegul.core.item.MailService;
import kr.kkaegul.core.jail.JailService;
import kr.kkaegul.core.luck.GaugeService;
import kr.kkaegul.core.mine.MineService;
import kr.kkaegul.core.minigame.MinigameManager;
import kr.kkaegul.core.npc.NpcService;
import kr.kkaegul.core.reward.PlaytimeService;
import kr.kkaegul.core.submit.SubmitService;
import kr.kkaegul.core.title.RankingService;
import kr.kkaegul.core.title.TitleService;
import kr.kkaegul.core.ui.HudService;
import kr.kkaegul.core.ui.MenuService;
import kr.kkaegul.core.ui.BoardService;
import kr.kkaegul.core.ui.UiBridge;
import kr.kkaegul.core.util.Clock;
import kr.kkaegul.core.util.Places;
import kr.kkaegul.core.util.Txt;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

/**
 * 깨굴이의 위험한 초대 : 운빨깨굴랜드 — 코어 플러그인.
 * UI 는 만들지 않고, 모든 화면 데이터는 UiBridge 로 고화질 렌더러(클라 모드)에 JSON 으로 보낸다.
 */
public final class KkaegulCore extends JavaPlugin {
    private static KkaegulCore instance;

    private DataStore data;
    private GlobalData global;
    private Clock clock;
    private Places places;
    private UiBridge ui;
    private HudService hud;
    private MenuService menu;
    private BoardService boards;
    private Items items;
    private MailService mail;
    private DiamondService diamonds;
    private RouletteService roulette;
    private WalletService wallet;
    private kr.kkaegul.core.casino.FrogJumpService frogJump;
    private kr.kkaegul.core.casino.FrogRaceService frogRace;
    private FeverService fever;
    private GaugeService gauge;
    private ActionRunner actions;
    private ApiService api;
    private BoxService boxes;
    private JailService jail;
    private WasdService wasd;
    private QuizService quiz;
    private HarassService harass;
    private EconomyService economy;
    private SubmitService submit;
    private GachaService gacha;
    private NpcService npcs;
    private PlaytimeService rewards;
    private kr.kkaegul.core.reward.CouponService coupons;
    private TitleService titles;
    private RankingService ranking;
    private MineService mine;
    private MinigameManager minigames;
    private kr.kkaegul.core.command.ChatService chat;
    private kr.kkaegul.core.land.LandService land;
    private YamlConfiguration messages;
    private YamlConfiguration quizBank;

    public static KkaegulCore get() { return instance; }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        saveResourceIfMissing("messages.yml");
        saveResourceIfMissing("quiz.yml");
        loadExtra();

        global = new GlobalData(this);
        data = new DataStore(this);
        clock = new Clock(this);
        places = new Places(this);
        ui = new UiBridge(this);
        items = new Items(this);
        mail = new MailService(this);
        titles = new TitleService(this);
        gauge = new GaugeService(this);
        fever = new FeverService(this);
        diamonds = new DiamondService(this);
        roulette = new RouletteService(this);
        wallet = new WalletService(this);
        actions = new ActionRunner(this);
        api = new ApiService(this);
        boxes = new BoxService(this);
        jail = new JailService(this);
        wasd = new WasdService(this);
        quiz = new QuizService(this);
        harass = new HarassService(this);
        economy = new EconomyService(this);
        submit = new SubmitService(this);
        gacha = new GachaService(this);
        npcs = new NpcService(this);
        rewards = new PlaytimeService(this);
        coupons = new kr.kkaegul.core.reward.CouponService(this);
        ranking = new RankingService(this);
        mine = new MineService(this);
        // 미니게임 월드 불러오기 (서버 폴더에 넣어둔 월드 폴더 이름)
        for (String w : getConfig().getStringList("minigame.load-worlds")) {
            if (w == null || w.isBlank() || Bukkit.getWorld(w) != null) continue;
            if (!new java.io.File(getServer().getWorldContainer(), w).isDirectory()) { getLogger().warning("미니게임 월드 폴더 없음: " + w); continue; }
            try { Bukkit.createWorld(new org.bukkit.WorldCreator(w)); getLogger().info("미니게임 월드 불러옴: " + w); }
            catch (Exception e) { getLogger().warning("월드 불러오기 실패 " + w + ": " + e.getMessage()); }
        }
        discord = new kr.kkaegul.core.hook.DiscordBridge(this);
        chat = new kr.kkaegul.core.command.ChatService(this);
        land = new kr.kkaegul.core.land.LandService(this);
        minigames = new MinigameManager(this);
        frogJump = new kr.kkaegul.core.casino.FrogJumpService(this);
        frogRace = new kr.kkaegul.core.casino.FrogRaceService(this);
        nickname = new kr.kkaegul.core.npc.NicknameService(this);
        hud = new HudService(this);
        menu = new MenuService(this);
        boards = new BoardService(this);

        Actions act = new Actions(this);
        act.register(ui);
        new Commands(this, act);

        Bukkit.getPluginManager().registerEvents(new CoreListener(this), this);
        Bukkit.getPluginManager().registerEvents(new ItemUseListener(this), this);
        Bukkit.getPluginManager().registerEvents(harass.mineMobs, this);
        Bukkit.getPluginManager().registerEvents(wallet, this);
        kr.kkaegul.core.api.DonationHook.install(this);
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            try { new kr.kkaegul.core.hook.KkaegulPlaceholders(this).register(); getLogger().info("PlaceholderAPI 연동: %kkaegul_nickname% %kkaegul_title% %kkaegul_display% ..."); }
            catch (Throwable t) { getLogger().warning("PlaceholderAPI 연동 실패: " + t); }
        }

        long autosave = Math.max(1, getConfig().getLong("data.autosave-minutes", 3)) * 60L * 20L;
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            data.saveDirty(true);
            if (global.dirty) global.save();
        }, autosave, autosave);
        kr.kkaegul.core.util.LobbyVision.start(this);   // 로비에선 야간 투시 무한 (표시 없이)
        backups = new kr.kkaegul.core.util.InvBackup(this);   // 5분마다 인벤토리 백업 (관리자 복구용)

        getLogger().info("운빨깨굴랜드 코어 활성화 — 서버 " + clock.serverDay() + "일차, 초반 보호 " + (clock.protectionActive() ? "ON" : "OFF"));
    }

    @Override
    public void onDisable() {
        try {
            if (minigames != null) minigames.stop();
            if (land != null) land.save();
            if (frogJump != null) frogJump.shutdown();
            if (frogRace != null) frogRace.shutdown();
            if (fever != null) fever.shutdown();
            if (mine != null) mine.shutdown();
            if (boards != null) boards.removeAll();
            if (roulette != null) roulette.flushAll();
        } catch (RuntimeException e) {
            getLogger().warning("종료 정리 중 오류: " + e);
        }
        if (data != null) data.saveAll();
        if (global != null) global.save();
    }

    private void saveResourceIfMissing(String name) {
        if (!new File(getDataFolder(), name).exists()) saveResource(name, false);
    }

    private void loadEmotes() {
        Txt.EMOTES.clear();
        if (!getConfig().getBoolean("emote.enabled", true)) return;
        var sec = getConfig().getConfigurationSection("emote.symbols");
        if (sec != null) for (String k : sec.getKeys(false)) Txt.EMOTES.put(k, sec.getString(k, ""));
    }

    private void loadExtra() {
        loadEmotes();
        messages = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "messages.yml"));
        quizBank = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "quiz.yml"));
    }

    public void reloadAll() {
        reloadConfig();
        loadExtra();
    }

    /** messages.yml 키로 메시지 보내기. kv = "name","값",... */
    /** 빨간 느낌표 에러 알림으로 보낼 메시지 */
    private static final java.util.Set<String> ERROR_KEYS = java.util.Set.of(
            "starter.already", "starter.profile-first", "mail.empty", "shop.nothing", "shop.disabled",
            "gacha.maxed", "fortune.no-more", "playtime.nothing", "playtime.not-enough");

    public void msg(CommandSender to, String key, Object... kv) {
        String raw = messages.getString(key);
        if (raw == null) raw = "&7" + key;
        if (raw.isEmpty()) return;
        String t = Txt.fmt(raw, kv);
        if (to instanceof Player pl && (ERROR_KEYS.contains(key) || Txt.isError(t))) {
            ui().error(pl, t);
            return;
        }
        to.sendMessage(Txt.color(Txt.notice(t)));
    }

    /** 전체 알림: 파란 느낌표 + 흰 글씨(중요) / 회색 글씨(덜 중요) */
    public void announce(String raw, boolean important) {
        String r = Txt.strip(Txt.color(raw)).trim();
        Bukkit.broadcastMessage(Txt.color("&f{e:system} " + (important ? "&f" : "&7") + r));
        if (important) {   // 모드 있는 사람에게는 화면 위 배너도 (금: 잭팟·졸업·행운상자 / 보라: 섭주·괴롭히기·초대 / 파랑: 나머지)
            String color = r.matches(".*(잭팟|졸업|행운상자|대박|서버 최초).*") ? "gold"
                    : r.matches(".*(섭주|괴롭히기|초대|즉사|순회공연|극악).*") ? "purple" : "blue";
            String text = r.replaceAll("[\\uE000-\\uF8FF\\u2000-\\u2BFF]", "").trim();
            kr.kkaegul.core.util.Json b = kr.kkaegul.core.util.Json.obj().put("type", "banner").put("text", text).put("color", color);
            for (Player p : Bukkit.getOnlinePlayers()) if (ui().hasRenderer(p)) ui().send(p, b);
        }
    }

    public void announce(String raw) { announce(raw, true); }

    public YamlConfiguration quizBank() { return quizBank; }

    public DataStore data() { return data; }
    public GlobalData global() { return global; }
    public Clock clock() { return clock; }
    public Places places() { return places; }
    public UiBridge ui() { return ui; }
    public HudService hud() { return hud; }
    public MenuService menu() { return menu; }
    public BoardService boards() { return boards; }
    public Items items() { return items; }
    public MailService mail() { return mail; }
    public DiamondService diamonds() { return diamonds; }
    public RouletteService roulette() { return roulette; }
    public WalletService wallet() { return wallet; }
    public kr.kkaegul.core.casino.FrogJumpService frogJump() { return frogJump; }
    public kr.kkaegul.core.casino.FrogRaceService frogRace() { return frogRace; }
    public FeverService fever() { return fever; }
    public GaugeService gauge() { return gauge; }
    public ActionRunner actions() { return actions; }
    public ApiService api() { return api; }
    private kr.kkaegul.core.npc.NicknameService nickname;
    public kr.kkaegul.core.npc.NicknameService nickname() { return nickname; }
    /** 화면/방송에 쓰는 이름 (닉네임 우선) */
    public String displayName(org.bukkit.entity.Player p) { return nickname == null ? p.getName() : nickname.display(data.get(p)); }
    public BoxService boxes() { return boxes; }
    public JailService jail() { return jail; }
    public WasdService wasd() { return wasd; }
    public QuizService quiz() { return quiz; }
    public HarassService harass() { return harass; }
    public EconomyService economy() { return economy; }
    public SubmitService submit() { return submit; }
    public GachaService gacha() { return gacha; }
    private kr.kkaegul.core.util.InvBackup backups;
    public kr.kkaegul.core.util.InvBackup backups() { return backups; }
    public NpcService npcs() { return npcs; }
    public PlaytimeService rewards() { return rewards; }
    public kr.kkaegul.core.reward.CouponService coupons() { return coupons; }
    public TitleService titles() { return titles; }
    public RankingService ranking() { return ranking; }
    public MineService mine() { return mine; }
    public MinigameManager minigames() { return minigames; }
    public kr.kkaegul.core.command.ChatService chat() { return chat; }
    public kr.kkaegul.core.land.LandService land() { return land; }

    /** 안전 구역(로비 월드, 마을 둘레): PK 금지·몬스터 소환 금지·죽어도 아이템 유지 */
    public boolean safeZone(org.bukkit.Location l) {
        if (l == null || l.getWorld() == null) return false;
        String w = l.getWorld().getName();
        if (getConfig().getStringList("safe-zones.worlds").contains(w)) return true;
        org.bukkit.configuration.ConfigurationSection ps = getConfig().getConfigurationSection("safe-zones.places");
        if (ps != null) for (String name : ps.getKeys(false)) {
            org.bukkit.Location c = places().get(name);
            if (c == null || c.getWorld() == null || !c.getWorld().getName().equals(w)) continue;
            double r = ps.getDouble(name, 100), dx = c.getX() - l.getX(), dz = c.getZ() - l.getZ();
            if (dx * dx + dz * dz <= r * r) return true;
        }
        return false;
    }
    private kr.kkaegul.core.hook.DiscordBridge discord;
    public kr.kkaegul.core.hook.DiscordBridge discord() { return discord; }
}
