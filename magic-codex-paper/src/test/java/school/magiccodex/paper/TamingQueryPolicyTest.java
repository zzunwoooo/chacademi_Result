package school.magiccodex.paper;
import java.util.UUID;import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class TamingQueryPolicyTest {
 @Test void serverResolvedModelTargetsDoNotNeedAClientVisibleBase(){UUID target=UUID.randomUUID();assertTrue(TamingQueryPolicy.matches(new UUID(0,0),target));assertTrue(TamingQueryPolicy.matches(target,target));assertFalse(TamingQueryPolicy.matches(UUID.randomUUID(),target));assertFalse(TamingQueryPolicy.matches(new UUID(0,0),null));}
}
