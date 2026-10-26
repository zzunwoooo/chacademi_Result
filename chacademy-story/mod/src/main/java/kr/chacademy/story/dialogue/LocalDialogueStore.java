package kr.chacademy.story.dialogue;
import java.io.*;
import java.nio.file.*;
import java.util.*;
/**
 * Per-account local progress. Persist before issuing ordinary player commands; no server balance writes.
 * 호감도는 MagicCodex 하나 (서버 값)만 쓴다: 이 파일에는 호감도 값을 저장하지 않는다. 예전 파일의 affinity.* 줄은 읽을 때 무시하고 다음 저장 때 지운다.
 */
public final class LocalDialogueStore {
    private final Path file;
    private Properties data=new Properties();
    public LocalDialogueStore(Path file)throws IOException {
        this.file=file;reload();
    }
    private void reload()throws IOException {
        data=new Properties();
        if(Files.exists(file))try(var reader=Files.newBufferedReader(file)){data.load(reader);}
        for(String key:data.stringPropertyNames())if(!key.startsWith("affinity.")&&!key.startsWith("journal.")&&!"true".equals(data.getProperty(key)))throw new IOException("invalid local progress record");
        for(String key:data.stringPropertyNames())if(key.startsWith("affinity."))
            try{Integer.parseInt(data.getProperty(key));}catch(NumberFormatException e){throw new IOException("invalid local affinity record",e);}
    }
    public boolean done(String id){return "true".equals(data.getProperty("done."+id));}
    public boolean claimEvent(String id,String event)throws IOException {return claim(id,"event."+id+"."+event,List.of());}
    public boolean hasAffinity(String id,String key){return data.containsKey("gain."+id+"."+key);}
    public boolean claimAffinity(String id,String key,List<Dialogue.AffinityChange> changes)throws IOException {return claim(id,"gain."+id+"."+key,changes);}
    private boolean claim(String id,String key,List<Dialogue.AffinityChange> changes)throws IOException {
        reload();if(done(id)||data.containsKey(key))return false;
        // 한 번만 처리했다는 표시만 남긴다 (호감도 값은 서버 MagicCodex 가 정한다)
        Properties next=copy();next.setProperty(key,"true");
        save(next);return true;
    }
    public boolean complete(String id)throws IOException {
        reload();boolean first=!done(id);Properties next=copy();next.setProperty("done."+id,"true");next.setProperty("journal.state."+id,"claimed");save(next);return first;
    }
    public void journalSeen(Dialogue d)throws IOException {
        reload();Properties next=copy();next.setProperty("journal.title."+d.id(),d.title());next.setProperty("journal.description."+d.id(),d.description());
        String key="journal.state."+d.id();if(!next.containsKey(key))next.setProperty(key,done(d.id())?"claimed":"active");save(next);
    }
    public Set<String> journalIds(){var ids=new TreeSet<String>();for(String key:data.stringPropertyNames()){if(key.startsWith("done."))ids.add(key.substring(5));else if(key.startsWith("journal.state."))ids.add(key.substring(14));}return Set.copyOf(ids);}
    public String journalTitle(String id){return data.getProperty("journal.title."+id,id);}
    public String journalDescription(String id){return data.getProperty("journal.description."+id,"");}
    public String journalState(String id){return data.getProperty("journal.state."+id,done(id)?"claimed":"available");}
    public String journalEdit(String id,String operation)throws IOException {
        reload();if(!DialogueParser.ID_PATTERN.matcher(id).matches())throw new IOException("Invalid journal ID");
        if(operation.equals("query"))return journalState(id);
        String value=switch(operation){case "grant","progress"->"active";case "complete"->"claimed";case "reset"->"hidden";default->throw new IOException("Invalid journal operation");};
        Properties next=copy();next.setProperty("journal.state."+id,value);save(next);return "Journal: "+id+" = "+value+" (rewards unchanged)";
    }
    private Properties copy(){Properties next=new Properties();next.putAll(data);next.stringPropertyNames().stream().filter(k->k.startsWith("affinity.")).forEach(next::remove);return next;}
    private void save(Properties next)throws IOException {
        Files.createDirectories(file.getParent());Path tmp=Files.createTempFile(file.getParent(),"dialogue-",".tmp");
        try{try(var writer=Files.newBufferedWriter(tmp)){next.store(writer,"Local dialogue progress; commands are attempts, not confirmed server rewards");}
            Files.move(tmp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);data=next;
        }finally{Files.deleteIfExists(tmp);}
    }
}
