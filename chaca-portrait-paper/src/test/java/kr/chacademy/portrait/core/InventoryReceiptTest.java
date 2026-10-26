package kr.chacademy.portrait.core;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class InventoryReceiptTest {
 static class Account implements InventoryReceipt.Account {
  int items=1,diskItems=1;String marker,diskMarker;boolean online=true,full,failBefore,failAfter;
  public String marker(){return marker;} public void marker(String v){marker=v;}
  public Object snapshot(){return new Object[]{items,marker};}
  public void restore(Object s){var a=(Object[])s;items=(Integer)a[0];marker=(String)a[1];}
  public boolean take(){if(!online||items<1)return false;items--;return true;}
  public boolean give(){if(!online||full)return false;items++;return true;}
  public void save(){if(failBefore)throw new IllegalStateException();diskItems=items;diskMarker=marker;if(failAfter)throw new IllegalStateException();}
  void restart(){items=diskItems;marker=diskMarker;failBefore=failAfter=false;}
 }
 @Test void crashAfterDebitBeforeDbPromotionRefundsOnce(){var a=new Account();assertTrue(InventoryReceipt.debit(a));a.restart();assertTrue(InventoryReceipt.refund(a));assertTrue(InventoryReceipt.refund(a));assertEquals(1,a.items);assertEquals(1,a.diskItems);}
 @Test void crashAfterRefundSaveBeforeDbAckDoesNotDuplicate(){var a=new Account();InventoryReceipt.debit(a);InventoryReceipt.refund(a);a.restart();assertTrue(InventoryReceipt.refund(a));assertEquals(1,a.items);assertFalse(InventoryReceipt.debit(a));}
 @Test void failedDebitSaveRestoresInventoryAndReceipt(){var a=new Account();a.failBefore=true;assertThrows(IllegalStateException.class,()->InventoryReceipt.debit(a));assertEquals(1,a.items);assertNull(a.marker);a.restart();assertFalse(InventoryReceipt.refund(a));assertEquals(1,a.items);}
 @Test void ambiguousSaveAfterDiskWriteRecoversFromPersistedPair(){var a=new Account();a.failAfter=true;assertThrows(IllegalStateException.class,()->InventoryReceipt.debit(a));assertEquals(1,a.items);a.restart();assertEquals(0,a.items);assertTrue(InventoryReceipt.refund(a));assertEquals(1,a.items);}
 @Test void failedRefundSaveRetainsRefundAndRetries(){var a=new Account();InventoryReceipt.debit(a);a.failBefore=true;assertThrows(IllegalStateException.class,()->InventoryReceipt.refund(a));assertEquals(0,a.items);assertEquals(InventoryReceipt.DEBITED,a.marker);a.restart();assertTrue(InventoryReceipt.refund(a));assertEquals(1,a.items);}
 @Test void disconnectAndFullInventoryDeferWithoutDropping(){var a=new Account();a.online=false;assertFalse(InventoryReceipt.debit(a));a.online=true;InventoryReceipt.debit(a);a.full=true;assertFalse(InventoryReceipt.refund(a));assertEquals(0,a.items);a.full=false;a.online=false;assertFalse(InventoryReceipt.refund(a));a.online=true;assertTrue(InventoryReceipt.refund(a));assertEquals(1,a.items);}
 @Test void crashBeforeDebitHasNoRefund(){var a=new Account();a.restart();assertFalse(InventoryReceipt.refund(a));assertEquals(1,a.items);}
}
