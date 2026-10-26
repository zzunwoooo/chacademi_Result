package school.magiccodex.paper;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import school.magiccodex.database.DatabaseSettings;
/** Two independent backend connections, isolated smoke schema only. */
public final class QuestDatabaseSmoke {
    public static void main(String[] args)throws Exception{
        var source=DatabaseSettings.load(Path.of(args[0]));
        String schema=args.length>1?args[1]:"chacademia_smoke";
        if(!schema.matches("chacademia_quest_verify_[a-z0-9]{8}")&&!schema.equals("chacademia_smoke"))throw new IllegalStateException("Must use isolated schema");
        var settings=new DatabaseSettings(true,source.url().replaceFirst("/[^/?]+\\?","/"+schema+"?"),source.user(),source.password());
        UUID id=UUID.randomUUID();long now=System.currentTimeMillis();String qid="probe_"+id.toString().substring(0,8);
        var q=new QuestDefinition(qid,"공용 검증","","",false,true,List.of(new QuestDefinition.Objective(QuestDefinition.Type.EVENT,"probe",100,"공용 진행","","")),"",List.of(),List.of(),"B",2,0,0,7);
        try(var school=new QuestStore(settings,null);var wild=new QuestStore(settings,null);var executor=Executors.newFixedThreadPool(2)){
            if(!school.accept(id,q,now))throw new AssertionError("accept");
            var a=executor.submit(()->{for(int i=0;i<50;i++)school.advance(id,QuestDefinition.Type.EVENT,"probe","school","world",1,now);return true;});
            var b=executor.submit(()->{for(int i=0;i<50;i++)wild.advance(id,QuestDefinition.Type.EVENT,"probe","wild","world",1,now);return true;});a.get();b.get();
            if(school.entries(id).getFirst().progress()[0]!=100)throw new AssertionError("lost updates");
            var ca=executor.submit(()->school.reserve(id,qid,"once",now));var cb=executor.submit(()->wild.reserve(id,qid,"once",now));var first=ca.get();var second=cb.get();
            if((first==null)==(second==null))throw new AssertionError("duplicate payout reservation");
            school.finish(id,first!=null?first:second,true);if(!wild.entries(id).getFirst().status().equals("claimed"))throw new AssertionError("handoff");
            var aa=executor.submit(()->school.accept(id,q,now));var ab=executor.submit(()->wild.accept(id,q,now));if(aa.get()==ab.get())throw new AssertionError("concurrent repeat accept");if(wild.entries(id).getFirst().completions()!=1)throw new AssertionError("repeat count lost");
        }
        try(var a=new SchoolStore(null,settings);var b=new SchoolStore(null,settings);var executor=Executors.newFixedThreadPool(2)){String token=UUID.randomUUID().toString();var x=executor.submit(()->a.questReward(token,0,7));var y=executor.submit(()->b.questReward(token,0,7));if(x.get()==y.get()||a.snapshot().scores().get(0)!=7)throw new AssertionError("house award not idempotent");}
        System.out.println("QUEST_MARIADB_PASS: two connections, 100 concurrent increments, no lost update, single reward reservation, shared completion, repeat count retained, house reward once across connections");
    }
}
