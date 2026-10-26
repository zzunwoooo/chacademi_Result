package school.magiccodex.paper;
import java.util.UUID;
final class TamingQueryPolicy {
 static boolean matches(UUID claimed,UUID authoritative){return authoritative!=null&&(new UUID(0,0).equals(claimed)||authoritative.equals(claimed));}
}
