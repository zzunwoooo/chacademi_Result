package school.magiccodex.client;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class NotificationRoutingTest {
 @Test void commandsDoNotCreateBottomCards(){for(String k:new String[]{"system","warning","error"})assertEquals(NotificationRouting.Destination.SYSTEM_CHAT,NotificationRouting.destination("command:reply",k));}
 @Test void eventsAndPreviewsRetainCards(){for(String k:new String[]{"quest","shop","mail","social","taming","collection"})assertEquals(NotificationRouting.Destination.CARD,NotificationRouting.destination("event",k));assertEquals(NotificationRouting.Destination.CARD,NotificationRouting.destination("preview:error","error"));}
 @Test void discoveryNeverDuplicatesAtBottom(){for(String k:new String[]{"discovery","spell_discovered","spell_unlock"})assertEquals(NotificationRouting.Destination.DISCOVERY_ONLY,NotificationRouting.destination("event",k));}
 @Test void feedbackKeepsBothLongTextParts(){String body="detail ".repeat(100);assertEquals("[\uC2DC\uC2A4\uD15C] Title : "+body.strip(),NotificationRouting.message("Title",body));assertEquals("[\uC2DC\uC2A4\uD15C] Title",NotificationRouting.message("Title","Title"));}
}
