package school.magiccodex.client.api;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * {@link ExternalDialogue#show} 가 받은 Map 을 검사해서 굳힌 값. Minecraft 타입이 없어 단위 테스트가 된다.
 * 글자 수 한도는 서버 대화(DialogueProtocol.Response)와 같다.
 */
public record ExternalFrame(String session,int sequence,String title,String speaker,String text,String portrait,
                            Path portraitFile,boolean playerPortrait,List<Choice> choices,String message,
                            boolean closable,boolean last,List<Path> preload) {
    public record Choice(String id,String text){}
    /** 지금 열려 있는 세션에 비추어 이 프레임이 무엇인지. */
    public enum Step{OPEN,UPDATE,REPEAT,STALE}

    public static final int MAX_CHOICES=6,MAX_PRELOAD=6;
    private static final Pattern SESSION=Pattern.compile("[A-Za-z0-9_.:-]{1,80}");
    private static final Pattern CHOICE=Pattern.compile("[a-z0-9_-]{1,48}");
    private static final Pattern PORTRAIT=Pattern.compile("[a-z0-9_/-]{1,120}");

    /** @throws IllegalArgumentException 필수 값이 없거나 형식이 틀리면 (이유를 메시지에) */
    public static ExternalFrame parse(Map<String,?> m){
        if(m==null)throw new IllegalArgumentException("frame 이 null");
        if(!(m.get("session") instanceof String session)||!SESSION.matcher(session).matches())throw new IllegalArgumentException("session 형식 오류");
        if(!(m.get("sequence") instanceof Number n)||n.longValue()<0||n.longValue()>Integer.MAX_VALUE)throw new IllegalArgumentException("sequence 는 0 이상의 정수");
        var choices=new ArrayList<Choice>();var ids=new HashSet<String>();
        Object raw=m.get("choices");
        if(raw!=null){
            if(!(raw instanceof List<?> list))throw new IllegalArgumentException("choices 는 List");
            if(list.size()>MAX_CHOICES)throw new IllegalArgumentException("선택지는 "+MAX_CHOICES+"개까지");
            for(Object o:list){
                Object id,label;
                if(o instanceof List<?> pair&&pair.size()>=2){id=pair.get(0);label=pair.get(1);}
                else if(o instanceof Object[] pair&&pair.length>=2){id=pair[0];label=pair[1];}
                else if(o instanceof Map.Entry<?,?> pair){id=pair.getKey();label=pair.getValue();}
                else throw new IllegalArgumentException("선택지는 [id, 글]");
                if(!(id instanceof String cid)||!CHOICE.matcher(cid).matches()||!ids.add(cid))throw new IllegalArgumentException("선택지 id 형식 오류 또는 중복: "+id);
                String t=cut(text(label),140);
                choices.add(new Choice(cid,t.isBlank()?"…":t));
            }
        }
        String portrait=text(m.get("portrait"));
        var preload=new ArrayList<Path>();
        if(m.get("preload") instanceof List<?> list)for(Object o:list){Path p=png(o);if(p!=null&&preload.size()<MAX_PRELOAD&&!preload.contains(p))preload.add(p);}
        return new ExternalFrame(session,n.intValue(),cut(text(m.get("title")),140),cut(text(m.get("speaker")),64),cut(text(m.get("text")),1600),
                PORTRAIT.matcher(portrait).matches()?portrait:"",png(m.get("portraitFile")),flag(m.get("playerPortrait"),false),
                List.copyOf(choices),cut(text(m.get("message")),200),flag(m.get("closable"),true),flag(m.get("last"),false),List.copyOf(preload));
    }

    /** 세션·순번 규칙: 다른 세션이면 새로 열기, 같은 세션이면 순번이 커야 바뀌고 같으면 다시 보이기, 작으면 버림. */
    public static Step step(String activeSession,int activeSequence,ExternalFrame next){
        if(activeSession==null||!activeSession.equals(next.session()))return Step.OPEN;
        return next.sequence()>activeSequence?Step.UPDATE:next.sequence()==activeSequence?Step.REPEAT:Step.STALE;
    }

    private static String text(Object o){return o instanceof String s?s:"";}
    private static boolean flag(Object o,boolean fallback){return o instanceof Boolean b?b:fallback;}
    /** UTF-16 길이로 자르되 짝(surrogate)을 반으로 가르지 않는다. */
    static String cut(String s,int max){
        if(s.length()<=max)return s;
        int end=Character.isHighSurrogate(s.charAt(max-1))?max-1:max;
        return s.substring(0,end);
    }
    /** PNG 파일 경로만. 파일을 건드리지 않는다 (있는지·크기는 읽는 스레드가 본다). */
    private static Path png(Object o){
        Path p;
        if(o instanceof Path path)p=path;
        else if(o instanceof String s&&!s.isBlank()){try{p=Path.of(s);}catch(InvalidPathException e){return null;}}
        else return null;
        Path name=p.getFileName();
        if(name==null||!name.toString().toLowerCase(Locale.ROOT).endsWith(".png"))return null;
        return p.toAbsolutePath().normalize();
    }
}
