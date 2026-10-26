package school.magiccodex.paper;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import com.mojang.brigadier.Command;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scheduler.BukkitTask;
import school.magiccodex.database.DatabaseSettings;
import school.magiccodex.protocol.SeasonProtocol;

/** Shared server season/temperature authority. Mutations are main-thread only.
 * 계절 진행 기준: SQLite(단독) 모드는 예전 그대로 clock-world의 월드 시간과 climate-state.yml.
 * MariaDB 모드는 학교·야생 두 서버가 codex_shared_state의 "season" 값(시작 계절·기준 시각·자동 여부·계절 길이)을 함께 보고,
 * 실제 시각으로 계산한다 (1틱=50ms, days-per-season 1일 = 24000틱 = 실제 20분). DB는 전용 IO 스레드에서만 읽고 쓰며
 * 30초마다 다시 읽는다. DB를 쓸 수 없으면 마지막으로 알던 상태로 계속 계산한다. */
public final class ClimateService implements Listener, PluginMessageListener, AutoCloseable {
    public enum Season {
        SPRING("봄"), SUMMER("여름"), AUTUMN("가을"), WINTER("겨울");
        public final String label;
        Season(String label){this.label=label;}
        public String id(){return name().toLowerCase(Locale.ROOT);}
        static Season parse(String text){for(var s:values())if(s.id().equalsIgnoreCase(text)||s.label.equals(text))return s;throw new IllegalArgumentException("봄·여름·가을·겨울 중 선택하세요.");}
    }
    private final MagicCodexBridge plugin;
    private final ManaService mana;
    private final TemperatureService temperature;
    private final NamespacedKey coldKey;
    private final Map<UUID,Float> temperatures=new ConcurrentHashMap<>();
    private final Map<UUID,Long> lastSample=new HashMap<>();
    private final ArrayDeque<UUID> pending=new ArrayDeque<>();
    private final Set<UUID> queued=new HashSet<>();
    private final Map<UUID,Subscriber> subscribers=new HashMap<>();
    private final BukkitTask task;
    private final ClimateExpansion expansion;
    private YamlConfiguration config;
    private SeasonClock clock;
    private volatile Season current;
    private volatile boolean enabled;
    private volatile Set<String> worlds;
    private volatile double firePower=1,waterPower=1;
    private int ticks;
    // ---- 공유 계절 (MariaDB 모드 전용). seasonStore는 IO 스레드 전용, 나머지는 메인 스레드 전용.
    private static final String SEASON_KEY="season";
    private static final int SEASON_SYNC_TICKS=600;
    private final boolean shared;
    private final DatabaseSettings seasonSettings;
    private final ExecutorService seasonIo;
    private ClimateStateStore seasonStore;
    private String sharedValue,pendingValue;
    private boolean seasonBusy,closed;
    private long lastSeasonLog;
    private static final class Subscriber {long expires,lastSent;int season=-1;}

