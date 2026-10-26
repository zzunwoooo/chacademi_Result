package kr.chacademy.storyplugin;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ReloadTicketsTest {
 @Test void resultGoesOnlyToInitiatorOfExactRequestedClient(){var tickets=new ReloadTickets<String>();UUID a=UUID.randomUUID(),b=UUID.randomUUID();var ticket=tickets.begin(a,"console");assertNull(tickets.finish(b,ticket.token()));assertNull(tickets.finish(a,UUID.randomUUID().toString()));assertEquals("console",tickets.finish(a,ticket.token()));assertNull(tickets.finish(a,ticket.token()));}
 @Test void newRequestAndDisconnectInvalidateOldResults(){var tickets=new ReloadTickets<String>();UUID a=UUID.randomUUID();var old=tickets.begin(a,"admin1");var current=tickets.begin(a,"admin2");assertNull(tickets.finish(a,old.token()));tickets.cancel(a);assertNull(tickets.finish(a,current.token()));}
}
