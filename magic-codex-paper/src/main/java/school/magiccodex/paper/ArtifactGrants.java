package school.magiccodex.paper;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.node.Node;
import org.bukkit.Bukkit;

/** Grants artifact ownership as a LuckPerms permission. Kept in its own class so LuckPerms stays optional at runtime. */
final class ArtifactGrants {
    private ArtifactGrants() {}

    /** null when LuckPerms is not installed. */
    static CompletableFuture<Void> grant(UUID player, String permission) {
        LuckPerms lp = Bukkit.getServicesManager().load(LuckPerms.class);
        if (lp == null) return null;
        return lp.getUserManager().modifyUser(player, user -> user.data().add(Node.builder(permission).value(true).build()));
    }

    /** Removes a permission node granted by {@link #grant}; null when LuckPerms is not installed. */
    static CompletableFuture<Void> revoke(UUID player, String permission) {
        LuckPerms lp = Bukkit.getServicesManager().load(LuckPerms.class);
        if (lp == null) return null;
        return lp.getUserManager().modifyUser(player, user -> user.data().remove(Node.builder(permission).value(true).build()));
    }

    static boolean available() {
        try { return Bukkit.getServicesManager().load(LuckPerms.class) != null; }
        catch (NoClassDefFoundError e) { return false; }
    }
}
