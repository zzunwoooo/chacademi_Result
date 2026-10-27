package school.magiccodex.paper;
import school.magiccodex.protocol.ManaProtocol;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import school.magiccodex.protocol.PermissionProtocol;
import school.magiccodex.database.DatabaseSettings;

/** Permission/wallet synchronization and authoritative mana/casting service. */
public final class MagicCodexBridge extends JavaPlugin implements PluginMessageListener, Listener {
    private PermissionSubscriptions subscriptions;
    private AuditLog audit;
    private final Set<UUID> viewers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> dirty = ConcurrentHashMap.newKeySet();
    private AutoCloseable luckPermsHook;
    private WalletBridge wallet;
    private MailboxBridge mailbox;
    private ShopBridge shops;
    private ShopAdminBridge shopAdmin;
    private ManaBridge mana;
    private SpellRuntime spellRuntime;
    private ServerHeartbeat heartbeat; // 메모 #92 운영 대시보드 하트비트
    private final Set<String> missingRuntimeRoutes = new java.util.HashSet<>();
    Boolean runtimeCast(Player player,String id){
        if(!MagicProtectionLink.cast(player)){player.sendMessage("이 구역에서는 마법을 사용할 수 없습니다.");return false;}
        // Gameplay route first: a refused target must not show a visual or keep the charge.
        SpellRuntime.Plan plan=spellRuntime==null?null:spellRuntime.prepare(player,id);
        if(plan==null&&missingRuntimeRoutes.add(id))getLogger().warning("No gameplay runtime route for "+id+"; using existing VFX/command fallback.");
        return CatalogGameplayFlow.cast(plan==null||plan.ok(),plan!=null,
            ()->CatalogVfxLink.cast(this,player,id,plan==null?null:plan.target()),()->spellRuntime.execute(player,plan),
            failure->getLogger().warning("마법 실행 실패: "+id+" / "+failure.getClass().getSimpleName()));
    }
    boolean requiresCatalogVisual(String id){return mana!=null&&mana.requiresCatalogVisual(id);}
    public boolean castMagic(Player player,String id){return mana!=null&&mana.castRegistered(player,id);}
    /** Server-thread registered ID/name/permission snapshot, including disabled casting entries; no file I/O. */
    public java.util.Map<String,java.util.Map<String,String>> registeredSpellCatalog(){
        ManaService.main();return mana==null?java.util.Map.of():mana.registeredSpellCatalog();
    }
    public double magicPower(Player player){
        refreshEquipment(player);
        Double base=stats==null?null:stats.service().get(player.getUniqueId()).power();
        Double value=equipmentPower(player.getUniqueId(),base);
        return value==null?0:Math.clamp(value,0,1_000_000);
    }
    public double magicHaste(Player player){return mana==null?0:mana.mana.snapshot(player.getUniqueId()).map(s->s.haste()).orElse(0d);}
    private StatsBridge stats;
    private SocialBridge social;
    private PartyIdentityBridge partyIdentity;
    private PartyVitalsBridge partyVitals;
    private AscensionBridge ascension;
    private CoreVisualBridge coreVisuals;
    org.bukkit.inventory.ItemStack coreAppearance(org.bukkit.inventory.ItemStack item){return coreVisuals==null?item:coreVisuals.appearance(item);}
    boolean coreSimilar(org.bukkit.inventory.ItemStack a,org.bukkit.inventory.ItemStack b){return coreVisuals==null?a!=null&&b!=null&&a.isSimilar(b):coreVisuals.similar(a,b);}
    private AppraisalBridge appraisal;
    private EnhancementBridge enhancement;
    /** 장신구 강화·등급등록 (EquipmentBridge). 켜지기 전이면 null. */
    EnhancementBridge enhancementBridge(){return enhancement;}
    /** 강화 진행 중(구슬이 도는 중)이면 장비 바꾸기를 막는다. */
    boolean enhancing(org.bukkit.entity.Player p){return enhancement!=null&&enhancement.active(p);}
    /** Server-thread API for future scroll items/NPC scripts. mode: 0 reset, 1 restore, 2 affinity. */
    public void grantReconfiguration(org.bukkit.entity.Player player,int mode,int count){reconfiguration.grant(player,mode,count);}
    public void openReconfiguration(org.bukkit.entity.Player player,int mode){reconfiguration.open(player,mode);}
    private ReconfigurationBridge reconfiguration;
    void refreshEquipment(org.bukkit.entity.Player p){if(equipment!=null)equipment.refresh(p);}
    /** 정보창(StatsBridge)이 장신구·아티팩트를 읽는다. 없으면 null. */
    EquipmentBridge equipmentBridge(){return equipment;}
    ArtifactBridge artifactBridge(){return artifacts;}
    /** #108 하우징 아티팩트 진열장 (ChacaHousing 이 리플렉션으로 부름): 장착 중이고 아직 가진 아티팩트 id. 메인 스레드. */
    public java.util.List<String> equippedArtifactIds(org.bukkit.entity.Player p){return artifacts==null||p==null?java.util.List.of():artifacts.equipped(p);}
    private TemperatureBridge temperature;
    private PetBridge pets;
    private TamingBridge taming;
    /** Shared server ray (ModelEngine sub-hitboxes / hitbox interactions resolved to the modeled mob); null before enable. */
    TamingBridge tamingBridge(){return taming;}
    private TargetHudBridge targetHud;
    private QuestBridge quests;
    private JournalBridge journal;
    JournalBridge journalBridge(){return journal;}
    private DialogueBridge dialogues;
    private NpcSocialService npcSocial;
    DialogueBridge dialogueBridge(){return dialogues;}
    QuestBridge questBridge(){return quests;}
    private TitleBridge titles;
    /** #47 마법 효과음 (마크 기본 사운드 층). */
    private SpellSounds spellSounds;
    /** 마법별 힌트 파일 spell-hints.yml + 발견 조건표 (마법 힌트권·깨달음의 빛·NPC 힌트 공통, 2026-10-26). */
    private SpellHints spellHints;
    SpellHints spellHints(){return spellHints;}
    /** Trusted server-thread API; the callback runs after database commit. */
    public void grantTitle(UUID player,String id,java.util.function.Consumer<Boolean> result){if(titles==null)result.accept(false);else titles.grant(player,id,false,result);}
    public void revokeTitle(UUID player,String id,java.util.function.Consumer<Boolean> result){if(titles==null)result.accept(false);else titles.grant(player,id,true,result);}
    /** MagicDiscovery가 리플렉션으로 부른다 (서버 스레드): 발견한 마법 → title-awards.yml discovery 칭호. 중복 지급은 칭호 장부가 막는다. */
    public void discoveryTitles(UUID player,java.util.Collection<String> spells){if(titles!=null)titles.discovered(player,spells);}
    /** MagicDiscovery가 리플렉션으로 부른다 (서버 스레드): 처음 발견한 마법 하나 (다시 습득·접속 시 맞추기는 안 옴) → 아티팩트 반응 일지. */
    public void discoveryFirst(UUID player,String spell){if(artifactEffects!=null)artifactEffects.comfort.discovered(player,spell);}
    public void registerDialogueAction(String id,java.util.function.BiConsumer<Player,String> action){if(dialogues==null)throw new IllegalStateException("Dialogue not ready");dialogues.register(id,action);}
    public void registerDialogueCondition(String id,java.util.function.BiPredicate<Player,String> condition){if(dialogues==null)throw new IllegalStateException("Dialogue not ready");dialogues.registerCondition(id,condition);}
    boolean dialogueQuestCondition(Player p,String id,String status){return quests!=null&&quests.dialogueCondition(p,id,status);}
    void acceptDialogueQuest(Player p,String id,java.util.function.Consumer<Boolean> result){if(quests==null)result.accept(false);else quests.acceptFromDialogue(p,id,result);}
    void dialogueQuestEvent(Player p,String id,java.util.function.Consumer<Boolean> result){if(quests==null)result.accept(false);else quests.eventFromDialogue(p,id,result);}
    /** Trusted server integrations only; not a client-authoritative progress endpoint. */
    /**
     * #47 마법 효과음: 이 마법의 사건(cast·hit·end 또는 VFX 사건 이름 external:hit 등) 소리를 그 자리에서 재생. 서버 스레드.
     * MagicMythic 이 VFX 사건을 낼 때 리플렉션으로 부른다 (없으면 조용히 건너뜀).
     */
    public void spellSound(Player caster,String spell,String event,org.bukkit.Location at){if(spellSounds!=null&&org.bukkit.Bukkit.isPrimaryThread())spellSounds.play(caster,spell,event,at);}
    SpellSounds spellSounds(){return spellSounds;}
    public void questEvent(Player player,String eventId,int amount){if(quests!=null)quests.event(player,QuestDefinition.Type.EVENT,eventId,amount);}
    /** Server-thread API for a spell/NPC integration. Server validates aim, mana, cooldown and permission. */
    public boolean castTaming(Player player){return castTamingResult(player).status()==ManaProtocol.OK;}
    ManaSpells.Spell tamingSpell(){return mana==null?null:mana.tamingSpell();}
    ManaCasting.Result castTamingResult(Player player){return taming==null?new ManaCasting.Result(ManaProtocol.FAILED,0):taming.castResult(player);}
    private ClimateService climate;
    /** Season source for fishing (null before start / when the climate failed). */
    ClimateService climateService(){return climate;}
    private DisplayNames names;
    private SchoolBridge school;
    private EquipmentBridge equipment;
    private ArtifactBridge artifacts;
    private AlchemyBridge alchemy;
    private FishingBridge fishing;
    private FishShopBridge fishShop;
    private BookBridge books;
    private LessonBridge lessons;
    /** 수업 (NPC 대화창 수업 버튼 → NpcSocialFacade.openLesson). 없으면 null. */
    LessonBridge lessonBridge(){return lessons;}
    /** NPC 호감도 서비스 (수업 비전 해금 등). 없으면 null. */
    NpcSocialService npcSocial(){return npcSocial;}
    private TravelBridge travel;
    private CombatTag combatTag;
    /** 전투 표시·전투 중 이동 막기 (메모 #91, combat-lock.yml). null = 초기화 실패. */
    public CombatTag combatTag(){return combatTag;}
    private MileageBridge mileage;
    /** 마일리지 상점 (null = shop-mailbox DB 초기화 실패). 별풍선 후원 마일리지 지급이 쓴다. */
    MileageBridge mileage(){return mileage;}
    private FishTankBridge fishTank;
    private BagPagesBridge bagPages;
    /** 공통 지급 함수 (2026-10-26). */
    private RewardDelivery rewardDelivery;
    /** 뽑기 상자 (API 상자, 2026-10-26). */
    private LootBoxBridge lootBoxes;
    /** 야생 청크 점유 (메모 #88, chunk-claims.yml servers 에 든 서버에서만 켜짐). */
    private ChunkClaimBridge chunkClaims;
    private HouseBoardBridge houseBoard;
    /** 생활 마법 377 / 382 (LifeSpells); null before enable or when the system failed. */
    FishingBridge fishingBridge(){return fishing;}
    BagPagesBridge bagPagesBridge(){return bagPages;}
    /** 비늘 감정 (LifeSpells): null when fish-shop.yml failed to load. */
    FishShopBridge fishShopBridge(){return fishShop;}
    private ArtifactEffects artifactEffects;
    private PartyRoster partyRoster;
    /** Party membership pushed by Velocity (also a Bukkit service). Null before enable. */
    public PartyRoster partyRoster(){return partyRoster;}
    /** Equipped artifacts changed (equip/unequip, ownership): recompute their effects now. */
    void artifactsChanged(Player p){if(artifactEffects!=null)artifactEffects.refresh(p);savePlayerState(p);}
    private PlayerStats stats2;
    /** Derived stats (equipment, artifacts, ...): one refresh for server arrival and for changes. Public for other plugins. */
    public PlayerStats stats(){return stats2;}
    /** Re-applies every registered stat source for this player (server thread). */
    public void refreshStats(Player p){if(stats2!=null)stats2.refresh(p);}
    String artifactCooldowns(UUID player){return artifacts==null?"":artifacts.cooldownText(player);}
    void restoreArtifactCooldowns(UUID player,String text){if(artifacts!=null)artifacts.restoreCooldowns(player,text);}
    /** Artifact runtime state that must survive a server switch (internal cooldowns, timed buffs, armed flags). */
    String artifactRuntime(Player p){
        // 동행 펫 (2026-10-26): 꺼낸 펫 ID를 같은 칸 앞에 "cpet=ID;" 로 붙인다 (새 DB 컬럼 없음, 칸 최대 4096자).
        var c=pets==null?null:pets.companions();
        return CompanionMath.join(c==null?null:CompanionMath.pet(c.runtimeToken(p)),artifactEffects==null?"":artifactEffects.runtimeText(p),4096);
    }
    void restoreArtifactRuntime(Player p,String text){
        var c=pets==null?null:pets.companions();
        if(c!=null)c.restore(p,CompanionMath.pet(text));
        if(artifactEffects!=null)artifactEffects.restoreRuntime(p,CompanionMath.withoutPet(text));
    }
    /** 동행 펫 passive fishing: 낚시 희귀 등급 배율 (1 = 없음). */
    double petFishing(Player p){var c=pets==null?null:pets.companions();return c==null?1:c.fishingFactor(p);}
    /** A spell was cast successfully (after mana and cooldown). */
    void artifactCast(Player p,String spell,double cost){AuditStats.add(p.getUniqueId(),"시전",1);AuditStats.add(p.getUniqueId(),"시전:"+spell,1);/* 서버 통계 #96 (메모리만) */if(artifactEffects!=null)artifactEffects.cast(p,spell,cost);}
    private PlayerStateBridge playerState;
    boolean playerStateReady(Player p){return playerState==null||playerState.ready(p);}
    /** Combat tag (artifact effects: dealt or took hostile damage in the last 5 s). */
    boolean inCombat(Player p){return artifactEffects!=null&&artifactEffects.inCombat(p);}
    /** 지도 이동 (null when travel.yml failed to load). Personal point API: travel().setPersonal(player, location, source, done). */
    public TravelBridge travel(){return travel;}
    void savePlayerState(Player p){if(playerState!=null)playerState.save(p);}
    /** 잦은 변경(마법 시전)용: 표시만 하고 다음 주기 저장에 묶는다. 종료·서버 이동 시에는 항상 저장된다. */
    void markPlayerStateDirty(Player p){if(playerState!=null)playerState.markDirty(p);}
    Double equipmentPower(UUID id,Double base){base=DiscoveryIntel.add(mana,id,base); // 발견한 마법 수 × 0.2 (2026-10-27)
        Double v=equipment==null?base:equipment.power(id,base);if(v!=null&&artifactEffects!=null)v=v*artifactEffects.rules.powerFactor(id); // 단 하나의 서: 지능 -50%
        var c=pets==null?null:pets.companions();if(v==null||c==null)return v;return v*c.powerFactor(id);} // 동행 펫 combat intelligence. 삼항식이면 null v 를 언박싱해 NPE (2026-10-27 길잡이 별 등 시전 실패)
    /** mana-spells.yml entry (cost, cooldown) of an enabled spell, or null. */
    ManaSpells.Spell spellRule(String id){return mana==null?null:mana.reviewSpell(id);}
    /** 단 하나의 서: may change the cooldown of a successful cast before the client hears it. */
    ManaCasting.Result artifactCooldown(Player p,ManaSpells.Spell spell,ManaCasting.Result result){return artifactEffects==null?result:artifactEffects.rules.cooldown(p,spell,result);}
    DisplayNames names(){return names;}
    /** 아티팩트(그림자 군주의 가면·변신 가면)가 펫 판정에 쓴다. null 일 수 있음. */
    PetBridge pets(){return pets;}
    TitleBridge titleService(){return titles;}
    SchoolBridge schoolBridge(){return school;}
    private CommandHub commandHub;
    CommandHub commandHub(){return commandHub;}
    int questHouse(Player p){return school==null?-1:school.house(p);}
    /** 기숙사 0 아르케온 · 1 루미나 · 2 베스티아즈 · 3 노크세르, 없으면 -1 (큐디치 준비 화면 등 다른 플러그인용). */
    public int houseOf(Player p){return questHouse(p);}
    /** MagicCodex 한글 닉네임 (saved nickname, else the display name, else the account name). Main thread. Used by 큐디치. */
    public String nicknameOf(org.bukkit.OfflinePlayer p){String fallback=p.getName()==null?"플레이어":p.getName();return names==null?fallback:names.name(p,fallback);}
    void questHouseReward(String token,int house,long points,Runnable done,java.util.function.Consumer<String> fail){if(school==null){fail.accept("학교 기록 없음");return;}school.questReward(token,house,points,done,fail);}
    String dorm(Player p){return school==null?"":school.dorm(p);}
    int circle(Player player){return ascension==null?1:ascension.current(player);}
    private boolean immediateScheduled;
    private static long now() { return System.nanoTime() / 1_000_000L; }

