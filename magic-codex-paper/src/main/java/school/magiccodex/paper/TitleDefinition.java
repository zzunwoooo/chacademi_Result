package school.magiccodex.paper;

import java.util.*;
import school.magiccodex.protocol.TitleProtocol;

record TitleDefinition(String id,int side,String name,int color,boolean enabled,boolean initial){
    TitleDefinition{
        if(!TitleProtocol.id(id)||side<0||side>1||name==null||name.isBlank()||name.length()>48||name.codePoints().anyMatch(c->Character.isISOControl(c)||c=='§'||c=='&'||c=='%'||c=='<'||c=='>')||color<0||color>0xffffff)throw new IllegalArgumentException("칭호 설정 확인: "+id);
    }
    static String composed(String prefix,String nickname,String suffix){return String.join(" ",List.of(prefix,suffix,nickname).stream().filter(v->!v.isBlank()).toList());}
}
