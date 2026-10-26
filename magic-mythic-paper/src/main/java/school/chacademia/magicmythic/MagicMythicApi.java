package school.chacademia.magicmythic;

import java.util.*;
import org.bukkit.Location;

/** Small read-only API for other Chacademi plugins (compileOnly dependency). */
public final class MagicMythicApi {
    private MagicMythicApi() {}

    /** Where 168 그림자 분신 doubles stand right now (the gimmick plugin counts them as standing on plates). */
    public static List<Location> shadowDoubles() {
        var st = ArcanaMech.State.get();
        if (st == null) return List.of();
        var out = new ArrayList<Location>();
        for (Object[] d : st.doubles.values()) if ((long) d[1] >= st.now && d[0] instanceof Location l) out.add(l.clone());
        return out;
    }
}
