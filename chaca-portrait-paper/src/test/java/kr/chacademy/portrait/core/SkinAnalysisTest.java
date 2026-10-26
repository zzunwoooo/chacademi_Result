package kr.chacademy.portrait.core;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SkinAnalysisTest {
    private String data(String type,String presentation){return Json.stringify(Json.map("skin_facts","White animal fur, dark ear tips, green scarf", "character_type",type,"presentation",presentation,"request_notes","calm expression","uncertainty","gender presentation unclear"));}
    @Test void keepsNonhumanAndNeutralAsData(){
        String result=SkinAnalysis.validatedNotes(data("animal","neutral/unspecified"));
        assertTrue(result.contains("animal"));assertTrue(result.contains("neutral/unspecified"));
        assertTrue(SkinAnalysis.RULES.contains("never automatically humanize"));
        assertTrue(SkinAnalysis.RULES.contains("Hair alone is not evidence"));
    }
    @Test void rejectsMalformedEmptyOrUnboundedAnalysis(){
        assertThrows(RuntimeException.class,()->SkinAnalysis.validatedNotes("{}"));
        assertThrows(RuntimeException.class,()->SkinAnalysis.validatedNotes(data("real-man","male")));
        assertThrows(RuntimeException.class,()->SkinAnalysis.validatedNotes(data("human","male-presenting").replace("White animal fur, dark ear tips, green scarf","")));
    }
    @Test void rulesDoNotAllowModelOutputToOverrideIdentityOrFixedStyle(){
        String prompt=PromptBuilder.build("SKIN FIRST", "{appearance}","{request}",SkinAnalysis.validatedNotes(data("robot","neutral/unspecified")),"female-presenting robot");
        assertTrue(prompt.contains("Preserve animals, robots and other nonhuman forms"));
        assertTrue(prompt.contains("explicit fictional-presentation request wins"));
        assertTrue(prompt.contains("white shirt, blue tie or human proportions"));
        assertTrue(prompt.contains("restrained cel shading"));
    }
}
