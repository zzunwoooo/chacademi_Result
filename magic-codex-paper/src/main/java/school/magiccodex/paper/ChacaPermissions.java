package school.magiccodex.paper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.node.Node;

/**
 * 보유 판정용 권한(빗자루·가구·펫·레시피 등)을 명령으로 주고 빼기. LuckPerms가 없으면 실패한다.
 * LuckPerms 클래스는 LP 내부 클래스에서만 쓴다(LuckPerms가 없어도 이 클래스는 로드된다).
 */
final class ChacaPermissions {
    private final MagicCodexBridge plugin;

    ChacaPermissions(MagicCodexBridge plugin) { this.plugin = plugin; }

    private boolean available() { return plugin.getServer().getPluginManager().isPluginEnabled("LuckPerms"); }

    CompletableFuture<Void> set(UUID id, String node, boolean grant) {
        if (!available()) return CompletableFuture.failedFuture(new IllegalStateException("LuckPerms가 없어 권한을 바꿀 수 없습니다"));
        return LP.set(plugin, id, node, grant);
    }

    CompletableFuture<List<String>> list(UUID id, String prefix) {
        if (!available()) return CompletableFuture.failedFuture(new IllegalStateException("LuckPerms가 없어 권한을 읽을 수 없습니다"));
        return LP.list(plugin, id, prefix);
    }

    private static final class LP {
        private static LuckPerms api(MagicCodexBridge plugin) {
            var provider = plugin.getServer().getServicesManager().getRegistration(LuckPerms.class);
            if (provider == null) throw new IllegalStateException("LuckPerms 서비스를 찾지 못했습니다");
            return provider.getProvider();
        }

        static CompletableFuture<Void> set(MagicCodexBridge plugin, UUID id, String node, boolean grant) {
            return api(plugin).getUserManager().modifyUser(id, user -> {
                var n = Node.builder(node).value(true).build();
                if (grant) user.data().add(n);
                else user.data().remove(n);
            });
        }

        static CompletableFuture<List<String>> list(MagicCodexBridge plugin, UUID id, String prefix) {
            return api(plugin).getUserManager().loadUser(id).thenApply(user -> {
                var out = new ArrayList<String>();
                for (var n : user.getNodes()) if (n.getValue() && n.getKey().startsWith(prefix)) out.add(n.getKey());
                return out;
            });
        }
    }
}
