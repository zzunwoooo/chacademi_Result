package school.magiccodex.protocol;

import java.io.*;
import java.util.*;

/** Raw plugin-message format shared by Paper and Fabric. No command or grant operation exists. */
public final class PermissionProtocol {
    private PermissionProtocol() {}
    public static final String REQUEST = "magiccodex:permissions";
    public static final String RESPONSE = "magiccodex:permission_state";
    public static final int VERSION = 1, MAX_PERMISSIONS = 512, MAX_BYTES = 27000;
    public static final int OPEN = 1, CLOSE = 2, KEEPALIVE = 3;
    public record Request(int action, long id, List<String> permissions) {
        public Request { permissions = List.copyOf(permissions); }
    }
    public record Response(long id, List<Boolean> granted) {
        public Response { granted = List.copyOf(granted); }
    }
    /**
     * Largest valid OPEN list that encodes within {@link #MAX_BYTES}: invalid or duplicate names are
     * dropped and the list is cut (in order) at {@link #MAX_PERMISSIONS} entries or the byte budget.
     */
    public static List<String> fit(List<String> permissions) {
        var out = new ArrayList<String>(); Set<String> seen = new HashSet<>();
        int bytes = 4 + 1 + 8 + 2;
        for (String p : permissions) {
            if (out.size() >= MAX_PERMISSIONS) break;
            if (p == null || !p.matches("[a-z0-9_.-]{1,100}") || !seen.add(p)) continue;
            if (bytes + 2 + p.length() > MAX_BYTES) break;
            bytes += 2 + p.length(); out.add(p);
        }
        return List.copyOf(out);
    }
    public static byte[] encodeRequest(Request request) {
        return write(out -> {
            validate(request);
            out.writeInt(VERSION); out.writeByte(request.action()); out.writeLong(request.id());
            if (request.action() == OPEN) {
                out.writeShort(request.permissions().size());
                for (String permission : request.permissions()) out.writeUTF(permission);
            }
        });
    }
    public static Request decodeRequest(byte[] data) throws IOException {
        try (var in = input(data)) {
            version(in);
            int action = in.readUnsignedByte(); long id = in.readLong();
            var permissions = new ArrayList<String>();
            if (action == OPEN) {
                int count = count(in);
                for (int i = 0; i < count; i++) permissions.add(in.readUTF());
            }
            end(in);
            var request = new Request(action, id, permissions);
            validate(request);
            return request;
        }
    }
    public static byte[] encodeResponse(Response response) {
        return write(out -> {
            if (response.granted().size() > MAX_PERMISSIONS) throw new IOException("Too many results");
            out.writeInt(VERSION); out.writeLong(response.id()); out.writeShort(response.granted().size());
            for (int i = 0; i < response.granted().size(); i += 8) {
                int bits = 0;
                for (int bit = 0; bit < 8 && i + bit < response.granted().size(); bit++)
                    if (response.granted().get(i + bit)) bits |= 1 << bit;
                out.writeByte(bits);
            }
        });
    }
    public static Response decodeResponse(byte[] data) throws IOException {
        try (var in = input(data)) {
            version(in); long id = in.readLong(); int count = count(in);
            var granted = new ArrayList<Boolean>(count);
            for (int i = 0; i < count; i += 8) {
                int bits = in.readUnsignedByte();
                int used = Math.min(8, count - i);
                if ((bits >>> used) != 0) throw new IOException("Invalid padding bits");
                for (int bit = 0; bit < used; bit++) granted.add((bits & (1 << bit)) != 0);
            }
            end(in);
            return new Response(id, granted);
        }
    }
    private static void validate(Request request) throws IOException {
        if (request.action() < OPEN || request.action() > KEEPALIVE) throw new IOException("Unknown action");
        if (request.permissions().size() > MAX_PERMISSIONS || request.action() != OPEN && !request.permissions().isEmpty())
            throw new IOException("Invalid permission count");
        Set<String> seen = new HashSet<>();
        for (String p : request.permissions())
            if (!p.matches("[a-z0-9_.-]{1,100}") || !seen.add(p)) throw new IOException("Invalid or duplicate permission");
    }
    private static DataInputStream input(byte[] bytes) throws IOException {
        if (bytes.length > MAX_BYTES) throw new IOException("Packet too large");
        return new DataInputStream(new ByteArrayInputStream(bytes));
    }
    private static void version(DataInputStream in) throws IOException {
        if (in.readInt() != VERSION) throw new IOException("Unsupported protocol version");
    }
    private static int count(DataInputStream in) throws IOException {
        int count = in.readUnsignedShort();
        if (count > MAX_PERMISSIONS) throw new IOException("Too many permissions");
        return count;
    }
    private static void end(DataInputStream in) throws IOException {
        if (in.available() != 0) throw new IOException("Trailing bytes");
    }
    private interface Writer { void write(DataOutputStream out) throws IOException; }
    private static byte[] write(Writer writer) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(bytes)) { writer.write(out); }
            if (bytes.size() > MAX_BYTES) throw new IOException("Packet too large");
            return bytes.toByteArray();
        } catch (IOException error) { throw new IllegalArgumentException(error.getMessage(), error); }
    }
}
