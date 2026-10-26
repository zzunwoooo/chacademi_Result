package school.chacademia.gimmick;

import java.io.File;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * ChacaGimmick - dungeon gimmick models, independent of MythicMobs.
 * Models are implemented by ModelEngine 4 (ItemsAdder only ships the merged resource pack); this plugin places,
 * saves and animates them, and wires blocks that touch (Signals: inputs -> conduits -> outputs). An ItemsAdder custom-entity renderer remains as an alternative. Spell hits arrive from ChAcademiaMagicMythic when it is installed.
 */
public final class GimmickPlugin extends JavaPlugin {
    private final GimmickProtection protection=new GimmickProtection();
    GimmickProtection protection(){return protection;}
    private final ModelCatalog catalog = new ModelCatalog();
    private boolean initialized;
    private Renderer renderer;
    private GimmickWorld world;
    private Signals signals;
    private Solids solids;
    private DebugTool tool;
    private Collision collision;
    private Devices devices;
    private Placer placer;
    private final GimmickVfx vfx = new GimmickVfx(this);
    private Object spellActions; // SpellActions when ChAcademiaMagicMythic is present (class not loaded otherwise)

    @Override public void onEnable() {
        saveDefaultConfig();
        if (!new File(getDataFolder(), "models.yml").exists()) saveResource("models.yml", false);
        catalog.load(new File(getDataFolder(), "models.yml"));
        renderer = pickRenderer();
        world = new GimmickWorld(this);
        signals = new Signals(this);
        solids = new Solids(this);
        tool = new DebugTool(this);
        devices = new Devices(this);
        collision = new Collision(this);
        placer = new Placer(this);
        world.load();
        solids.load();
        world.applyOffsets();
        signals.recompute(false);
        collision.load();

        initialized=true;
        var pm = getServer().getPluginManager();
        pm.registerEvents(world, this);
        pm.registerEvents(solids, this);
        pm.registerEvents(new Inputs(this), this);
        pm.registerEvents(tool, this);
        pm.registerEvents(collision, this);
        if (pm.isPluginEnabled("ChAcademiaMagicMythic")) {
            try { var hook = new MagicHook(this); pm.registerEvents(hook, this); spellActions = hook.actions(); getLogger().info("마법 연동 켜짐 (ChAcademiaMagicMythic, 기믹 마법 239~250)"); }
            catch (LinkageError e) { getLogger().warning("마법 연동 실패: ChAcademiaMagicMythic 버전을 확인하세요 (" + e + ")"); }
        } else getLogger().info("ChAcademiaMagicMythic 이 없어 마법 연동은 꺼져 있습니다.");

        if (ChacaLink.ready()) {
            try { new GimmickCommand(this).register(); }
            catch (RuntimeException e) { getLogger().log(java.util.logging.Level.WARNING, "명령어 허브에 /던전관리 갈래를 붙이지 못했습니다", e); }
        } else getLogger().warning("MagicCodexBridge 명령어 허브가 없어 /던전관리 명령을 등록하지 않았습니다 (기믹은 그대로 동작).");

        // ModelEngine loads blueprints a moment after start; spawn a little later.
        Bukkit.getScheduler().runTaskLater(this, () -> { signals.recompute(false); world.spawnLoaded(); for (Gimmick g : world.all()) collision.refresh(g); }, 40);
        getLogger().info("기믹 " + world.all().size() + "개, 렌더러 " + renderer.name()
            + (renderer.available() ? "" : " (사용 불가 - 플러그인 설치 확인)"));
    }

    @Override public void onDisable() {
        if(!initialized)return;
        if (spellActions instanceof SpellActions a) a.shutdown();
        if (world != null) { world.despawnAll(); world.save(); }
    }

    private Renderer pickRenderer() {
        String want = getConfig().getString("renderer", "modelengine").toLowerCase(Locale.ROOT);
        if (want.equals("itemsadder")) return new ItemsAdderRenderer(getLogger());
        try { return new ModelEngineRenderer(getLogger()); }
        catch (LinkageError e) {
            getLogger().severe("ModelEngine 을 불러오지 못했습니다. ModelEngine 4 설치를 확인하세요: " + e);
            return new ItemsAdderRenderer(getLogger());
        }
    }

    /** config/models reload; live models are rebuilt so renderer or prefix changes apply. */
    List<String> reloadAll() {
        try{for(String name:List.of("config.yml","models.yml","gimmicks.yml"))AtomicYaml.load(new File(getDataFolder(),name));
        }catch(IllegalStateException e){return List.of("Reload rejected; current state retained: "+e.getMessage());}
        reloadConfig();
        world.save();
        world.despawnAll();
        catalog.load(new File(getDataFolder(), "models.yml"));
        renderer = pickRenderer();
        world.applyOffsets();
        vfx.reload();
        signals.recompute(false);
        world.spawnLoaded();
        for (Gimmick g : world.all()) collision.refresh(g);
        return List.of();
    }

    String fullId(String model) { return getConfig().getString("model-prefix", "") + model; }
    boolean debug() { return getConfig().getBoolean("debug", false); }
    ModelCatalog catalog() { return catalog; }
    Renderer renderer() { return renderer; }
    GimmickWorld world() { return world; }
    Signals signals() { return signals; }
    DebugTool tool() { return tool; }
    Collision collision() { return collision; }
    Devices devices() { return devices; }
    Placer placer() { return placer; }
    GimmickVfx vfx() { return vfx; }
}
