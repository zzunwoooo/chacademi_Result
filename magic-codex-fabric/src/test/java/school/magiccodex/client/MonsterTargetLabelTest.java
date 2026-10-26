package school.magiccodex.client;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class MonsterTargetLabelTest {
 @Test void ownershipDoesNotBecomeIneligible(){assertEquals("보유중",MonsterTargetLabel.text(true,0,"보유중"));}
 @Test void absentGolemPetIsNotRedirected(){assertEquals("교화 불가",MonsterTargetLabel.text(false,.9,""));}
 @Test void authoritativeChanceShown(){assertEquals("교화 35%",MonsterTargetLabel.text(true,.35,""));}
}
