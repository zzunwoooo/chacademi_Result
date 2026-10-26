package kr.chacademy.portrait.ai;
import org.junit.jupiter.api.Test;
import org.bukkit.configuration.file.YamlConfiguration;
import kr.chacademy.portrait.core.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class SkinPreparationApiTest {
    @Test void requestUsesLunaLowAndOriginalSkinWithNoTools(){
        var c=new YamlConfiguration();var s=new PortraitSettings(c);
        var body=OpenAiImageClient.describeBody(s,new byte[]{1,2,3},"neutral robot");
        assertEquals("gpt-6-luna",body.get("model"));
        assertEquals("low",Json.obj(body.get("reasoning")).get("effort"));
        assertEquals(2048,body.get("max_output_tokens"));assertFalse(body.containsKey("tools"));
        assertTrue(Json.stringify(body).contains("data:image/png;base64,AQID"));
        assertTrue(Json.stringify(body).contains("neutral robot"));
        assertTrue(Json.stringify(body).contains("json_schema"));
        assertTrue(((String)body.get("instructions")).contains("never infer the real user's"));
    }
    @Test void incompleteOrRefusedOrEmptyCannotProceed(){
        for(String json:List.of("{\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"max_output_tokens\"}}",
                "{\"status\":\"failed\"}","{\"status\":\"completed\",\"output\":[]}",
                "{\"status\":\"completed\",\"output\":[{\"content\":[{\"type\":\"refusal\",\"refusal\":\"no\"}]}]}"))
            assertThrows(OpenAiImageClient.ApiException.class,()->OpenAiImageClient.parseResponseText(json));
    }
    @Test void requestedTinyBudgetIsRaisedForReasoningAndUnknownUsageIsReserved(){
        var c=new YamlConfiguration();c.set("describe.max-output-tokens",300);var s=new PortraitSettings(c);
        assertEquals(1024,s.describeMaxTokens);
        var cost=new CostModel(Map.of("gpt-6-luna",new CostModel.Prices(.1,.1,0,.5)),new CostModel.Estimate(600,2400,9000,9000,1500,3000));
        assertEquals(1324,cost.reserveDescribe("gpt-6-luna",2048));
        assertEquals(1324,cost.settleDescribe("gpt-6-luna",-1,-1,1324));
        assertEquals(110,cost.settleDescribe("gpt-6-luna",900,40,1324));
    }
}
