package school.magiccodex.paper;

import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.bukkit.*;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;
import school.magiccodex.protocol.SchoolProtocol;
import school.magiccodex.protocol.SchoolProtocol.*;
import school.magiccodex.database.DatabaseSettings;

final class SchoolBridge implements PluginMessageListener,Listener,org.bukkit.command.CommandExecutor,org.bukkit.command.TabCompleter,AutoCloseable {
    private final MagicCodexBridge plugin;
    private final StatsService stats;
    private final SchoolStore store;
    private final ExecutorService io=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"MagicCodex-school-io");t.setDaemon(true);return t;});
    private volatile SchoolStore.Snapshot data;
    SchoolStore.Snapshot data(){return data;}
    private record Spell(String name,String permission,long points){}
    private Map<String,Spell> spells=Map.of();
    private final Map<UUID,Long> limits=new HashMap<>(),viewers=new HashMap<>();
    /** 그레고르 대화(ch1-17)에서 고른 희망 기숙사 0~3. 배정되면 지움 (서버 재시작 때는 사라짐 → 가산점 없이 분류). */
    private final Map<UUID,Integer> sortingWishes=new java.util.concurrent.ConcurrentHashMap<>();
    private long revision=1;private int pending;private boolean closing;
    private boolean shared,refreshing;
    private SchoolExpansion expansion;
    SchoolBridge(MagicCodexBridge plugin,StatsService stats)throws Exception{
        this.plugin=plugin;this.stats=stats;
        if(!new File(plugin.getDataFolder(),"donation-spells.yml").exists())plugin.saveResource("donation-spells.yml",false);
        loadCatalog();
        try{var settings=GenericDatabaseFile.load(plugin.getDataFolder().toPath());shared=settings.mariaDb();store=io.submit(()->new SchoolStore(plugin.getDataFolder().toPath().resolve("school.db"),settings)).get(10,TimeUnit.SECONDS);var active=shared?activePlayers():null;data=io.submit(()->store.snapshot(active)).get(10,TimeUnit.SECONDS);}catch(Exception e){io.shutdownNow();throw e;}
        Bukkit.getMessenger().registerIncomingPluginChannel(plugin,SchoolProtocol.REQUEST,this);Bukkit.getMessenger().registerOutgoingPluginChannel(plugin,SchoolProtocol.RESPONSE);Bukkit.getPluginManager().registerEvents(this,plugin);
        registerCommands();
        // Preserve the independently reviewed sorting command; its dialogue content is deferred.
        Objects.requireNonNull(plugin.getCommand("기숙사분류")).setExecutor(this);
        plugin.getCommand("기숙사분류").setTabCompleter(this);
        if(Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")){expansion=new SchoolExpansion(this);expansion.register();}
        Bukkit.getScheduler().runTaskTimer(plugin,()->{long n=System.currentTimeMillis();viewers.values().removeIf(v->v<n);limits.values().removeIf(v->v<n);},200,200);
        if(shared)Bukkit.getScheduler().runTaskTimer(plugin,this::refreshShared,40,40);
    }
    private void refreshShared(){
        if(closing||refreshing)return;refreshing=true;
        var active=activePlayers();
        io.execute(()->{try{var snapshot=store.snapshot(active);main(()->{refreshing=false;if(!snapshot.equals(data)){replaceData(snapshot);push();}});}
            catch(Exception error){plugin.getLogger().warning("공용 기숙사 기록 갱신 실패: "+error.getMessage());main(()->refreshing=false);}});
    }
    private Set<UUID> activePlayers(){var ids=new HashSet<UUID>();for(Player p:Bukkit.getOnlinePlayers())ids.add(p.getUniqueId());return Set.copyOf(ids);}
    /** jar 안 기본 목록에서 빠진 마법만 서버 파일에 덧붙인다 (예전 300종 파일 대응). 이미 있는 항목의 이름·점수는 그대로. */
    private void mergeBundledCatalog(File file){
        try(var in=plugin.getResource("donation-spells.yml")){
            if(in==null)return;
            var bundled=YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(in,java.nio.charset.StandardCharsets.UTF_8)).getConfigurationSection("spells");
            var y=new YamlConfiguration();y.load(file);var root=y.getConfigurationSection("spells");
            if(bundled==null||root==null)return;
            var added=new ArrayList<String>();
            for(String id:bundled.getKeys(false)){var s=bundled.getConfigurationSection(id);if(root.contains(id)||s==null)continue;root.createSection(id,s.getValues(false));added.add(id);}
            if(added.isEmpty())return;
            y.save(file);plugin.getLogger().info("donation-spells.yml: 기본 목록에서 빠진 기증 마법 "+added.size()+"개를 덧붙였습니다 "+added);
        }catch(Exception e){plugin.getLogger().warning("donation-spells.yml 병합 실패, 기존 파일 그대로 사용: "+e);}
    }
    private void loadCatalog()throws Exception{
        File file=new File(plugin.getDataFolder(),"donation-spells.yml");mergeBundledCatalog(file);
        var y=new YamlConfiguration();y.load(file);var root=y.getConfigurationSection("spells");if(root==null||root.getKeys(false).size()>4096)throw new IllegalArgumentException("Donation catalog size");var next=new HashMap<String,Spell>();
        for(String id:root.getKeys(false)){var s=root.getConfigurationSection(id);if(s==null)throw new IllegalArgumentException(id);String name=s.getString("name",id),permission=s.getString("permission","");long points=s.getLong("points",1);
            if(!id.matches("[a-z0-9_-]{1,64}")||name.length()>100||name.isBlank()||!permission.matches("[a-z0-9_.-]{1,100}")||points<0||points>1000000)throw new IllegalArgumentException("Invalid donation: "+id);next.put(id,new Spell(name,permission,points));}
        spells=Map.copyOf(next);
    }
    int house(Player p){
        Integer saved=data.houses().get(p.getUniqueId());if(saved!=null)return saved;
        return outsideHouse(p);
    }
    /** DB 밖의 소속 (스탯 기숙사 또는 LuckPerms magiccodex.house.*). 없으면 -1. */
    private int outsideHouse(Player p){
        String dorm=stats.get(p.getUniqueId()).dormitory();int i=dorm==null?-1:SchoolProtocol.HOUSES.indexOf(dorm);if(i>=0)return i;
        for(i=0;i<4;i++)if(p.hasPermission("magiccodex.house."+HOUSE_IDS.get(i)))return i;
        return -1;
    }
    void questReward(String token,int house,long points,Runnable done,Consumer<String> fail){work(()->store.questReward(token,house,points),ok->done.run(),fail);}
    String dorm(Player p){int h=house(p);return h<0?"":SchoolProtocol.HOUSES.get(h);}
    @Override public void onPluginMessageReceived(String channel,Player p,byte[] bytes){
        if(!channel.equals(SchoolProtocol.REQUEST))return;Request r;try{r=SchoolProtocol.request(bytes);}catch(IllegalArgumentException e){return;}
        long now=System.currentTimeMillis();if(limits.getOrDefault(p.getUniqueId(),0L)>now)return;limits.put(p.getUniqueId(),now+100);
        if(r.action()==SchoolProtocol.IDENTITY){reply(p,r,"");return;}
        if(!p.hasPermission("magiccodex.school")){reply(p,r,"학교 기록을 볼 권한이 없습니다.");return;}
        viewers.put(p.getUniqueId(),now+45000);
        if(r.action()!=SchoolProtocol.DONATE){reply(p,r,"");return;}
        if(!p.hasPermission("magiccodex.donate")){reply(p,r,"기증할 권한이 없습니다.");return;}
        if(data.blocked().contains(r.spell())){reply(p,r,"관리자가 기증을 금지한 마법입니다.");return;}
        // Duplicate status takes precedence so every later donor gets the same clear response.
        if(data.records().stream().anyMatch(e->e.spell().equals(r.spell()))){reply(p,r,"누군가 이미 기증한 마법입니다.");return;}
        Spell spell=spells.get(r.spell());if(spell==null||!p.hasPermission(spell.permission())){reply(p,r,"배운 마법만 기증할 수 있습니다.");return;}
        int h=house(p);if(h<0){reply(p,r,"소속 기숙사가 지정되지 않았습니다.");return;}
        if(p.isDead()||p.getGameMode()==GameMode.SPECTATOR){reply(p,r,"지금은 기증할 수 없습니다.");return;}
        var record=new Donation(r.spell(),spell.name(),p.getUniqueId(),plugin.names().name(p),h,now);
        // 기증 기록 INSERT와 점수는 SchoolStore.donate 한 트랜잭션. 공지는 커밋이 끝난 뒤 메인 스레드에서만.
        work(()->store.donate(record,spell.points()),ok->{if(ok){announce(record);AuditLog.log(p,AuditLog.마법,"마법 기증 · "+spell.name()+" · +"+spell.points()+"점","마법",r.spell(),"이름",spell.name(),"점수",spell.points(),"기숙사",h);}reply(p,r,ok?"학교에 기증했습니다. +"+spell.points()+"점":"누군가 이미 기증한 마법입니다.");},error->reply(p,r,error));
    }
    // ------------------------------------------------------------------ 첫 기증 전체 공지 (7-5 #3)
    private static final long ANNOUNCE_WINDOW=120_000L;
    private final Set<String> announced=new LinkedHashSet<>();
    /** 새 스냅샷으로 바꾸고, 이전에 없던 최근 기증 기록을 알린다. 공유(MariaDB) 모드에서는 다른 서버의 기증도 기존 2초 갱신으로 들어와 같은 카드가 뜬다. */
    private void replaceData(SchoolStore.Snapshot next){
        var before=data;data=next;revision++;
        if(before==null)return;
        var old=new HashSet<String>();for(var e:before.records())old.add(e.spell()+"@"+e.time());
        long cutoff=System.currentTimeMillis()-ANNOUNCE_WINDOW;
        for(var e:next.records())if(e.time()>=cutoff&&!old.contains(e.spell()+"@"+e.time()))announce(e);
    }
    /** 접속자 전원에게 알림 카드. 같은 기록(마법+시각)은 한 번만. */
    private void announce(Donation e){
        if(!announced.add(e.spell()+"@"+e.time()))return;
        if(announced.size()>512){var it=announced.iterator();it.next();it.remove();}
        if(e.house()<0||e.house()>=SchoolProtocol.HOUSES.size())return;
        Spell spell=spells.get(e.spell());
        String who=plugin.names().name(Bukkit.getOfflinePlayer(e.donor()),e.nickname());
        String title=who+"님이 「"+e.spellName()+"」"+objectParticle(e.spellName())+" 학교에 처음 기증했습니다! ("+SchoolProtocol.HOUSES.get(e.house())+(spell==null?"":" +"+spell.points())+")";
        for(Player online:Bukkit.getOnlinePlayers())CodexCommands.notify(online,"system","school-donation-"+e.spell(),title,0);
    }
    /** 받침이 있으면 "을", 없으면 "를". 한글로 끝나지 않으면 "을(를)". */
    static String objectParticle(String word){
        if(word==null||word.isEmpty())return "을(를)";
        char c=word.charAt(word.length()-1);
        if(c<0xAC00||c>0xD7A3)return "을(를)";
        return (c-0xAC00)%28==0?"를":"을";
    }
    private void reply(Player p,Request r,String message){
        if(!p.isOnline()||!p.getListeningPluginChannels().contains(SchoolProtocol.RESPONSE))return;
        var snapshot=data;List<Donation> records;
        if(r.action()==SchoolProtocol.LIST){int from=Math.min(snapshot.records().size(),r.page()*SchoolProtocol.PAGE_SIZE);records=snapshot.records().subList(from,Math.min(from+SchoolProtocol.PAGE_SIZE,snapshot.records().size()));}
        else if(r.action()==SchoolProtocol.IDENTITY)records=List.of();
        else records=snapshot.records().stream().filter(e->e.spell().equals(r.spell())).limit(1).toList();
        records=records.stream().map(e->new Donation(e.spell(),e.spellName(),e.donor(),plugin.names().name(Bukkit.getOfflinePlayer(e.donor()),e.nickname()),e.house(),e.time())).toList();
        var response=new Response(r.action(),r.sequence(),revision,r.page(),snapshot.records().size(),r.spell(),message,plugin.names().name(p),dorm(p),snapshot.scores(),records);
        p.sendPluginMessage(plugin,SchoolProtocol.RESPONSE,SchoolProtocol.encode(response));
    }
    private interface Job<T>{T run()throws Exception;}
    private <T> void work(Job<T> task,Consumer<T> done,Consumer<String> fail){
        if(closing||pending>=64){fail.accept("다른 요청을 처리 중입니다. 잠시 후 다시 시도해 주세요.");return;}pending++;
        var active=shared?activePlayers():null;
        io.execute(()->{try{T value=task.run();SchoolStore.Snapshot refreshed=null;
                try{refreshed=store.snapshot(active);}catch(Exception refreshError){plugin.getLogger().warning("학교 기록 처리 완료, 화면 갱신 실패: "+refreshError);}
                var snapshot=refreshed;main(()->{pending--;if(snapshot!=null)replaceData(snapshot);done.accept(value);push();});}
            catch(Exception e){plugin.getLogger().warning("학교 기록 저장 실패: "+e);main(()->{pending--;fail.accept(e instanceof SchoolStore.DonationBlocked?"관리자가 기증을 금지한 마법입니다.":"저장하지 못했습니다. 점수 범위 또는 서버 로그를 확인해 주세요.");});}});
    }
    private void main(Runnable r){if(!closing&&plugin.isEnabled())Bukkit.getScheduler().runTask(plugin,()->{if(!closing)r.run();});}
    private void push(){for(UUID id:List.copyOf(viewers.keySet())){Player p=Bukkit.getPlayer(id);if(p!=null)reply(p,new Request(SchoolProtocol.IDENTITY,0,0,""),"");}}
    void adminHouse(Player player,int house,Consumer<String> done){
        if(house<0||house>=SchoolProtocol.HOUSES.size()){done.accept("등록된 기숙사를 선택해 주세요.");return;}
        UUID id=player.getUniqueId();
        work(()->{store.presetHouse(id,house);return true;},ok->{DiscoveryLink.signal(id,"dormitory.assigned",1);AuditLog.log(id,AuditLog.기숙사,"기숙사 지정 · "+SchoolProtocol.HOUSES.get(house),"기숙사",SchoolProtocol.HOUSES.get(house),"경로","관리자 지정");reply(player,new Request(SchoolProtocol.IDENTITY,0,0,""),"");done.accept(null);},done);
    }
    void adminScore(int house,String action,long amount,Consumer<String> done){
        if(house<0||house>=SchoolProtocol.HOUSES.size())throw new IllegalArgumentException("등록된 기숙사를 선택해 주세요.");
        long delta=AdminCommandRules.scoreValue(action,amount);
        work(()->{if(!action.equals("조회"))store.change(house,delta,action.equals("설정"));return true;},ok->done.accept(SchoolProtocol.HOUSES.get(house)+" "+data.scores().get(house)+"점"),done);
    }
    /** 관리 명령 규칙: OP 또는 콘솔만. */
    private boolean sortingAdmin(CommandSender sender){return sender instanceof ConsoleCommandSender||sender.isOp();}
    /** 유저가 고칠 수 있는 입력 오류 (이 문구만 보여 준다). */
    private static final class SortingInput extends IllegalArgumentException{SortingInput(String m){super(m);}}
    /** 결과 안내: 허브가 있으면 알림(실패는 삐빅), 없으면 채팅. 기술적 원인은 콘솔에만. */
    private void sortOk(CommandSender to,String message){if(ChacaApi.ready())ChacaApi.ok(to,message);else to.sendMessage(message);}
    private void sortFail(CommandSender to,String message,Throwable cause){
        if(ChacaApi.ready())ChacaApi.fail(to,message,cause);
        else{to.sendMessage(message);if(cause!=null)plugin.getLogger().log(java.util.logging.Level.WARNING,"[기숙사분류] "+message,cause);}
    }
    /** 희망 기숙사 가산점 (config house-sorting.wish-bonus, 기본 1, 0~6). 잘못된 값이면 1. */
    private int sortingWishBonus(){
        Object raw=plugin.getConfig().get("house-sorting.wish-bonus",1);
        return raw instanceof Integer v&&v>=0&&v<=6?v:1;
    }
    private int sortingTolerance(){
        Object raw=plugin.getConfig().get("house-sorting.population-tolerance",2);
        if(!(raw instanceof Integer value))throw new SortingInput("config 의 house-sorting.population-tolerance 는 정수여야 해요");
        HouseSorting.tolerance(value);return value;
    }
    private boolean sortingCommand(CommandSender sender,String[] a){
        if(!sortingAdmin(sender)){sortFail(sender,"관리자(OP)와 콘솔만 사용할 수 있어요",null);return true;}
        try{
            if(a.length==2&&a[0].equals("허용치")){
                int value;try{value=Integer.parseInt(a[1]);HouseSorting.tolerance(value);}catch(IllegalArgumentException bad){throw new SortingInput("허용치는 0~1000000 사이의 정수예요");}
                var config=plugin.getConfig();
                Object before=config.get("house-sorting.population-tolerance");config.set("house-sorting.population-tolerance",value);
                try{config.save(new File(plugin.getDataFolder(),"config.yml"));}catch(Exception error){config.set("house-sorting.population-tolerance",before);sortFail(sender,"허용치를 저장하지 못했어요",error);return true;}
                sortOk(sender,"배정 전 최소 인원 + "+value+"명까지 허용해요 (배정 후 차이는 허용치+1일 수 있어요)");return true;
            }
            if(a.length==1&&a[0].equals("현황")){
                work(store::houseCounts,counts->{var rows=new ArrayList<String>();for(int h=0;h<4;h++)rows.add(" "+SchoolProtocol.HOUSES.get(h)+": "+counts[h]+"명");
                    long gap=Arrays.stream(counts).max().orElseThrow()-Arrays.stream(counts).min().orElseThrow();
                    rows.add(" 최대-최소 차이: "+gap+"명"+(gap>5?" · 경고: 기숙사 인원 차이가 5명을 넘었어요":""));
                    if(ChacaApi.ready())CodexCommands.lines(sender,"── 기숙사 인원 ──",rows);else rows.forEach(sender::sendMessage);},failTo(sender));return true;
            }
            if(a.length==3&&a[0].equals("희망")){
                Player player=plugin.names().resolve(a[1]);if(player==null||!player.isOnline())throw new SortingInput("현재 서버에 접속한 유저를 지정해 주세요");
                String w=a[2].toLowerCase(Locale.ROOT);if(w.startsWith("w_"))w=w.substring(2);
                int wish=HouseSorting.IDS.indexOf(w);if(wish<0)wish=SchoolProtocol.HOUSES.indexOf(a[2]);
                if(wish<0)throw new SortingInput("희망 기숙사는 w_arkeon·w_lumina·w_bestiaz·w_noxer (또는 한글 이름)이에요");
                sortingWishes.put(player.getUniqueId(),wish);
                plugin.getLogger().info("[기숙사분류] "+player.getName()+" 희망 "+SchoolProtocol.HOUSES.get(wish));return true;
            }
            if(a.length==2&&a[0].equals("시작")||a.length==3&&a[0].equals("결정")){
                Player player=plugin.names().resolve(a[1]);if(player==null||!player.isOnline())throw new SortingInput("현재 서버에 접속한 유저를 지정해 주세요");
                UUID id=player.getUniqueId();
                // 권한·스탯으로만 소속이 있는 유저는 다른 기숙사로 다시 분류하지 않고 그 소속을 DB 에 옮긴다
                int outside=outsideHouse(player);
                if(a[0].equals("시작")){
                    work(()->store.sortingStart(id),start->{
                        // 창시자 연출은 관리자가 미리 정한 유저만. 이미 분류된 일반 유저(발표 중 접속 끊김 등)는 결과 발표를 다시
                        if(start.house()!=null)sortingDialogue(sender,player,(start.preset()?"ch1-19f-":"ch1-19r-")+HouseSorting.IDS.get(start.house()),false);
                        else if(outside>=0)adopt(sender,player,outside);
                        else sortingDialogue(sender,player,"ch1-19",false);
                    },failTo(sender));
                }
                else{
                    if(!a[2].matches("p_[0-3][0-3]"))throw new SortingInput("결과는 p_00~p_33 형식이며 T,D는 각각 0~3이에요");
                    int t=a[2].charAt(2)-'0',d=a[2].charAt(3)-'0',tolerance=sortingTolerance(),bonus=sortingWishBonus();Integer wishBox=sortingWishes.get(id);int wish=wishBox==null?-1:wishBox;
                    if(outside>=0){adopt(sender,player,outside);return true;}
                    work(()->store.assignHouse(id,t,d,tolerance,wish,bonus),result->{sortingWishes.remove(id);
                        // Database has committed before signals or Bukkit commands run on the main thread.
                        if(result.created()){assigned(sender,id);AuditLog.log(id,AuditLog.기숙사,"기숙사 배정 · "+SchoolProtocol.HOUSES.get(result.house())+(result.house()!=result.preferred()?" (인원 맞춤, 원래 "+SchoolProtocol.HOUSES.get(result.preferred())+")":"")+(wish>=0?" · 희망 "+SchoolProtocol.HOUSES.get(wish):""),"기숙사",SchoolProtocol.HOUSES.get(result.house()),"성향",SchoolProtocol.HOUSES.get(result.preferred()),"경로","분류의 거울","진행",sender.getName());}
                        reply(player,new Request(SchoolProtocol.IDENTITY,0,0,""),"");
                        sortingDialogue(sender,player,"ch1-19"+(!result.created()||result.house()==result.preferred()?"r-":"m-")+HouseSorting.IDS.get(result.house()),true);
                    },failTo(sender));
                }return true;
            }
        }catch(SortingInput input){sortFail(sender,input.getMessage(),null);return true;}
        catch(Exception error){sortFail(sender,"명령어 사용에 실패했습니다",error);return true;}
        sortFail(sender,"/기숙사분류 시작 <유저> · 희망 <유저> <w_기숙사> · 결정 <유저> <p_TD> · 현황 · 허용치 <0~1000000>",null);return true;
    }
    private void assigned(CommandSender sender,UUID id){
        try{DiscoveryLink.signal(id,"dormitory.assigned",1);}catch(RuntimeException error){sortFail(sender,"소속은 저장됐지만 배정 신호 처리에 실패했어요",error);}
    }
    /** 권한·스탯 소속을 DB 로 옮기고 그 기숙사의 결과 발표를 띄운다. */
    private void adopt(CommandSender sender,Player player,int house){
        UUID id=player.getUniqueId();
        work(()->store.adoptHouse(id,house),result->{
            if(result.created())assigned(sender,id);
            reply(player,new Request(SchoolProtocol.IDENTITY,0,0,""),"");
            sortingDialogue(sender,player,"ch1-19r-"+HouseSorting.IDS.get(result.house()),true);
        },failTo(sender));
    }
    private void sortingDialogue(CommandSender sender,Player player,String dialogue,boolean saved){
        String state=saved?" (소속은 저장돼 있어요. 결정 명령을 다시 실행하면 기존 소속으로 결과 대사를 다시 띄워요)":"";
        if(!player.isOnline()){sortFail(sender,"유저가 접속을 종료해 대사를 띄우지 못했어요"+state,null);return;}
        try{if(!Bukkit.dispatchCommand(Bukkit.getConsoleSender(),"대화관리 재생 "+player.getName()+" 대화 "+dialogue))sortFail(sender,"스토리 명령을 실행하지 못했어요"+state,null);}
        catch(RuntimeException error){sortFail(sender,"기숙사 대사를 띄우지 못했어요"+state,error);}
    }
    private List<String> sortingTabs(CommandSender sender,String[] a){
        if(!sortingAdmin(sender)||a.length==0)return List.of();Collection<String> choices=List.of();
        if(a.length==1)choices=List.of("시작","희망","결정","현황","허용치");
        else if(a.length==2&&Set.of("시작","희망","결정").contains(a[0]))choices=Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        else if(a.length==2&&a[0].equals("허용치"))choices=List.of("0","1","2","3","5");
        else if(a.length==3&&a[0].equals("희망"))choices=HouseSorting.IDS.stream().map(h->"w_"+h).toList();
        else if(a.length==3&&a[0].equals("결정")){var values=new ArrayList<String>();for(int t=0;t<4;t++)for(int d=0;d<4;d++)values.add("p_"+t+d);choices=values;}
        String prefix=a[a.length-1].toLowerCase(Locale.ROOT);return choices.stream().filter(v->v.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted().toList();
    }
    @Override public boolean onCommand(CommandSender sender,org.bukkit.command.Command cmd,String label,String[] args){return sortingCommand(sender,args);}
    @Override public List<String> onTabComplete(CommandSender sender,org.bukkit.command.Command cmd,String label,String[] args){return sortingTabs(sender,args);}
    private String resolveDonation(String input){
        if(spells.containsKey(input))return input;
        var matches=spells.entrySet().stream().filter(e->e.getValue().name().replace(" ","").equals(input.replace(" ",""))).map(Map.Entry::getKey).toList();
        if(matches.size()!=1)throw new IllegalArgumentException(matches.isEmpty()?"등록된 마법 이름 또는 ID를 입력해 주세요.":"이름이 중복됩니다. 마법 ID를 입력해 주세요.");
        return matches.getFirst();
    }
    /** 기숙사 이름(한글) 또는 ID 값(arkeon·lumina·bestiaz·noxer, 스토리 1-14 결말 ID 호환). 없으면 -1. */
    static int parseHouse(String value){int h=SchoolProtocol.HOUSES.indexOf(value);return h>=0?h:HOUSE_IDS.indexOf(value.toLowerCase(Locale.ROOT));}
    static final List<String> HOUSE_IDS=List.of("arkeon","lumina","bestiaz","noxer");

    // ------------------------------------------------------------------ 명령 (2026-10-20 명령어 정리)
    private static int house(CommandContext<CommandSourceStack> ctx,String arg)throws com.mojang.brigadier.exceptions.CommandSyntaxException{
        int h=parseHouse(ctx.getArgument(arg,String.class));if(h<0)throw ChacaApi.error("아르케온·루미나·베스티아즈·노크세르 중에서 골라 주세요");return h;
    }
    private Collection<String> donationChoices(){var names=new TreeSet<String>();spells.forEach((id,v)->{String n=v.name().replace(" ","");names.add(n.isEmpty()?id:n);});return names;}
    private String donation(CommandContext<CommandSourceStack> ctx)throws com.mojang.brigadier.exceptions.CommandSyntaxException{
        try{return resolveDonation(ctx.getArgument("마법",String.class));}catch(IllegalArgumentException e){throw ChacaApi.error(e.getMessage());}
    }
    /** work() 실패 문구는 이미 유저용(기술 원인은 콘솔에 남김). */
    private Consumer<String> failTo(CommandSender sender){return m->sortFail(sender,m==null||m.isBlank()?"명령어 사용에 실패했습니다":m,null);}
    private void score(CommandSender sender,int h,String action,long value){
        long delta=action.equals("빼기")?-value:value;boolean set=action.equals("설정");
        work(()->{store.change(h,delta,set);return true;},v->ChacaApi.ok(sender,SchoolProtocol.HOUSES.get(h)+" 점수 "+data.scores().get(h)+"점"),failTo(sender));
    }
    private void registerCommands(){
        if(!CodexCommands.ready(plugin,"기숙사"))return;
        ChacaApi.reload(plugin,"기증","기증 마법 목록(donation-spells.yml)",this::loadCatalog);
        ChacaApi.summary(plugin,"기숙사",id->{Integer h=data.houses().get(id);return h==null||h<0||h>3?"":SchoolProtocol.HOUSES.get(h);});
        // /기숙사 (누구나): 학교 점수·기증 창
        ChacaApi.playerCommand(plugin,Commands.literal("기숙사").executes(ctx->{
            var p=CodexCommands.self(ctx);
            if(!p.getListeningPluginChannels().contains(SchoolProtocol.RESPONSE))throw ChacaApi.error("최신 MagicCodex 모드가 필요해요");
            reply(p,new Request(SchoolProtocol.LIST,0,0,""),"open");return Command.SINGLE_SUCCESS;
        }),"기숙사 점수·학교 기증 창 열기",List.of());

        // /유저관리 <닉네임> 기숙사 <기숙사|기증보기>
        ChacaApi.branch(plugin,ChacaApi.유저관리,Commands.literal("기숙사")
            .then(Commands.literal("기증보기").executes(ctx->{
                UUID id=ChacaApi.target(ctx);var rows=new ArrayList<String>();
                for(var e:data.records())if(e.donor().equals(id))rows.add(" "+e.spellName()+" ("+e.spell()+") · "+SchoolProtocol.HOUSES.get(e.house())+" · "+java.time.Instant.ofEpochMilli(e.time()).atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalDate());
                if(rows.isEmpty())rows.add(" 기증한 마법이 없어요");
                CodexCommands.lines(CodexCommands.sender(ctx),"── "+ChacaApi.name(id)+" 님의 학교 기증 ──",rows);return Command.SINGLE_SUCCESS;
            }))
            // /유저관리 <닉네임> 기숙사 초기화 확인: DB 소속·미리 정함 표시 지우기 (테스트 계정이 인원 집계에 남지 않게)
            .then(CodexCommands.confirmed(Commands.literal("초기화"),ctx->{
                UUID id=ChacaApi.target(ctx);var sender=CodexCommands.sender(ctx);
                work(()->store.clearHouse(id),had->{
                    Player online=Bukkit.getPlayer(id);if(online!=null)reply(online,new Request(SchoolProtocol.IDENTITY,0,0,""),"");
                    if(!had){ChacaApi.fail(sender,ChacaApi.name(id)+" 님은 DB에 저장된 기숙사가 없어요",null);return;}
                    int outside=online==null?-1:outsideHouse(online);
                    ChacaApi.ok(sender,ChacaApi.name(id)+" 님의 기숙사 소속을 지웠어요"+(outside>=0?" · 권한/스탯 기숙사("+SchoolProtocol.HOUSES.get(outside)+")는 그대로라 따로 지워야 해요":"")+" · 스토리 단계는 그대로예요");
                },failTo(sender));return Command.SINGLE_SUCCESS;
            }))
            .then(Commands.argument("기숙사",ChacaApi.word(()->SchoolProtocol.HOUSES)).executes(ctx->{
                UUID id=ChacaApi.target(ctx);int h=house(ctx,"기숙사");var sender=CodexCommands.sender(ctx);
                work(()->{store.presetHouse(id,h);return true;},ok->{
                    DiscoveryLink.signal(id,"dormitory.assigned",1);Player online=Bukkit.getPlayer(id);if(online!=null)reply(online,new Request(SchoolProtocol.IDENTITY,0,0,""),"");
                    ChacaApi.ok(sender,ChacaApi.name(id)+" 님의 기숙사를 "+SchoolProtocol.HOUSES.get(h)+"(으)로 미리 정했어요 (분류 거울에서 창시자 연출)");
                },failTo(sender));return Command.SINGLE_SUCCESS;
            })),"소속 기숙사 미리 정하기·초기화·기증 기록 보기","기숙사 루미나");

        // /기숙사관리 점수 [<기숙사> <보기|설정|추가|빼기> [값]]
        var houseArg=Commands.argument("기숙사",ChacaApi.word(()->SchoolProtocol.HOUSES))
            .then(Commands.literal("보기").executes(ctx->{int h=house(ctx,"기숙사");ChacaApi.ok(CodexCommands.sender(ctx),SchoolProtocol.HOUSES.get(h)+" 점수 "+data.scores().get(h)+"점");return Command.SINGLE_SUCCESS;}));
        for(String action:List.of("설정","추가","빼기"))
            houseArg.then(Commands.literal(action).then(Commands.argument("값",LongArgumentType.longArg(action.equals("설정")?-SchoolStore.MAX_SCORE:0,SchoolStore.MAX_SCORE))
                .executes(ctx->{score(CodexCommands.sender(ctx),house(ctx,"기숙사"),action,LongArgumentType.getLong(ctx,"값"));return Command.SINGLE_SUCCESS;})));
        ChacaApi.branch(plugin,ChacaApi.기숙사관리,Commands.literal("점수").executes(ctx->{
            var rows=new ArrayList<String>();for(int i=0;i<4;i++)rows.add(" "+SchoolProtocol.HOUSES.get(i)+" · "+data.scores().get(i)+"점");
            CodexCommands.lines(CodexCommands.sender(ctx),"── 기숙사 점수 ──",rows);return Command.SINGLE_SUCCESS;
        }).then(houseArg),"기숙사 점수 보기·설정·추가·빼기","점수 루미나 추가 10");
        ChacaApi.branch(plugin,ChacaApi.기숙사관리,CodexCommands.confirmed(Commands.literal("점수초기화"),ctx->{
            var sender=CodexCommands.sender(ctx);work(()->{store.resetPoints();return true;},v->ChacaApi.ok(sender,"네 기숙사 점수를 0으로 초기화했어요"),failTo(sender));return Command.SINGLE_SUCCESS;
        }),"네 기숙사 점수를 0으로 (기증 기록은 유지)","점수초기화 확인");

        // /기숙사관리 기증마법 <보기 [마법]|금지 <마법>|해제 <마법>>
        var donate=Commands.literal("기증마법");
        donate.then(Commands.literal("보기").executes(ctx->{
            var rows=new ArrayList<String>();
            for(var e:new TreeMap<>(spells).entrySet()){String id=e.getKey();boolean done=data.records().stream().anyMatch(r->r.spell().equals(id));boolean blocked=data.blocked().contains(id);if(done||blocked)rows.add(" "+e.getValue().name()+" ("+id+") · "+(done?"기증 완료":"기증 전")+(blocked?" · 금지":""));}
            rows.add(" 등록된 기증 마법 "+spells.size()+"종 · 기증 완료 "+data.records().size()+"종");
            CodexCommands.lines(CodexCommands.sender(ctx),"── 기증 마법 ──",rows);return Command.SINGLE_SUCCESS;
        }).then(Commands.argument("마법",ChacaApi.word(this::donationChoices)).executes(ctx->{
            String id=donation(ctx);var rec=data.records().stream().filter(r->r.spell().equals(id)).findFirst().orElse(null);
            ChacaApi.lines(CodexCommands.sender(ctx),List.of("── "+spells.get(id).name()+" ("+id+") ──",
                " 기증: "+(rec==null?"아직 없음":ChacaApi.name(rec.donor())+" · "+SchoolProtocol.HOUSES.get(rec.house())),
                " 기증 허용: "+(data.blocked().contains(id)?"금지":"허용")+" · 점수 "+spells.get(id).points()+"점"));return Command.SINGLE_SUCCESS;
        })));
        for(String action:List.of("금지","해제"))donate.then(Commands.literal(action).then(Commands.argument("마법",ChacaApi.word(this::donationChoices)).executes(ctx->{
            String id=donation(ctx);var sender=CodexCommands.sender(ctx);boolean block=action.equals("금지");
            work(()->{store.setBlocked(id,block);return true;},ok->ChacaApi.ok(sender,spells.get(id).name()+" 기증을 "+(block?"금지했어요":"다시 허용했어요")),failTo(sender));return Command.SINGLE_SUCCESS;
        })));
        ChacaApi.branch(plugin,ChacaApi.기숙사관리,donate,"기증 마법 보기·금지·해제","기증마법 보기");

        // /기숙사관리 기증마법초기화 <마법|전체> 확인
        var resetArg=Commands.argument("마법",ChacaApi.word(()->{var l=new ArrayList<String>();l.add("전체");l.addAll(donationChoices());return l;}));
        CodexCommands.confirmed(resetArg,ctx->{
            String input=ctx.getArgument("마법",String.class);String id=input.equals("전체")?"all":donation(ctx);var sender=CodexCommands.sender(ctx);
            work(()->{store.reset(id);return true;},v->ChacaApi.ok(sender,(id.equals("all")?"기증 기록 전체":spells.get(id).name()+" 기증 기록")+"을 지웠어요 (점수는 그대로)"),failTo(sender));return Command.SINGLE_SUCCESS;
        });
        ChacaApi.branch(plugin,ChacaApi.기숙사관리,Commands.literal("기증마법초기화").then(resetArg),"기증 기록 지우기 (점수는 유지)","기증마법초기화 전체 확인");
    }
    @EventHandler public void quit(PlayerQuitEvent e){limits.remove(e.getPlayer().getUniqueId());viewers.remove(e.getPlayer().getUniqueId());}
    @EventHandler public void join(PlayerJoinEvent e){if(shared)refreshShared();}
    public void close(){closing=true;if(expansion!=null)expansion.unregister();io.submit(()->{try{store.close();}catch(Exception e){plugin.getLogger().warning(e.toString());}});io.shutdown();try{if(!io.awaitTermination(20,TimeUnit.SECONDS))plugin.getLogger().severe("학교 DB 종료 대기시간 초과");}catch(InterruptedException e){Thread.currentThread().interrupt();}}
}
