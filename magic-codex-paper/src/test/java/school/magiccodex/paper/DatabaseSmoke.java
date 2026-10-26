package school.magiccodex.paper;

import java.nio.file.Path;
import java.util.UUID;
import school.magiccodex.database.DatabaseSettings;
import school.magiccodex.protocol.SchoolProtocol.Donation;
import school.magiccodex.protocol.SocialProtocol;

/** Manual integration probe against an isolated MariaDB schema; no Minecraft server required. */
public final class DatabaseSmoke {
    public static void main(String[] args)throws Exception {
        String password=System.getenv("CHACADEMIA_DB_PASSWORD");
        if(password==null||password.isBlank())throw new IllegalArgumentException("Missing DB password");
        var config=new DatabaseSettings(true,"jdbc:mariadb://127.0.0.1:3306/chacademia_smoke?connectTimeout=5000","chacademia_app",password);
        UUID owner=UUID.randomUUID(),friend=UUID.randomUUID();
        try(var store=new FriendStore(Path.of("unused"),config)){
            if(!store.add(owner,new SocialProtocol.Entry(friend,"Friend","루미나",false)))throw new AssertionError("friend insert");
            if(store.load(owner).size()!=1)throw new AssertionError("friend load");
            store.profile(friend,"Renamed","아르케온");
            if(!store.load(owner).getFirst().name().equals("Renamed"))throw new AssertionError("friend profile");
            if(!store.remove(owner,friend))throw new AssertionError("friend remove");
        }
        try(var store=new SchoolStore(Path.of("unused"),config)){
            String spell="probe_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
            if(!store.donate(new Donation(spell,"시험 마법",owner,"시험자",0,System.currentTimeMillis()),5))throw new AssertionError("donation");
            if(store.donate(new Donation(spell,"시험 마법",friend,"중복",1,System.currentTimeMillis()),5))throw new AssertionError("duplicate donation");
            store.house(owner,2);
            var snapshot=store.snapshot(java.util.List.of(owner));
            if(snapshot.scores().getFirst()!=5||!snapshot.houses().containsKey(owner))throw new AssertionError("school snapshot");
            if(store.snapshot(java.util.List.of(friend)).houses().containsKey(owner))throw new AssertionError("active house filter");
            store.reset(spell);store.resetPoints();
        }
        try(var store=new PlayerStateStore(config)){
            var initial=new PlayerStateStore.State(3,71,140,7,12,"",new byte[4][],new int[]{2,1,0});
            if(store.firstOrCurrent(owner,initial).circle()!=3)throw new AssertionError("initial player state");
            String lease=UUID.randomUUID().toString(),secondLease=UUID.randomUUID().toString();
            if(!store.claim(owner,lease))throw new AssertionError("first lease");
            var changed=new PlayerStateStore.State(4,60,160,8,14,"",new byte[4][],new int[]{1,1,0});
            try(var other=new PlayerStateStore(config)){
                if(other.claim(owner,secondLease))throw new AssertionError("overlapping server lease");
                store.save(owner,changed,lease,true);
                if(!other.claim(owner,secondLease)||other.load(owner).orElseThrow().circle()!=4)throw new AssertionError("player state handoff");
                other.release(owner,secondLease);
            }
        }
        System.out.println("MAGIC_CODEX_MARIADB_SMOKE_OK");
    }
}
