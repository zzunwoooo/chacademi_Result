package school.magiccodex.paper;

import java.util.*;
import org.bukkit.*;
import org.bukkit.attribute.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.messaging.PluginMessageListener;
import school.magiccodex.protocol.EquipmentProtocol;
import school.magiccodex.protocol.EquipmentProtocol.*;

/** Server-owned equipment. Vanilla armor stays in vanilla slots; accessories live in player PDC. */
final class EquipmentBridge implements PluginMessageListener,Listener,AutoCloseable {
    private static final String[] TYPES={"artifact","ring","earring","necklace"};
    /** 장신구 칸 한글 이름 (TYPES와 같은 순서). */
    static final List<String> SLOT_NAMES=List.of("아티팩트","반지","귀걸이","목걸이");
    private final MagicCodexBridge plugin;
    private final ManaService mana;
    private final NamespacedKey type,hpModifier;
    private final NamespacedKey[] bonuses=new NamespacedKey[5],slots=new NamespacedKey[4];
    private final Map<UUID,Session> sessions=new HashMap<>();
    private final Map<UUID,Long> limits=new HashMap<>();
    private final Map<UUID,double[]> totals=new HashMap<>();
    private long revision;
    private final Map<UUID,ItemStack> hands=new HashMap<>();
    void refresh(Player p){apply(p);}
    private record Session(long revision,long until,ItemStack[] equipped,Map<Integer,ItemStack> inventory){}
    EquipmentBridge(MagicCodexBridge plugin,ManaService mana){
        this.plugin=plugin;this.mana=mana;type=new NamespacedKey(plugin,"accessory_type");hpModifier=new NamespacedKey(plugin,"equipment_health");
        for(int i=0;i<5;i++)bonuses[i]=new NamespacedKey(plugin,"equipment_bonus_"+i);
        for(int i=0;i<4;i++)slots[i]=new NamespacedKey(plugin,"accessory_slot_"+i);
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin,EquipmentProtocol.REQUEST,this);
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin,EquipmentProtocol.RESPONSE);
        Bukkit.getPluginManager().registerEvents(this,plugin);
        CmdKit.register(plugin,"/아이템관리 장비·/유저관리 장신구",this::commands);
        for(Player p:Bukkit.getOnlinePlayers())apply(p);
        Bukkit.getScheduler().runTaskTimer(plugin,()->{for(Player p:Bukkit.getOnlinePlayers())if(!Objects.equals(hands.get(p.getUniqueId()),p.getInventory().getItemInMainHand()))apply(p);},10,10);
        Bukkit.getScheduler().runTaskTimer(plugin,()->{long now=System.currentTimeMillis();sessions.values().removeIf(s->s.until<now);limits.values().removeIf(t->t<now);},200,200);
    }
    Double power(UUID id,Double base){double extra=totals.getOrDefault(id,new double[5])[0];if(base!=null)return base+extra;if(extra==0)return null;return extra;}
    /** 정보창(StatsBridge): 장신구 칸 i (0 아티팩트 · 1 반지 · 2 귀걸이 · 3 목걸이). 비어 있으면 null. */
    ItemStack accessoryItem(Player p,int i){return accessory(p,i);}
    private ItemStack accessory(Player p,int i){byte[] b=p.getPersistentDataContainer().get(slots[i],PersistentDataType.BYTE_ARRAY);if(b==null)return null;return ItemStack.deserializeBytes(b);}
    private void accessory(Player p,int i,ItemStack item){if(empty(item))p.getPersistentDataContainer().remove(slots[i]);else p.getPersistentDataContainer().set(slots[i],PersistentDataType.BYTE_ARRAY,item.serializeAsBytes());}
    private static boolean empty(ItemStack i){return i==null||i.getType().isAir()||i.getAmount()<=0;}
    private ItemStack[] equipped(Player p){var inv=p.getInventory();return new ItemStack[]{inv.getHelmet(),inv.getChestplate(),inv.getLeggings(),inv.getBoots(),accessory(p,0),accessory(p,1),accessory(p,2),accessory(p,3)};}
    private void set(Player p,int slot,ItemStack item){var inv=p.getInventory();switch(slot){case 0->inv.setHelmet(item);case 1->inv.setChestplate(item);case 2->inv.setLeggings(item);case 3->inv.setBoots(item);default->accessory(p,slot-4,item);}}
    private int slot(ItemStack item){
        if(empty(item))return -1;
        String tag=item.getItemMeta().getPersistentDataContainer().get(type,PersistentDataType.STRING);
        if(tag!=null){for(int i=0;i<4;i++)if(TYPES[i].equals(tag))return i+4;return -1;}
        return switch(item.getType().getEquipmentSlot()){case HEAD->0;case CHEST->1;case LEGS->2;case FEET->3;default->-1;};
    }
    private double[] bonuses(ItemStack item){double[] values=new double[5];if(empty(item)||slot(item)<4)return values;
        for(int i=0;i<5;i++){Double v=item.getItemMeta().getPersistentDataContainer().get(bonuses[i],PersistentDataType.DOUBLE);if(v!=null&&Double.isFinite(v)&&v>=0&&v<=(i==1?200:100000))values[i]=v;}return values;
    }
    private void apply(Player p){
        double[] sum=new double[5];boolean ready=plugin.playerStateReady(p);
        // 서버 간 공유 상태를 적용하기 전의 로컬 장신구(PDC)는 다른 서버에서 이미 뺀 예전 값일 수 있다: 능력치에 넣지 않는다 (적용 직후 refresh가 다시 계산).
        if(ready)for(int i=0;i<4;i++){ItemStack item=accessory(p,i);if(!empty(item)&&slot(item)==i+4){double[] b=bonuses(item);for(int j=0;j<5;j++)sum[j]+=b[j];}}
        ItemStack hand=p.getInventory().getItemInMainHand();hands.put(p.getUniqueId(),hand.clone());
        if(!empty(hand)&&hand.getItemMeta().getPersistentDataContainer().has(new NamespacedKey(plugin,"wand_id"),PersistentDataType.STRING)){
            String[] keys={"wand_power","wand_mana","wand_haste"};int[] indexes={0,2,4};
            for(int i=0;i<3;i++){double v=hand.getItemMeta().getPersistentDataContainer().getOrDefault(new NamespacedKey(plugin,keys[i]),PersistentDataType.DOUBLE,0d);if(Double.isFinite(v)&&v>=0&&v<=100000)sum[indexes[i]]+=v;}
        }
        totals.put(p.getUniqueId(),sum);
        // 준비 전에는 최대 체력 보정과 현재 체력을 건드리지 않는다: 여기서 체력을 기본 최대치로 깎으면 잠시 뒤 보정이 돌아와도 복구되지 않는다.
        // 준비되면(PlayerStateBridge가 ready 표시 직후 refresh 호출) 공유 상태 기준으로 다시 계산하므로 예전 보정이 남지 않는다. 준비 실패는 강제 퇴장이다.
        var health=ready?p.getAttribute(Attribute.MAX_HEALTH):null;if(health!=null){health.removeModifier(hpModifier);if(sum[1]>0)health.addTransientModifier(new AttributeModifier(hpModifier,sum[1],AttributeModifier.Operation.ADD_NUMBER));if(p.getHealth()>health.getValue())p.setHealth(health.getValue());}
        if(mana.snapshot(p.getUniqueId()).isPresent()){mana.setModifier(p.getUniqueId(),"magiccodex:equipment",sum[2],sum[3]);mana.setHasteModifier(p.getUniqueId(),"magiccodex:equipment",sum[4]);}
    }
    @Override public void onPluginMessageReceived(String channel,Player p,byte[] bytes){
        if(!channel.equals(EquipmentProtocol.REQUEST)||!p.getListeningPluginChannels().contains(EquipmentProtocol.RESPONSE))return;
        Request r;try{r=EquipmentProtocol.request(bytes);}catch(IllegalArgumentException e){return;}
        if(r.action()==EquipmentProtocol.CLOSE){sessions.remove(p.getUniqueId());return;}
        long now=System.currentTimeMillis();if(now<limits.getOrDefault(p.getUniqueId(),0L))return;limits.put(p.getUniqueId(),now+100);
        if(!plugin.playerStateReady(p)){send(p,r.sequence(),"캐릭터 정보를 불러오는 중입니다.");return;}
        if(!p.hasPermission("magiccodex.equipment")||p.isDead()||p.getGameMode()==GameMode.SPECTATOR){send(p,r.sequence(),"지금은 장비를 변경할 수 없습니다.");return;}
        if(r.action()==EquipmentProtocol.VIEW){send(p,r.sequence(),"");return;}
        // 전투 스펙 2026-10-26 장신구 강화: 강화가 도는 동안에는 손에 든 장신구·마법봉을 장착할 수 없다 (마법봉 강화와 같은 규칙).
        if(plugin.enhancing(p)){send(p,r.sequence(),"강화를 먼저 끝내 주세요.");return;}
        var s=sessions.get(p.getUniqueId());
        if(s==null||s.until<now||s.revision!=r.revision()||!Arrays.equals(s.equipped,equipped(p))){send(p,r.sequence(),"장비 정보가 바뀌었습니다. 다시 선택해 주세요.");return;}
        // Never modify inventory while a chest/trade/container or vanilla cursor owns an item.
        if(!equipmentInventoryReady(p)){send(p,r.sequence(),"다른 보관함을 닫은 뒤 다시 시도해 주세요.");return;}
        ItemStack old=s.equipped[r.slot()];
        if(r.slot()<4&&!empty(old)&&old.containsEnchantment(Enchantment.BINDING_CURSE)&&p.getGameMode()!=GameMode.CREATIVE){send(p,r.sequence(),"귀속 저주가 있는 장비는 해제할 수 없습니다.");return;}
        String notice;
        if(r.action()==EquipmentProtocol.REMOVE){
            int free=p.getInventory().firstEmpty();
            if(empty(old))notice="장착된 장비가 없습니다.";
            else if(free<0)notice="인벤토리에 빈 칸이 필요합니다.";
            else {set(p,r.slot(),null);p.getInventory().setItem(free,old);notice="장비를 해제했습니다.";}
        }else{
            ItemStack current=p.getInventory().getItem(r.inventory()),expected=s.inventory.get(r.inventory());
            if(expected==null||!expected.equals(current)||slot(current)!=r.slot()){send(p,r.sequence(),"아이템이 변경되었습니다. 다시 선택해 주세요.");return;}
            // Full metadata is retained in storage; only the preview is size-limited.
            if(current.serializeAsBytes().length>32768){send(p,r.sequence(),"데이터가 너무 큰 장비는 장착할 수 없습니다.");return;}
            int free=current.getAmount()==1?r.inventory():p.getInventory().firstEmpty();
            if(!empty(old)&&free<0){send(p,r.sequence(),"기존 장비를 돌려받을 빈 칸이 필요합니다.");return;}
            ItemStack next=current.clone();next.setAmount(1);ItemStack rest=current.clone();rest.setAmount(current.getAmount()-1);
            set(p,r.slot(),next);p.getInventory().setItem(r.inventory(),rest.getAmount()==0?null:rest);
            if(!empty(old))p.getInventory().setItem(free,old);
            notice="장비를 장착했습니다.";
        }
        apply(p);plugin.savePlayerState(p);p.updateInventory();send(p,r.sequence(),notice);
    }
    // CraftInventoryView reports CREATIVE for a creative player's personal CRAFTING inventory.
    // Inspect the actual top inventory: real containers/trades and held cursor items stay blocked.
    private static boolean equipmentInventoryReady(Player p){
        return p.getOpenInventory().getTopInventory().getType()==org.bukkit.event.inventory.InventoryType.CRAFTING
                && empty(p.getItemOnCursor());
    }
    private static byte[] preview(ItemStack item){if(empty(item))return new byte[0];byte[] b=item.serializeAsBytes();if(b.length>512){var simple=new ItemStack(item.getType());var meta=simple.getItemMeta();meta.displayName(item.displayName());simple.setItemMeta(meta);b=simple.serializeAsBytes();if(b.length>512)b=new ItemStack(item.getType()).serializeAsBytes();}return b.length>512?new byte[0]:b;}
    private void send(Player p,long seq,String notice){
        ItemStack[] eq=equipped(p);Map<Integer,ItemStack> inventory=new HashMap<>();List<Item> candidates=new ArrayList<>();
        for(int i=0;i<36;i++){ItemStack item=p.getInventory().getItem(i);int slot=slot(item);if(slot<0)continue;inventory.put(i,item.clone());candidates.add(new Item(i,slot,preview(item)));}
        ItemStack[] saved=Arrays.stream(eq).map(i->i==null?null:i.clone()).toArray(ItemStack[]::new);long rev=++revision;
        sessions.put(p.getUniqueId(),new Session(rev,System.currentTimeMillis()+30000,saved,inventory));
        double[] b=totals.getOrDefault(p.getUniqueId(),new double[5]);
        var response=new Response(seq,rev,Math.clamp(plugin.circle(p),1,9),plugin.names().name(p),notice,b[0],b[1],b[2],b[3],Arrays.stream(eq).map(EquipmentBridge::preview).toList(),candidates,b[4]);
        p.sendPluginMessage(plugin,EquipmentProtocol.RESPONSE,EquipmentProtocol.response(response));
    }
    private static final String[] BONUS_NAMES={"지능","체력","최대마나","마나회복","마법가속"};
    private void commands(){
        var kind=CmdKit.word("종류",()->SLOT_NAMES);
        var last=CmdKit.argument(BONUS_NAMES[3],com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(0,100000)).executes(CmdKit.run(ctx->registerHeld(ctx,false)))
                .then(CmdKit.argument(BONUS_NAMES[4],com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(0,100000)).executes(CmdKit.run(ctx->registerHeld(ctx,true))));
        kind.then(CmdKit.argument(BONUS_NAMES[0],com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(0,100000))
                .then(CmdKit.argument(BONUS_NAMES[1],com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(0,200))
                        .then(CmdKit.argument(BONUS_NAMES[2],com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(0,100000)).then(last))));
        // 전투 스펙 2026-10-26: /아이템관리 장비 등급등록 <칸> <등급> — enhancement.yml accessory-grades 수치로 등록 (반지·귀걸이·목걸이).
        var grade=CmdKit.word("칸",()->SLOT_NAMES.subList(1,4)).then(CmdKit.word("등급",()->{var e=plugin.enhancementBridge();return e==null?List.<String>of():e.accessoryGradeNames();}).executes(CmdKit.run(this::registerGrade)));
        ChacaApi.branch(plugin,ChacaApi.아이템관리,CmdKit.literal("장비").then(CmdKit.literal("등록").then(kind)).then(CmdKit.literal("등급등록").then(grade)),"손에 든 아이템을 장신구로 등록 (능력치 직접·등급별)","장비 등급등록 반지 고급");
        var slot=CmdKit.word("칸",()->SLOT_NAMES);
        var user=CmdKit.literal("장신구")
                .then(CmdKit.literal("보기").executes(CmdKit.run(this::show)))
                .then(CmdKit.literal("돌려주기").then(CmdKit.word("칸",()->SLOT_NAMES).executes(CmdKit.run(ctx->takeOut(ctx,true)))))
                .then(CmdKit.literal("비우기").then(CmdKit.confirm(slot,ctx->takeOut(ctx,false))));
        ChacaApi.branch(plugin,ChacaApi.유저관리,user,"장신구 칸 보기·인벤토리로 돌려주기·비우기","장신구 보기");
    }
    private void registerHeld(com.mojang.brigadier.context.CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> ctx,boolean haste)throws com.mojang.brigadier.exceptions.CommandSyntaxException{
        Player p=CmdKit.self(ctx);int index=SLOT_NAMES.indexOf(CmdKit.text(ctx,"종류"));if(index<0)throw ChacaApi.error("종류는 아티팩트·반지·귀걸이·목걸이 중 하나예요");
        ItemStack item=p.getInventory().getItemInMainHand();if(empty(item))throw ChacaApi.error("등록할 아이템을 주 손에 들어 주세요");
        double[] v=new double[5];for(int i=0;i<4;i++)v[i]=com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx,BONUS_NAMES[i]);v[4]=haste?com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx,BONUS_NAMES[4]):0;
        store(item,index,v);
        CmdKit.ok(CmdKit.sender(ctx),"손에 든 아이템을 "+SLOT_NAMES.get(index)+" 장비로 등록했어요");
    }
    /** 장신구 칸·능력치를 적고, 강화 원본 수치·강화 줄을 새로 적는다 (강화 기록은 그대로). */
    private void store(ItemStack item,int index,double[] v){
        var meta=item.getItemMeta();meta.getPersistentDataContainer().set(type,PersistentDataType.STRING,TYPES[index]);for(int i=0;i<5;i++)meta.getPersistentDataContainer().set(bonuses[i],PersistentDataType.DOUBLE,v[i]);item.setItemMeta(meta);
        var e=plugin.enhancementBridge();if(e!=null)e.accessoryRegistered(item);
    }
    private void registerGrade(com.mojang.brigadier.context.CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> ctx)throws com.mojang.brigadier.exceptions.CommandSyntaxException{
        Player p=CmdKit.self(ctx);int index=SLOT_NAMES.indexOf(CmdKit.text(ctx,"칸"));if(index<1)throw ChacaApi.error("칸은 반지·귀걸이·목걸이 중 하나예요 (아티팩트는 아티팩트 도감 수치를 씀)");
        String g=CmdKit.text(ctx,"등급");var e=plugin.enhancementBridge();double[] v=e==null?null:e.accessoryGrade(g);
        if(v==null)throw ChacaApi.error("없는 장신구 등급이에요. enhancement.yml accessory-grades: "+(e==null?"":String.join("·",e.accessoryGradeNames())));
        ItemStack item=p.getInventory().getItemInMainHand();if(empty(item)||item.getAmount()!=1)throw ChacaApi.error("등록할 아이템 한 개를 주 손에 들어 주세요");
        if(item.getItemMeta().getPersistentDataContainer().has(new NamespacedKey(plugin,"wand_id"),PersistentDataType.STRING))throw ChacaApi.error("마법봉은 장신구로 등록할 수 없어요");
        if(item.getItemMeta().getPersistentDataContainer().getOrDefault(new NamespacedKey(plugin,"enhance_successes"),PersistentDataType.INTEGER,0)>0)throw ChacaApi.error("이미 강화한 장신구는 등급을 다시 정할 수 없어요");
        store(item,index,v);p.saveData();
        CmdKit.ok(CmdKit.sender(ctx),"손에 든 아이템을 "+g+" "+SLOT_NAMES.get(index)+"(으)로 등록했어요: 지능 "+AdminCommandRules.number(v[0])+" · 체력 "+AdminCommandRules.number(v[1])+" · 최대마나 "+AdminCommandRules.number(v[2])+" · 마나회복 "+AdminCommandRules.number(v[3]));
    }
    private static String itemName(ItemStack item){return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(item.displayName());}
    private void show(com.mojang.brigadier.context.CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> ctx)throws com.mojang.brigadier.exceptions.CommandSyntaxException{
        Player p=ChacaApi.targetOnline(ctx);if(!plugin.playerStateReady(p))throw ChacaApi.error("캐릭터 정보를 불러오는 중이에요. 잠시 뒤 다시 해 주세요");
        var out=new ArrayList<String>();out.add("── "+plugin.names().name(p)+" 장신구 ──");
        for(int i=0;i<4;i++){ItemStack item=accessory(p,i);out.add(SLOT_NAMES.get(i)+": "+(empty(item)?"(비어 있음)":itemName(item)));}
        double[] t=totals.getOrDefault(p.getUniqueId(),new double[5]);
        out.add("장비 합계: 지능 "+AdminCommandRules.number(t[0])+" · 체력 "+AdminCommandRules.number(t[1])+" · 최대마나 "+AdminCommandRules.number(t[2])+" · 마나회복 "+AdminCommandRules.number(t[3])+" · 마법가속 "+AdminCommandRules.number(t[4]));
        CmdKit.lines(CmdKit.sender(ctx),out);
    }
    /** 장신구 한 칸을 인벤토리로 돌려주거나(giveBack) 지운다. */
    private void takeOut(com.mojang.brigadier.context.CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> ctx,boolean giveBack)throws com.mojang.brigadier.exceptions.CommandSyntaxException{
        Player p=ChacaApi.targetOnline(ctx);if(!plugin.playerStateReady(p))throw ChacaApi.error("캐릭터 정보를 불러오는 중이에요. 잠시 뒤 다시 해 주세요");
        int index=SLOT_NAMES.indexOf(CmdKit.text(ctx,"칸"));if(index<0)throw ChacaApi.error("칸은 아티팩트·반지·귀걸이·목걸이 중 하나예요");
        ItemStack item=accessory(p,index);String who=plugin.names().name(p);
        if(empty(item))throw ChacaApi.error(who+" 님 "+SLOT_NAMES.get(index)+" 칸이 비어 있어요");
        if(giveBack&&p.getInventory().firstEmpty()<0)throw ChacaApi.error(who+" 님 인벤토리에 빈칸이 없어요");
        accessory(p,index,null);if(giveBack)p.getInventory().addItem(item);
        sessions.remove(p.getUniqueId());apply(p);p.saveData();plugin.savePlayerState(p);
        plugin.getLogger().info("EQUIPMENT ADMIN "+(giveBack?"return":"clear")+" admin="+CmdKit.sender(ctx).getName()+" player="+p.getUniqueId()+" slot="+TYPES[index]+" item="+itemName(item));
        CmdKit.ok(CmdKit.sender(ctx),who+" 님 "+SLOT_NAMES.get(index)+" 칸 "+itemName(item)+"을(를) "+(giveBack?"인벤토리로 돌려주었어요":"비웠어요"));
    }
    @EventHandler(priority=EventPriority.MONITOR) public void join(PlayerJoinEvent e){apply(e.getPlayer());}
    @EventHandler public void quit(PlayerQuitEvent e){sessions.remove(e.getPlayer().getUniqueId());limits.remove(e.getPlayer().getUniqueId());totals.remove(e.getPlayer().getUniqueId());hands.remove(e.getPlayer().getUniqueId());}
    // 취소된 사망·keepInventory는 건드리지 않는다. 공유 상태 적용 전(!ready)의 로컬 장신구는 예전 값일 수 있어 떨어뜨리면 복사가 된다:
    // 그대로 두면 불러오기가 끝날 때 DB 값으로 덮어쓰인다 (그 짧은 구간의 사망에서는 장신구를 잃지 않는다).
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void death(PlayerDeathEvent e){if(e.getKeepInventory())return;Player p=e.getEntity();if(!plugin.playerStateReady(p)){sessions.remove(p.getUniqueId());return;}for(int i=0;i<4;i++){ItemStack item=accessory(p,i);if(!empty(item)&&!item.containsEnchantment(Enchantment.VANISHING_CURSE))e.getDrops().add(item);accessory(p,i,null);}plugin.savePlayerState(p);sessions.remove(p.getUniqueId());}
    @EventHandler public void respawn(PlayerRespawnEvent e){Bukkit.getScheduler().runTask(plugin,()->{if(e.getPlayer().isOnline())apply(e.getPlayer());});}
    @Override public void close(){for(Player p:Bukkit.getOnlinePlayers()){var hp=p.getAttribute(Attribute.MAX_HEALTH);if(hp!=null){hp.removeModifier(hpModifier);if(p.getHealth()>hp.getValue())p.setHealth(hp.getValue());}if(mana.snapshot(p.getUniqueId()).isPresent()){mana.removeModifier(p.getUniqueId(),"magiccodex:equipment");mana.setHasteModifier(p.getUniqueId(),"magiccodex:equipment",0);}}sessions.clear();totals.clear();limits.clear();hands.clear();}
}
