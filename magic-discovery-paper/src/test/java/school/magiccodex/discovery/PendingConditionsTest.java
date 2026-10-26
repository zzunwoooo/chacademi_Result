package school.magiccodex.discovery;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
class PendingConditionsTest {
 @Test void unconfirmedConditionsCannotAutoGrant()throws Exception{var d=new Definitions(Path.of("src/main/resources/discoveries.yml").toFile());d.manual(Path.of("src/main/resources/manual-conditions.yml").toFile());assertTrue(d.meta.stream().noneMatch(s->Definitions.PENDING.contains(s.id())));assertTrue(d.stateOnly.stream().noneMatch(s->Definitions.PENDING.contains(s.id())));assertTrue(d.index.values().stream().flatMap(java.util.Collection::stream).noneMatch(s->Definitions.PENDING.contains(s.id())));assertTrue(d.index.containsKey("pet.phoenix"));assertTrue(d.index.containsKey("mine.submerged_gold"));}
}
