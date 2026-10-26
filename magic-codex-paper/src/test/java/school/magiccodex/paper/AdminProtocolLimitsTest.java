package school.magiccodex.paper;

import java.util.*;
import org.junit.jupiter.api.Test;
import school.magiccodex.protocol.DialogueAdminProtocol;
import school.magiccodex.protocol.QuestAdminProtocol;
import school.magiccodex.protocol.QuestProtocol;
import static org.junit.jupiter.api.Assertions.*;

class AdminProtocolLimitsTest {
    static String korean(int n){return "가".repeat(n);}
    static List<QuestAdminProtocol.Summary> quests(int n){var out=new ArrayList<QuestAdminProtocol.Summary>();for(int i=0;i<n;i++)out.add(new QuestAdminProtocol.Summary(String.format("%048d",i),korean(60),"F","예약 2026-10-05 12:00"));return out;}
    static List<DialogueAdminProtocol.Summary> dialogues(int n){var out=new ArrayList<DialogueAdminProtocol.Summary>();for(int i=0;i<n;i++)out.add(new DialogueAdminProtocol.Summary(String.format("%048d",i),korean(60),"","비공개"));return out;}
    /** A document as large as a client can ever save (request cap), returned with the worst-case list. */
    static Map<String,String> savedDocument(){var f=new HashMap<String,String>();for(int i=0;i<9;i++)f.put("k"+i,korean(1000));return f;}

    @Test void questAdminListHoldsFiveHundredTwelveWorstCaseEntriesPlusDocument(){
        var fields=savedDocument();QuestAdminProtocol.encode(new QuestAdminProtocol.Request(2,"q","",fields));
        var r=new QuestAdminProtocol.Response("",quests(QuestAdminProtocol.MAX_ENTRIES),"q","rev",fields);
        byte[] bytes=QuestAdminProtocol.encode(r);assertTrue(bytes.length>QuestProtocol.MAX_BYTES);
        var back=QuestAdminProtocol.response(bytes);assertEquals(512,back.entries().size());assertEquals(r.entries().get(511),back.entries().get(511));
        assertThrows(IllegalArgumentException.class,()->QuestAdminProtocol.encode(new QuestAdminProtocol.Response("",quests(513),"","",Map.of())));
    }
    @Test void questAdminDecoderRejectsOneOverTheLimit(){
        byte[] ok=QuestAdminProtocol.encode(new QuestAdminProtocol.Response("",quests(1),"","",Map.of()));
        byte[] bad=ok.clone();bad[6]=0;bad[7]=0;bad[8]=2;bad[9]=1; // entry count 513 (after version int and empty message)
        assertThrows(IllegalArgumentException.class,()->QuestAdminProtocol.response(bad));
    }
    @Test void requestsStayBelowTheClientToServerCap(){
        var big=new HashMap<String,String>();for(int i=0;i<4;i++)big.put("k"+i,korean(3000));
        assertThrows(IllegalArgumentException.class,()->QuestAdminProtocol.encode(new QuestAdminProtocol.Request(2,"q","",big)));
        assertThrows(IllegalArgumentException.class,()->DialogueAdminProtocol.encode(new DialogueAdminProtocol.Request(2,"q","",big)));
        var many=new HashMap<String,String>();for(int i=0;i<101;i++)many.put("k"+i,"v");
        assertThrows(IllegalArgumentException.class,()->QuestAdminProtocol.encode(new QuestAdminProtocol.Request(2,"q","",many)));
        assertThrows(IllegalArgumentException.class,()->QuestAdminProtocol.encode(new QuestAdminProtocol.Request(2,"q","",Map.of("x".repeat(65),"v"))));
    }
    @Test void dialogueAdminListBoundary(){
        var fields=savedDocument();var r=new DialogueAdminProtocol.Response("",dialogues(DialogueAdminProtocol.MAX_ENTRIES),"d","rev",fields);
        assertEquals(512,DialogueAdminProtocol.response(DialogueAdminProtocol.encode(r)).entries().size());
        assertThrows(IllegalArgumentException.class,()->DialogueAdminProtocol.encode(new DialogueAdminProtocol.Response("",dialogues(513),"","",Map.of())));
    }
    @Test void questBoardTotalBoundary(){
        var card=new QuestProtocol.Card("q","t","d","r","active",List.of(new QuestProtocol.Goal("g",0,1)));
        var ok=new QuestProtocol.Response(true,99,QuestProtocol.MAX_TOTAL,"",Collections.nCopies(6,card));
        assertEquals(600,QuestProtocol.response(QuestProtocol.encode(ok)).total());
        assertThrows(IllegalArgumentException.class,()->QuestProtocol.encode(new QuestProtocol.Response(true,0,QuestProtocol.MAX_TOTAL+1,"",List.of())));
        assertThrows(IllegalArgumentException.class,()->QuestProtocol.encode(new QuestProtocol.Response(true,0,7,"",Collections.nCopies(7,card))));
        assertThrows(IllegalArgumentException.class,()->QuestProtocol.encode(new QuestProtocol.Response(true,0,1,"",List.of(new QuestProtocol.Card("q","t","d","r","active",List.of(),"Z",0,1)))));
        assertEquals(100,QuestProtocol.request(QuestProtocol.encode(new QuestProtocol.Request(0,100,""))).page());
        assertThrows(IllegalArgumentException.class,()->QuestProtocol.encode(new QuestProtocol.Request(0,101,"")));
    }
}
