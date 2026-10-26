package dev.portablevfx.protocol;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Regression/security tests for bounded atomic catalog assembly; no server or GPU required. */
public final class CatalogReadinessV2Tests {
    private static final UUID EPOCH = new UUID(0x123456789abcdef0L, 0x1020304050607080L);
    private static int assertions;
    private CatalogReadinessV2Tests() { }
    public static void run() throws Exception {
        epoch(); smallAndWithdrawal(); futureBudget(); duplicateAndOrdering(); timeoutAndGenerations(); malformed();
        canonicalValidation(); actualInventory(); fuzz();
        System.out.println("Catalog readiness v2 tests passed (" + assertions + " assertions).");
    }
    private static void epoch() throws Exception {
        equal(EPOCH, CatalogReadinessV2.decodeEpoch(CatalogReadinessV2.encodeEpoch(EPOCH)), "epoch round trip");
        for (int n : new int[]{0, 15, 17, 100}) bad(() -> CatalogReadinessV2.decodeEpoch(new byte[n]));
        bad(() -> CatalogReadinessV2.decodeEpoch(null)); invalid(() -> CatalogReadinessV2.encodeEpoch(null));
    }
    private static void smallAndWithdrawal() throws Exception {
        var ids = Set.of("claude:z/test", "claude:a/test"); var receiver = new CatalogReadinessV2.Receiver(EPOCH);
        var chunks = CatalogReadinessV2.encode(EPOCH, 1, ids); equal(1, chunks.size(), "small single chunk");
        var outcome = receiver.accept(chunks.getFirst(), 0); check(outcome.started(), "small begins transfer"); equal(ids, outcome.complete(), "small complete");
        check(receiver.accept(chunks.getFirst(), 1).complete() == null, "completed replay not applied");
        var empty = receiver.accept(CatalogReadinessV2.encode(EPOCH, 2, Set.of()).getFirst(), 2);
        check(empty.started(), "empty withdraw begins"); equal(Set.of(), empty.complete(), "empty withdrawal atomic");
        check(!receiver.expire(1000), "no completed assembly to expire");
    }
    private static void futureBudget() throws Exception {
        var ids = ids(4096, 126); var chunks = CatalogReadinessV2.encode(EPOCH, 1, ids);
        equal(19, chunks.size(), "future bounded parts"); int bytes = 0;
        var receiver = new CatalogReadinessV2.Receiver(EPOCH); Set<String> complete = null;
        for (int i = 0; i < chunks.size(); i++) {
            byte[] packet = chunks.get(i); bytes += packet.length - CatalogReadinessV2.HEADER_BYTES;
            check(packet.length <= 28072 && packet.length < 32767, "every chunk below actual C2S limit");
            if (i < chunks.size() - 1) equal(28072, packet.length, "full packet boundary");
            var outcome = receiver.accept(packet, i); if (i < chunks.size() - 1) check(outcome.complete() == null, "future partial never complete");
            complete = outcome.complete();
        }
        equal(520196, bytes, "future serialized bytes"); equal(new HashSet<>(ids), complete, "4096 effects preserved");
        invalid(() -> CatalogReadinessV2.encode(EPOCH, 1, ids(4097, 25)));
        invalid(() -> CatalogReadinessV2.encode(EPOCH, 1, ids(4096, 128)));
    }
    private static void duplicateAndOrdering() throws Exception {
        var ids = ids(1245, 35); var chunks = CatalogReadinessV2.encode(EPOCH, 1, ids); equal(2, chunks.size(), "multi chunk fixture");
        var receiver = new CatalogReadinessV2.Receiver(EPOCH);
        var last = receiver.accept(chunks.getLast(), 0); check(last.started() && last.complete() == null, "last first is incomplete");
        var duplicate = receiver.accept(chunks.getLast(), 1); check(!duplicate.started() && duplicate.complete() == null, "identical duplicate ignored");
        equal(new HashSet<>(ids), receiver.accept(chunks.getFirst(), 2).complete(), "out of order full assembly");
        receiver = new CatalogReadinessV2.Receiver(EPOCH); receiver.accept(chunks.getFirst(), 0);
        final var conflictReceiver = receiver; byte[] conflict = chunks.getFirst().clone(); conflict[80] ^= 1;
        bad(() -> conflictReceiver.accept(conflict, 1));
        check(receiver.accept(chunks.getLast(), 2).complete() == null, "conflict cannot resurrect same transfer");
    }
    private static void timeoutAndGenerations() throws Exception {
        var ids = ids(1245, 35); var one = CatalogReadinessV2.encode(EPOCH, 1, ids); var two = CatalogReadinessV2.encode(EPOCH, 2, ids);
        var receiver = new CatalogReadinessV2.Receiver(EPOCH); receiver.accept(one.getFirst(), 0);
        receiver.accept(one.getFirst(), 150); check(!receiver.expire(199), "before fixed deadline");
        check(receiver.expire(200), "duplicate does not extend deadline"); check(receiver.accept(one.getLast(), 201).complete() == null, "late tail cannot revive");
        check(receiver.accept(two.getFirst(), 202).started(), "new generation restarts after timeout");
        equal(new HashSet<>(ids), receiver.accept(two.getLast(), 203).complete(), "new generation complete");
        receiver = new CatalogReadinessV2.Receiver(EPOCH); receiver.accept(one.getFirst(), 0);
        check(receiver.accept(two.getLast(), 1).started(), "new generation supersedes partial");
        check(receiver.accept(one.getLast(), 2).complete() == null, "old generation ignored");
        equal(new HashSet<>(ids), receiver.accept(two.getFirst(), 3).complete(), "current generation unaffected by old packet");
        var oldEpoch = CatalogReadinessV2.encode(UUID.randomUUID(), 3, Set.of("claude:a/a")).getFirst();
        final var current = receiver; bad(() -> current.accept(oldEpoch, 4));
        receiver = new CatalogReadinessV2.Receiver(EPOCH); receiver.accept(one.getFirst(), 100);
        check(receiver.expire(99), "clock rewind expires partial safely");
    }
    private static void malformed() throws Exception {
        var ids = ids(1245, 35); var chunks = CatalogReadinessV2.encode(EPOCH, 1, ids); byte[] first = chunks.getFirst();
        bad(() -> new CatalogReadinessV2.Receiver(EPOCH).accept(null, 0));
        for (int n : new int[]{0, 71, 72, 28073}) bad(() -> new CatalogReadinessV2.Receiver(EPOCH).accept(new byte[n], 0));
        bad(() -> new CatalogReadinessV2.Receiver(EPOCH).accept(Arrays.copyOf(first, first.length - 1), 0));
        for (int offset : new int[]{0, 20, 28, 30, 32, 36}) {
            byte[] invalid = first.clone();
            if (offset == 20) ByteBuffer.wrap(invalid).putLong(offset, 0);
            else if (offset == 28 || offset == 30) ByteBuffer.wrap(invalid).putShort(offset, (short)65535);
            else ByteBuffer.wrap(invalid).putInt(offset, -1);
            bad(() -> new CatalogReadinessV2.Receiver(EPOCH).accept(invalid, 0));
        }
        var receiver = new CatalogReadinessV2.Receiver(EPOCH); receiver.accept(first, 0);
        byte[] mismatch = chunks.getLast().clone(); ByteBuffer.wrap(mismatch).putInt(36, ids.size() - 1);
        bad(() -> receiver.accept(mismatch, 1));
        var hashReceiver = new CatalogReadinessV2.Receiver(EPOCH); hashReceiver.accept(first, 0);
        byte[] tampered = chunks.getLast().clone(); tampered[tampered.length - 1] ^= 1; bad(() -> hashReceiver.accept(tampered, 1));
        invalid(() -> CatalogReadinessV2.encode(EPOCH, 0, Set.of())); invalid(() -> CatalogReadinessV2.encode(null, 1, Set.of()));
        invalid(() -> CatalogReadinessV2.encode(EPOCH, 1, null)); invalid(() -> CatalogReadinessV2.encode(EPOCH, 1, List.of("claude:a/a", "claude:a/a")));
        invalid(() -> CatalogReadinessV2.encode(EPOCH, 1, Set.of("other:a"))); invalid(() -> CatalogReadinessV2.encode(EPOCH, 1, Set.of("claude:a/../b")));
    }
    private static void canonicalValidation() throws Exception {
        for (byte[] body : new byte[][]{
                body(2, "claude:z/z", "claude:a/a"), body(2, "claude:a/a", "claude:a/a"), body(1, "other:a"),
                body(2, "claude:a/a"), new byte[]{0, 0, 0, 0, 1}, new byte[]{0, 0, 0, 1, (byte)129},
                new byte[]{0, 0, 0, 1, 1, (byte)0xff}}) {
            byte[] packet = packet(body, ByteBuffer.wrap(body).getInt());
            bad(() -> new CatalogReadinessV2.Receiver(EPOCH).accept(packet, 0));
        }
        byte[] countMismatch = packet(body(1, "claude:a/a"), 2); bad(() -> new CatalogReadinessV2.Receiver(EPOCH).accept(countMismatch, 0));
    }
    private static void actualInventory() throws Exception {
        var stream = CatalogReadinessV2Tests.class.getResourceAsStream("/readiness-1245-ids.txt");
        if (stream == null) throw new AssertionError("Actual 1245-effect regression fixture missing");
        List<String> ids;
        try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.US_ASCII))) { ids = reader.lines().toList(); }
        equal(1245, ids.size(), "actual inventory effects"); var chunks = CatalogReadinessV2.encode(EPOCH, 1, ids); equal(2, chunks.size(), "actual two parts");
        equal(38188, chunks.stream().mapToInt(p -> p.length - 72).sum(), "actual canonical bytes");
        equal(28072, chunks.getFirst().length, "actual first chunk bytes"); equal(10260, chunks.getLast().length, "actual second chunk bytes");
        var receiver = new CatalogReadinessV2.Receiver(EPOCH); check(receiver.accept(chunks.getFirst(), 0).complete() == null, "actual first never ready");
        equal(Set.copyOf(ids), receiver.accept(chunks.getLast(), 1).complete(), "actual all IDs complete");
    }
    private static void fuzz() throws Exception {
        var random = new Random(827450L);
        for (int i = 0; i < 2000; i++) {
            byte[] packet = new byte[random.nextInt(300)]; random.nextBytes(packet);
            bad(() -> new CatalogReadinessV2.Receiver(EPOCH).accept(packet, 0));
        }
    }
    private static List<String> ids(int count, int length) {
        var ids = new ArrayList<String>(); String prefix = "claude:future/";
        for (int i = 0; i < count; i++) { String suffix = String.format(Locale.ROOT, "%04d", i); ids.add(prefix + "a".repeat(length - prefix.length() - suffix.length()) + suffix); }
        return ids;
    }
    private static byte[] body(int count, String... ids) throws Exception {
        var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes); out.writeInt(count);
        for (String id : ids) { byte[] raw = id.getBytes(StandardCharsets.US_ASCII); out.writeByte(raw.length); out.write(raw); }
        return bytes.toByteArray();
    }
    private static byte[] packet(byte[] body, int count) throws Exception {
        var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes); out.writeInt(2);
        out.writeLong(EPOCH.getMostSignificantBits()); out.writeLong(EPOCH.getLeastSignificantBits()); out.writeLong(1);
        out.writeShort(0); out.writeShort(1); out.writeInt(body.length); out.writeInt(count); out.write(MessageDigest.getInstance("SHA-256").digest(body)); out.write(body);
        return bytes.toByteArray();
    }
    private static void check(boolean condition, String label) { if (!condition) throw new AssertionError(label); assertions++; }
    private static void equal(Object expected, Object actual, String label) { check(expected.equals(actual), label + ": expected=" + expected + ", actual=" + actual); }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    private static void bad(Checked action) throws Exception { try { action.run(); } catch (ProtocolException expected) { assertions++; return; } throw new AssertionError("Malformed transfer accepted"); }
    private static void invalid(Checked action) throws Exception { try { action.run(); } catch (IllegalArgumentException expected) { assertions++; return; } throw new AssertionError("Invalid inventory accepted"); }
}
