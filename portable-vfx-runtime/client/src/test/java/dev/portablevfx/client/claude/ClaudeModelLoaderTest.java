package dev.portablevfx.client.claude;

import com.google.gson.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

final class ClaudeModelLoaderTest {
    @Test void reflectsGeometryWindingAndTextureCoordinatesExactlyOnce()throws Exception {
        var f=new Fixture();var model=ClaudeModelLoader.load(f.bytes());var p=model.meshes().get(0).primitives().get(0);
        assertEquals(3,p.vertexCount());assertEquals(-1,p.positions().get(0));assertEquals(1,p.normals().get(2));
        assertEquals(List.of(0,2,1),List.of(p.indices().get(0),p.indices().get(1),p.indices().get(2)));
        assertEquals(1,p.texcoords().get(1));assertEquals(0,p.texcoords().get(5));
        assertEquals(.4f,p.colors().get(1));assertThrows(ReadOnlyBufferException.class,()->p.positions().put(0,9));
        var pose=ClaudeModelAnimator.sample(model,null,0,false);
        assertEquals(-1,pose.global(1).m30());assertEquals(2,pose.nodeCount());assertEquals(List.of(0,1),model.sceneOrder());
        pose.global(1).identity();assertEquals(-1,pose.global(1).m30(),"Pose matrices must not be externally mutable");
    }
    @Test void samplesHierarchyTranslationScaleAndShortestQuaternionSlerp()throws Exception {
        var f=new Fixture();f.animate();var model=ClaudeModelLoader.load(f.bytes());
        var middle=ClaudeModelAnimator.sample(model,"move",1,false);var m=middle.global(1);
        assertEquals(-2,m.m30(),1e-6);assertEquals(0,m.m31(),1e-6);
        var direction=m.transformDirection(new Vector3f(0,0,1));assertEquals(-1,direction.x,1e-5);assertEquals(0,direction.z,1e-5);
        assertEquals(2,m.getScale(new Vector3f()).x,1e-5);assertEquals(1.5,m.getScale(new Vector3f()).y,1e-5);
        assertEquals(-3,ClaudeModelAnimator.sample(model,"move",9,false).global(1).m30(),1e-6);
        assertEquals(-1,ClaudeModelAnimator.sample(model,"move",-9,false).global(1).m30(),1e-6);
        assertEquals(-2,ClaudeModelAnimator.sample(model,"move",3,true).global(1).m30(),1e-6);
        assertEquals(-2,ClaudeModelAnimator.sample(model,"move",-1,true).global(1).m30(),1e-6);
        assertEquals(0,ClaudeModelAnimator.clipTime(5,0,true));
        assertThrows(IllegalArgumentException.class,()->ClaudeModelAnimator.sample(model,"missing",0,false));
        assertThrows(IllegalArgumentException.class,()->ClaudeModelAnimator.sample(model,"move",Double.NaN,false));
    }
    @Test void normalizedIntegerColorsAndUvsAndStridedPositionsAreReadCorrectly()throws Exception {
        var f=new Fixture();
        int color=f.accessor(new byte[]{(byte)255,0,(byte)128,(byte)255,0,(byte)255,0,(byte)255,0,0,(byte)255,(byte)255},5121,"VEC4",3);
        f.accessor(color).addProperty("normalized",true);f.attributes().addProperty("COLOR_0",color);
        byte[] packed=ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN).putFloat(1).putFloat(0).putFloat(0).putFloat(99)
                .putFloat(0).putFloat(1).putFloat(0).putFloat(99).putFloat(0).putFloat(0).putFloat(1).putFloat(99).array();
        int positions=f.accessor(packed,5126,"VEC3",3);f.view(positions).addProperty("byteStride",16);f.attributes().addProperty("POSITION",positions);
        var p=ClaudeModelLoader.load(f.bytes()).meshes().get(0).primitives().get(0);
        assertEquals(128/255f,p.colors().get(2),1e-6);assertEquals(-1,p.positions().get(0));assertEquals(1,p.positions().get(4));
    }
    @Test void rejectsCorruptContainerAndStrictJson()throws Exception {
        var f=new Fixture();byte[] good=f.bytes();
        byte[] version=good.clone();version[4]=1;reject(version);
        byte[] length=good.clone();length[8]--;reject(length);
        byte[] chunk=good.clone();chunk[16]=0;reject(chunk);
        reject(Arrays.copyOf(good,good.length-1));
        reject(Fixture.glb("{\"asset\":{},\"asset\":{}}",new byte[0]));
        reject(Fixture.glb("{/*comment*/\"asset\":{}}",new byte[0]));
        reject(Fixture.glb("{\"asset\":{}} {}",new byte[0]));
        reject(Fixture.glb("[".repeat(60)+"]".repeat(60),new byte[0]));
    }
    @Test void rejectsExternalResourcesAndUnsupportedRequiredFeatures()throws Exception {
        for(String feature:List.of("skin","matrix","weights")) {
            var f=new Fixture();f.node(1).add(feature,feature.equals("matrix")?JsonParser.parseString("[1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1]"):new JsonPrimitive(0));reject(f.bytes());
        }
        var f=new Fixture();f.root.add("extensionsRequired",JsonParser.parseString("[\"KHR_draco_mesh_compression\"]"));reject(f.bytes());
        f=new Fixture();f.root.add("extensionsUsed",JsonParser.parseString("[\"KHR_lights_punctual\"]"));reject(f.bytes());
        f=new Fixture();f.primitive().add("extensions",JsonParser.parseString("{\"KHR_unknown\":{}}"));reject(f.bytes());
        f=new Fixture();f.primitive().addProperty("mode",5);reject(f.bytes());
        f=new Fixture();f.attributes().addProperty("JOINTS_0",0);reject(f.bytes());
        f=new Fixture();f.root.add("images",JsonParser.parseString("[{\"uri\":\"../../secret.png\"}]"));reject(f.bytes());
        f=new Fixture();f.bufferExtra="../../secret.bin";reject(f.bytes());
        f=new Fixture();f.accessor(0).add("sparse",new JsonObject());reject(f.bytes());
        f=new Fixture();f.animate();f.root.getAsJsonArray("animations").get(0).getAsJsonObject().getAsJsonArray("samplers").get(0).getAsJsonObject().addProperty("interpolation","CUBICSPLINE");reject(f.bytes());
    }
    @Test void rejectsAccessorRangesIndicesAndNonfiniteOrInvalidChannels()throws Exception {
        var f=new Fixture();f.accessor(0).addProperty("count",Integer.MAX_VALUE);reject(f.bytes());
        f=new Fixture();f.accessor(0).addProperty("byteOffset",4);reject(f.bytes());
        f=new Fixture();f.view(0).addProperty("byteStride",8);reject(f.bytes());
        f=new Fixture();f.view(0).addProperty("byteOffset",2);reject(f.bytes());
        f=new Fixture();f.accessor(0).addProperty("normalized",true);reject(f.bytes());
        f=new Fixture();f.overwriteFloat(0,Float.NaN);reject(f.bytes());
        f=new Fixture();f.overwriteFloat(0,Float.POSITIVE_INFINITY);reject(f.bytes());
        f=new Fixture();f.primitive().addProperty("indices",f.accessor(new byte[]{0,0,1,0,9,0},5123,"SCALAR",3));reject(f.bytes());
        f=new Fixture();f.animate();var channels=f.root.getAsJsonArray("animations").get(0).getAsJsonObject().getAsJsonArray("channels");channels.add(channels.get(0).deepCopy());reject(f.bytes());
        f=new Fixture();f.animate();int input=f.root.getAsJsonArray("animations").get(0).getAsJsonObject().getAsJsonArray("samplers").get(0).getAsJsonObject().get("input").getAsInt();
        int offset=f.view(input).get("byteOffset").getAsInt();f.overwriteFloat(offset+4,0);reject(f.bytes());
    }
    @Test void rejectsMalformedHierarchyAndBudgetsBeforeAllocation()throws Exception {
        var f=new Fixture();f.node(1).add("children",JsonParser.parseString("[0]"));reject(f.bytes());
        f=new Fixture();f.node(0).add("children",JsonParser.parseString("[1,1]"));reject(f.bytes());
        f=new Fixture();f.node(0).remove("children");reject(f.bytes());
        f=new Fixture();f.node(1).add("rotation",JsonParser.parseString("[0,0,0,0]"));reject(f.bytes());
        byte[] bytes=new Fixture().bytes();var d=ClaudeModelLoader.Limits.DEFAULT;
        assertThrows(IOException.class,()->ClaudeModelLoader.load(bytes,new ClaudeModelLoader.Limits(32,d.decodedBytes(),d.nodes(),d.primitives(),d.accessors(),d.vertices(),d.indices(),d.animationKeys())));
        assertThrows(IOException.class,()->ClaudeModelLoader.load(bytes,new ClaudeModelLoader.Limits(d.encodedBytes(),16,d.nodes(),d.primitives(),d.accessors(),d.vertices(),d.indices(),d.animationKeys())));
        assertThrows(IOException.class,()->ClaudeModelLoader.load(bytes,new ClaudeModelLoader.Limits(d.encodedBytes(),d.decodedBytes(),1,d.primitives(),d.accessors(),d.vertices(),d.indices(),d.animationKeys())));
    }
    @Test void unsafeModelPathsNeverReachDependencyReader()throws Exception {
        AtomicInteger reads=new AtomicInteger();
        for(String path:List.of("../x.glb","models/../x.glb","models//x.glb","models/x/../../y.glb","/models/x.glb","models/x.glb?secret","https://x/m.glb","models\\x.glb","models/x.dll"))
            assertThrows(IOException.class,()->ClaudeModelLoader.load(path,ref->{reads.incrementAndGet();return new Fixture().bytes();}));
        assertEquals(0,reads.get());ClaudeModelLoader.load("models/Folder/Model.glb",ref->{reads.incrementAndGet();return new Fixture().bytes();});assertEquals(1,reads.get());
    }
    @Test void materialFlagsAndFactorsArePreserved()throws Exception {
        var f=new Fixture();f.root.add("materials",JsonParser.parseString("[{\"name\":\"fin\",\"pbrMetallicRoughness\":{\"baseColorFactor\":[1,0.5,1,0.7],\"metallicFactor\":0,\"roughnessFactor\":0.6},\"alphaMode\":\"BLEND\",\"doubleSided\":true,\"extras\":{\"unlit\":true,\"noOutline\":true}}]"));
        f.primitive().addProperty("material",0);var m=ClaudeModelLoader.load(f.bytes()).materials().get(0);
        assertEquals(ClaudeModel.AlphaMode.BLEND,m.alphaMode());assertTrue(m.doubleSided());assertTrue(m.unlit());assertTrue(m.noOutline());assertEquals(.7f,m.baseColor().a());
    }
    @Test void embeddedPngRetainsPixelsSamplerAndRejectsCorruption()throws Exception {
        var f=new Fixture();var image=new java.awt.image.BufferedImage(1,2,java.awt.image.BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0,0,0xffff0000);image.setRGB(0,1,0xff0000ff);var bytes=new ByteArrayOutputStream();
        assertTrue(javax.imageio.ImageIO.write(image,"png",bytes));
        int imageAccessor=f.accessor(bytes.toByteArray(),5121,"SCALAR",bytes.size());int view=f.accessor(imageAccessor).get("bufferView").getAsInt();
        f.root.add("images",JsonParser.parseString("[{\"bufferView\":"+view+",\"mimeType\":\"image/png\"}]"));
        f.root.add("textures",JsonParser.parseString("[{\"source\":0}]"));
        f.root.add("materials",JsonParser.parseString("[{\"pbrMetallicRoughness\":{\"baseColorTexture\":{\"index\":0}}}]"));f.primitive().addProperty("material",0);
        var model=ClaudeModelLoader.load(f.bytes());assertEquals(8,model.decodedTextureBytes());var decoded=model.images().get(0);
        assertEquals(1,decoded.width());assertEquals(2,decoded.height());assertEquals(255,Byte.toUnsignedInt(decoded.rgba().get(2)),"Bottom blue row first");
        assertEquals(255,Byte.toUnsignedInt(decoded.rgba().get(4)),"Top red row second");
        var sampler=model.samplers().get(model.textures().get(0).sampler());assertEquals(10497,sampler.wrapS());assertEquals(9987,sampler.minFilter());
        byte[] corrupt=f.bin.toByteArray();int offset=f.view(imageAccessor).get("byteOffset").getAsInt();corrupt[offset+29]^=1;f.bin.reset();f.bin.writeBytes(corrupt);reject(f.bytes());
    }
    @Test @EnabledIfEnvironmentVariable(named="CLAUDE_MODEL_FIXTURE_DIR",matches=".+")
    void authenticWaterModelsKeepAllPrimitivesImagesAndClips()throws Exception {
        Path dir=Path.of(System.getenv("CLAUDE_MODEL_FIXTURE_DIR"));
        var spirit=ClaudeModelLoader.load(Files.readAllBytes(dir.resolve("WaterElemental.glb")));
        assertEquals(66,spirit.nodes().size());assertEquals(65,spirit.meshes().size());assertEquals(87,spirit.meshes().stream().mapToInt(m->m.primitives().size()).sum());
        assertEquals(4,spirit.images().size());assertEquals(Set.of("summon","idle","attack","dismiss"),spirit.animations().keySet());
        var dolphin=ClaudeModelLoader.load(Files.readAllBytes(dir.resolve("WaterDolphin.glb")));assertEquals(3,dolphin.nodes().size());assertEquals(2,dolphin.meshes().size());assertEquals(Set.of("swim"),dolphin.animations().keySet());
        for(var model:List.of(spirit,dolphin))for(var clip:model.animations().values())for(int i=0;i<=40;i++) {
            var pose=ClaudeModelAnimator.sample(model,clip.name(),clip.duration()*i/40.0,false);
            for(int node:model.sceneOrder())assertTrue(pose.global(node).isFinite());
        }
    }
    private static void reject(byte[] bytes) { assertThrows(IOException.class,()->ClaudeModelLoader.load(bytes)); }
    private static final class Fixture {
        final JsonObject root=new JsonObject();final ByteArrayOutputStream bin=new ByteArrayOutputStream();String bufferExtra;
        Fixture() {
            root.add("asset",JsonParser.parseString("{\"version\":\"2.0\"}"));root.addProperty("scene",0);root.add("scenes",JsonParser.parseString("[{\"nodes\":[0]}]"));
            root.add("nodes",JsonParser.parseString("[{\"name\":\"root\",\"translation\":[1,0,0],\"children\":[1]},{\"name\":\"child\",\"mesh\":0}]"));
            root.add("bufferViews",new JsonArray());root.add("accessors",new JsonArray());
            int p=floats("VEC3",3,1,0,0,0,1,0,0,0,1),n=floats("VEC3",3,0,0,1,0,0,1,0,0,1);
            int c=floats("VEC4",3,.2f,.4f,.8f,1,.2f,.4f,.8f,1,.2f,.4f,.8f,1),uv=floats("VEC2",3,0,0,1,0,0,1);
            int ix=accessor(new byte[]{0,0,1,0,2,0},5123,"SCALAR",3);
            root.add("meshes",JsonParser.parseString("[{\"primitives\":[{\"attributes\":{\"POSITION\":"+p+",\"NORMAL\":"+n+",\"COLOR_0\":"+c+",\"TEXCOORD_0\":"+uv+"},\"indices\":"+ix+"}]}]"));
        }
        int floats(String type,int count,float... values) {var b=ByteBuffer.allocate(values.length*4).order(ByteOrder.LITTLE_ENDIAN);for(float v:values)b.putFloat(v);return accessor(b.array(),5126,type,count);}
        int accessor(byte[] bytes,int component,String type,int count) {
            while(bin.size()%4!=0)bin.write(0);int offset=bin.size();bin.writeBytes(bytes);
            JsonObject v=new JsonObject();v.addProperty("buffer",0);v.addProperty("byteOffset",offset);v.addProperty("byteLength",bytes.length);
            var views=root.getAsJsonArray("bufferViews");views.add(v);JsonObject a=new JsonObject();a.addProperty("bufferView",views.size()-1);a.addProperty("componentType",component);a.addProperty("count",count);a.addProperty("type",type);
            var accessors=root.getAsJsonArray("accessors");accessors.add(a);return accessors.size()-1;
        }
        void animate() {
            int time=floats("SCALAR",2,0,2),translation=floats("VEC3",2,0,0,0,2,0,0),rotation=floats("VEC4",2,0,0,0,1,0,1,0,0),scale=floats("VEC3",2,1,1,1,3,2,1);
            root.add("animations",JsonParser.parseString("[{\"name\":\"move\",\"samplers\":[{\"input\":"+time+",\"output\":"+translation+"},{\"input\":"+time+",\"output\":"+rotation+"},{\"input\":"+time+",\"output\":"+scale+"}],\"channels\":[{\"sampler\":0,\"target\":{\"node\":1,\"path\":\"translation\"}},{\"sampler\":1,\"target\":{\"node\":1,\"path\":\"rotation\"}},{\"sampler\":2,\"target\":{\"node\":1,\"path\":\"scale\"}}]}]"));
        }
        JsonObject primitive(){return root.getAsJsonArray("meshes").get(0).getAsJsonObject().getAsJsonArray("primitives").get(0).getAsJsonObject();}
        JsonObject attributes(){return primitive().getAsJsonObject("attributes");}
        JsonObject accessor(int i){return root.getAsJsonArray("accessors").get(i).getAsJsonObject();}
        JsonObject view(int i){return root.getAsJsonArray("bufferViews").get(accessor(i).get("bufferView").getAsInt()).getAsJsonObject();}
        JsonObject node(int i){return root.getAsJsonArray("nodes").get(i).getAsJsonObject();}
        void overwriteFloat(int offset,float value){byte[] bytes=bin.toByteArray();ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putFloat(offset,value);bin.reset();bin.writeBytes(bytes);}
        byte[] bytes(){JsonObject buffer=new JsonObject();buffer.addProperty("byteLength",bin.size());if(bufferExtra!=null)buffer.addProperty("uri",bufferExtra);JsonArray buffers=new JsonArray();buffers.add(buffer);root.add("buffers",buffers);return glb(root.toString(),bin.toByteArray());}
        static byte[] glb(String json,byte[] binary){byte[] j=json.getBytes(StandardCharsets.UTF_8);int jl=(j.length+3)&~3,bl=(binary.length+3)&~3;var b=ByteBuffer.allocate(28+jl+bl).order(ByteOrder.LITTLE_ENDIAN);b.putInt(0x46546c67).putInt(2).putInt(b.capacity()).putInt(jl).putInt(0x4e4f534a).put(j);while(b.position()<20+jl)b.put((byte)' ');b.putInt(bl).putInt(0x004e4942).put(binary);return b.array();}
    }
}
