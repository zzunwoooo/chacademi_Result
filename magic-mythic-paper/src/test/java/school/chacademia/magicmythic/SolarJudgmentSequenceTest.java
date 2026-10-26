package school.chacademia.magicmythic;
import org.junit.jupiter.api.Test;import java.util.*;import static org.junit.jupiter.api.Assertions.*;
class SolarJudgmentSequenceTest {
 @Test void cancellationOnlyBreaksStructuresAlreadyFormed(){assertEquals(List.of("external:cancel"),SolarJudgmentSequence.cancel(7));assertEquals(2,SolarJudgmentSequence.cancel(8).size());assertEquals(5,SolarJudgmentSequence.cancel(140).size());assertEquals(7,SolarJudgmentSequence.cancel(239).size());}
 @Test void prechargePreservesApprovedTwelveSecondGameplayTiming(){assertEquals(List.of("external:star_link"),SolarJudgmentSequence.events(228,240));assertEquals(List.of(),SolarJudgmentSequence.events(239,240));assertEquals(List.of("external:laser_fire"),SolarJudgmentSequence.events(240,240));assertEquals(List.of("external:ground_hit"),SolarJudgmentSequence.events(244,240));assertEquals(List.of("external:laser_final","external:ground_final"),SolarJudgmentSequence.events(290,240));assertEquals(List.of("external:laser_end"),SolarJudgmentSequence.events(300,240));assertEquals(7,SolarJudgmentSequence.events(310,240).size());}
}
