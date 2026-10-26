package dev.portablevfx.client.claude;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;

/**
 * PNG-only CPU decoder plus GPU texture preparation. Bounds are checked before ImageIO can allocate or inflate
 * image data. Particle textures are prepared as BC7 when the result is visually faithful (see
 * {@link ClaudeTextureCodec}); the encoded blocks are cached on disk by PNG digest so only the first launch
 * after a pack change pays the encoding cost.
 */
final class ClaudeTexture {
    static final int MAX_DIMENSION = 2048;
    static final int MAX_PNG_BYTES = 4 * 1024 * 1024;
    static final int MAX_ASSET_PNG_BYTES = 32 * 1024 * 1024;
    static final int MAX_ASSET_RGBA_BYTES = 64 * 1024 * 1024;
    static final long MAX_BACKEND_RGBA_BYTES = 1024L * 1024 * 1024;
    private static final long SIGNATURE = 0x89504e470d0a1a0aL;
    private static final int IHDR = 0x49484452, PLTE = 0x504c5445, TRNS = 0x74524e53;
    private static final int IDAT = 0x49444154, IEND = 0x49454e44;

    record Decoded(int width, int height, byte[] rgba) { }
    /** GPU-ready pixels: {@link #RGBA8} rows or {@link #BC7} blocks, both bottom-up. {@code data.length} is the GPU cost. */
    record Gpu(int width, int height, int format, byte[] data) {
        long rawBytes() { return (long) width * height * 4; }
    }
    static final int RGBA8 = 0, BC7 = 1;
    private static final int CACHE_MAGIC = 0x50565458, CACHE_VERSION = 1; // "PVTX"
    private static final int CACHE_KEEP_RGBA = 0, CACHE_BC7 = 1;
    private static volatile Path cacheDirectory;
    private static final AtomicLong compressed = new AtomicLong(), kept = new AtomicLong(), cacheHits = new AtomicLong(),
            encodeNanos = new AtomicLong(), gpuBytes = new AtomicLong(), rawBytes = new AtomicLong();
    private ClaudeTexture() { }

    /** Compression is on unless {@code -Dportablevfx.claude.textureCompression=off}. */
    static boolean compressionEnabled() {
        return !"off".equalsIgnoreCase(System.getProperty("portablevfx.claude.textureCompression", "bc7"));
    }
    static void cacheDirectory(Path directory) { cacheDirectory = directory; }
    static void resetSummary() { for (var counter : new AtomicLong[]{compressed, kept, cacheHits, encodeNanos, gpuBytes, rawBytes}) counter.set(0); }
    static String summary() {
        return "textures bc7=" + compressed.get() + " rgba=" + kept.get() + " diskHits=" + cacheHits.get()
                + " encode=" + encodeNanos.get() / 1_000_000 + "ms gpu=" + gpuBytes.get() / 1048576 + "MiB (raw "
                + rawBytes.get() / 1048576 + "MiB)";
    }

    /**
     * Validated GPU texture for a PNG whose SHA-256 is {@code digest}. A disk-cache hit skips PNG decoding: entries
     * are only written after the same bytes passed full validation and encoding.
     */
    static Gpu prepare(byte[] png, String digest, long remainingRgbaBytes) throws IOException {
        if (!compressionEnabled()) return count(rgba(decode(png, remainingRgbaBytes)));
        Gpu cached = readCache(png, digest, remainingRgbaBytes);
        if (cached != null) { cacheHits.incrementAndGet(); return count(cached); }
        Decoded decoded = decode(png, remainingRgbaBytes);
        // Tiny textures (a 4x4 block is 16 bytes) gain nothing from block compression.
        if (ClaudeTextureCodec.compressedBytes(decoded.width(), decoded.height()) >= decoded.rgba().length) return count(rgba(decoded));
        long started = System.nanoTime();
        byte[] blocks = ClaudeTextureCodec.compressIfFaithful(decoded.rgba(), decoded.width(), decoded.height());
        encodeNanos.addAndGet(System.nanoTime() - started);
        writeCache(digest, decoded.width(), decoded.height(), blocks);
        return count(blocks == null ? rgba(decoded) : new Gpu(decoded.width(), decoded.height(), BC7, blocks));
    }
    private static Gpu rgba(Decoded decoded) { return new Gpu(decoded.width(), decoded.height(), RGBA8, decoded.rgba()); }
    private static Gpu count(Gpu gpu) {
        (gpu.format() == BC7 ? compressed : kept).incrementAndGet();
        gpuBytes.addAndGet(gpu.data().length); rawBytes.addAndGet(gpu.rawBytes());
        return gpu;
    }

