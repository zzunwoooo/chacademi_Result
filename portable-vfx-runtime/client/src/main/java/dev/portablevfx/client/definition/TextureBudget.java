package dev.portablevfx.client.definition;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Prevents tiny compressed resource files from declaring enormous native GPU allocations. */
public final class TextureBudget {
    public static final long MAX_TOTAL_TEXELS = 16_777_216;
    private TextureBudget() { }
    public static long texels(String path, byte[] bytes) {
        int width, height;
        var b = ByteBuffer.wrap(bytes);
        if (path.endsWith(".png")) {
            if (bytes.length < 24 || b.getLong() != 0x89504e470d0a1a0aL || b.getInt() != 13 || b.getInt() != 0x49484452)
                throw new IllegalArgumentException("PNG requires valid IHDR");
            width=b.getInt(); height=b.getInt();
        } else if (path.endsWith(".dds")) {
            b.order(ByteOrder.LITTLE_ENDIAN);
            if (bytes.length < 128 || b.getInt() != 0x20534444 || b.getInt() != 124)
                throw new IllegalArgumentException("DDS requires valid header");
            height=b.getInt(12); width=b.getInt(16);
            int depth=b.getInt(24), caps2=b.getInt(112);
            if (depth>1 || caps2!=0) throw new IllegalArgumentException("Only 2D DDS textures supported");
        } else if (path.endsWith(".tga")) {
            if(bytes.length<18) throw new IllegalArgumentException("Invalid TGA header");
            b.order(ByteOrder.LITTLE_ENDIAN);
            width=Short.toUnsignedInt(b.getShort(12)); height=Short.toUnsignedInt(b.getShort(14));
        } else return 0;
        if(width<1 || height<1 || width>4096 || height>4096) throw new IllegalArgumentException("Texture dimensions must be 1..4096");
        return (long)width*height;
    }
}
