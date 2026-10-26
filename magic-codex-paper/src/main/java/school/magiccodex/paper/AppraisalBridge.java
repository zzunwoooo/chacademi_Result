package school.magiccodex.paper;
import java.io.File;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.messaging.PluginMessageListener;
import school.magiccodex.protocol.AppraisalProtocol;
import school.magiccodex.protocol.AppraisalProtocol.*;

final class AppraisalBridge implements PluginMessageListener,Listener,AutoCloseable {
 private final MagicCodexBridge plugin;
 private YamlConfiguration config;
 private final Map<UUID,Offer> offers=new HashMap<>();
 private final Map<UUID,Long> next=new HashMap<>();
 private record Offer(long token,int slot,ItemStack item,long expires,double cost){}
 AppraisalBridge(MagicCodexBridge plugin){
  this.plugin=plugin;File file=new File(plugin.getDataFolder(),"core-appraisal.yml");if(!file.exists())plugin.saveResource("core-appraisal.yml",false);migrateFormation(file);reload();
  CmdKit.register(plugin,"/코어감정·마력코어 새로고침",()->{
   ChacaApi.playerCommand(plugin,CmdKit.literal("코어감정").executes(CmdKit.run(ctx->open(CmdKit.self(ctx)))),"손에 든 마력코어 감정 창 열기",List.of());
   ChacaApi.reload(plugin,"마력코어","core-appraisal.yml (마력코어 감정)",this::reload);
  });
  Bukkit.getMessenger().registerIncomingPluginChannel(plugin,AppraisalProtocol.REQUEST,this);Bukkit.getMessenger().registerOutgoingPluginChannel(plugin,AppraisalProtocol.RESPONSE);
  Bukkit.getPluginManager().registerEvents(this,plugin);
 }
 private void migrateFormation(File file){
  var cfg=YamlConfiguration.loadConfiguration(file);
  double[][] defaults={{1,.95,.88,.35,.85,.75,.22,.7,.55,.08},{1,.995,.99,.7,.98,.9,.1,.45,.12,.01}};
  double[] updated={1,.95,.9,.5,.7,.6,.3,.55,.35,.04};boolean migrate=false;
  for(double[] old:defaults){boolean same=true;for(int i=0;i<10;i++)if(Math.abs(cfg.getDouble("formation."+(i+1),-1)-old[i])>1e-9)same=false;if(same)migrate=true;}if(!migrate)return;
  try{
   java.nio.file.Files.copy(file.toPath(),new File(file.getParentFile(),"core-appraisal-before-0183-"+System.currentTimeMillis()+".yml").toPath());
   for(int i=0;i<10;i++)cfg.set("formation."+(i+1),updated[i]);cfg.save(file);
   plugin.getLogger().info("기존 기본 감정 확률을 평균 3.806코어 설정으로 갱신했습니다. 이전 설정 백업 완료.");
  }catch(java.io.IOException e){throw new IllegalStateException("감정 확률 설정 갱신 실패",e);}
 }
 private NamespacedKey key(String name){return new NamespacedKey(plugin,name);}
 private int value(ItemStack item,String key,int fallback){if(item==null||!item.hasItemMeta())return fallback;return item.getItemMeta().getPersistentDataContainer().getOrDefault(key(key),PersistentDataType.INTEGER,fallback);}
 private boolean core(ItemStack item){return value(item,"core_state",-1)>=0;}
 private void reload(){
  var fresh=YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(),"core-appraisal.yml"));
  for(int i=1;i<=10;i++){double p=fresh.getDouble("formation."+i,-1);if(!Double.isFinite(p)||p<0||p>1)throw new IllegalArgumentException("formation."+i+" must be 0..1");}
  for(String s:new String[]{"appraisal-cost","break-chance"}){double p=fresh.getDouble(s,-1);if(!Double.isFinite(p)||p<0||(s.equals("break-chance")&&p>1))throw new IllegalArgumentException(s);}
  for(int i=0;i<=10;i++)for(String s:new String[]{"base-success","starcatch-bonus","stat-gain"}){double v=fresh.getDouble("profiles."+i+"."+s,-1);if(!Double.isFinite(v)||v<0||(!s.equals("stat-gain")&&v>1))throw new IllegalArgumentException("profiles."+i+"."+s);}
  AppraisalRoll.affinity(affinityWeights(fresh),()->0);
  config=fresh;offers.clear();
 }
 private static final String[] AFFINITY_KEYS={"power","mana","haste"};
 /** affinity-weights.power/mana/haste (없으면 각 1 = 같은 확률). */
 private static double[] affinityWeights(YamlConfiguration c){double[] w=new double[3];for(int i=0;i<3;i++)w[i]=c.getDouble("affinity-weights."+AFFINITY_KEYS[i],1);return w;}
 private ItemStack make(){ItemStack item=new ItemStack(Material.AMETHYST_SHARD);var m=item.getItemMeta();m.getPersistentDataContainer().set(key("core_id"),PersistentDataType.STRING,UUID.randomUUID().toString());item.setItemMeta(m);return update(item,0,0);}
 private ItemStack update(ItemStack input,int state,int nodes){
  ItemStack item=input.clone();var m=item.getItemMeta();var p=m.getPersistentDataContainer();p.set(key("core_state"),PersistentDataType.INTEGER,state);p.set(key("core_nodes"),PersistentDataType.INTEGER,nodes);
  for(String s:new String[]{"base-success","starcatch-bonus","stat-gain"})p.set(key("core_"+s.replace('-','_')),PersistentDataType.DOUBLE,config.getDouble("profiles."+nodes+"."+s));
  m.displayName(net.kyori.adventure.text.Component.text(state==2?"파괴된 마력코어":state==0?"미감정 마력코어":"마력코어").decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC,false));
  m.lore(List.of(net.kyori.adventure.text.Component.text("강화 재료"),net.kyori.adventure.text.Component.text(state==0?"아직 해독하지 못한 룬이 새겨져 있습니다.":state==2?"마력이 흩어져 더 이상 사용할 수 없습니다.":"해독된 룬이 마력 회로에 자리 잡았습니다.")));
  item.setItemMeta(m);return plugin.coreAppearance(item);
 }
 /** Creates one fully appraised core using the existing persistent item schema. */
 ItemStack adminCore(int nodes,double successPercent,double bonusPercent,double statGain,int affinity){
  AdminCommandRules.validateCore(nodes,successPercent,bonusPercent,statGain,affinity);
  ItemStack item=update(make(),1,nodes);var meta=item.getItemMeta();var data=meta.getPersistentDataContainer();
  data.set(key("core_base_success"),PersistentDataType.DOUBLE,successPercent/100d);
  data.set(key("core_starcatch_bonus"),PersistentDataType.DOUBLE,bonusPercent/100d);
  data.set(key("core_stat_gain"),PersistentDataType.DOUBLE,statGain);
  data.set(key("core_affinity"),PersistentDataType.INTEGER,affinity);
  item.setItemMeta(meta);return item;
 }
 /** /아이템관리 마력코어 주기: 미감정 마력코어 한 개. */
 ItemStack unappraisedCore(){return make();}
 /** /코어감정: 손에 든 마력코어 감정 창. */
 private void open(Player player){
  if(!player.hasPermission("magiccodex.core.use")||player.isDead())return;
  if(!player.getListeningPluginChannels().contains(AppraisalProtocol.RESPONSE)){CmdKit.notify(player,"warning","appraisal-mod","감정 창을 열 수 없어요","차카데미 모드를 업데이트해 주세요",3000);return;}
  long now=System.currentTimeMillis();if(now<next.getOrDefault(player.getUniqueId(),0L))return;next.put(player.getUniqueId(),now+500);
  int slot=player.getInventory().getHeldItemSlot();ItemStack oldItem=player.getInventory().getItem(slot);ItemStack item=plugin.coreAppearance(oldItem);if(item!=oldItem)player.getInventory().setItem(slot,item);
  if(!core(item)||item.getAmount()!=1){CmdKit.notify(player,"warning","appraisal-open","감정할 수 없어요","마력코어 한 개를 주 손에 들고 사용해 주세요",1500);return;}
  int state=value(item,"core_state",0);double cost=state==0?config.getDouble("appraisal-cost"):0;
  long token=ThreadLocalRandom.current().nextLong(1,Long.MAX_VALUE);offers.put(player.getUniqueId(),new Offer(token,slot,item.clone(),now+60000,cost));
  send(player,new Response(token,state,value(item,"core_nodes",0),false,false,cost,balance(player),0,state==2?"파괴된 코어는 사용할 수 없습니다.":""));
 }
 @Override public void onPluginMessageReceived(String channel,Player p,byte[] bytes){
  if(!channel.equals(AppraisalProtocol.REQUEST))return;Request request;try{request=AppraisalProtocol.decodeRequest(bytes);}catch(IllegalArgumentException e){return;}
  Offer o=offers.get(p.getUniqueId());if(o==null||o.token()!=request.token())return;offers.remove(p.getUniqueId());
  ItemStack held=p.getInventory().getItem(o.slot());
  if(p.isDead()||!p.hasPermission("magiccodex.core.use")||System.currentTimeMillis()>o.expires()||p.getInventory().getHeldItemSlot()!=o.slot()||!o.item().equals(held)){deny(p,o,"아이템이 변경됐거나 요청이 만료됐습니다.");return;}
  int state=value(held,"core_state",0);if(state!=0){deny(p,o,state==2?"파괴된 코어는 사용할 수 없습니다.":"이미 감정된 코어입니다.");return;}
  ItemStack result;int nodes=0,newState=0;
  {double[] chances=new double[10];for(int i=0;i<10;i++)chances[i]=config.getDouble("formation."+(i+1));var r=AppraisalRoll.roll(chances,config.getDouble("break-chance"),()->ThreadLocalRandom.current().nextDouble());nodes=r.nodes();newState=r.broken()?2:1;}
  result=update(held,newState,nodes);
  if(newState==1){var m=result.getItemMeta();m.getPersistentDataContainer().set(key("core_affinity"),PersistentDataType.INTEGER,AppraisalRoll.affinity(affinityWeights(config),()->ThreadLocalRandom.current().nextDouble()));result.setItemMeta(m);}
  if(!withdraw(p,o.cost())){deny(p,o,"돈이 부족합니다.");return;}
  // Entire transaction executes on the main thread. Commit before animation, never trust client outcome.
  p.getInventory().setItem(o.slot(),result);p.saveData();
  AuditLog.log(p,AuditLog.감정,"코어 감정 · "+(newState==2?"파괴":"성공 · 마력 노드 "+nodes+"개")+" · 비용 "+o.cost(),"결과",newState==2?"파괴":"감정","노드",nodes,"비용",o.cost(),"아이템",AuditLog.item(held),"칸",o.slot());
  send(p,new Response(o.token(),newState,nodes,true,false,o.cost(),balance(p),0,""));
 }
 private void deny(Player p,Offer o,String text){send(p,new Response(o.token(),value(o.item(),"core_state",0),value(o.item(),"core_nodes",0),false,false,o.cost(),balance(p),0,text));}
 private void send(Player p,Response r){var chances=new ArrayList<Double>();for(int i=1;i<=10;i++)chances.add(config.getDouble("formation."+i));r=new Response(r.token(),r.state(),r.nodes(),r.result(),r.repaired(),r.cost(),r.balance(),r.materials(),r.message(),chances);if(p.getListeningPluginChannels().contains(AppraisalProtocol.RESPONSE))p.sendPluginMessage(plugin,AppraisalProtocol.RESPONSE,AppraisalProtocol.encodeResponse(r));}
 private record Economy(Class<?> type,Object provider){}
 @SuppressWarnings({"rawtypes","unchecked"}) private Economy economy(){for(Class type:Bukkit.getServicesManager().getKnownServices())if(type.getName().equals("net.milkbowl.vault.economy.Economy")){var r=Bukkit.getServicesManager().getRegistration(type);if(r!=null)return new Economy(type,r.getProvider());}return null;}
 private double balance(Player p){try{var e=economy();if(e==null)return -1;double b=((Number)e.type().getMethod("getBalance",OfflinePlayer.class).invoke(e.provider(),p)).doubleValue();return Double.isFinite(b)&&b>=0?b:-1;}catch(ReflectiveOperationException e){return -1;}}
 private boolean withdraw(Player p,double cost){if(cost==0)return true;try{var e=economy();if(e==null)return false;Object r=e.type().getMethod("withdrawPlayer",OfflinePlayer.class,double.class).invoke(e.provider(),p,cost);return (boolean)r.getClass().getMethod("transactionSuccess").invoke(r);}catch(ReflectiveOperationException e){plugin.getLogger().warning("코어 감정 Vault 결제 실패: "+e.getClass().getSimpleName());return false;}}
 @EventHandler public void quit(PlayerQuitEvent e){offers.remove(e.getPlayer().getUniqueId());next.remove(e.getPlayer().getUniqueId());}
 public void close(){offers.clear();next.clear();}
}
