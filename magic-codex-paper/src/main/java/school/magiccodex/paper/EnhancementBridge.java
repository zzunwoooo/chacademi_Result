package school.magiccodex.paper;
import java.io.File;import java.util.*;import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.*;import org.bukkit.entity.Player;import org.bukkit.event.*;import org.bukkit.event.player.*;import org.bukkit.configuration.file.YamlConfiguration;import org.bukkit.inventory.ItemStack;import org.bukkit.persistence.PersistentDataType;import org.bukkit.plugin.messaging.PluginMessageListener;
import school.magiccodex.protocol.EnhancementProtocol;import school.magiccodex.protocol.EnhancementProtocol.*;
/**
 * Debit and base result commit before animation. Interrupted challenges keep that result, never reroll.
 * 전투 스펙 2026-10-26: 마법봉과 장신구(반지·귀걸이·목걸이)가 같은 흐름(마력코어·스타캐치·시도 횟수·클라 강화 창)을 쓴다.
 *  - 마법봉: wand_power / wand_mana / wand_haste (원본 wand_base_*)
 *  - 장신구: EquipmentBridge 의 equipment_bonus_0 (지능) / _2 (최대 마나) / _3 (초당 마나 회복) (원본 accessory_base_*)
 *    장신구 강화는 체력(_1)·마법 가속(_4)을 올리지 않는다. 아티팩트 칸 아이템은 강화하지 않는다.
 *  - 성공 1회 상승량 = 단계값(n번째 성공) × gain × 코어 성향 배율. 코어 품질(노드 수)은 성공 확률·스타캐치에만 영향.
 */
