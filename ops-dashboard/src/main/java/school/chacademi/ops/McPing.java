package school.chacademi.ops;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 마인크래프트 서버 목록 핑 (상태 패킷). 게임 서버는 이걸 네트워크 스레드에서 처리해 틱에 영향이 없다.
 * 하트비트와 따로 "포트가 살아 있나" 를 본다. 프록시(Velocity)는 이것만으로 켜짐/접속자 수를 본다.
 */
final class McPing {
    record Result(boolean up, int online, int max, String version, long latencyMs, String error) {
        static Result down(String error) { return new Result(false, -1, -1, "", -1, error); }
    }

    private McPing() { }

    static Result ping(String host, int port, int timeoutMs) {
        long start = System.nanoTime();
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            s.setSoTimeout(timeoutMs);
            OutputStream out = s.getOutputStream();
            ByteArrayOutputStream hs = new ByteArrayOutputStream();
            writeVarInt(hs, 0x00);
            writeVarInt(hs, 769); // 1.21.4
            byte[] h = host.getBytes(StandardCharsets.UTF_8);
            writeVarInt(hs, h.length);
            hs.write(h);
            hs.write((port >> 8) & 0xFF);
            hs.write(port & 0xFF);
            writeVarInt(hs, 1); // status
            frame(out, hs.toByteArray());
            frame(out, new byte[]{0x00});
            out.flush();
            DataInputStream in = new DataInputStream(s.getInputStream());
            int length = readVarInt(in);
            if (length <= 0 || length > 1 << 21) throw new IOException("bad length");
            int id = readVarInt(in);
            if (id != 0) throw new IOException("bad packet " + id);
            int jsonLen = readVarInt(in);
            if (jsonLen < 0 || jsonLen > 1 << 21) throw new IOException("bad json length");
            byte[] json = new byte[jsonLen];
            in.readFully(json);
            long ms = (System.nanoTime() - start) / 1_000_000;
            return parse(new String(json, StandardCharsets.UTF_8), ms);
        } catch (IOException | RuntimeException e) {
            return Result.down(e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }
    }

    static Result parse(String json, long ms) {
        Map<String, Object> root = Json.parseObject(json);
        Map<String, Object> players = Json.obj(root.get("players"));
        Map<String, Object> version = Json.obj(root.get("version"));
        String name = Json.str(version, "name");
        return new Result(true, (int) Json.num(players, "online", -1), (int) Json.num(players, "max", -1),
                name == null ? "" : name, ms, "");
    }

    private static void frame(OutputStream out, byte[] body) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        writeVarInt(b, body.length);
        b.write(body);
        out.write(b.toByteArray());
    }

    static void writeVarInt(OutputStream out, int value) throws IOException {
        while (true) {
            if ((value & ~0x7F) == 0) { out.write(value); return; }
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
    }

    static int readVarInt(InputStream in) throws IOException {
        int value = 0, position = 0;
        while (true) {
            int b = in.read();
            if (b < 0) throw new IOException("closed");
            value |= (b & 0x7F) << position;
            if ((b & 0x80) == 0) return value;
            position += 7;
            if (position >= 32) throw new IOException("varint too big");
        }
    }
}
