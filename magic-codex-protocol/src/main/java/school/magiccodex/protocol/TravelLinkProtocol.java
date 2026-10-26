package school.magiccodex.protocol;

import java.io.*;
import java.util.UUID;

/**
 * 지도 이동: game server <-> Velocity (2026-10-23), channel {@link #LINK}. Clients never see or send it (the proxy
 * swallows it in both directions).
 *
 * The origin server has already checked the point, the unlock and the rules, and written the ticket (destination,
 * expiry) to the shared DB. It only asks the proxy to connect the player to the destination server:
 *   server -> proxy  TRANSFER  uuid player, long ticket, UTF proxyServer
 * The destination server reads the ticket when the player's character data is loaded and moves them; the proxy is
 * not trusted with coordinates and never says "arrived". If the proxy cannot connect the player it answers through
 * the player's (still current) origin connection:
 *   proxy -> server  FAILED    uuid player, long ticket, byte reason, UTF detail
 */
public final class TravelLinkProtocol {
    public static final String LINK = "chacademi:travel_link";
    public static final int VERSION = 1, MAX_BYTES = 512;
    public static final int TRANSFER = 1, FAILED = 2;
    /** Why the proxy could not connect: unknown server name, offline (ping failed), refused (full / kicked / whitelist), busy (already moving or in a match), other. */
    public static final int NO_SERVER = 1, OFFLINE = 2, REFUSED = 3, BUSY = 4, OTHER = 5;

    public sealed interface Message permits Transfer, Failed {}
    public record Transfer(UUID player, long ticket, String server) implements Message {
        public Transfer { if (player == null || ticket == 0 || server == null || !server.matches("[A-Za-z0-9_\\-]{1,48}")) throw new IllegalArgumentException("travel transfer"); }
    }
    public record Failed(UUID player, long ticket, int reason, String detail) implements Message {
        public Failed { if (player == null || ticket == 0 || reason < NO_SERVER || reason > OTHER || detail == null || detail.length() > 120) throw new IllegalArgumentException("travel failed"); }
    }

    public static byte[] encode(Message m) {
        try {
            var b = new ByteArrayOutputStream(); var d = new DataOutputStream(b);
            d.writeByte(VERSION);
            switch (m) {
                case Transfer t -> { d.writeByte(TRANSFER); uuid(d, t.player()); d.writeLong(t.ticket()); d.writeUTF(t.server()); }
                case Failed f -> { d.writeByte(FAILED); uuid(d, f.player()); d.writeLong(f.ticket()); d.writeByte(f.reason()); d.writeUTF(f.detail()); }
            }
            if (b.size() > MAX_BYTES) throw new IOException("too large");
            return b.toByteArray();
        } catch (IOException | RuntimeException e) { throw new IllegalArgumentException("Invalid travel link packet", e); }
    }

    public static Message decode(byte[] bytes) {
        try {
            if (bytes == null || bytes.length > MAX_BYTES) throw new IOException("size");
            var d = new DataInputStream(new ByteArrayInputStream(bytes));
            if (d.readUnsignedByte() != VERSION) throw new IOException("version");
            int kind = d.readUnsignedByte();
            Message m = switch (kind) {
                case TRANSFER -> new Transfer(uuid(d), d.readLong(), d.readUTF());
                case FAILED -> new Failed(uuid(d), d.readLong(), d.readUnsignedByte(), d.readUTF());
                default -> throw new IOException("kind " + kind);
            };
            if (d.available() != 0) throw new IOException("trailing");
            return m;
        } catch (IOException | RuntimeException e) { throw new IllegalArgumentException("Invalid travel link packet", e); }
    }

    private static void uuid(DataOutputStream d, UUID u) throws IOException { d.writeLong(u.getMostSignificantBits()); d.writeLong(u.getLeastSignificantBits()); }
    private static UUID uuid(DataInputStream d) throws IOException { return new UUID(d.readLong(), d.readLong()); }

    private TravelLinkProtocol() {}
}