    private static Path cacheFile(String digest) {
        Path directory = cacheDirectory;
        return directory == null || !digest.matches("[0-9a-f]{64}") ? null : directory.resolve(digest + ".v" + ClaudeTextureCodec.ENCODER_VERSION + ".pvtx");
    }
    /** Returns the cached texture, or null on miss. A "keep RGBA" entry decodes the PNG without re-encoding. */
    private static Gpu readCache(byte[] png, String digest, long remainingRgbaBytes) throws IOException {
        Path file = cacheFile(digest);
        if (file == null) return null;
        byte[] bytes;
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > 20 + (long) MAX_DIMENSION * MAX_DIMENSION) return null;
            bytes = Files.readAllBytes(file);
        } catch (IOException | SecurityException unreadable) { return null; }
        if (bytes.length < 20) return null;
        ByteBuffer in = ByteBuffer.wrap(bytes);
        int width = ByteBuffer.wrap(png).getInt(16), height = ByteBuffer.wrap(png).getInt(20);
        if (in.getInt() != CACHE_MAGIC || in.getInt() != CACHE_VERSION) return null;
        int kind = in.getInt();
        if (in.getInt() != width || in.getInt() != height) return null;
        touch(file);
        if (kind == CACHE_KEEP_RGBA && bytes.length == 20) return rgba(decode(png, remainingRgbaBytes));
        if (kind != CACHE_BC7 || bytes.length != 20 + ClaudeTextureCodec.compressedBytes(width, height)) return null;
        byte[] blocks = new byte[bytes.length - 20];
        in.get(blocks);
        return new Gpu(width, height, BC7, blocks);
    }
    private static void touch(Path file) {
        try { Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis())); } catch (IOException | SecurityException ignored) { }
    }
    /** Best effort: a failed write only means the next launch encodes again. */
    private static void writeCache(String digest, int width, int height, byte[] blocks) {
        Path file = cacheFile(digest);
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            ByteBuffer out = ByteBuffer.allocate(20 + (blocks == null ? 0 : blocks.length));
            out.putInt(CACHE_MAGIC).putInt(CACHE_VERSION).putInt(blocks == null ? CACHE_KEEP_RGBA : CACHE_BC7).putInt(width).putInt(height);
            if (blocks != null) out.put(blocks);
            Path temporary = Files.createTempFile(file.getParent(), digest, ".tmp");
            try {
                Files.write(temporary, out.array());
                try { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
                catch (AtomicMoveNotSupportedException e) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
            } finally { Files.deleteIfExists(temporary); }
        } catch (IOException | SecurityException ignored) { }
    }
    /** Removes cache entries not used for {@code days}; called once per resource reload. */
    static void pruneCache(int days) {
        Path directory = cacheDirectory;
        if (directory == null || !Files.isDirectory(directory)) return;
        long cutoff = System.currentTimeMillis() - days * 86_400_000L;
        try (var files = Files.list(directory)) {
            files.filter(f -> f.getFileName().toString().endsWith(".pvtx") || f.getFileName().toString().endsWith(".tmp")).forEach(f -> {
                try { if (Files.getLastModifiedTime(f).toMillis() < cutoff) Files.deleteIfExists(f); } catch (IOException | SecurityException ignored) { }
            });
        } catch (IOException | SecurityException ignored) { }
    }

    static Decoded decode(byte[] png, long remainingRgbaBytes) throws IOException {
        if (png == null || png.length < 45 || png.length > MAX_PNG_BYTES)
            throw invalid("PNG must be within the 4 MiB encoded byte budget");
        ByteBuffer header = ByteBuffer.wrap(png);
        if (header.getLong() != SIGNATURE || header.getInt() != 13 || header.getInt() != IHDR)
            throw invalid("PNG requires its signature and first IHDR chunk");
        int width = header.getInt(), height = header.getInt();
        if (width < 1 || height < 1 || width > MAX_DIMENSION || height > MAX_DIMENSION)
            throw invalid("PNG dimensions must be 1..2048");
        int rgbaBytes = width * height * 4;
        if (rgbaBytes > remainingRgbaBytes) throw invalid("Decoded PNG texture byte budget exceeded");
        // Ancillary text/profile chunks can themselves contain compressed data. Never give those
        // chunks to ImageIO; only the five image-essential chunk types reach the decoder.
        byte[] imageOnly = imageChunks(png);
        var readers = ImageIO.getImageReadersByFormatName("png");
        if (!readers.hasNext()) throw invalid("PNG decoder unavailable");
        ImageReader reader = readers.next();
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(imageOnly))) {
            reader.setInput(input, true, true);
            if (reader.getWidth(0) != width || reader.getHeight(0) != height)
                throw invalid("PNG decoded dimensions disagree with IHDR");
            BufferedImage image = reader.read(0);
            if (image == null || image.getWidth() != width || image.getHeight() != height)
                throw invalid("PNG decoder returned invalid image dimensions");
            byte[] rgba = new byte[rgbaBytes];
            int[] row = new int[width];
            // Interchange UVs use bottom-left origin; PNG scanlines are top-to-bottom.
            for (int y = 0, output = 0; y < height; y++) {
                image.getRGB(0, height - 1 - y, width, 1, row, 0, width);
                for (int argb : row) {
                    rgba[output++] = (byte) (argb >>> 16);
                    rgba[output++] = (byte) (argb >>> 8);
                    rgba[output++] = (byte) argb;
                    rgba[output++] = (byte) (argb >>> 24);
                }
            }
            return new Decoded(width, height, rgba);
        } catch (RuntimeException e) {
            throw new IOException("Invalid PNG texture: " + e.getMessage(), e);
        } finally { reader.dispose(); }
    }

    private static byte[] imageChunks(byte[] png) throws IOException {
        var stripped = new ByteArrayOutputStream(png.length);
        stripped.write(png, 0, 8);
        int offset = 8;
        boolean sawData = false, endedData = false, sawPalette = false, sawTransparency = false;
        while (offset <= png.length - 12) {
            var chunk = ByteBuffer.wrap(png, offset, png.length - offset);
            int length = chunk.getInt(), type = chunk.getInt();
            if (length < 0 || length > png.length - offset - 12) throw invalid("Truncated PNG chunk");
            CRC32 crc = new CRC32(); crc.update(png, offset + 4, length + 4);
            if (crc.getValue() != Integer.toUnsignedLong(chunk.getInt(offset + length + 8)))
                throw invalid("PNG chunk CRC mismatch");
            if (type == IHDR && (offset != 8 || length != 13)) throw invalid("Duplicate or malformed PNG IHDR");
            if (type == 0x6163544c || type == 0x6663544c || type == 0x66644154)
                throw invalid("Animated PNG textures are unsupported");
            if (type == PLTE) {
                if (sawPalette || sawData || length == 0 || length > 768 || length % 3 != 0) throw invalid("Invalid PNG palette");
                sawPalette = true;
            }
            if (type == TRNS) {
                if (sawTransparency || sawData || length > 256) throw invalid("Invalid PNG transparency");
                sawTransparency = true;
            }
            if (type == IDAT) {
                if (endedData) throw invalid("PNG IDAT chunks must be consecutive");
                sawData = true;
            } else if (sawData) endedData = true;
            boolean essential = type == IHDR || type == PLTE || type == TRNS || type == IDAT || type == IEND;
            if (!essential && (type & 0x20000000) == 0) throw invalid("Unknown critical PNG chunk");
            if (essential) stripped.write(png, offset, length + 12);
            offset += length + 12;
            if (type == IEND) {
                if (length != 0 || !sawData || offset != png.length) throw invalid("Malformed PNG end or trailing data");
                return stripped.toByteArray();
            }
        }
        throw invalid("PNG missing complete IEND chunk");
    }
    private static IOException invalid(String message) { return new IOException(message); }
}
