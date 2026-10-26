package school.magiccodex.discovery;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import org.bukkit.Bukkit;

/** Serialize permission writes so a delayed grant cannot overwrite a newer admin removal. */
final class LuckPermsGrant {
    private final PermissionWriteQueue writes = new PermissionWriteQueue();

    /** 플레이어당 한 번의 modifyUser로 필요한 노드만 바꾼다. 이미 모두 맞으면 저장 자체를 하지 않는다. */
    CompletableFuture<Void> set(MagicDiscovery plugin, UUID id, Map<String, Boolean> wanted) {
        MagicDiscovery.main();
        var api = Bukkit.getServicesManager().load(LuckPerms.class);
        if (api == null) return CompletableFuture.failedFuture(new IllegalStateException("LuckPerms service unavailable"));
        var nodes = Map.copyOf(wanted);
        // 접속 중이면 이미 로드된 유저로 먼저 확인한다. 대기 중인 쓰기가 있으면 순서 보장을 위해 반드시 줄을 선다.
        var cached = api.getUserManager().getUser(id);
        if (cached != null && !writes.busy(id) && changes(cached, nodes).isEmpty()) return CompletableFuture.completedFuture(null);
        return writes.enqueue(id, () ->
                api.getUserManager().modifyUser(id, user -> {
                    for (var change : changes(user, nodes).entrySet()) {
                        user.data().remove(Node.builder(change.getKey()).value(true).build());
                        user.data().remove(Node.builder(change.getKey()).value(false).build());
                        user.data().add(Node.builder(change.getKey()).value(change.getValue()).build());
                    }
                }));
    }

    /** 컨텍스트·만료가 없는 노드만 본다. 원하는 값의 노드만 정확히 있을 때를 제외하고 변경 대상으로 돌려준다. */
    private static Map<String, Boolean> changes(User user, Map<String, Boolean> wanted) {
        var present = new HashMap<String, Integer>();
        for (Node node : user.data().toCollection()) {
            if (!wanted.containsKey(node.getKey()) || node.hasExpiry() || !node.getContexts().isEmpty()) continue;
            present.merge(node.getKey(), node.getValue() ? 1 : 2, (a, b) -> a | b);
        }
        var out = new LinkedHashMap<String, Boolean>();
        wanted.forEach((permission, learned) -> { if (present.getOrDefault(permission, 0) != (learned ? 1 : 2)) out.put(permission, learned); });
        return out;
    }
}
