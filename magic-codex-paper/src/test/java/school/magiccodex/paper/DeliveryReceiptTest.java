package school.magiccodex.paper;
import static org.junit.jupiter.api.Assertions.*;import java.io.*;import java.nio.file.*;import java.util.zip.GZIPOutputStream;import org.junit.jupiter.api.*;import org.junit.jupiter.api.io.TempDir;
class DeliveryReceiptTest {
 @TempDir Path dir;
 private Path file()throws Exception{Path p=dir.resolve("player.dat");try(var out=new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(p)))){out.writeByte(10);out.writeUTF("");out.writeByte(10);out.writeUTF("BukkitValues");out.writeByte(1);out.writeUTF("magiccodexbridge:mail_receipt_unique");out.writeByte(1);out.writeByte(0);out.writeByte(0);}return p;}
 @Test void uniqueReceiptIsAcknowledgedOnlyAfterSavedFileExists()throws Exception{assertFalse(DeliveryReceipt.saved(dir.resolve("absent"),"magiccodexbridge:mail_receipt_unique"));assertTrue(DeliveryReceipt.saved(file(),"magiccodexbridge:mail_receipt_unique"));}
 @Test void anotherTokenCannotConfirmDelivery()throws Exception{assertFalse(DeliveryReceipt.saved(file(),"magiccodexbridge:mail_receipt_other"));}
 @Test void truncatedPlayerDataDoesNotConfirm()throws Exception{Path p=dir.resolve("broken.dat");Files.write(p,new byte[]{31,(byte)139,0});assertThrows(IOException.class,()->DeliveryReceipt.saved(p,"receipt"));}
}
