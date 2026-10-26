package school.magiccodex.paper;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import school.magiccodex.protocol.QuestProtocol;

class QuestPrerequisiteTest {
    @TempDir Path dir;
    private QuestDefinition quest(String id,boolean main,List<String> requires){
        var q=QuestStoreTest.quest(id,false);
        return new QuestDefinition(q.id(),q.title(),q.description(),q.permission(),q.daily(),q.enabled(),q.objectives(),q.rewardLabel(),q.commands(),q.items(),q.rank(),q.completionLimit(),q.opensAt(),q.money(),q.housePoints(),main,requires);
    }
    @Test void prerequisitesRequireConfirmedPayoutAndPersistAcrossBackends()throws Exception{
        UUID player=UUID.randomUUID();long now=System.currentTimeMillis();var first=quest("first",true,List.of());var next=quest("next",true,List.of("first"));
        Path file=dir.resolve("quest.db");
        try(var school=new QuestStore(QuestStoreTest.SQLITE,file);var wild=new QuestStore(QuestStoreTest.SQLITE,file)){
            assertFalse(school.accept(player,next,now));
            assertTrue(school.accept(player,first,now));
            wild.advance(player,QuestDefinition.Type.KILL,"ZOMBIE","wild","world",10,now);
            var payout=wild.reserve(player,"first","once",now);assertNotNull(payout);
            assertFalse(school.accept(player,next,now));
            wild.finish(player,payout,true);
            assertTrue(school.accept(player,next,now));
            assertFalse(wild.accept(player,next,now));
        }
        try(var restarted=new QuestStore(QuestStoreTest.SQLITE,file)){assertTrue(restarted.completed(player).contains("first"));}
    }
    @Test void mainQuestsDoNotUseThreeSubquestSlots()throws Exception{
        UUID player=UUID.randomUUID();long now=System.currentTimeMillis();
        try(var s=new QuestStore(QuestStoreTest.SQLITE,dir.resolve("quota.db"))){
            for(int i=0;i<3;i++)assertTrue(s.accept(player,quest("sub"+i,false,List.of()),now));
            assertTrue(s.accept(player,quest("main",true,List.of()),now));
            assertEquals(3,QuestStore.activeSubquests(s.entries(player),now));
            assertFalse(s.accept(player,quest("sub3",false,List.of()),now));
        }
    }
    @Test void legacyDefinitionsRetainEncodingAndOptionalFieldsRoundTrip(){
        var old=QuestStoreTest.quest("old",false);
        assertFalse(old.main());assertTrue(old.requires().isEmpty());
        assertEquals(old,QuestDefinition.decode(old.encode()));
        var main=quest("next",true,List.of("first"));assertEquals(main,QuestDefinition.decode(main.encode()));var admin=new QuestDefinition(main.id(),main.title(),main.description(),main.permission(),false,true,List.of(new QuestDefinition.Objective(QuestDefinition.Type.EVENT,"test",1,"Event","","")),main.rewardLabel(),List.of(),List.of(),main.rank(),main.completionLimit(),0,0,0,true,main.requires());assertEquals(admin,QuestAdminDocument.parse(admin.id(),QuestAdminDocument.fields(admin)));
        assertEquals(main,main.release(123,true).release(0,true));
        assertThrows(IllegalArgumentException.class,()->quest("self",true,List.of("self")));
        assertThrows(IllegalArgumentException.class,()->quest("next",true,List.of("first","first")));
    }
    @Test void oldV2ResponseRemainsReadable()throws Exception{
        var bytes=new java.io.ByteArrayOutputStream();
        var out=new java.io.DataOutputStream(bytes);
        out.writeInt(2);out.writeBoolean(true);out.writeInt(0);out.writeInt(1);out.writeUTF("");out.writeInt(1);
        for(String value:List.of("old","Title","Description","Reward","available","F"))out.writeUTF(value);
        out.writeInt(0);out.writeInt(1);out.writeInt(0);
        var response=QuestProtocol.response(bytes.toByteArray());
        assertEquals(3,response.remainingSubquests());assertFalse(response.cards().getFirst().main());
        assertEquals("old",response.cards().getFirst().id());
    }
    @Test void wireCarriesMainFlagAndRemainingSlotsAndRejectsInvalidCount(){
        var card=new QuestProtocol.Card("main","Title","Description","Reward","available",List.of(),"F",0,1,true);
        var response=new QuestProtocol.Response(true,0,1,"",List.of(card),0);
        assertEquals(response,QuestProtocol.response(QuestProtocol.encode(response)));
        assertThrows(IllegalArgumentException.class,()->QuestProtocol.encode(new QuestProtocol.Response(true,0,1,"",List.of(card),4)));
    }
}
