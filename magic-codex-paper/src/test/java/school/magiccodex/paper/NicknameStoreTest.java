package school.magiccodex.paper;

import java.nio.file.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import school.magiccodex.database.DatabaseSettings;
import school.magiccodex.protocol.SocialProtocol;

class NicknameStoreTest {
    @TempDir Path temp;
    @Test void persistByUuidAndKeepFriendTables()throws Exception{
        Path file=temp.resolve("friends.db");UUID owner=UUID.randomUUID(),friend=UUID.randomUUID();
        try(var friends=new FriendStore(file)){assertTrue(friends.add(owner,new SocialProtocol.Entry(friend,"친구","루미나",false)));}
        try(var store=new NicknameStore(file,new DatabaseSettings(false,"","",""))){assertNull(store.load(owner));var result=store.save(owner,"Account","별빛",0);assertEquals(NicknameStore.Outcome.SAVED,result.outcome());var saved=result.state();assertEquals(1,saved.revision());assertEquals("Account",saved.account());assertFalse(saved.firstDone());}
        try(var store=new NicknameStore(file,new DatabaseSettings(false,"","",""));var friends=new FriendStore(file)){assertEquals("별빛",store.load(owner).nickname());assertEquals(friend,friends.load(owner).getFirst().id());assertEquals(1,friends.load(owner).size());}
    }
    @Test void staleRevisionAndOtherOwnerCannotOverwrite()throws Exception{
        Path file=temp.resolve("friends.db");UUID a=UUID.randomUUID(),b=UUID.randomUUID();var settings=new DatabaseSettings(false,"","","");
        try(var first=new NicknameStore(file,settings);var second=new NicknameStore(file,settings)){
            first.save(a,"A","처음",0);first.save(b,"B","다른유저",0);
            assertEquals("변경",first.save(a,"A","변경",1).state().nickname());
            var stale=second.save(a,"A","오래된요청",1);assertEquals(NicknameStore.Outcome.STALE,stale.outcome());assertEquals("변경",stale.state().nickname());
            var again=second.save(a,"A","중복최초",0);assertEquals(NicknameStore.Outcome.STALE,again.outcome());assertEquals("변경",again.state().nickname());
            assertEquals("다른유저",second.load(b).nickname());assertEquals(2,second.load(a).revision());
        }
    }
    @Test void duplicateNicknamesAreRejectedAcrossCaseWidthAndConnections()throws Exception{
        Path file=temp.resolve("friends.db");UUID a=UUID.randomUUID(),b=UUID.randomUUID();var settings=new DatabaseSettings(false,"","","");
        try(var first=new NicknameStore(file,settings);var second=new NicknameStore(file,settings)){
            assertTrue(first.warnings().isEmpty());
            assertEquals(NicknameStore.Outcome.SAVED,first.save(a,"Alpha","Star",0).outcome());
            // 다른 연결(다른 서버)에서 같은 이름, 대소문자·전각만 다른 이름은 모두 거부되고 기존 행은 그대로다.
            for(String taken:new String[]{"Star","star","STAR","Ｓｔａｒ"}){var r=second.save(b,"Beta",taken,0);assertEquals(NicknameStore.Outcome.DUPLICATE,r.outcome(),taken);assertNull(r.state());}
            assertEquals(NicknameStore.Outcome.SAVED,second.save(b,"Beta","Moon",0).outcome());
            var changed=second.save(b,"Beta","sTAR",1);assertEquals(NicknameStore.Outcome.DUPLICATE,changed.outcome());assertEquals("Moon",changed.state().nickname());assertEquals(1,changed.state().revision());
            // 본인의 현재 닉네임 다시 저장, 대소문자만 바꾸기는 중복이 아니다.
            assertEquals(NicknameStore.Outcome.SAVED,first.save(a,"Alpha","Star",1).outcome());
            var recased=first.save(a,"Alpha","STAR",2);assertEquals(NicknameStore.Outcome.SAVED,recased.outcome());assertEquals("STAR",recased.state().nickname());
            // 다른 플레이어의 계정 이름과 같은 닉네임도 거부한다. 자기 계정 이름은 쓸 수 있다.
            assertEquals(NicknameStore.Outcome.DUPLICATE,second.save(b,"Beta","alpha",1).outcome());
            assertEquals(NicknameStore.Outcome.SAVED,second.save(b,"Beta","beta",1).outcome());
            assertEquals("STAR",second.load(a).nickname());assertEquals("beta",first.load(b).nickname());
        }
    }
    @Test void normalizedKeyFoldsCaseAndCompatibilityForms(){
        assertEquals(NicknameStore.key("star"),NicknameStore.key("ＳＴＡＲ"));
        assertEquals(NicknameStore.key("별빛"),NicknameStore.key("\u1107\u1167\u11AF\u1107\u1175\u11BE"));
        assertNotEquals(NicknameStore.key("별빛"),NicknameStore.key("별빚"));
        assertThrows(IllegalArgumentException.class,()->NicknameStore.key(""));
    }
    @Test void legacyRowsGetKeysAndExistingDuplicatesOnlyWarn()throws Exception{
        Path file=temp.resolve("friends.db");UUID a=UUID.randomUUID(),b=UUID.randomUUID(),c=UUID.randomUUID();var settings=new DatabaseSettings(false,"","","");
        // 구버전 스키마와 이미 존재하는 중복 행
        try(var db=settings.connect(file);var s=db.createStatement()){
            s.execute("CREATE TABLE nicknames (owner TEXT PRIMARY KEY,account VARCHAR(16) NOT NULL,nickname VARCHAR(16) NOT NULL,revision BIGINT NOT NULL DEFAULT 1)");
            s.execute("INSERT INTO nicknames(owner,account,nickname,revision) VALUES('"+a+"','A','Echo',3)");
            s.execute("INSERT INTO nicknames(owner,account,nickname,revision) VALUES('"+b+"','B','echo',1)");
        }
        try(var store=new NicknameStore(file,settings)){
            assertFalse(store.warnings().isEmpty());
            assertEquals("Echo",store.load(a).nickname());assertEquals(3,store.load(a).revision());assertEquals("echo",store.load(b).nickname());
            assertEquals(NicknameStore.Outcome.DUPLICATE,store.save(c,"C","ECHO",0).outcome());
            assertEquals(NicknameStore.Outcome.SAVED,store.save(c,"C","Unique",0).outcome());
            // 기존 중복 당사자도 자기 이름은 다시 저장할 수 없고(상대와 겹침) 다른 이름으로는 바꿀 수 있다.
            assertEquals(NicknameStore.Outcome.SAVED,store.save(b,"B","Delta",1).outcome());
        }
        // 중복이 정리된 뒤 재시작하면 경고 없이 열리고 유일성은 계속 지켜진다.
        try(var store=new NicknameStore(file,settings)){
            assertTrue(store.warnings().isEmpty());
            assertEquals(NicknameStore.Outcome.DUPLICATE,store.save(c,"C","delta",1).outcome());
        }
    }
    @Test void firstNicknameCompletionHappensOnceAcrossConnections()throws Exception{
        Path file=temp.resolve("friends.db");UUID a=UUID.randomUUID();var settings=new DatabaseSettings(false,"","","");
        try(var school=new NicknameStore(file,settings);var wild=new NicknameStore(file,settings)){
            assertFalse(school.completeFirst(a)); // 닉네임 행이 없으면 완료할 수 없다
            school.save(a,"A","처음",0);
            assertTrue(school.completeFirst(a));assertFalse(wild.completeFirst(a));assertFalse(school.completeFirst(a));
            assertTrue(wild.load(a).firstDone());assertTrue(wild.all().get(a).firstDone());
            // 닉네임을 바꿔도 완료 기록은 유지된다.
            assertTrue(wild.save(a,"A","다음",1).state().firstDone());
            school.reopenFirst(a);assertFalse(wild.load(a).firstDone());assertTrue(wild.completeFirst(a));
        }
    }
}
