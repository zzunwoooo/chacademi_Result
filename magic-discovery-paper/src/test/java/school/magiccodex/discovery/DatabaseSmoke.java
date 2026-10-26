package school.magiccodex.discovery;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import school.magiccodex.database.DatabaseSettings;

/** Manual first-discovery transaction probe against the isolated MariaDB schema. */
public final class DatabaseSmoke {
    public static void main(String[] args)throws Exception {
        String password=System.getenv("CHACADEMIA_DB_PASSWORD");
        if(password==null||password.isBlank())throw new IllegalArgumentException("Missing DB password");
        var config=new DatabaseSettings(true,"jdbc:mariadb://127.0.0.1:3306/chacademia_smoke?connectTimeout=5000","chacademia_app",password);
        UUID first=UUID.randomUUID(),second=UUID.randomUUID();
        String spell="probe_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        try(var store=new DiscoveryStore(Path.of("unused"),config)){
            var a=store.acquire(first,spell,Map.of("cast.wind",3d),false).join();
            var b=store.acquire(second,spell,Map.of("cast.wind",1d),false).join();
            if(!a.created()||!a.acquisition().first()||!b.created()||b.acquisition().first())throw new AssertionError("first discoverer");
            if(store.acquire(first,spell,Map.of(),false).join().created())throw new AssertionError("duplicate acquisition");
            if(store.load(first).join().progress().get("cast.wind")!=3d)throw new AssertionError("progress");
            if(!store.reserve(first,a.acquisition().token()).join())throw new AssertionError("reward reservation");
            store.delivered(first,a.acquisition().token()).join();
        }
        System.out.println("MAGIC_DISCOVERY_MARIADB_SMOKE_OK");
    }
}