    ClimateService(MagicCodexBridge plugin,ManaService mana,TemperatureService temperature)throws IOException{
        this.plugin=plugin;this.mana=mana;this.temperature=temperature;this.coldKey=new NamespacedKey(plugin,"climate_cold");
        if(!new File(plugin.getDataFolder(),"climate.yml").exists())plugin.saveResource("climate.yml",false);
        config=readConfig();enabled=config.getBoolean("enabled",true);worlds=Set.copyOf(config.getStringList("worlds"));
        seasonSettings=GenericDatabaseFile.load(plugin.getDataFolder().toPath());shared=seasonSettings.mariaDb();
        seasonIo=shared?Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"MagicCodex-climate-io");t.setDaemon(true);return t;}):null;
        long now=clockTime(),world=worldTime();var state=YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(),"climate-state.yml"));
        int start=Season.parse(state.getString("season",config.getString("initial-season","spring"))).ordinal();
        // 공유 모드: 이 서버 파일에 남아 있던 진행도(월드 틱)를 실제 시각 기준으로 옮겨 첫 값으로 쓴다. DB에 값이 이미 있으면 곧 그 값으로 바뀐다.
        long anchor=shared?now-Math.max(0,world-state.getLong("anchor",world)):state.getLong("anchor",now);
        clock=new SeasonClock(anchor,start,state.getBoolean("automatic",config.getBoolean("automatic",true)),config.getLong("days-per-season",7)*24000,now);
        if(clock.anchor>now)clock.anchor=now;
        updateClock();saveState();
        Bukkit.getServicesManager().register(ClimateService.class,this,plugin,ServicePriority.Normal);
        Bukkit.getPluginManager().registerEvents(this,plugin);
        var messenger=Bukkit.getMessenger();messenger.registerIncomingPluginChannel(plugin,SeasonProtocol.REQUEST,this);messenger.registerOutgoingPluginChannel(plugin,SeasonProtocol.RESPONSE);
        registerCommands();
        for(var p:Bukkit.getOnlinePlayers()){clearPenalty(p);enqueue(p);}
        expansion=Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")?new ClimateExpansion(this):null;
        if(expansion!=null)expansion.register();
        task=Bukkit.getScheduler().runTaskTimer(plugin,this::tick,5,5);
        if(realtimeMode())plugin.getLogger().info("계절: 실제 경과 시간 / 기준 "+realtimeClock(config).epoch()+" / "+realtimeClock(config).hours()+"시간마다 전환");
        else if(calendarMode())plugin.getLogger().info("계절: 실제 달력 / "+calendarZone()+" / "+current.label);
        else if(shared){plugin.getLogger().info("계절: MariaDB 공유 모드 (두 서버가 같은 계절, 실제 시간 기준 "+config.getLong("days-per-season",7)*20+"분마다 전환).");syncSeason();}
    }
    public Season season(){return current;}
    /** 381 계절 예보 (LifeSpells): real time until the next season, null when the season is fixed (automatic off). Main thread. */
    java.time.Duration untilNextSeason(){
        if(!clock.automatic)return null;
        var now=java.time.Instant.now();
        if(realtimeMode())return java.time.Duration.between(now,realtimeClock(config).next(now));
        if(calendarMode()){
            var zone=calendarZone();var today=java.time.LocalDate.now(zone);int m=today.getMonthValue();
            var next=m>=12?java.time.LocalDate.of(today.getYear()+1,3,1):java.time.LocalDate.of(today.getYear(),m<3?3:m<6?6:m<9?9:12,1);
            return java.time.Duration.between(now,next.atStartOfDay(zone).toInstant());
        }
        long left=clock.length-Math.max(0,clock.last-clock.anchor)%clock.length;
        return java.time.Duration.ofMillis(left*SeasonClock.MILLIS_PER_TICK);
    }
    /** The season follows this server's world clock (sleeping skips ahead), so {@link #untilNextSeason} is an estimate. */
    boolean seasonByGameTime(){return !externalClock()&&!shared;}
    public boolean enabledIn(World world){return enabled&&world!=null&&worlds.contains(world.getName());}
    public OptionalDouble temperature(UUID id){var v=temperatures.get(id);return v==null?OptionalDouble.empty():OptionalDouble.of(v);}
    public double powerMultiplier(String element){return switch(element.toLowerCase(Locale.ROOT)){case "fire","flame","화염"->firePower;case "water","물"->waterPower;default->1;};}
    /** 시전자 기준 원소 위력 배율: 동행 펫 climate_immune 플레이어는 1 (좋은 것·나쁜 것 모두 안 받음). 계절 마법 쪽은 이것을 부르면 된다. */
    public double powerMultiplier(org.bukkit.entity.Entity caster,String element){return caster instanceof Player p&&immune.test(p.getUniqueId())?1:powerMultiplier(element);}
    /** 동행 펫 climate_immune (PetCompanions). 메인 스레드. */
    private java.util.function.Predicate<UUID> immune=id->false;
    void immune(java.util.function.Predicate<UUID> test){immune=test==null?id->false:test;}
    /** 다음 표본 때 이 플레이어의 계절·온도 배율을 다시 계산 (펫을 꺼내거나 넣었을 때). */
    void resample(Player p){enqueue(p);}
    private long worldTime(){var w=Bukkit.getWorld(config.getString("clock-world","world"));return w==null?0:w.getFullTime();}
    /** 계절 시계의 현재 값: 단독 모드는 월드 시간(틱), 공유 모드는 실제 시각을 틱으로 환산한 값. */
    private boolean realtimeMode(){return config.getString("season-clock","calendar").equalsIgnoreCase("realtime");}
    private static RealtimeSeason realtimeClock(YamlConfiguration c){
        return RealtimeSeason.parse(c.getString("real-time-season.epoch",""),c.getString("real-time-season.timezone","Asia/Seoul"),
            Season.parse(c.getString("real-time-season.initial-season","spring")).ordinal(),c.getLong("real-time-season.hours-per-season",72));
    }
    private boolean externalClock(){return calendarMode()||realtimeMode();}
    private boolean calendarMode(){return config.getString("season-clock","calendar").equalsIgnoreCase("calendar");}
    private java.time.ZoneId calendarZone(){return java.time.ZoneId.of(config.getString("calendar-timezone","Asia/Seoul"));}
    private long clockTime(){return shared?System.currentTimeMillis()/SeasonClock.MILLIS_PER_TICK:worldTime();}
    private void updateClock(){
        // 공유 모드는 기준 시각이 DB 값이므로 시계가 뒤로 가도 로컬에서 보정(anchor 이동)하지 않는다.
        if(shared)clock.last=clockTime();else clock.observe(clockTime());
        current=Season.values()[clock.automatic&&realtimeMode()?realtimeClock(config).at(java.time.Instant.now()):calendarMode()&&clock.automatic?CalendarSeason.at(java.time.Instant.now(),calendarZone()):clock.season()];
        firePower=enabled?setting("seasons."+current.id()+".fire-power",1):1;
        waterPower=enabled?setting("seasons."+current.id()+".water-power",1):1;
    }
    private double setting(String key,double fallback){return config.getDouble(key,fallback);}
    /** 관리자 변경을 DB에 올린다 (비동기). 쓰기가 끝날 때까지 로컬 상태를 DB 값으로 덮어쓰지 않는다. */
    private void publishSeason(){if(externalClock()||!shared)return;pendingValue=SeasonClock.Shared.of(clock).encode();syncSeason();}
    /** DB와 맞추기: 쓸 것이 있으면 쓰고, 없으면(값이 아직 없을 때만) 현재 상태를 넣은 뒤 읽어서 반영한다. 메인 스레드는 기다리지 않는다. */
    private void syncSeason(){
        if(externalClock()||!shared||seasonBusy||closed)return;seasonBusy=true;
        final String write=pendingValue,seed=SeasonClock.Shared.of(clock).encode();
        try{seasonIo.execute(()->{
            String value=null,failure=null;
            try{
                if(seasonStore==null)seasonStore=new ClimateStateStore(seasonSettings);
                if(write!=null)seasonStore.write(SEASON_KEY,write);else seasonStore.seed(SEASON_KEY,seed);
                value=seasonStore.read(SEASON_KEY);
            }catch(Exception e){failure=e.getClass().getSimpleName()+": "+e.getMessage();}
            final String read=value,error=failure;
            try{Bukkit.getScheduler().runTask(plugin,()->{
                seasonBusy=false;if(closed)return;
                if(error!=null){
                    // 마지막으로 알던 상태로 계속 계산한다. 5분에 한 줄만 남긴다.
                    long now=System.currentTimeMillis();if(now-lastSeasonLog>=300_000){lastSeasonLog=now;plugin.getLogger().warning("계절 공유 상태 동기화 실패 (마지막 값으로 계속 진행, 30초마다 재시도): "+error);}
                    return;
                }
                if(lastSeasonLog!=0){lastSeasonLog=0;plugin.getLogger().info("계절 공유 상태 동기화가 복구되었습니다.");}
                if(write!=null&&write.equals(pendingValue))pendingValue=null;
                if(pendingValue!=null){syncSeason();return;} // 쓰는 사이 관리자 변경이 또 있었다
                adoptSeason(read);
            });}catch(RuntimeException ignored){} // 플러그인 종료 중
        });}catch(RejectedExecutionException e){seasonBusy=false;}
    }
    /** DB에서 읽은 공유 상태를 반영 (다른 서버의 관리자 변경 포함). */
    private void adoptSeason(String value){
        if(externalClock()||value==null||value.equals(sharedValue))return;
        SeasonClock.Shared next;
        try{next=SeasonClock.Shared.decode(value);}catch(IllegalArgumentException e){sharedValue=value;plugin.getLogger().warning("계절 공유 상태를 해석하지 못했습니다 (로컬 상태 유지): "+value);return;}
        sharedValue=value;
        if(value.equals(SeasonClock.Shared.of(clock).encode()))return;
        if(next.lengthTicks()!=config.getLong("days-per-season",7)*24000)plugin.getLogger().warning("계절 길이는 공유 상태("+next.lengthTicks()/24000+"일)를 따릅니다. 이 서버 climate.yml의 days-per-season("+config.getLong("days-per-season",7)+")과 다릅니다.");
        Season before=current;clock=next.clock(System.currentTimeMillis());updateClock();
        if(before!=current){for(var p:Bukkit.getOnlinePlayers())enqueue(p);sendSeasons();plugin.getLogger().info("계절 공유 상태 반영: "+current.label);}
    }
    private YamlConfiguration readConfig()throws IOException{
        var next=new YamlConfiguration();try{next.load(new File(plugin.getDataFolder(),"climate.yml"));}catch(Exception e){throw new IOException("climate.yml: "+e.getMessage(),e);}
        try {
            String mode=next.getString("season-clock","calendar");
            if(!Set.of("calendar","elapsed","realtime").contains(mode.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("Invalid season-clock");
            if(mode.equalsIgnoreCase("realtime")) realtimeClock(next);
            java.time.ZoneId.of(next.getString("calendar-timezone","Asia/Seoul"));
        } catch(RuntimeException e){throw new IOException("Season clock: "+e.getMessage(),e);}
        long days=next.getLong("days-per-season",7);if(days<1||days>365)throw new IOException("days-per-season must be 1..365");
        for(String key:next.getKeys(true)){Object value=next.get(key);if(value instanceof Number n&&(!Double.isFinite(n.doubleValue())||Math.abs(n.doubleValue())>10000))throw new IOException("Invalid number: "+key);}
        for(var season:Season.values()){
            String prefix="seasons."+season.id()+".";
            double min=next.getDouble(prefix+"minimum",0),max=next.getDouble(prefix+"maximum",30);
            if(min>max||min< -100||max>100)throw new IOException("Invalid temperatures: "+season.id());
            for(String key:List.of("mana-regeneration","fire-power","water-power")){double v=next.getDouble(prefix+key,1);if(v<0||v>3)throw new IOException("Invalid multiplier: "+prefix+key);}
        }
        for(String key:List.of("cold-speed-multiplier","severe-cold-speed-multiplier","cold-mana-multiplier","severe-cold-mana-multiplier")){double v=next.getDouble("temperature."+key,1);if(v<0||v>1)throw new IOException("Invalid multiplier: "+key);}
        return next;
    }
    private void tick(){
        ticks+=5;
        if(ticks%20==0){Season before=current;updateClock();if(before!=current){for(var p:Bukkit.getOnlinePlayers())enqueue(p);saveState();}sendSeasons();}
        if(shared&&ticks%SEASON_SYNC_TICKS==0)syncSeason();
        int period=Math.clamp(config.getInt("performance.player-refresh-ticks",100),20,1200);
        if(ticks%period<5)for(var p:Bukkit.getOnlinePlayers())enqueue(p);
        for(int i=0;i<Math.clamp(config.getInt("performance.players-per-pass",8),1,64)&&!pending.isEmpty();i++){
            UUID id=pending.removeFirst();queued.remove(id);var p=Bukkit.getPlayer(id);if(p!=null)sample(p);
        }
    }
    private void enqueue(Player player){if(queued.add(player.getUniqueId()))pending.addLast(player.getUniqueId());}
    private void sample(Player p){
        UUID id=p.getUniqueId();
        if(!enabledIn(p.getWorld())){temperature.clearComputed(p);temperatures.remove(id);DiscoveryLink.state(id,"state.temperature",-100);clearPenalty(p);return;}
        var block=p.getLocation().getBlock();String biome=block.getBiome().getKey().toString();
        var offsets=config.getConfigurationSection("temperature.biome-offsets");Object explicit=offsets==null?null:offsets.getValues(false).get(biome);
        double offset=explicit instanceof Number n?n.doubleValue():Math.clamp((block.getTemperature()-0.8)*setting("temperature.biome-offset-scale",12),-25,25);
        String prefix="seasons."+current.id()+".";
        float computed=ClimateMath.temperature(setting(prefix+"minimum",8),setting(prefix+"maximum",24),p.getWorld().getTime(),offset,p.getWorld().hasStorm(),setting("temperature.rain-cooling",2));
        temperature.computed(p,computed);float celsius=temperature.getTemperature(id);temperatures.put(id,celsius);DiscoveryLink.state(id,"state.temperature",celsius);
        boolean survival=!p.isDead()&&(p.getGameMode()==GameMode.SURVIVAL||p.getGameMode()==GameMode.ADVENTURE);
        boolean immuneNow=immune.test(id); // 동행 펫 climate_immune: 계절·온도 배율 전부 1
        double speed=1,regen=immuneNow?1:setting(prefix+"mana-regeneration",1);
        if(!immuneNow&&survival&&celsius<setting("temperature.cold-below",0)){
            boolean severe=celsius<setting("temperature.severe-cold-below",-10);String band=severe?"severe-cold":"cold";
            speed=setting("temperature."+band+"-speed-multiplier",1);regen*=setting("temperature."+band+"-mana-multiplier",1);
        }
        var attribute=p.getAttribute(Attribute.MOVEMENT_SPEED);
        if(attribute!=null){var old=attribute.getModifier(coldKey);double amount=speed-1;
            if(old!=null&&Double.compare(old.getAmount(),amount)!=0){attribute.removeModifier(coldKey);old=null;}
            if(amount!=0&&old==null)attribute.addTransientModifier(new AttributeModifier(coldKey,amount,AttributeModifier.Operation.ADD_SCALAR));
        }
        if(mana.snapshot(id).isPresent())mana.setRegenerationMultiplier(id,"climate:regeneration",regen);
        Long previous=lastSample.put(id,(long)ticks);double seconds=previous==null?0:Math.clamp((ticks-previous)/20.0,0,10);
        if(!immuneNow&&survival&&celsius>=setting("temperature.hot-from",35)){
            String band=celsius>=setting("temperature.severe-hot-from",40)?"severe-hot":"hot";
            double extra=Math.max(0,setting("temperature."+band+"-exhaustion-per-5-seconds",0))*seconds/5;
            p.setExhaustion((float)Math.min(40,p.getExhaustion()+extra));
        }
    }
    private void clearPenalty(Player p){
        var attribute=p.getAttribute(Attribute.MOVEMENT_SPEED);if(attribute!=null)attribute.removeModifier(coldKey);
        if(mana.snapshot(p.getUniqueId()).isPresent())mana.setRegenerationMultiplier(p.getUniqueId(),"climate:regeneration",1);
        lastSample.remove(p.getUniqueId());
    }
    @EventHandler public void join(PlayerJoinEvent event){clearPenalty(event.getPlayer());enqueue(event.getPlayer());}
    @EventHandler public void world(PlayerChangedWorldEvent event){clearPenalty(event.getPlayer());enqueue(event.getPlayer());}
    @EventHandler public void quit(PlayerQuitEvent event){var p=event.getPlayer();clearPenalty(p);temperatures.remove(p.getUniqueId());subscribers.remove(p.getUniqueId());}
    @Override public void onPluginMessageReceived(String channel,Player player,byte[] bytes){
        if(channel.equals(SeasonProtocol.REQUEST)&&SeasonProtocol.validRequest(bytes)){var s=subscribers.computeIfAbsent(player.getUniqueId(),k->new Subscriber());s.expires=System.currentTimeMillis()+60000;sendSeason(player,s,System.currentTimeMillis());}
    }
    @EventHandler public void channelRegistered(PlayerRegisterChannelEvent event){
        if(SeasonProtocol.RESPONSE.equals(event.getChannel())){
            // May fire off the main thread; subscribers is main-thread only.
            var player=event.getPlayer();
            Bukkit.getScheduler().runTask(plugin,()->{var s=subscribers.computeIfAbsent(player.getUniqueId(),k->new Subscriber());s.expires=System.currentTimeMillis()+60000;if(player.isOnline())sendSeason(player,s,System.currentTimeMillis());});
        }
    }
    private void sendSeasons(){
        long now=System.currentTimeMillis();var iterator=subscribers.entrySet().iterator();
        while(iterator.hasNext()){var entry=iterator.next();var s=entry.getValue();var p=Bukkit.getPlayer(entry.getKey());
            if(p==null||now>s.expires){iterator.remove();continue;}
            sendSeason(p,s,now);
        }
    }
    private void sendSeason(Player p,Subscriber s,long now){
        if((s.season!=current.ordinal()||now-s.lastSent>=15000)&&p.getListeningPluginChannels().contains(SeasonProtocol.RESPONSE)){
            p.sendPluginMessage(plugin,SeasonProtocol.RESPONSE,SeasonProtocol.encode(current.ordinal()));s.lastSent=now;s.season=current.ordinal();
        }
    }
    private void saveState(){
        // 공유 모드의 시계는 실제 시각 기준이라 월드 틱 기준인 로컬 파일에 쓰지 않는다 (파일은 단독 모드로 돌아갈 때를 위해 그대로 둔다).
        if(shared&&!externalClock())return;
        var state=new YamlConfiguration();state.set("season",Season.values()[clock.start].id());state.set("anchor",clock.anchor);state.set("automatic",clock.automatic);
        try{state.save(new File(plugin.getDataFolder(),"climate-state.yml"));}catch(IOException e){plugin.getLogger().severe("계절 저장 실패: "+e.getMessage());}
    }
    // ------------------------------------------------------------------ 명령 (2026-10-20 명령어 정리)
    /** 계절 설정 파일을 다시 읽는다. 실패하면 예외(기존 설정 유지). */
    private void reloadClimate()throws IOException{
        var next=readConfig();int season=current.ordinal();boolean auto=clock.automatic;config=next;enabled=config.getBoolean("enabled",true);worlds=Set.copyOf(config.getStringList("worlds"));
        long length=config.getLong("days-per-season",7)*24000;
        // 공유 모드: 계절 길이가 그대로면 두 서버가 함께 쓰는 진행도(기준 시각)를 건드리지 않는다. 길이가 바뀌었을 때만 새로 시작해 DB에 올린다.
        if(!shared||clock.length!=length){clock=new SeasonClock(clockTime(),season,auto,length,clockTime());publishSeason();}
        updateClock();saveState();refreshAll();
    }
    private void refreshAll(){for(var p:Bukkit.getOnlinePlayers())enqueue(p);sendSeasons();}
    private List<String> status(){
        var out=new ArrayList<String>();out.add("── 계절 ──");out.add(" 지금 계절: "+current.label+(enabled?"":" (계절 효과 꺼짐)"));
        if(realtimeMode()){var rc=realtimeClock(config);out.add(" 방식: 실제 시간 "+rc.hours()+"시간마다 · "+(clock.automatic?"자동":"관리자 고정"));if(clock.automatic)out.add(" 다음 바뀌는 때: "+rc.next(java.time.Instant.now()).atZone(rc.zone()).toLocalDateTime().withNano(0).toString().replace('T',' '));}
        else if(calendarMode())out.add(" 방식: 실제 달력 ("+java.time.LocalDate.now(calendarZone())+") · "+(clock.automatic?"자동":"관리자 고정"));
        else out.add(" 방식: 게임 날짜 "+clock.day()+"/"+(clock.length/24000)+"일째 · "+(clock.automatic?"자동":"관리자 고정")+(shared?" · 두 서버 공유":""));
        return out;
    }
    private void registerCommands(){
        if(!CodexCommands.ready(plugin,"/월드관리 계절"))return;
        ChacaApi.reload(plugin,"계절","계절·기온 설정(climate.yml)",this::reloadClimate);
        var change=Commands.literal("변경");
        for(var season:Season.values())change.then(Commands.literal(season.label).executes(ctx->{
            clock.set(season.ordinal(),clockTime(),externalClock()?false:clock.automatic);updateClock();saveState();publishSeason();refreshAll();
            ChacaApi.ok(CodexCommands.sender(ctx),"계절을 "+current.label+"(으)로 바꿨어요"+(externalClock()?" (자동 전환은 꺼짐)":""));return Command.SINGLE_SUCCESS;
        }));
        var auto=Commands.literal("자동");
        for(String v:List.of("켜기","끄기"))auto.then(Commands.literal(v).executes(ctx->{
            clock.set(current.ordinal(),clockTime(),v.equals("켜기"));updateClock();saveState();publishSeason();refreshAll();
            ChacaApi.ok(CodexCommands.sender(ctx),"계절 자동 전환을 "+(v.equals("켜기")?"켰어요":"껐어요")+" · 지금 "+current.label);return Command.SINGLE_SUCCESS;
        }));
        ChacaApi.branch(plugin,ChacaApi.월드관리,Commands.literal("계절")
            .then(Commands.literal("보기").executes(ctx->{ChacaApi.lines(CodexCommands.sender(ctx),status());return Command.SINGLE_SUCCESS;}))
            .then(change).then(auto),"계절 보기·변경·자동 전환","계절 보기");
    }
    @Override public void close(){
        closed=true;
        if(seasonIo!=null){
            // 아직 올리지 못한 관리자 변경이 있으면 마지막으로 한 번 쓰고 닫는다 (최대 3초).
            final String write=pendingValue;
            try{seasonIo.execute(()->{try{if(seasonStore!=null){if(write!=null)seasonStore.write(SEASON_KEY,write);seasonStore.close();}}catch(Exception ignored){}});}catch(RejectedExecutionException ignored){}
            seasonIo.shutdown();
            try{seasonIo.awaitTermination(3,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
        }
        task.cancel();saveState();for(var p:Bukkit.getOnlinePlayers()){clearPenalty(p);temperature.clearComputed(p);}if(expansion!=null)expansion.unregister();Bukkit.getServicesManager().unregister(ClimateService.class,this);temperatures.clear();subscribers.clear();pending.clear();queued.clear();}
}
