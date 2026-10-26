package dev.portablevfx.client.claude;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.List;
import java.util.Map;

/** Immutable CPU model in schema Unity space. No GL objects, files, scripts, or Minecraft state. */
public final class ClaudeModel {
    public record Vec3(float x, float y, float z) { }
    public record Quaternion(float x, float y, float z, float w) { }
    public record Color(float r, float g, float b, float a) { }
    public record Node(String name, int parent, int mesh, Vec3 translation, Quaternion rotation,
                       Vec3 scale, List<Integer> children) {
        public Node { children = List.copyOf(children); }
    }
    public record Mesh(String name, List<Primitive> primitives) {
        public Mesh { primitives = List.copyOf(primitives); }
    }
    /** XYZ positions/normals, RGBA sRGB colors, bottom-left UVs; triangles have Unity winding. */
    public static final class Primitive {
        private final float[] positions, normals, colors, texcoords;
        private final int[] indices;
        private final int material;
        Primitive(float[] positions, float[] normals, float[] colors, float[] texcoords, int[] indices, int material) {
            this.positions=positions;this.normals=normals;this.colors=colors;this.texcoords=texcoords;
            this.indices=indices;this.material=material;
        }
        public int vertexCount() { return positions.length/3; }
        public int indexCount() { return indices.length; }
        public int material() { return material; }
        public FloatBuffer positions() { return FloatBuffer.wrap(positions).asReadOnlyBuffer(); }
        public FloatBuffer normals() { return FloatBuffer.wrap(normals).asReadOnlyBuffer(); }
        public FloatBuffer colors() { return FloatBuffer.wrap(colors).asReadOnlyBuffer(); }
        public FloatBuffer texcoords() { return FloatBuffer.wrap(texcoords).asReadOnlyBuffer(); }
        public IntBuffer indices() { return IntBuffer.wrap(indices).asReadOnlyBuffer(); }
    }
    public enum AlphaMode { OPAQUE, MASK, BLEND }
    public record Material(String name, Color baseColor, int texture, AlphaMode alphaMode, float alphaCutoff,
                           boolean doubleSided, boolean unlit, boolean noOutline, float metallic, float roughness) { }
    public record Sampler(int magFilter, int minFilter, int wrapS, int wrapT) { }
    public record Texture(int image, int sampler) { }
    /** RGBA rows are bottom-to-top, matching ClaudeTexture and the converted primitive UVs. */
    public static final class Image {
        private final String name;
        private final int width,height;
        private final byte[] png,rgba;
        Image(String name, int width, int height, byte[] png, byte[] rgba) {
            this.name=name;this.width=width;this.height=height;this.png=png;this.rgba=rgba;
        }
        public String name() { return name; }
        public int width() { return width; }
        public int height() { return height; }
        public ByteBuffer png() { return ByteBuffer.wrap(png).asReadOnlyBuffer(); }
        public ByteBuffer rgba() { return ByteBuffer.wrap(rgba).asReadOnlyBuffer(); }
    }
    public enum Target { TRANSLATION, ROTATION, SCALE }
    public static final class Channel {
        final int node;
        final Target target;
        final float[] times,values;
        Channel(int node, Target target, float[] times, float[] values) {
            this.node=node;this.target=target;this.times=times;this.values=values;
        }
        public int node() { return node; }
        public Target target() { return target; }
        public FloatBuffer times() { return FloatBuffer.wrap(times).asReadOnlyBuffer(); }
        public FloatBuffer values() { return FloatBuffer.wrap(values).asReadOnlyBuffer(); }
    }
    public record Animation(String name, float duration, List<Channel> channels) {
        public Animation { channels=List.copyOf(channels); }
    }
    private final List<Node> nodes;
    private final List<Mesh> meshes;
    private final List<Material> materials;
    private final List<Image> images;
    private final List<Texture> textures;
    private final List<Sampler> samplers;
    private final Map<String,Animation> animations;
    private final List<Integer> sceneOrder;
    private final long decodedTextureBytes,retainedBytes;
    ClaudeModel(List<Node> nodes,List<Mesh> meshes,List<Material> materials,List<Image> images,
                List<Texture> textures,List<Sampler> samplers,Map<String,Animation> animations,
                List<Integer> sceneOrder,long decodedTextureBytes,long retainedBytes) {
        this.nodes=List.copyOf(nodes);this.meshes=List.copyOf(meshes);this.materials=List.copyOf(materials);
        this.images=List.copyOf(images);this.textures=List.copyOf(textures);this.samplers=List.copyOf(samplers);
        this.animations=Map.copyOf(animations);this.sceneOrder=List.copyOf(sceneOrder);this.decodedTextureBytes=decodedTextureBytes;this.retainedBytes=retainedBytes;
    }
    public List<Node> nodes() { return nodes; }
    public List<Mesh> meshes() { return meshes; }
    public List<Material> materials() { return materials; }
    public List<Image> images() { return images; }
    public List<Texture> textures() { return textures; }
    public List<Sampler> samplers() { return samplers; }
    public Map<String,Animation> animations() { return animations; }
    /** Parent-first indices reachable in the single selected scene. */
    public List<Integer> sceneOrder() { return sceneOrder; }
    public long decodedTextureBytes() { return decodedTextureBytes; }
    /** Conservative retained CPU allocation charge, including decoded accessor/geometry storage. */
    public long retainedBytes() { return retainedBytes; }
}
