package school.magiccodex.discovery;
import java.io.*;
import java.util.*;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
final class TitleRewards {
    private final MagicDiscovery plugin;private final File file;private YamlConfiguration data;private final NamespacedKey tokenKey;
    private final Set<UUID> busy=new HashSet<>();
    TitleRewards(MagicDiscovery plugin)throws Exception{this.plugin=plugin;file=new File(plugin.getDataFolder(),"titles.yml");data=new YamlConfiguration();if(file.exists())data.load(file);tokenKey=new NamespacedKey(plugin,"reward_token");}
    /** titles.yml 다시 읽기. 읽지 못하면 예외(이전 값 유지). */
    void reload()throws Exception{var next=new YamlConfiguration();if(file.exists())next.load(file);data=next;}
    /** /아이템관리 칭호보상 등록 &lt;마법&gt;: 손에 든 아이템을 그 마법의 최초 발견 칭호 보상으로. */
    void configure(Player p,String id)throws Exception{
        if(!plugin.definitions.spells.containsKey(id))throw ChacaLink.error("마법 발견 목록에 없는 마법이에요: "+id);var item=p.getInventory().getItemInMainHand().clone();if(item.getType().isAir())throw ChacaLink.error("칭호 아이템을 손에 들어 주세요");item.setAmount(1);
        data.set(id,Base64.getEncoder().encodeToString(item.serializeAsBytes()));data.save(file);ChacaLink.ok(p,plugin.definitions.spells.get(id).name()+" 칭호 보상을 등록했어요 (이미 발견한 유저도 /칭호 수령 가능)");
    }
    void claim(Player p,MagicDiscovery.Session s,boolean feedback){
        if(!p.isOnline()||p.isDead()||busy.contains(s.id))return;
        // A crash after reserving never blindly grants a second item. Existing marked items can reconcile safely.
        for(var a:s.acquired.values())if(a.reward()==2){
            boolean found=Arrays.stream(p.getInventory().getContents()).filter(Objects::nonNull).anyMatch(item->item.hasItemMeta()&&Objects.equals(item.getItemMeta().getPersistentDataContainer().get(tokenKey,PersistentDataType.LONG),a.token()));
            if(found)plugin.store.delivered(s.id,a.token());else if(feedback)ChacaLink.notify(p,"warning","title-reward-pending","지급 확인이 필요한 칭호 보상이 있어요","관리자에게 문의해 주세요. 번호 "+a.token(),3000);
        }
        var a=s.acquired.values().stream().filter(v->v.reward()==1&&data.isString(v.spell())).sorted(Comparator.comparingLong(DiscoveryStore.Acquisition::token)).findFirst().orElse(null);
        if(a==null){if(feedback)ChacaLink.notify(p,"system","title-reward-none","지금 받을 수 있는 칭호가 없어요","아직 등록되지 않은 칭호는 받을 권리가 보관돼요",2000);return;}
        if(p.getInventory().firstEmpty()<0){if(feedback)ChacaLink.notify(p,"warning","title-reward-full","인벤토리 한 칸을 비운 뒤 다시 받아 주세요","",2000);return;}
        ItemStack item;try{item=ItemStack.deserializeBytes(Base64.getDecoder().decode(data.getString(a.spell())));}catch(Exception e){plugin.failure(e);return;}
        var meta=item.getItemMeta();meta.getPersistentDataContainer().set(tokenKey,PersistentDataType.LONG,a.token());item.setItemMeta(meta);item.setAmount(1);busy.add(s.id);
        plugin.store.reserve(s.id,a.token()).whenComplete((reserved,error)->plugin.later(()->{
            if(error!=null){busy.remove(s.id);plugin.failure(error);return;}if(!reserved){busy.remove(s.id);return;}
            if(!p.isOnline()||p.isDead()||plugin.sessions.get(s.id)!=s||p.getInventory().firstEmpty()<0){plugin.store.unreserve(s.id,a.token()).whenComplete((ok,e)->plugin.later(()->{busy.remove(s.id);if(e!=null)plugin.failure(e);}));return;}
            s.acquired.put(a.spell(),new DiscoveryStore.Acquisition(a.token(),a.spell(),a.first(),2,a.notified()));
            p.getInventory().addItem(item);var spell=plugin.definitions.spells.get(a.spell());ChacaLink.notify(p,"shop","title-reward:"+a.token(),"최초 발견 칭호 아이템을 받았어요",spell==null?"":spell.name(),0);
            plugin.store.delivered(s.id,a.token()).whenComplete((ok,e)->plugin.later(()->{busy.remove(s.id);if(e!=null){plugin.failure(e);return;}
                var latest=s.acquired.get(a.spell());s.acquired.put(a.spell(),new DiscoveryStore.Acquisition(a.token(),a.spell(),a.first(),3,latest.notified()));claim(p,s,false);
            }));
        }));
    }
    /** /유저관리 &lt;닉네임&gt; 마법발견보상 보기: 지급 확인 대기(예약, reward=2)로 멈춘 보상 목록. */
    void adminReserved(CommandSender sender,UUID id,String name){
        plugin.store.reserved(id).whenComplete((list,error)->plugin.later(()->{
            if(error!=null){ChacaLink.fail(sender,"칭호 보상 정보를 읽지 못했어요",error);return;}
            var out=new ArrayList<String>();
            out.add("── "+name+" 님 지급 확인 대기 칭호 보상 "+list.size()+"건 ──");
            if(list.isEmpty())out.add(" (없음)");
            for(var a:list){var spell=plugin.definitions.spells.get(a.spell());out.add(" 번호 "+a.token()+" · "+(spell==null?a.spell():spell.name())+(a.first()?" (최초 발견)":""));}
            if(!list.isEmpty())out.add(" 복구: 마법발견보상 다시주기 <번호> (다시 받을 수 있게) · 완료처리 <번호> (이미 받은 것으로)");
            ChacaLink.lines(sender,out);
        }));
    }
    /** 관리자: 예약 상태 보상을 수령 가능(다시주기) 또는 지급 완료(완료처리)로 되돌린다. 지급 처리 중에는 중복 지급을 막기 위해 거절한다. */
    void adminResolve(CommandSender sender,UUID id,String name,long token,boolean claimable)throws com.mojang.brigadier.exceptions.CommandSyntaxException{
        if(busy.contains(id))throw ChacaLink.error("지금 지급 처리 중인 유저예요. 잠시 뒤 다시 시도해 주세요");
        busy.add(id);
        plugin.store.resolveReserved(id,token,claimable).whenComplete((changed,error)->plugin.later(()->{
            busy.remove(id);
            if(error!=null){ChacaLink.fail(sender,"칭호 보상 상태를 바꾸지 못했어요",error);return;}
            if(!changed){userFail(sender,"번호 "+token+"인 지급 확인 대기 보상이 없어요");return;}
            var s=plugin.sessions.get(id);
            if(s!=null)for(var a:List.copyOf(s.acquired.values()))if(a.token()==token)s.acquired.put(a.spell(),new DiscoveryStore.Acquisition(a.token(),a.spell(),a.first(),claimable?1:3,a.notified()));
            plugin.getLogger().info("칭호 보상 복구: "+sender.getName()+" → "+name+"("+id+") 번호 "+token+" "+(claimable?"수령 가능으로 되돌림":"지급 완료 처리"));
            ChacaLink.ok(sender,claimable?name+" 님 번호 "+token+" 보상을 다시 받을 수 있게 했어요 (/칭호 수령)":name+" 님 번호 "+token+" 보상을 지급 완료로 처리했어요");
        }));
    }
    /** 비동기 결과에서 유저가 고칠 수 있는 실패 (콘솔 로그 없음). */
    private static void userFail(CommandSender to,String message){
        if(to instanceof Player p)ChacaLink.notify(p,"error","cmd-fail",message,"",0);else to.sendMessage(message);
    }
}
