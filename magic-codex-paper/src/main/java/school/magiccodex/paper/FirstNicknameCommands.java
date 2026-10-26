package school.magiccodex.paper;
import java.util.*;
/** Server-owned configuration only; no command text comes from a client. */
final class FirstNicknameCommands {
    static final List<String> DEFAULT=List.of("대화관리 재생 {player} 대화 ch1-2");
    record Plan(String dialogue,List<String> after) {}
    static Plan plan(List<String> configured,String player,UUID uuid,String nickname){
        String dialogue=null;List<String> after=new ArrayList<>();
        for(String raw:configured==null?DEFAULT:configured){
            String c=raw.replace("{player}",player).replace("{uuid}",uuid.toString()).replace("{nickname}",nickname).strip();
            if(c.startsWith("/"))c=c.substring(1).strip();
            if(c.isBlank())continue;
            String[] words=c.split("\\s+");String id=null;
            if(words[0].equals("대화관리")&&words.length>=2&&words[1].equals("재생")){
                // 2026-10-20 명령어 정리: 대화관리 재생 <플레이어> 대화 <id>
                if(words.length!=5||!words[2].equals(player)||!words[3].equals("대화"))throw new IllegalArgumentException("대화관리 재생 syntax");
                id=words[4];
            }else if(words[0].equalsIgnoreCase("storydialogue")){ // 예전 운영 설정 호환 (명령은 없어졌지만 설정 값으로는 읽음)
                if((words.length!=3&&words.length!=4)||!words[1].equals(player)||(words.length==4&&!words[3].equalsIgnoreCase("replay")))throw new IllegalArgumentException("storydialogue syntax");
                id=words[2];
            }else if(words[0].equalsIgnoreCase("story")&&words.length>=2&&words[1].equalsIgnoreCase("dialogue")){
                if(words.length!=3)throw new IllegalArgumentException("story dialogue syntax");
                id=words[2];
            }
            if(id!=null){
                if(dialogue!=null||!id.matches("[a-z0-9_-]{1,64}"))throw new IllegalArgumentException("dialogue id");
                dialogue=id;
            }else after.add(c);
        }
        return new Plan(dialogue,List.copyOf(after));
    }
}