    /** /서버관리 상태 "DB 공유" 줄 (시작 시 한 번 계산). */
    private String databaseStatus = "확인 전";
    /**
     * 시작 시 DB 설정마다 한 줄(파일 유무·모드)을 남긴다: 일반 도메인(GenericDatabaseFile), 캐릭터 저장(전용 → 일반), 상점·우편.
     * SQLite로 동작하는 도메인이 있으면 WARNING으로 목록을 남긴다. database.require-mariadb: true면 그런 설정이 하나라도 있을 때 시작을 거부한다.
     */
    private boolean databaseModeAccepted() {
        boolean require = getConfig().getBoolean("database.require-mariadb", false), accepted = true;
        var folder = getDataFolder().toPath();
        var playerState = folder.resolve(GenericDatabaseFile.DEDICATED);
        var checks = new java.util.LinkedHashMap<String, java.nio.file.Path>();
        checks.put(String.join("·", GenericDatabaseFile.DOMAINS), GenericDatabaseFile.resolve(folder));
        checks.put("캐릭터 저장·아티팩트·연금술·낚시", java.nio.file.Files.exists(playerState) ? playerState : folder.resolve(GenericDatabaseFile.GENERIC));
        checks.put("상점·우편", folder.resolve(ShopMailboxDatabaseSettings.FILE_NAME));
        var local = new java.util.ArrayList<String>();
        for (var check : checks.entrySet()) {
            var file = check.getValue();
            try {
                var settings = DatabaseSettings.load(file);
                getLogger().info(DatabaseSettings.describe(file, settings) + " ← " + check.getKey()
                        + (file.getFileName().toString().equals(GenericDatabaseFile.DEDICATED) && check.getKey().startsWith("닉네임") ? " (" + GenericDatabaseFile.OPT_IN + "=true)" : ""));
                if (!settings.mariaDb()) { accepted = false; local.add(check.getKey()); }
            } catch (IOException error) { getLogger().severe("DB 설정 " + file.getFileName() + " 오류: " + error.getMessage()); accepted = false; local.add(check.getKey() + "(설정 오류)"); }
        }
        if (!local.isEmpty()) getLogger().warning("SQLite(이 서버 파일)로 동작하는 도메인: " + String.join(" / ", local)
                + ". 이 데이터는 다른 서버(학교·야생·아레나)와 공유되지 않습니다. 일반 도메인 로컬 파일: " + GenericDatabaseFile.SQLITE_FILES
                + ". 공유 DB로 옮기려면 먼저 tools/db-migrate 이관 도구를 실행하세요 (docs/handoff/CLAUDE_FIX_DBTOOL_20261021.md).");
        databaseStatus = local.isEmpty() ? "정상 · 모든 도메인 MariaDB 공유" : "주의 · SQLite(서버 로컬): " + String.join(" / ", local);
        if (!require || accepted) return true;
        getLogger().severe("database.require-mariadb=true 인데 MariaDB가 아닌(SQLite 기본값 또는 잘못된) DB 설정 파일이 있습니다. 서버 간 데이터가 갈라지지 않도록 플러그인을 시작하지 않습니다. 위 'DB 설정' 줄을 확인하세요.");
        getServer().getPluginManager().disablePlugin(this);
        return false;
    }
    @Override public void onEnable() {
        saveDefaultConfig();
        if (!databaseModeAccepted()) return;
        names=new DisplayNames(this);
        commandHub=new CommandHub(this);
        commandHub.status(this,"DB 공유",()->databaseStatus);
        audit=AuditLog.start(this); // 운영 기록 (메모 #92): 대기열 + 기록 스레드, 게임 스레드 IO 없음
        wallet=new WalletBridge(this);
        long refresh = Math.clamp(getConfig().getLong("refresh-interval-ticks", 200), 20, 1200) * 50;
        long timeout = Math.clamp(getConfig().getLong("session-timeout-seconds", 45), 30, 120) * 1000;
        int budget = Math.clamp(getConfig().getInt("max-permission-checks-per-pass", 2048), PermissionProtocol.MAX_PERMISSIONS, 16384);
        subscriptions = new PermissionSubscriptions(refresh, timeout, budget);
        getServer().getMessenger().registerIncomingPluginChannel(this, PermissionProtocol.REQUEST, this);
        getServer().getMessenger().registerOutgoingPluginChannel(this, PermissionProtocol.RESPONSE);
        getServer().getPluginManager().registerEvents(this, this);
        if (getServer().getPluginManager().isPluginEnabled("LuckPerms")) {
            try { luckPermsHook = LuckPermsHook.connect(this, this::markDirty); }
            catch (RuntimeException | LinkageError error) { getLogger().warning("LuckPerms 알림 연결 실패: 보조 권한 확인을 사용합니다."); }
        }
        getServer().getScheduler().runTaskTimer(this, this::process, 1L, 20L);
        try { mana=new ManaBridge(this);stats2=new PlayerStats(this,mana.mana);stats2.register("equipment",this::refreshEquipment);stats=new StatsBridge(this,mana);school=new SchoolBridge(this,stats.service());social=new SocialBridge(this,mana,stats.service());partyIdentity=new PartyIdentityBridge(this);partyVitals=new PartyVitalsBridge(this,mana.mana);ascension=new AscensionBridge(this,stats.service());temperature=new TemperatureBridge(this);pets=new PetBridge(this,mana.mana);climate=new ClimateService(this,mana.mana,temperature.service());if(pets.companions()!=null)climate.immune(pets.companions()::climateImmune);equipment=new EquipmentBridge(this,mana.mana);coreVisuals=new CoreVisualBridge(this);appraisal=new AppraisalBridge(this);enhancement=new EnhancementBridge(this);reconfiguration=new ReconfigurationBridge(this,enhancement);playerState=new PlayerStateBridge(this,mana.mana,DatabaseSettings.load(java.nio.file.Files.exists(getDataFolder().toPath().resolve("player-state-database.properties"))?getDataFolder().toPath().resolve("player-state-database.properties"):getDataFolder().toPath().resolve("database.properties"))); }
        catch(Exception error) {
            getLogger().severe("마나 초기화 실패: "+error.getMessage());
            getServer().getPluginManager().disablePlugin(this);return;
        }
        try{taming=new TamingBridge(this,mana.mana,pets);}catch(Exception error){getLogger().severe("교화 초기화 실패: "+error.getMessage());getServer().getPluginManager().disablePlugin(this);return;}
        try{targetHud=new TargetHudBridge(this,taming,mana.mana,names);}catch(RuntimeException|LinkageError error){targetHud=null;getLogger().warning("대상 체력바 초기화 실패 (나머지는 계속): "+error);}
        try{quests=new QuestBridge(this);}catch(Exception error){getLogger().severe("의뢰 초기화 실패: "+error.getMessage());getServer().getPluginManager().disablePlugin(this);return;}
        try{dialogues=new DialogueBridge(this);}catch(Exception error){getLogger().severe("대화 초기화 실패: "+error.getMessage());getServer().getPluginManager().disablePlugin(this);return;}
        try{npcSocial=new NpcSocialService(this);}catch(Exception error){npcSocial=null;getLogger().warning("NPC 호감도 초기화 실패 (AI NPC 연결 없이 계속): "+error.getMessage());}
        DailyLimits.registerAffinity(this,npcSocial); // 메모 #90: /유저관리 <닉> 일일제한 보기·초기화
        spellSounds=new SpellSounds(this);
        ChacaApi.reload(this,"마법소리","spell-sounds.yml (마법 효과음)",()->spellSounds.reload());
        spellHints=new SpellHints(this);
        ChacaApi.reload(this,"힌트","spell-hints.yml (마법별 힌트 1·2성)",()->spellHints.reload());
        try{titles=new TitleBridge(this);}catch(Exception error){getLogger().severe("칭호 초기화 실패: "+error.getMessage());getServer().getPluginManager().disablePlugin(this);return;}
        if(getServer().getPluginManager().isPluginEnabled("MythicMobs"))try{spellRuntime=new SpellRuntime(this);getLogger().info("Spell gameplay runtime ready: "+spellRuntime.routeCount()+" routes; catalog="+mana.isCatalogMode());}catch(Exception error){getLogger().severe("마법 실행 초기화 실패: "+error.getMessage());getServer().getPluginManager().disablePlugin(this);return;}
        if(spellRuntime==null)getLogger().warning("Spell gameplay runtime unavailable: MythicMobs is not enabled; existing fallback routes remain active.");
        mana.bindSpellCommand();
        try { mailbox=new MailboxBridge(this); shops=new ShopBridge(this); shopAdmin=new ShopAdminBridge(this); } catch(Exception e) { getLogger().severe("Mailbox initialization failed: "+e.getClass().getSimpleName()); getServer().getPluginManager().disablePlugin(this); return; }
        // 공통 지급 함수 (2026-10-26): 넣을 수 있는 칸(핫바 + 잠금 해제된 지금 쪽) → 남은 것만 우편. 실패해도 각 경로는 예전 방식으로 계속.
        try{rewardDelivery=new RewardDelivery(this);}catch(Exception error){rewardDelivery=null;getLogger().severe("공통 지급(보상 우편 대기열) 초기화 실패: "+error);}
        try{mileage=new MileageBridge(this);}catch(Exception error){getLogger().severe("마일리지 상점 초기화 실패 (shop-mailbox DB 확인): "+error);}
        journal=new JournalBridge(this);
        partyRoster=new PartyRoster(this);
        try{artifacts=new ArtifactBridge(this);}catch(Exception error){getLogger().severe("아티팩트 도감 초기화 실패 (artifacts.yml 확인): "+error.getMessage());}
        if(artifacts!=null)try{artifactEffects=new ArtifactEffects(this,artifacts,mana.mana,partyRoster,npcSocial);}catch(RuntimeException|LinkageError error){getLogger().severe("아티팩트 효과 초기화 실패: "+error);}
        if(artifactEffects!=null){stats2.register("artifacts",artifactEffects::refresh);stats2.afterArrival("artifacts",artifactEffects::arrived);}
        if(artifacts!=null)stats2.afterArrival("artifact-client",artifacts::push);
        try{alchemy=new AlchemyBridge(this);}catch(Exception error){getLogger().severe("연금술 초기화 실패 (alchemy.yml 확인): "+error.getMessage());}
        try{fishing=new FishingBridge(this);}catch(Exception error){getLogger().severe("낚시 초기화 실패 (fishing*.yml 확인): "+error.getMessage());}
        if(fishing!=null)try{fishShop=new FishShopBridge(this,fishing);}catch(Exception error){getLogger().severe("물고기 판매소 초기화 실패 (fish-shop.yml 확인): "+error.getMessage());}
        if(fishing!=null){fishTank=new FishTankBridge(this,fishing,fishShop);fishing.tankWindow(fishTank);if(mana!=null)mana.fishTrapCast=fishTank::cast;}
        try{books=new BookBridge(this);}catch(Exception error){getLogger().severe("책 읽기 초기화 실패 (books.yml·book-blocks.yml 확인): "+error.getMessage());}
        if(books!=null&&dialogues!=null)try{lessons=new LessonBridge(this,books,dialogues);}catch(Exception error){getLogger().severe("수업 초기화 실패 (lessons.yml 확인): "+error.getMessage());}
        try{travel=new TravelBridge(this);}catch(Exception error){getLogger().severe("지도 이동 초기화 실패 (travel.yml 확인): "+error.getMessage());}
        if(travel!=null&&mana!=null){mana.spatialLeapCast=travel::castSpatialLeap;travel.mana(mana);}
        try{combatTag=new CombatTag(this);}catch(Exception error){getLogger().severe("전투 중 이동 막기 초기화 실패 (combat-lock.yml 확인): "+error.getMessage());}
        if(travel!=null)try{new ReturnTicketBridge(this,travel);}catch(Exception error){getLogger().severe("귀환권 초기화 실패 (return-ticket.yml 확인): "+error.getMessage());}
        if(travel!=null)try{new TravelItemsBridge(this,travel);}catch(Exception error){getLogger().severe("리턴권·텔포권 초기화 실패 (travel-items.yml 확인): "+error.getMessage());}   // 2026-10-26
        try{new MegaphoneBridge(this);}catch(Exception|LinkageError error){getLogger().severe("확성기 초기화 실패 (megaphone.yml 확인): "+error.getMessage());}
        try{new UtilityItemsBridge(this);}catch(Exception|LinkageError error){getLogger().severe("사용 아이템(변색종 변경권·마법 힌트권·재화 주머니) 초기화 실패 (utility-items.yml 확인): "+error.getMessage());}   // 2026-10-26
        // 아공간 배낭 2·3쪽 (2026-10-24). 실패해도 나머지는 계속 (인벤토리는 1쪽만).
        try{bagPages=new BagPagesBridge(this);}catch(Exception|LinkageError error){bagPages=null;getLogger().severe("아공간 배낭 초기화 실패 (bag-pages.yml·DB 확인): "+error.getMessage());}
        try{new InventorySaveBridge(this);}catch(Exception|LinkageError error){getLogger().severe("인벤세이브 초기화 실패 (inventory-save.yml 확인): "+error.getMessage());}   // 2026-10-26 (배낭 다음: 사망 시 다른 쪽 떨어뜨리기)
        // 뽑기 상자 (API 상자, 2026-10-26): 아공간 배낭 다음에 (지급 칸 판정이 배낭 상태를 읽음).
        try{lootBoxes=new LootBoxBridge(this);}catch(Exception|LinkageError error){lootBoxes=null;getLogger().severe("뽑기 상자 초기화 실패 (loot-boxes.yml 확인): "+error.getMessage());}
        // 별풍선 후원 보상 (2026-10-26, FINAL_PATCHES §43): donation 플러그인이 있으면 후원 이벤트를 받아 처리하고 취소 (api 콘솔 명령 안 돌게).
        try{new BalloonDonationBridge(this);}catch(Exception|LinkageError error){getLogger().severe("별풍선 후원 보상 초기화 실패 (donation.yml 확인): "+error.getMessage());}
        try{chunkClaims=new ChunkClaimBridge(this);}catch(Exception|LinkageError error){chunkClaims=null;getLogger().severe("청크 점유 초기화 실패 (chunk-claims.yml·DB 확인): "+error.getMessage());}
        try{houseBoard=new HouseBoardBridge(this);}catch(Exception|LinkageError error){houseBoard=null;getLogger().severe("기숙사 점수 홀로그램 초기화 실패 (house-board.yml 확인): "+error.getMessage());}   // 메모 #106
        try{new DrawBoardBridge(this);}catch(Exception|LinkageError error){getLogger().severe("행운의 뽑기판 초기화 실패 (database.properties·DB 확인): "+error.getMessage());}   // 운영자 100칸 뽑기판 (§44, 공유 DB)
        new AdminCommandBridge(this,mana,school,enhancement,appraisal,titles,npcSocial);
        NameplateBootstrap.initialize(this);
        try{heartbeat=ServerHeartbeat.start(this);commandHub.status(this,"하트비트",()->heartbeat==null?"꺼짐":heartbeat.describe());}catch(Exception|LinkageError error){heartbeat=null;getLogger().warning("운영 하트비트 시작 실패 (게임은 계속): "+error);}
        getLogger().info("권한 연동 준비 완료. 활성 도감만 조회, 작업당 최대 " + budget + "회, 보조 확인 " + refresh / 1000 + "초.");
    }
    private void markDirty(UUID player) {
        // LuckPerms may invoke this off-thread. No Bukkit API is accessed here.
        if (viewers.contains(player)) dirty.add(player);
    }
    @Override public void onPluginMessageReceived(String channel, Player player, byte[] data) {
        if (!PermissionProtocol.REQUEST.equals(channel) || data.length < 13 || data.length > PermissionProtocol.MAX_BYTES) return;
        int action = data[4];
        if (action == PermissionProtocol.OPEN) {
            if (!subscriptions.allowOpen(player.getUniqueId(), now())) return;
        } else if ((action != PermissionProtocol.CLOSE && action != PermissionProtocol.KEEPALIVE) || data.length != 13) return;
        try {
            var request = PermissionProtocol.decodeRequest(data);
            subscriptions.accept(player.getUniqueId(), request, now());
            if (subscriptions.subscribed(player.getUniqueId())) viewers.add(player.getUniqueId());
            else { viewers.remove(player.getUniqueId()); dirty.remove(player.getUniqueId()); }
            if (request.action() == PermissionProtocol.OPEN) scheduleImmediate();
        } catch (IOException | IllegalArgumentException ignored) { /* Invalid input cannot trigger permission work. */ }
    }
    private void scheduleImmediate() {
        if (immediateScheduled) return;
        immediateScheduled = true;
        getServer().getScheduler().runTask(this, () -> {
            immediateScheduled = false;
            process();
        });
    }
    private void process() {
        for (UUID player : dirty) if (dirty.remove(player)) subscriptions.dirty(player);
        if (subscriptions.size() == 0) { viewers.clear(); return; }
        subscriptions.process(now(), new PermissionSubscriptions.Access() {
            private UUID cachedId;
            private Player cachedPlayer;
            private Player player(UUID id) {
                if (!id.equals(cachedId)) { cachedId = id; cachedPlayer = getServer().getPlayer(id); }
                return cachedPlayer;
            }
            public boolean online(UUID id) { var p = player(id); return p != null && p.isOnline(); }
            public boolean hasPermission(UUID id, String permission) {
                var p = player(id);
                return p != null && p.hasPermission(permission);
            }
            public void send(UUID id, PermissionProtocol.Response response) {
                var p = player(id);
                if (p != null) p.sendPluginMessage(MagicCodexBridge.this, PermissionProtocol.RESPONSE, PermissionProtocol.encodeResponse(response));
            }
        });
        viewers.removeIf(id -> !subscriptions.subscribed(id));
    }
    @EventHandler public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        subscriptions.remove(id); viewers.remove(id); dirty.remove(id);
    }
    @EventHandler public void onWorldChange(PlayerChangedWorldEvent event) { markDirty(event.getPlayer().getUniqueId()); }
    @Override public void onDisable() {
        if(commandHub!=null)commandHub.close();
        if(heartbeat!=null){heartbeat.close();heartbeat=null;}
        if(alchemy!=null){alchemy.close();alchemy=null;}
        if(fishTank!=null){fishTank.close();fishTank=null;}
        if(bagPages!=null){bagPages.close();bagPages=null;}
        if(chunkClaims!=null){chunkClaims.close();chunkClaims=null;}
        if(houseBoard!=null){houseBoard.close();houseBoard=null;}
        if(fishShop!=null){fishShop.close();fishShop=null;}
        if(books!=null){books.close();books=null;}
        if(travel!=null){travel.close();travel=null;}
        if(mileage!=null){mileage.close();mileage=null;}
        if(fishing!=null){fishing.close();fishing=null;}
        if(lootBoxes!=null){lootBoxes.close();lootBoxes=null;}
        if(rewardDelivery!=null){rewardDelivery.close();rewardDelivery=null;}
        if(shopAdmin!=null)shopAdmin.close(); if(shops!=null)shops.close(); if(mailbox!=null)mailbox.close();
        if(spellRuntime!=null){spellRuntime.close();spellRuntime=null;}
        if(titles!=null){titles.close();titles=null;}
        if(npcSocial!=null){npcSocial.close();npcSocial=null;}
        if(dialogues!=null){dialogues.close();dialogues=null;}
        if(quests!=null){quests.close();quests=null;}
        if(targetHud!=null){targetHud.close();targetHud=null;}
        if(taming!=null){taming.close();taming=null;}
        if(playerState!=null){playerState.close();playerState=null;}
        if(artifactEffects!=null){artifactEffects.close();artifactEffects=null;}
        if(artifacts!=null)artifacts.close();
        if(reconfiguration!=null)reconfiguration.close();
        if(enhancement!=null)enhancement.close();
        if(appraisal!=null)appraisal.close();
        if(equipment!=null){equipment.close();equipment=null;}
        if(climate!=null){climate.close();climate=null;}
        if(pets!=null){pets.close();pets=null;}
        if(temperature!=null){temperature.close();temperature=null;}
        if(ascension!=null){ascension.close();ascension=null;}
        if(partyIdentity!=null){partyIdentity.close();partyIdentity=null;}
        if(partyVitals!=null){partyVitals.close();partyVitals=null;}
        if(social!=null){social.close();social=null;}
        if(school!=null){school.close();school=null;}
        if(stats!=null){stats.close();stats=null;}
        if(mana!=null){mana.close();mana=null;}
        if(wallet!=null){wallet.close();wallet=null;}
        if (luckPermsHook != null) try { luckPermsHook.close(); } catch (Exception ignored) {}
        if(audit!=null){audit.close();audit=null;} // 다른 기능이 닫히며 남긴 줄까지 마저 쓴다
        getServer().getScheduler().cancelTasks(this);
        getServer().getMessenger().unregisterIncomingPluginChannel(this);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this);
        if (subscriptions != null) subscriptions.clear();
        immediateScheduled = false;
        viewers.clear(); dirty.clear();
    }
}
