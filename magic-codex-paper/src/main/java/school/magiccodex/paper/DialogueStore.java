package school.magiccodex.paper;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import school.magiccodex.database.ConnectionHolder;
import school.magiccodex.database.DatabaseSettings;

/**
 * Single-worker JDBC ownership; optimistic revision fences serialize cross-server choices.
 *
 * 외부 동작 기록 (codex_dialogue_effect.status):
 *  queued  : 남은 동작(actions)을 아직 실행하지 않았다. 맨 앞의 quest 동작은 멱등이라 다시 해도 안전 -> 자동으로 이어서 실행
 *  running : command/custom/event 를 실행하기 직전에 기록한다. 실행 뒤 남은 동작으로 갱신한다. 서버가 죽어 이 상태로 남으면 실행 여부를 알 수 없다 -> 관리자 확인
 *  review  : 동작이 실패했다(명령어 없음·예외). actions 맨 앞이 실패한 동작 -> 관리자 확인
 *  done    : 끝
 *  pending : 예전 버전이 남긴 기록(실행 여부 불명) -> 관리자 확인
 */
final class DialogueStore implements AutoCloseable {
    record State(long revision,Map<String,String> values){}
    record Transition(State state,String receipt,List<String> external){}
    record Effect(String key,String token,String status,List<String> actions){
        /** 대화 ID (effect_key = 대화ID:장면:선택지). */
        String dialogue(){int at=key.indexOf(':');return at<0?key:key.substring(0,at);}
    }
    /** signature 는 id=revision 목록이다. 카탈로그 전체를 풀지 않고 바뀌었는지만 싸게 확인하는 데 쓴다. */
    record Snapshot(String signature,Map<String,DialogueDefinition> definitions,Map<String,String> revisions){}
    private final ConnectionHolder holder;
    private Connection db()throws SQLException{return holder.get();}
    DialogueStore(DatabaseSettings settings,Path path)throws Exception{
        holder=settings.holder(path);String tail=settings.mariaDb()?" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4":"";
        try(var s=db().createStatement()){
            s.execute("CREATE TABLE IF NOT EXISTS codex_dialogue_catalog(id VARCHAR(48) PRIMARY KEY,revision VARCHAR(64) NOT NULL,definition TEXT NOT NULL)"+tail);
            s.execute("CREATE TABLE IF NOT EXISTS codex_story_state(player VARCHAR(36) PRIMARY KEY,revision BIGINT NOT NULL,content TEXT NOT NULL)"+tail);
            s.execute("CREATE TABLE IF NOT EXISTS codex_dialogue_effect(player VARCHAR(36) NOT NULL,effect_key VARCHAR(160) NOT NULL,token VARCHAR(36) NOT NULL,status VARCHAR(12) NOT NULL,actions TEXT NOT NULL,PRIMARY KEY(player,effect_key))"+tail);
        }
    }
    Map<String,DialogueDefinition> catalog()throws Exception{var out=new TreeMap<String,DialogueDefinition>();try(var s=db().createStatement();var r=s.executeQuery("SELECT id,definition FROM codex_dialogue_catalog")){while(r.next()){var d=DialogueDefinition.decode(r.getString(1),r.getString(2));out.put(d.id(),d);}}return Map.copyOf(out);}
    /** known 과 같으면(바뀐 것이 없으면) null. 다르면 정의와 저장된 revision 을 함께 읽는다(메인 스레드에서 revision 을 다시 계산하지 않도록). */
    Snapshot snapshot(String known)throws Exception{
        var signature=new StringBuilder();try(var s=db().createStatement();var r=s.executeQuery("SELECT id,revision FROM codex_dialogue_catalog ORDER BY id")){while(r.next())signature.append(r.getString(1)).append('=').append(r.getString(2)).append(';');}
        if(signature.toString().equals(known))return null;
        var out=new TreeMap<String,DialogueDefinition>();var revisions=new TreeMap<String,String>();var actual=new StringBuilder();
        try(var s=db().createStatement();var r=s.executeQuery("SELECT id,revision,definition FROM codex_dialogue_catalog ORDER BY id")){while(r.next()){var d=DialogueDefinition.decode(r.getString(1),r.getString(3));out.put(d.id(),d);revisions.put(d.id(),r.getString(2));actual.append(r.getString(1)).append('=').append(r.getString(2)).append(';');}}
        return new Snapshot(actual.toString(),Map.copyOf(out),Map.copyOf(revisions));
    }
    boolean edit(String id,String expected,DialogueDefinition next)throws Exception{
        String encoded=next==null?"":next.encode();if(encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>26000)throw new IllegalArgumentException("대화 문서가 큽니다. 여러 대화로 나누세요 (26KB)");
        if(expected.isEmpty()){if(next==null)return false;try(var s=db().prepareStatement("INSERT INTO codex_dialogue_catalog(id,revision,definition) VALUES(?,?,?)")){s.setString(1,id);s.setString(2,DialogueDefinition.revision(next));s.setString(3,encoded);try{return s.executeUpdate()==1;}catch(SQLException e){if(constraint(e))return false;throw e;}}}
        String sql=next==null?"DELETE FROM codex_dialogue_catalog WHERE id=? AND revision=?":"UPDATE codex_dialogue_catalog SET revision=?,definition=? WHERE id=? AND revision=?";
        try(var s=db().prepareStatement(sql)){int i=1;if(next!=null){s.setString(i++,DialogueDefinition.revision(next));s.setString(i++,encoded);}s.setString(i++,id);s.setString(i,expected);return s.executeUpdate()==1;}
    }
    void seed(DialogueDefinition d)throws Exception{if(catalog().isEmpty())edit(d.id(),"",d);}
    State state(UUID player)throws SQLException{try(var s=db().prepareStatement("SELECT revision,content FROM codex_story_state WHERE player=?")){s.setString(1,player.toString());try(var r=s.executeQuery()){if(r.next())return new State(r.getLong(1),decode(r.getString(2)));}}
        try(var s=db().prepareStatement("INSERT INTO codex_story_state(player,revision,content) VALUES(?,0,'')")){s.setString(1,player.toString());try{s.executeUpdate();}catch(SQLException e){if(!constraint(e))throw e;}}
        try(var s=db().prepareStatement("SELECT revision,content FROM codex_story_state WHERE player=?")){s.setString(1,player.toString());try(var r=s.executeQuery()){if(!r.next())throw new SQLException("missing state");return new State(r.getLong(1),decode(r.getString(2)));}}
    }
    Transition transition(UUID player,State before,String effectKey,List<String> actions)throws Exception{return transition(player,before,effectKey,actions,false);}
    /** arm=true: 외부 동작 기록을 처음부터 running 으로 만든다(첫 동작이 command/custom/event 일 때, 기록 직후 바로 실행하기 위해). */
    Transition transition(UUID player,State before,String effectKey,List<String> actions,boolean arm)throws Exception{
        var values=new TreeMap<>(before.values);var external=new ArrayList<String>();
        for(String action:actions){String[] a=action.split(" ",3);if(a[0].equals("flag")||a[0].equals("story"))values.put(a[0]+"."+a[1],a[2]);else external.add(action);}
        if(values.size()>512)throw new IllegalArgumentException("스토리 상태 한도 초과");
        String content=encode(values),token="",scope=effectKey.substring(0,effectKey.indexOf(':')+1);holder.begin();
        try{
            try(var s=db().prepareStatement("UPDATE codex_story_state SET revision=revision+1,content=? WHERE player=? AND revision=?")){s.setString(1,content);s.setString(2,player.toString());s.setLong(3,before.revision);if(s.executeUpdate()!=1)throw new IllegalStateException("다른 서버에서 진행이 바뀌었습니다. 대화를 다시 열어 주세요.");}
            // 끝나지 않은 외부 동작이 있는 "같은 대화"만 막는다. 다른 대화는 계속 진행할 수 있다.
            if(!scope.isEmpty())for(Effect e:effects(player))if(e.key.startsWith(scope)&&!e.key.equals(effectKey))throw new IllegalStateException("이 대화의 이전 실행 기록 처리가 끝나지 않았습니다. 대화를 다시 열어 주세요.");
            if(!external.isEmpty()){
                boolean done=false;
                try(var s=db().prepareStatement("SELECT status FROM codex_dialogue_effect WHERE player=? AND effect_key=?")){s.setString(1,player.toString());s.setString(2,effectKey);try(var r=s.executeQuery()){if(r.next()){if(!r.getString(1).equals("done"))throw new IllegalStateException("이 선택지의 이전 실행 기록 처리가 끝나지 않았습니다. 대화를 다시 열어 주세요.");done=true;}}}
                if(done)external.clear();else{token=UUID.randomUUID().toString();try(var s=db().prepareStatement("INSERT INTO codex_dialogue_effect(player,effect_key,token,status,actions) VALUES(?,?,?,?,?)")){s.setString(1,player.toString());s.setString(2,effectKey);s.setString(3,token);s.setString(4,arm?"running":"queued");s.setString(5,String.join("\n",external));s.executeUpdate();}}
            }
            db().commit();return new Transition(new State(before.revision+1,Map.copyOf(values)),token,List.copyOf(external));
        }catch(Exception e){holder.rollback();throw e;}finally{holder.end();}
    }
    void journalEdit(UUID player,String id,String operation)throws Exception{
        if(!id.matches("[a-z0-9_-]{1,64}"))throw new IllegalArgumentException("Story ID");
        String status=switch(operation){case "grant","progress"->"active";case "complete"->"claimed";case "reset"->"hidden";default->throw new IllegalArgumentException("Journal operation");};
        State before=state(player);var values=new TreeMap<>(before.values());values.put("journal."+id,status);if(values.size()>512)throw new IllegalArgumentException("Story state limit");
        try(var q=db().prepareStatement("UPDATE codex_story_state SET revision=revision+1,content=? WHERE player=? AND revision=?")){q.setString(1,encode(values));q.setString(2,player.toString());q.setLong(3,before.revision());if(q.executeUpdate()!=1)throw new IllegalStateException("Story state changed; retry");}
    }
    /** 끝나지 않은 외부 동작 기록 전부 (오래된 것부터 정해진 순서). */
    List<Effect> effects(UUID player)throws SQLException{var out=new ArrayList<Effect>();try(var s=db().prepareStatement("SELECT effect_key,token,status,actions FROM codex_dialogue_effect WHERE player=? AND status<>'done' ORDER BY effect_key")){s.setString(1,player.toString());try(var r=s.executeQuery()){while(r.next())out.add(new Effect(r.getString(1),r.getString(2),r.getString(3),r.getString(4).lines().filter(l->!l.isEmpty()).toList()));}}return out;}
    /** 상태 비교-교체. from 중 하나일 때만 next 로 바꾸며, remaining 이 null 이 아니면 남은 동작도 함께 기록한다. 바뀌었으면 true. */
    boolean move(UUID player,String token,List<String> from,String next,List<String> remaining)throws SQLException{
        if(from.isEmpty()||from.stream().anyMatch(v->!v.matches("[a-z]{1,12}")))throw new IllegalArgumentException("status");
        try(var s=db().prepareStatement("UPDATE codex_dialogue_effect SET status=?"+(remaining==null?"":",actions=?")+" WHERE player=? AND token=? AND status IN ('"+String.join("','",from)+"')")){int i=1;s.setString(i++,next);if(remaining!=null)s.setString(i++,String.join("\n",remaining));s.setString(i++,player.toString());s.setString(i,token);return s.executeUpdate()==1;}
    }
    /** 서버 종료 시: running 으로 기록했지만 실행을 시작하지 못한 것이 확실한 선택을 queued 로 되돌린다. */
    boolean requeue(UUID player,String effectKey)throws SQLException{try(var s=db().prepareStatement("UPDATE codex_dialogue_effect SET status='queued' WHERE player=? AND effect_key=? AND status='running'")){s.setString(1,player.toString());s.setString(2,effectKey);return s.executeUpdate()==1;}}
    /** 실행하지 않고 완료로 닫는다 (관리자 "실행 없이 완료"). */
    void finish(UUID player,String token)throws SQLException{if(!move(player,token,List.of("queued","running","review","pending"),"done",null))throw new SQLException("receipt changed");}
    List<String> audit(UUID player)throws SQLException{var out=new ArrayList<String>();for(Effect e:effects(player))out.add(e.key+" | "+e.token+" | "+e.status+" | "+String.join(" ; ",e.actions));return out;}
    private static boolean constraint(SQLException e){return e.getErrorCode()==19||e.getErrorCode()==1062||"23505".equals(e.getSQLState());}
    private static String encode(Map<String,String> map){return String.join("\n",new TreeMap<>(map).entrySet().stream().map(e->e.getKey()+"="+e.getValue()).toList());}
    private static Map<String,String> decode(String data){var out=new TreeMap<String,String>();data.lines().forEach(l->{int i=l.indexOf('=');if(i>0)out.put(l.substring(0,i),l.substring(i+1));});return Map.copyOf(out);}
    public void close()throws SQLException{holder.close();}
}