final class EnhancementBridge implements Listener,PluginMessageListener,AutoCloseable {
 private static final int WAND=EnhancementProtocol.WAND,ACCESSORY=EnhancementProtocol.ACCESSORY;
 private static final String[] WAND_KEYS={"wand_power","wand_mana","wand_haste"},ACC_KEYS={"equipment_bonus_0","equipment_bonus_2","equipment_bonus_3"};
 private static final String[] WAND_BASE={"wand_base_power","wand_base_mana","wand_base_haste"},ACC_BASE={"accessory_base_power","accessory_base_mana","accessory_base_regen"};
 /** 강화할 수 있는 장신구 칸 (EquipmentBridge TYPES 중 아티팩트 제외). */
 private static final Set<String> ACC_TYPES=Set.of("ring","earring","necklace");
 record WandGrade(String name,double[] stats){}
 private final MagicCodexBridge plugin;private YamlConfiguration config;
 private double[] qualityMultipliers=new double[11];
 private List<Double> wandStages=List.of(),accStages=List.of();private Map<String,WandGrade> wandGrades=Map.of();private Map<String,double[]> accGrades=Map.of();
 private final Map<UUID,Session> sessions=new HashMap<>();private final Map<UUID,Long> limits=new HashMap<>();
 private static long now(){return System.nanoTime()/1_000_000;}
 private static final class Session {
  long token,created,started;int slot,coreSlot,nodes,attempts,limit,window,hit,miss,state,latency,kind;double cost,base,bonus,roll;boolean consume,baseWin;String name,message="";ItemStack wand,core,committed;List<Double> before,gains;List<Integer> targets;
 }
 EnhancementBridge(MagicCodexBridge p){plugin=p;File f=new File(p.getDataFolder(),"enhancement.yml");if(!f.exists())p.saveResource("enhancement.yml",false);reload();
  CmdKit.register(p,"/강화·마법봉 새로고침",()->{
   ChacaApi.playerCommand(p,CmdKit.literal("강화").executes(CmdKit.run(ctx->open(CmdKit.self(ctx)))),"마법봉·장신구 강화 창 열기",List.of());
   ChacaApi.reload(p,"마법봉","enhancement.yml (마법봉·장신구 강화)",this::reloadAll);
  });
  Bukkit.getPluginManager().registerEvents(this,p);Bukkit.getMessenger().registerIncomingPluginChannel(p,EnhancementProtocol.REQUEST,this);Bukkit.getMessenger().registerOutgoingPluginChannel(p,EnhancementProtocol.RESPONSE);
  Bukkit.getScheduler().runTaskTimer(p,this::tick,2,2);
 }
 private NamespacedKey key(String k){return new NamespacedKey(plugin,k);}
 private int integer(ItemStack i,String k,int fallback){return i==null||!i.hasItemMeta()?fallback:i.getItemMeta().getPersistentDataContainer().getOrDefault(key(k),PersistentDataType.INTEGER,fallback);}
 private double number(ItemStack i,String k){if(i==null||!i.hasItemMeta())return 0;double v=i.getItemMeta().getPersistentDataContainer().getOrDefault(key(k),PersistentDataType.DOUBLE,0d);return Double.isFinite(v)&&v>=0?v:0;}
 private boolean wand(ItemStack i){return i!=null&&i.getAmount()==1&&i.hasItemMeta()&&i.getItemMeta().getPersistentDataContainer().has(key("wand_id"),PersistentDataType.STRING);}
 /** 등록된 장신구(반지·귀걸이·목걸이) 한 개. 마법봉으로도 등록된 아이템은 마법봉으로 본다. */
 private boolean accessory(ItemStack i){if(i==null||i.getAmount()!=1||!i.hasItemMeta()||wand(i))return false;String t=i.getItemMeta().getPersistentDataContainer().get(key("accessory_type"),PersistentDataType.STRING);return t!=null&&ACC_TYPES.contains(t);}
 private boolean accessoryEnabled(){return config.getBoolean("accessory.enabled",false);}
 /** 0 마법봉 · 1 장신구 · -1 강화 대상 아님. */
 private int kind(ItemStack i){return wand(i)?WAND:accessory(i)&&accessoryEnabled()?ACCESSORY:-1;}
 private static String[] keys(int kind){return kind==ACCESSORY?ACC_KEYS:WAND_KEYS;}
 private static String[] baseKeys(int kind){return kind==ACCESSORY?ACC_BASE:WAND_BASE;}
 private int limitOf(int kind){return kind==ACCESSORY?config.getInt("accessory.maximum-attempts"):config.getInt("maximum-attempts");}
 private boolean core(ItemStack i){int n=integer(i,"core_nodes",0);return i!=null&&i.getAmount()==1&&integer(i,"core_state",-1)==1&&n>=1&&n<=10;}
 private void reload(){var c=YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(),"enhancement.yml"));
  valid(c,"cost",0,1e12);valid(c,"maximum-attempts",1,100);for(String k:new String[]{"power","mana","haste"})valid(c,"gain."+k,0,10000);
  valid(c,"starcatch.interval-ms",650,3000);valid(c,"starcatch.minimum-window-ms",30,500);valid(c,"starcatch.first-window-ms",30,500);valid(c,"starcatch.window-decrease-ms",0,40);
  valid(c,"base-chance-override",-1,1);valid(c,"bonus-chance-override",-1,1);
  List<Double> ws=stages(c,"stage-gains",false),as=List.of();
  if(c.getBoolean("accessory.enabled",false)){valid(c,"accessory.maximum-attempts",1,100);valid(c,"accessory.cost",0,1e12);for(String k:new String[]{"power","mana","regen"})valid(c,"accessory.gain."+k,0,10000);as=stages(c,"accessory.stage-gains",true);}
  var wg=new LinkedHashMap<String,WandGrade>();var root=c.getConfigurationSection("wand-grades");
  if(root!=null)for(String g:root.getKeys(false)){var s=root.getConfigurationSection(g);List<Double> st=s==null?List.of():s.getDoubleList("stats");String name=s==null?"":s.getString("name","");
   if(g.isBlank()||g.length()>16||name.isBlank()||name.length()>60||st.size()!=3||st.stream().anyMatch(v->!Double.isFinite(v)||v<0||v>100000))throw new IllegalArgumentException("wand-grades."+g);
   wg.put(g,new WandGrade(name,new double[]{st.get(0),st.get(1),st.get(2)}));}
  var ag=new LinkedHashMap<String,double[]>();var acc=c.getConfigurationSection("accessory-grades");
  if(acc!=null)for(String g:acc.getKeys(false)){List<Double> st=acc.getDoubleList(g);
   if(g.isBlank()||g.length()>16||st.size()!=5)throw new IllegalArgumentException("accessory-grades."+g);
   double[] v=new double[5];for(int i=0;i<5;i++){double x=st.get(i);if(!Double.isFinite(x)||x<0||x>(i==1?200:100000))throw new IllegalArgumentException("accessory-grades."+g);v[i]=x;}ag.put(g,v);}
  config=c;qualityMultipliers=qualities(c);wandStages=ws;accStages=as;wandGrades=Collections.unmodifiableMap(wg);accGrades=Collections.unmodifiableMap(ag);
 }
 /** core-quality-multiplier: 노드 수(1~10) → 배율. 없거나 잘못되면 기본(1·2노드 0.9·0.95, 3노드 1.0 → 10노드 1.15 직선). */
 private static double[] qualities(YamlConfiguration c){
  double[] out=new double[11];for(int n=1;n<=10;n++)out[n]=EnhancementRules.defaultQuality(n);
  var sec=c.getConfigurationSection("core-quality-multiplier");if(sec==null)return out;
  for(int n=1;n<=10;n++){double v=sec.getDouble(String.valueOf(n),out[n]);if(Double.isFinite(v)&&v>0&&v<=10)out[n]=v;}
  return out;
 }
 private static List<Double> stages(YamlConfiguration c,String k,boolean required){
  if(!c.contains(k)){if(required)throw new IllegalArgumentException(k);return List.of();}
  List<Double> v=c.getDoubleList(k);if(v.isEmpty()||v.size()>100||v.stream().anyMatch(x->!Double.isFinite(x)||x<0||x>10000))throw new IllegalArgumentException(k);return List.copyOf(v);
 }
 private static void valid(YamlConfiguration c,String k,double lo,double hi){double v=c.getDouble(k,Double.NaN);if(!Double.isFinite(v)||v<lo||v>hi)throw new IllegalArgumentException(k);}
 private void reloadAll(){reload();for(Player online:Bukkit.getOnlinePlayers())for(ItemStack item:online.getInventory().getContents())if(kind(item)>=0)lore(item);}
 /** /강화: 손에 든 마법봉·장신구의 강화 창. */
 private void open(Player p){
  if(!p.hasPermission("magiccodex.enhance"))return;
  if(!p.getListeningPluginChannels().contains(EnhancementProtocol.RESPONSE)){CmdKit.notify(p,"warning","enhance-mod","강화 창을 열 수 없어요","차카데미 모드를 업데이트해 주세요",3000);return;}
  Session previous=sessions.get(p.getUniqueId());if(previous!=null&&previous.state==1){send(p,previous);return;}
  if(now()<limits.getOrDefault(p.getUniqueId(),0L))return;limits.put(p.getUniqueId(),now()+350);
  offer(p,-1);
 }
 boolean isWand(ItemStack i){return wand(i);}
 /** 마력 재구성 (장신구 재구성, 오너 2026-10-26): 강화와 같은 대상 판정 · 키 · 시도 상한을 그대로 쓴다. 0 마법봉 · 1 장신구 · -1 아님. */
 int targetKind(ItemStack i){return kind(i);}
 /** 강화로 오르는 세 값의 PDC 키 (마법봉 wand_* · 장신구 equipment_bonus_0/2/3). 복사본. */
 String[] statKeys(int kind){return keys(kind).clone();}
 /** 강화 전 원본 수치 PDC 키 (wand_base_* · accessory_base_*). 복사본. */
 String[] baseStatKeys(int kind){return baseKeys(kind).clone();}
 /** enhancement.yml 시도 상한 (마법봉 maximum-attempts · 장신구 accessory.maximum-attempts). */
 int attemptLimit(int kind){return limitOf(kind);}
 /** 등급별 마법봉 이름 (enhancement.yml wand-grades 순서). */
 List<String> wandGradeNames(){return List.copyOf(wandGrades.keySet());}
 /** 등급별 장신구 기본 수치 이름 (enhancement.yml accessory-grades 순서). */
 List<String> accessoryGradeNames(){return List.copyOf(accGrades.keySet());}
 /** [지능, 체력, 최대 마나, 초당 마나 회복, 마법 가속] 복사본. 없는 등급이면 null. */
 double[] accessoryGrade(String grade){double[] v=accGrades.get(grade);return v==null?null:v.clone();}
 /** /아이템관리 마법봉 주기 &lt;등급&gt;: enhancement.yml wand-grades 수치의 새 마법봉. */
 ItemStack gradeWand(String grade){WandGrade g=wandGrades.get(grade);if(g==null)throw new IllegalArgumentException("없는 마법봉 등급이에요: "+grade);
  ItemStack i=new ItemStack(Material.BLAZE_ROD);var m=i.getItemMeta();m.displayName(net.kyori.adventure.text.Component.text(g.name()));i.setItemMeta(m);register(i,g.stats()[0],g.stats()[1],g.stats()[2]);return i;}
 /** /아이템관리 마법봉 주기: 기본 수습 마법봉 (wand-grades.수습, 없으면 전투 스펙 기본 지능 20 · 추가 마나 50). */
 ItemStack apprenticeWand(){if(wandGrades.containsKey("수습"))return gradeWand("수습");ItemStack i=new ItemStack(Material.BLAZE_ROD);var m=i.getItemMeta();m.displayName(net.kyori.adventure.text.Component.text("수습 마법봉"));i.setItemMeta(m);register(i,20,50,0);return i;}
 /** /아이템관리 마법봉 원본설정: 손에 든 (구형) 마법봉의 강화 전 원본 수치. 현재 수치 이하만. */
 void setBase(Player p,double[] values){
  if(active(p))throw new IllegalArgumentException("강화를 먼저 끝내 주세요");
  ItemStack i=p.getInventory().getItemInMainHand();if(!wand(i))throw new IllegalArgumentException("마법봉을 주 손에 들어 주세요");
  String[] stats={"power","mana","haste"};for(int j=0;j<3;j++)if(!Double.isFinite(values[j])||values[j]<0||values[j]>number(i,"wand_"+stats[j]))throw new IllegalArgumentException("원본 수치는 지금 수치 이하여야 해요");
  var m=i.getItemMeta();for(int j=0;j<3;j++)m.getPersistentDataContainer().set(key("wand_base_"+stats[j]),PersistentDataType.DOUBLE,values[j]);i.setItemMeta(m);p.saveData();
 }
 void register(ItemStack i,double power,double mana,double haste){for(double v:new double[]{power,mana,haste})if(!Double.isFinite(v)||v<0||v>100000)throw new IllegalArgumentException("수치 범위 0~100000");var m=i.getItemMeta();var d=m.getPersistentDataContainer();d.set(key("wand_id"),PersistentDataType.STRING,UUID.randomUUID().toString());d.set(key("wand_power"),PersistentDataType.DOUBLE,power);d.set(key("wand_mana"),PersistentDataType.DOUBLE,mana);d.set(key("wand_haste"),PersistentDataType.DOUBLE,haste);i.setItemMeta(m);captureBase(i);lore(i);}
 /** 장신구 등록·등급등록 직후 (EquipmentBridge): 강화 원본 수치와 강화 줄을 새로 적는다. 강화 대상이 아니면 아무것도 안 함. */
 void accessoryRegistered(ItemStack i){if(!accessory(i))return;var m=i.getItemMeta();for(String k:ACC_BASE)m.getPersistentDataContainer().remove(key(k));i.setItemMeta(m);captureBase(i);if(accessoryEnabled())lore(i);}
 void lore(ItemStack item){int kind=kind(item);if(kind<0)return;var m=item.getItemMeta();m.getPersistentDataContainer().set(key("enhance_limit"),PersistentDataType.INTEGER,limitOf(kind));var lines=new ArrayList<net.kyori.adventure.text.Component>();if(kind==WAND)lines.add(net.kyori.adventure.text.Component.text("마법봉"));if(m.lore()!=null)for(var l:m.lore()){String s=net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(l);if(!s.startsWith("[강화]")&&!(kind==WAND&&s.equals("마법봉")))lines.add(l);}
  lines.add(net.kyori.adventure.text.Component.text("[강화] 사용 횟수 "+integer(item,"enhance_attempts",0)+" · 성공 "+integer(item,"enhance_successes",0)));
  String[] k=keys(kind);
  lines.add(net.kyori.adventure.text.Component.text(String.format(Locale.ROOT,kind==WAND?"[강화] 지능 %.1f · 추가 마나 %.1f · 마법 가속 %.1f":"[강화] 지능 %.1f · 최대 마나 %.1f · 초당 마나 회복 %.2f",number(item,k[0]),number(item,k[1]),number(item,k[2]))));m.lore(lines);item.setItemMeta(m);
 }
 boolean active(Player p){Session s=sessions.get(p.getUniqueId());return s!=null&&s.state==1;}
 boolean hasBase(ItemStack i){int kind=kind(i);if(kind<0)return false;for(String n:baseKeys(kind))if(!i.getItemMeta().getPersistentDataContainer().has(key(n),PersistentDataType.DOUBLE))return false;return true;}
 void captureBase(ItemStack i){int kind=wand(i)?WAND:accessory(i)?ACCESSORY:-1;if(kind<0)return;String[] b=baseKeys(kind),k=keys(kind);boolean has=true;for(String n:b)has&=i.getItemMeta().getPersistentDataContainer().has(key(n),PersistentDataType.DOUBLE);if(has||integer(i,"enhance_successes",0)!=0)return;var m=i.getItemMeta();for(int j=0;j<3;j++)m.getPersistentDataContainer().set(key(b[j]),PersistentDataType.DOUBLE,number(i,k[j]));i.setItemMeta(m);}
 private boolean usable(Player p){return !p.isDead()&&p.getGameMode()!=GameMode.SPECTATOR&&(p.getOpenInventory().getType()==org.bukkit.event.inventory.InventoryType.CRAFTING||p.getOpenInventory().getType()==org.bukkit.event.inventory.InventoryType.CREATIVE)&&p.getItemOnCursor().getType().isAir();}
 private void offer(Player p,int after){
  if(!usable(p)){refuseOpen(p,"다른 보관함을 닫은 뒤 사용해 주세요");return;}
  var inv=p.getInventory();ItemStack w=inv.getItemInMainHand();int kind=kind(w);if(kind<0){refuseOpen(p,"등록된 마법봉이나 장신구(반지·귀걸이·목걸이)를 주 손에 들어 주세요");return;}
  int slot=-1;for(int j=1;j<=36;j++){int i=Math.floorMod(after+j,36);if(i!=inv.getHeldItemSlot()&&core(inv.getItem(i))){slot=i;break;}}
  if(slot<0){refuseOpen(p,"인벤토리에 감정된 마력코어가 필요해요");return;}
  ItemStack oldCore=inv.getItem(slot),visualCore=plugin.coreAppearance(oldCore);if(visualCore!=oldCore)inv.setItem(slot,visualCore);
  Session s=new Session();s.kind=kind;s.token=ThreadLocalRandom.current().nextLong(1,Long.MAX_VALUE);s.created=now();s.slot=inv.getHeldItemSlot();s.coreSlot=slot;captureBase(w);s.wand=w.clone();s.core=inv.getItem(slot).clone();s.nodes=integer(s.core,"core_nodes",1);s.attempts=integer(w,"enhance_attempts",0);s.limit=limitOf(kind);if(s.attempts>=s.limit){refuseOpen(p,kind==WAND?"이 마법봉의 강화 횟수를 모두 썼어요":"이 장신구의 강화 횟수를 모두 썼어요");return;}
  s.cost=config.getDouble(kind==WAND?"cost":"accessory.cost");s.consume=config.getBoolean("consume-attempt-on-failure",true);s.base=Math.min(1,number(s.core,"core_base_success"));s.bonus=Math.min(1,number(s.core,"core_starcatch_bonus"));if(config.getDouble("base-chance-override")>=0)s.base=config.getDouble("base-chance-override");if(config.getDouble("bonus-chance-override")>=0)s.bonus=config.getDouble("bonus-chance-override");
  s.window=Math.max(config.getInt("starcatch.minimum-window-ms"),config.getInt("starcatch.first-window-ms")-(s.nodes-1)*config.getInt("starcatch.window-decrease-ms"));
  String[] k=keys(kind);s.before=List.of(number(w,k[0]),number(w,k[1]),number(w,k[2]));
  List<Double> stages=kind==WAND?wandStages:accStages;double factor=number(s.core,"core_stat_gain");if(stages.isEmpty()&&(factor<=0||factor>10000)){refuseOpen(p,"코어의 상승량 정보가 올바르지 않아요");return;}
  double stage=EnhancementRules.stageGain(stages,integer(w,"enhance_successes",0),factor);
  if(!stages.isEmpty())stage*=EnhancementRules.qualityMultiplier(qualityMultipliers,s.nodes); // 섞기 (오너 2026-10-26): 단계값 × 코어 품질 배율
  double[] gain=kind==WAND?new double[]{config.getDouble("gain.power"),config.getDouble("gain.mana"),config.getDouble("gain.haste")}:new double[]{config.getDouble("accessory.gain.power"),config.getDouble("accessory.gain.mana"),config.getDouble("accessory.gain.regen")};
  double[] g=EnhancementRules.gains(stage,gain,integer(s.core,"core_affinity",0));s.gains=List.of(g[0],g[1],g[2]);for(int i=0;i<3;i++)if(s.before.get(i)+s.gains.get(i)>100000){refuseOpen(p,"능력치 상한을 넘어요");return;}
  String name=net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(w.displayName());s.name=name.length()>60?name.substring(0,60):name;
  s.targets=new ArrayList<>();int t=0;for(int i=0;i<s.nodes;i++){t+=Math.max(500,config.getInt("starcatch.interval-ms")-s.nodes*20+ThreadLocalRandom.current().nextInt(-100,101));s.targets.add(t);}
  sessions.put(p.getUniqueId(),s);send(p,s);
 }
 @Override public void onPluginMessageReceived(String channel,Player p,byte[] b){if(!channel.equals(EnhancementProtocol.REQUEST))return;Request r;try{r=EnhancementProtocol.request(b);}catch(IllegalArgumentException e){return;}Session s=sessions.get(p.getUniqueId());if(s==null||s.token!=r.token())return;
  if(r.action()==EnhancementProtocol.CLOSE){sessions.remove(p.getUniqueId());return;}
  if(!p.hasPermission("magiccodex.enhance")||!usable(p)){if(s.state==1)finish(p,s,false);else refuse(p,s,"지금은 강화할 수 없습니다.");return;}
  if(r.action()==EnhancementProtocol.NEXT&&s.state==0){if(now()<limits.getOrDefault(p.getUniqueId(),0L))return;limits.put(p.getUniqueId(),now()+200);offer(p,s.coreSlot);return;}
  if(r.action()==EnhancementProtocol.START){if(s.state!=0)return;
   if(now()-s.created>60000||p.getInventory().getHeldItemSlot()!=s.slot||!s.wand.equals(p.getInventory().getItem(s.slot))||!s.core.equals(p.getInventory().getItem(s.coreSlot))){refuse(p,s,"아이템이 변경되었습니다. 다시 열어 주세요.");return;}
   s.roll=ThreadLocalRandom.current().nextDouble();s.baseWin=EnhancementRules.success(s.roll,s.base);ItemStack result=result(s,s.baseWin);
   if(!withdraw(p,s.cost)){refuse(p,s,"돈이 부족합니다.");return;}
   p.getInventory().setItem(s.coreSlot,null);p.getInventory().setItem(s.slot,result);s.committed=result.clone();s.attempts=integer(result,"enhance_attempts",0);s.state=1;s.latency=Math.clamp(p.getPing(),0,200);p.saveData();plugin.refreshEquipment(p);s.started=now();send(p,s);return;
  }
  if(r.action()==EnhancementProtocol.HIT&&s.state==1){int expected=0;while(expected<s.nodes&&((s.hit|s.miss)&(1<<expected))!=0)expected++;if(r.node()!=expected)return;int bit=1<<r.node();if(r.node()>=s.nodes||((s.hit|s.miss)&bit)!=0)return;long elapsed=now()-s.started-800;
   if(EnhancementRules.hit(elapsed,s.targets.get(r.node()),s.window,s.latency))s.hit|=bit;else s.miss|=bit;send(p,s);
  }
 }
 private ItemStack result(Session s,boolean win){ItemStack i=s.wand.clone();var m=i.getItemMeta();var d=m.getPersistentDataContainer();d.set(key("enhance_attempts"),PersistentDataType.INTEGER,integer(s.wand,"enhance_attempts",0)+(s.consume||win?1:0));d.set(key("enhance_successes"),PersistentDataType.INTEGER,integer(s.wand,"enhance_successes",0)+(win?1:0));String[] keys=keys(s.kind);for(int j=0;j<3;j++)d.set(key(keys[j]),PersistentDataType.DOUBLE,s.before.get(j)+(win?s.gains.get(j):0));i.setItemMeta(m);lore(i);return i;}
 private void finish(Player p,Session s,boolean eligible){boolean perfect=eligible&&s.miss==0&&s.hit==(1<<s.nodes)-1;boolean win=s.baseWin;
  if(perfect&&!win&&EnhancementRules.success(s.roll,Math.min(1,s.base+s.bonus))){if(s.committed.equals(p.getInventory().getItem(s.slot))){var result=result(s,true);p.getInventory().setItem(s.slot,result);s.committed=result.clone();s.attempts=integer(result,"enhance_attempts",0);p.saveData();plugin.refreshEquipment(p);win=true;}}
  s.state=win?2:3;s.message=win?"강화 성공":"강화 실패 · 기존 능력치는 유지됩니다.";
  AuditLog.log(p,AuditLog.강화,"장비 강화 "+(win?"성공":"실패")+" · "+s.name+(perfect&&win&&!s.baseWin?" (스타캐치)":"")+" · 비용 "+s.cost,"결과",win?"성공":"실패","종류",s.kind==WAND?"마법봉":"장신구","장비",s.name,"스타캐치",perfect?"완벽":"아님","전",s.before.toString(),"상승",win?s.gains.toString():"없음","시도",s.attempts,"비용",s.cost);
  send(p,s);
 }
 private void tick(){long time=now();for(var it=sessions.entrySet().iterator();it.hasNext();){var e=it.next();Session s=e.getValue();Player p=Bukkit.getPlayer(e.getKey());if(p==null){it.remove();continue;}if(s.state!=1){if(time-s.created>90000)it.remove();continue;}
  if(!p.hasPermission("magiccodex.enhance")||!usable(p)||p.getInventory().getHeldItemSlot()!=s.slot||!s.committed.equals(p.getInventory().getItem(s.slot))){finish(p,s,false);continue;}
  long elapsed=time-s.started-800;boolean changed=false;for(int i=0;i<s.nodes;i++){int bit=1<<i;if(((s.hit|s.miss)&bit)==0&&elapsed>s.targets.get(i)+s.window+s.latency+100){s.miss|=bit;changed=true;}}
  if(elapsed>s.targets.get(s.nodes-1)+s.window+s.latency+250)finish(p,s,true);else if(changed)send(p,s);
 }limits.values().removeIf(t->t<time);}
 private static void refuseOpen(Player p,String msg){CmdKit.notify(p,"warning","enhance-open","강화할 수 없어요",msg,1500);}
 private void refuse(Player p,Session s,String msg){s.state=4;s.message=msg;send(p,s);}
 private void send(Player p,Session s){if(p.getListeningPluginChannels().contains(EnhancementProtocol.RESPONSE))p.sendPluginMessage(plugin,EnhancementProtocol.RESPONSE,EnhancementProtocol.response(new Response(s.token,s.state,s.nodes,s.attempts,s.limit,s.hit,s.miss,s.window,800,s.name,s.message,s.cost,balance(p),s.base,s.bonus,s.before,s.gains,s.targets,school.magiccodex.protocol.CoreAffinity.valid(integer(s.core,"core_affinity",0)),s.kind)));}
 private record Economy(Class<?> type,Object provider){}
 @SuppressWarnings({"unchecked","rawtypes"}) private Economy economy(){for(Class type:Bukkit.getServicesManager().getKnownServices())if(type.getName().equals("net.milkbowl.vault.economy.Economy")){var r=Bukkit.getServicesManager().getRegistration(type);if(r!=null)return new Economy(type,r.getProvider());}return null;}
 private double balance(Player p){try{var e=economy();if(e==null)return -1;double b=((Number)e.type.getMethod("getBalance",OfflinePlayer.class).invoke(e.provider,p)).doubleValue();return Double.isFinite(b)&&b>=0?b:-1;}catch(ReflectiveOperationException e){return -1;}}
 private boolean withdraw(Player p,double cost){if(cost==0)return true;try{var e=economy();if(e==null)return false;Object r=e.type.getMethod("withdrawPlayer",OfflinePlayer.class,double.class).invoke(e.provider,p,cost);return (boolean)r.getClass().getMethod("transactionSuccess").invoke(r);}catch(ReflectiveOperationException e){plugin.getLogger().warning("강화 결제 실패: "+e.getClass().getSimpleName());return false;}}
 @EventHandler public void join(PlayerJoinEvent e){for(ItemStack item:e.getPlayer().getInventory().getContents())if(kind(item)>=0)lore(item);}
 @EventHandler public void quit(PlayerQuitEvent e){sessions.remove(e.getPlayer().getUniqueId());limits.remove(e.getPlayer().getUniqueId());}
 @Override public void close(){sessions.clear();limits.clear();}
}
