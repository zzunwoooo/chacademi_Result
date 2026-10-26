package school.chacademi.ops;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * 마인크래프트 RCON 클라이언트 (명령 한 번 = 연결 → 인증 → 실행 → 닫기).
 * server.properties: enable-rcon=true, rcon.port, rcon.password. 서버는 127.0.0.1 에서만 받게 둔다 (방화벽).
 */
final class Rcon {
    static final int AUTH = 3, EXEC = 2;

    record Packet(int id, int type, String body) { }

    private Rcon() { }

    static String exec(String host, int port, String password, String command, int timeoutMs) throws IOException {
        if (password == null || password.isEmpty()) throw new IOException("RCON 비밀번호가 설정되지 않음");
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            s.setSoTimeout(timeoutMs);
            OutputStream out = s.getOutputStream();
            DataInputStream in = new DataInputStream(s.getInputStream());
            out.write(encode(new Packet(1, AUTH, password)));
            out.flush();
            Packet auth = read(in);
            // 일부 구현은 빈 RESPONSE_VALUE(type 0) 를 먼저 보낸다
            if (auth.type() == 0 && auth.id() != -1) auth = read(in);
            if (auth.id() == -1) throw new IOException("RCON 비밀번호가 틀림");
            out.write(encode(new Packet(2, EXEC, command)));
            out.flush();
            StringBuilder sb = new StringBuilder();
            Packet first = read(in);
            sb.append(first.body());
            // 4KB 넘는 응답은 여러 패킷으로 온다. 잠깐 더 기다려 이어 붙인다.
            s.setSoTimeout(300);
            try {
                while (true) { Packet more = read(in); if (more.id() != 2) break; sb.append(more.body()); }
            } catch (SocketTimeoutException | java.io.EOFException done) { /* 끝 (더 없음 또는 서버가 닫음) */ }
            catch (IOException ignored) { /* 첫 응답은 이미 받음 */ }
            return stripColors(sb.toString());
        }
    }

    static byte[] encode(Packet p) {
        byte[] body = p.body().getBytes(StandardCharsets.UTF_8);
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + 4 + body.length + 2).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(4 + 4 + body.length + 2);
        b.putInt(p.id());
        b.putInt(p.type());
        b.put(body);
        b.put((byte) 0).put((byte) 0);
        return b.array();
    }

    static Packet read(DataInputStream in) throws IOException {
        byte[] lenBytes = new byte[4];
        in.readFully(lenBytes);
        int len = ByteBuffer.wrap(lenBytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (len < 10 || len > 1 << 20) throw new IOException("RCON 패킷 길이 이상: " + len);
        byte[] rest = new byte[len];
        in.readFully(rest);
        ByteBuffer b = ByteBuffer.wrap(rest).order(ByteOrder.LITTLE_ENDIAN);
        int id = b.getInt();
        int type = b.getInt();
        String body = new String(rest, 8, len - 10, StandardCharsets.UTF_8);
        return new Packet(id, type, body);
    }

    static Packet decode(byte[] bytes) throws IOException {
        return read(new DataInputStream(new java.io.ByteArrayInputStream(bytes)));
    }

    /** §a 같은 색 코드 제거. */
    static String stripColors(String s) {
        return s.replaceAll("§[0-9a-fk-orA-FK-ORxX]", "");
    }
}
