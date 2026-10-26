package school.magiccodex.paper;

import java.io.*;
import java.time.*;
import java.util.*;

record QuestDefinition(String id,String title,String description,String permission,boolean daily,boolean enabled,
                       List<Objective> objectives,String rewardLabel,List<String> commands,List<String> items,
                       String rank,int completionLimit,long opensAt,double money,int housePoints,boolean main,List<String> requires) {
    QuestDefinition(String id,String title,String description,String permission,boolean daily,boolean enabled,List<Objective> objectives,String rewardLabel,List<String> commands,List<String> items,String rank,int completionLimit,long opensAt,double money,int housePoints){this(id,title,description,permission,daily,enabled,objectives,rewardLabel,commands,items,rank,completionLimit,opensAt,money,housePoints,false,List.of());}
    QuestDefinition(String id,String title,String description,String permission,boolean daily,boolean enabled,List<Objective> objectives,String rewardLabel,List<String> commands,List<String> items){this(id,title,description,permission,daily,enabled,objectives,rewardLabel,commands,items,"F",1,0,0,0);}
    boolean available(long now){return enabled&&opensAt<=now;}
    QuestDefinition release(long when,boolean enable){return new QuestDefinition(id,title,description,permission,daily,enable,objectives,rewardLabel,commands,items,rank,completionLimit,when,money,housePoints,main,requires);}
    enum Type { KILL, MYTHIC_KILL, SUBMIT, NPC, BIOME, EVENT }
    record Objective(Type type,String target,int amount,String label,String server,String world) {
        Objective {if(target.isBlank()||target.length()>(type==Type.SUBMIT?16000:160)||amount<1||amount>100000||label.length()>100||server.length()>48||world.length()>64)throw new IllegalArgumentException("의뢰 목표 설정 오류");}
        boolean matches(Type kind,String value,String sid,String wid){return type==kind&&target.equals(value)&&(server.isEmpty()||server.equals(sid))&&(world.isEmpty()||world.equals(wid));}
    }
    QuestDefinition {
        if(!rank.matches("[A-F]")||completionLimit<1||completionLimit>100000||opensAt<0||!Double.isFinite(money)||money<0||money>1_000_000_000||housePoints<0||housePoints>1000000)throw new IllegalArgumentException("랭크/완료 한도/공개 시각/보상 범위 오류");
        if(!id.matches("[a-z0-9_-]{1,48}")||title.isBlank()||title.length()>60||description.length()>500||permission.length()>100||rewardLabel.length()>160||objectives.isEmpty()||objectives.size()>6||commands.size()>8||items.size()>8)throw new IllegalArgumentException("의뢰 설정 범위 오류: "+id);
        requires=List.copyOf(requires);
        if(requires.size()>16||new HashSet<>(requires).size()!=requires.size()||requires.stream().anyMatch(v->!v.matches("[a-z0-9_-]{1,48}")||v.equals(id)))throw new IllegalArgumentException("선행 퀘스트 ID를 확인하세요");
        objectives=List.copyOf(objectives);commands=List.copyOf(commands);items=List.copyOf(items);
        for(String c:commands)if(c.isBlank()||c.length()>300||c.contains("\n")||c.startsWith("/"))throw new IllegalArgumentException("보상 명령어 오류");
        for(String item:items)if(!item.matches("[A-Z_]+:[1-9][0-9]{0,2}"))throw new IllegalArgumentException("보상 아이템은 MATERIAL:수량 형식");
    }
    String cycle(long now){return daily?Instant.ofEpochMilli(now).atZone(ZoneId.of("Asia/Seoul")).toLocalDate().toString():"once";}
    String encode(){try{var b=new ByteArrayOutputStream();var o=new DataOutputStream(b);o.writeUTF(id);o.writeUTF(title);o.writeUTF(description);o.writeUTF(permission);o.writeBoolean(daily);o.writeBoolean(enabled);o.writeInt(objectives.size());for(var g:objectives){o.writeUTF(g.type.name());o.writeUTF(g.target);o.writeInt(g.amount);o.writeUTF(g.label);o.writeUTF(g.server);o.writeUTF(g.world);}o.writeUTF(rewardLabel);for(var list:List.of(commands,items)){o.writeInt(list.size());for(String s:list)o.writeUTF(s);}o.writeUTF(rank);o.writeInt(completionLimit);o.writeLong(opensAt);o.writeDouble(money);o.writeInt(housePoints);if(main||!requires.isEmpty()){o.writeBoolean(main);o.writeInt(requires.size());for(String id:requires)o.writeUTF(id);}return Base64.getEncoder().encodeToString(b.toByteArray());}catch(IOException e){throw new IllegalArgumentException(e);}}
    static QuestDefinition decode(String data){try{var i=new DataInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(data)));String id=i.readUTF(),title=i.readUTF(),desc=i.readUTF(),perm=i.readUTF();boolean daily=i.readBoolean(),enabled=i.readBoolean();int n=i.readInt();if(n<1||n>6)throw new IOException();var goals=new ArrayList<Objective>();for(int j=0;j<n;j++)goals.add(new Objective(Type.valueOf(i.readUTF()),i.readUTF(),i.readInt(),i.readUTF(),i.readUTF(),i.readUTF()));String label=i.readUTF();var lists=new ArrayList<List<String>>();for(int j=0;j<2;j++){int k=i.readInt();if(k<0||k>8)throw new IOException();var l=new ArrayList<String>();for(int t=0;t<k;t++)l.add(i.readUTF());lists.add(l);}String rank="F";int limit=1,housePoints=0;long opens=0;double money=0;if(i.available()>0){rank=i.readUTF();limit=i.readInt();opens=i.readLong();money=i.readDouble();housePoints=i.readInt();}boolean main=false;var requires=new ArrayList<String>();if(i.available()>0){main=i.readBoolean();int count=i.readInt();if(count<0||count>16)throw new IOException();for(int k=0;k<count;k++)requires.add(i.readUTF());}if(i.available()!=0)throw new IOException();return new QuestDefinition(id,title,desc,perm,daily,enabled,goals,label,lists.get(0),lists.get(1),rank,limit,opens,money,housePoints,main,requires);}catch(IOException e){throw new IllegalArgumentException(e);}}
}
