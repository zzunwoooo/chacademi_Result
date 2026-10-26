package school.magiccodex.paper;

import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import school.magiccodex.database.DatabaseSettings;
import school.magiccodex.protocol.TitleProtocol;

class TitleTest {
    @TempDir Path dir;
    static final DatabaseSettings SQLITE=new DatabaseSettings(false,"","","");
    static final Map<String,TitleDefinition> CATALOG=Map.of("prefix_star",new TitleDefinition("prefix_star",0,"별빛을 발견한",0x96e4ec,true,false),"suffix_student",new TitleDefinition("suffix_student",1,"학생",0xdec58e,true,true),"suffix_research",new TitleDefinition("suffix_research",1,"연구가",0xdec58e,true,false));
    static final Set<String> DEFAULTS=Set.of("suffix_student");
    @Test void ownershipBothSidesAndReconnect()throws Exception{var p=UUID.randomUUID();var path=dir.resolve("titles.db");
        try(var s=new TitleStore(SQLITE,path)){var first=s.load(p,DEFAULTS);assertEquals(DEFAULTS,first.owned());assertFalse(s.select(p,first.revision(),"prefix_star","",CATALOG,DEFAULTS).applied());
            var granted=s.grant(p,"prefix_star",DEFAULTS);assertFalse(s.select(p,first.revision(),"prefix_star","suffix_student",CATALOG,DEFAULTS).applied());
            assertFalse(s.select(p,granted.revision(),"suffix_student","prefix_star",CATALOG,DEFAULTS).applied());assertFalse(s.select(p,granted.revision(),"forged","",CATALOG,DEFAULTS).applied());
            var selected=s.select(p,granted.revision(),"prefix_star","suffix_student",CATALOG,DEFAULTS);assertTrue(selected.applied());assertEquals("prefix_star",selected.state().prefix());assertEquals("suffix_student",selected.state().suffix());}
        try(var wild=new TitleStore(SQLITE,path)){var saved=wild.load(p,DEFAULTS);assertEquals("prefix_star",saved.prefix());assertEquals("suffix_student",saved.suffix());assertTrue(wild.select(p,saved.revision(),"","",CATALOG,DEFAULTS).applied());}
    }
    @Test void revokeSelectedDefaultDoesNotRegrantAndGrantIsIdempotent()throws Exception{var p=UUID.randomUUID();try(var s=new TitleStore(SQLITE,dir.resolve("titles.db"))){var first=s.load(p,DEFAULTS);var a=s.select(p,first.revision(),"","suffix_student",CATALOG,DEFAULTS);assertTrue(a.applied());long revision=a.state().revision();assertEquals(revision,s.grant(p,"suffix_student",DEFAULTS).revision());var removed=s.revoke(p,"suffix_student",DEFAULTS);assertEquals("",removed.suffix());assertFalse(s.load(p,DEFAULTS).owned().contains("suffix_student"));assertEquals(removed.revision(),s.revoke(p,"suffix_student",DEFAULTS).revision());}}
    @Test void staleOtherServerCannotOverwriteAndDisabledCannotApply()throws Exception{var p=UUID.randomUUID();var path=dir.resolve("titles.db");try(var school=new TitleStore(SQLITE,path);var wild=new TitleStore(SQLITE,path)){
        var a=school.grant(p,"prefix_star",DEFAULTS);var b=wild.load(p,DEFAULTS);assertTrue(school.select(p,a.revision(),"prefix_star","suffix_student",CATALOG,DEFAULTS).applied());assertFalse(wild.select(p,b.revision(),"","",CATALOG,DEFAULTS).applied());var current=wild.load(p,DEFAULTS);assertEquals("prefix_star",current.prefix());var disabled=new HashMap<>(CATALOG);disabled.put("prefix_star",new TitleDefinition("prefix_star",0,"별빛을 발견한",1,false,false));assertFalse(wild.select(p,current.revision(),"prefix_star","",disabled,DEFAULTS).applied());}}
    @Test void nicknameAndDefinitionArePlainAndComposeCleanly(){assertEquals("별빛을 발견한 연구가 차카",TitleDefinition.composed("별빛을 발견한","차카","연구가"));assertEquals("차카",TitleDefinition.composed("","차카",""));assertThrows(IllegalArgumentException.class,()->new TitleDefinition("bad",0,"%user_nickname%",1,true,false));assertThrows(IllegalArgumentException.class,()->new TitleDefinition("bad",2,"명칭",1,true,false));}
    @Test void protocolCarriesMoreThanTheOldNinetySixCap(){var token=UUID.randomUUID().toString();var many=new ArrayList<TitleProtocol.Entry>();for(int i=0;i<1200;i++)many.add(new TitleProtocol.Entry("suffix_magic_spell_"+i,i%2,"아주긴칭호이름"+i,0xdec58e));var reply=new TitleProtocol.Response(true,1,token,3,"차카","","","",many);assertEquals(reply,TitleProtocol.response(TitleProtocol.encode(reply)));}
    @Test void boundedProtocolRejectsTrailingForgedSidesAndOversizedLists(){var r=new TitleProtocol.Request(TitleProtocol.APPLY,1,UUID.randomUUID().toString(),3,"prefix_star","suffix_student");assertEquals(r,TitleProtocol.request(TitleProtocol.encode(r)));var reply=new TitleProtocol.Response(true,1,r.token(),3,"차카",r.prefix(),r.suffix(),"적용 완료",List.of(new TitleProtocol.Entry("prefix_star",0,"별빛을 발견한",0x96e4ec)));assertEquals(reply,TitleProtocol.response(TitleProtocol.encode(reply)));byte[] bytes=TitleProtocol.encode(r);assertThrows(IllegalArgumentException.class,()->TitleProtocol.request(Arrays.copyOf(bytes,bytes.length+1)));assertThrows(IllegalArgumentException.class,()->TitleProtocol.request(new byte[40000]));assertThrows(IllegalArgumentException.class,()->TitleProtocol.encode(new TitleProtocol.Request(1,2,"",0,"","")));assertThrows(IllegalArgumentException.class,()->TitleProtocol.encode(new TitleProtocol.Response(false,1,r.token(),0,"차카","","","",List.of(new TitleProtocol.Entry("x",2,"칭호",1)))));}
    @Test void fullBundledCatalogueAndGeneratedNpcTitlesRoundTrip() throws Exception {
        var titles=new org.bukkit.configuration.file.YamlConfiguration();
        try(var in=getClass().getClassLoader().getResourceAsStream("titles.yml")){titles.loadFromString(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}
        var awards=new org.bukkit.configuration.file.YamlConfiguration();
        try(var in=getClass().getClassLoader().getResourceAsStream("title-awards.yml")){awards.loadFromString(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}
        var rules=TitleAwards.parse(awards,new org.bukkit.configuration.file.YamlConfiguration());
        var catalogue=new ArrayList<TitleProtocol.Entry>();var root=titles.getConfigurationSection("titles");
        assertEquals(278,root.getKeys(false).size()); // 2026-10-26: 꺼진 마법 12개 칭호 삭제
        for(String id:root.getKeys(false)){var entry=root.getConfigurationSection(id);catalogue.add(new TitleProtocol.Entry(id,entry.getString("side").equals("prefix")?0:1,entry.getString("name"),Integer.parseInt(entry.getString("color").substring(1),16)));}
        for(int i=0;i<40;i++){String npc="npc_"+i;catalogue.add(new TitleProtocol.Entry(rules.npcTitle(npc),0,rules.npcTitleName("NPC "+i),rules.npcColor));}
        var reply=new TitleProtocol.Response(true,12,UUID.randomUUID().toString(),7,"Player","","","",catalogue);
        byte[] wire=TitleProtocol.encode(reply);
        assertEquals(reply,TitleProtocol.response(wire));
        var input=new java.io.DataInputStream(new java.io.ByteArrayInputStream(wire));
        assertEquals(0x54495402,input.readInt());input.readBoolean();input.readLong();input.readUTF();input.readLong();
        for(int i=0;i<4;i++)input.readUTF();
        assertEquals(318,input.readUnsignedShort(),"v2 count is unsigned 16-bit, not truncated to 8-bit");
        assertEquals("prefix_npc_npc_39",TitleProtocol.response(wire).entries().get(317).id());
    }
    @Test void v2RejectsOldVersionAndEveryTruncatedRequest() {
        var query=new TitleProtocol.Request(TitleProtocol.APPLY,1,UUID.randomUUID().toString(),3,"prefix_star","suffix_student");
        byte[] request=TitleProtocol.encode(query);byte[] old=request.clone();old[3]=1;
        assertThrows(IllegalArgumentException.class,()->TitleProtocol.request(old));
        for(int n=0;n<request.length;n++){byte[] truncated=Arrays.copyOf(request,n);assertThrows(IllegalArgumentException.class,()->TitleProtocol.request(truncated));}
        assertThrows(IllegalArgumentException.class,()->TitleProtocol.request(new byte[TitleProtocol.MAX_REQUEST_BYTES+1]));
        var reply=new TitleProtocol.Response(true,1,query.token(),3,"Player","","","",List.of(new TitleProtocol.Entry("prefix_star",0,"Star",1)));
        byte[] response=TitleProtocol.encode(reply);byte[] oldReply=response.clone();oldReply[3]=1;
        assertThrows(IllegalArgumentException.class,()->TitleProtocol.response(oldReply));
        for(int n=0;n<response.length;n++){byte[] truncated=Arrays.copyOf(response,n);assertThrows(IllegalArgumentException.class,()->TitleProtocol.response(truncated));}
        assertThrows(IllegalArgumentException.class,()->TitleProtocol.response(new byte[TitleProtocol.MAX_BYTES+1]));
    }
    @Test void v2StillBoundsTotalResponseBytesAndUnsignedCount() {
        String token=UUID.randomUUID().toString();var huge=new ArrayList<TitleProtocol.Entry>();
        for(int i=0;i<10000;i++)huge.add(new TitleProtocol.Entry("title_"+i,i%2,"가".repeat(48),1));
        assertThrows(IllegalArgumentException.class,()->TitleProtocol.encode(new TitleProtocol.Response(true,1,token,0,"Player","","","",huge)));
        var tooMany=Collections.nCopies(TitleProtocol.MAX_TITLES+1,new TitleProtocol.Entry("title",0,"Title",1));
        assertThrows(IllegalArgumentException.class,()->TitleProtocol.encode(new TitleProtocol.Response(true,1,token,0,"Player","","","",tooMany)));
    }

}
