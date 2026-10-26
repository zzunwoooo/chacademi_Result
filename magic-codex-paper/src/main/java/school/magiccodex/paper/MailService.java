package school.magiccodex.paper;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.bukkit.inventory.ItemStack;
/** Trusted main-thread system-mail API. sourceKey must be the producer's durable operation ID. */
public interface MailService {
 CompletableFuture<UUID> sendSystem(String sourceKey,UUID recipient,String title,String body,List<ItemStack> attachments);
 /** Trusted recovery API; restoring a deleted mail does not reset attachment claim state. */
 CompletableFuture<Boolean> restoreDeletedMail(UUID recipient,UUID mailId);
}
