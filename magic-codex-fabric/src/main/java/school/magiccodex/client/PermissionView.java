package school.magiccodex.client;

import java.util.*;
import school.magiccodex.client.CodexData.Spell;
import school.magiccodex.protocol.PermissionProtocol;

/** Catalog generations prevent old replies from granting a newly loaded spell or a new connection. */
public final class PermissionView {
    private List<Spell> definitions = List.of();
    private List<Spell> displayed = List.of();
    private List<String> allPermissions = List.of();
    private PermissionProtocol.Request active;
    private long serial;
    public void replace(List<Spell> spells) {
        definitions = List.copyOf(spells); allPermissions=definitions.stream().map(Spell::permission).distinct().toList(); clear();
    }
    public List<String> allPermissions() { return allPermissions; }
    public void clear() {
        active = null;
        displayed = definitions.stream().map(s -> s.withPermission(null)).toList();
    }
    public List<Spell> spells() { return displayed; }
    public PermissionProtocol.Request active() { return active; }
    public PermissionProtocol.Request open() {
        return open(allPermissions);
    }
    /** Exactly the list {@link #open(List)} would request, so callers can compare without reopening. */
    public List<String> requestable(List<String> permissions) {
        return PermissionProtocol.fit(permissions.stream().filter(allPermissions::contains).distinct().toList());
    }
    public PermissionProtocol.Request open(List<String> permissions) {
        clear();
        active = new PermissionProtocol.Request(PermissionProtocol.OPEN, ++serial, requestable(permissions));
        return active;
    }
    public boolean apply(PermissionProtocol.Response response) {
        if (active == null || response.id() != active.id() || response.granted().size() != active.permissions().size()) return false;
        var granted = new HashMap<String, Boolean>();
        for (int i = 0; i < active.permissions().size(); i++) granted.put(active.permissions().get(i), response.granted().get(i));
        displayed = definitions.stream().map(s -> s.withPermission(granted.get(s.permission()))).toList();
        return true;
    }
}
