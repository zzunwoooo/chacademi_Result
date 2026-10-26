package school.magiccodex.paper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
/** A02 (2026-10-21): 관리자 우편 결과 문구는 실제 결과만 말한다. */
class AdminMailResultTest {
 @Test void failureNeverClaimsTheItemWasReturnedByMail(){
  assertEquals("우편을 보내지 못했어요 (첨부는 손에 그대로 있어요)",MailboxBridge.adminSendResult(false,true,false));
  assertEquals("우편을 보내지 못했어요",MailboxBridge.adminSendResult(false,false,false));
 }
 @Test void successSaysWhetherTheHandItemWasTaken(){
  assertEquals("우편을 보냈어요 (첨부 1개)",MailboxBridge.adminSendResult(true,true,true));
  assertTrue(MailboxBridge.adminSendResult(true,true,false).contains("빼지 못했어요"));
  assertEquals("우편을 보냈어요",MailboxBridge.adminSendResult(true,false,false));
 }
}
