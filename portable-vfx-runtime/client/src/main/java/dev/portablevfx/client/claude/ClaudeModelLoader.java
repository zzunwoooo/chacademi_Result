package dev.portablevfx.client.claude;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import dev.portablevfx.client.render.EffectBackend;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static dev.portablevfx.client.claude.ClaudeModel.*;

/**
 * Bounded GLB 2.0 node-TRS profile for schema modelParticle. Only embedded buffers/PNG,
 * TRIANGLES, POSITION/NORMAL/COLOR_0/TEXCOORD_0 and LINEAR TRS clips are accepted.
 * Unsupported extensions, skins, morphs, external URIs and rendering features fail closed.
 */
public final class ClaudeModelLoader {
    public record Limits(int encodedBytes,long decodedBytes,int nodes,int primitives,int accessors,
                         int vertices,int indices,int animationKeys) {
        public static final Limits DEFAULT=new Limits(16*1024*1024,128L*1024*1024,512,2048,8192,1_000_000,3_000_000,1_000_000);
        public Limits {
            if(encodedBytes<28||encodedBytes>64*1024*1024||decodedBytes<1||decodedBytes>512L*1024*1024
                    ||nodes<1||nodes>2048||primitives<1||primitives>8192||accessors<1||accessors>32768
                    ||vertices<1||vertices>4_000_000||indices<3||indices>12_000_000||animationKeys<1||animationKeys>4_000_000)
                throw new IllegalArgumentException("Invalid model limits");
        }
    }
    private record View(int offset,int length,int stride,int target) { }
    private record Accessor(View view,int offset,int count,int component,int lanes,boolean normalized,int stride) { }
    private final Limits limits;
    private final ByteBuffer binary;
    private final JsonObject json;
    private final List<View> views=new ArrayList<>();
    private final List<Accessor> accessors=new ArrayList<>();
    private final Map<Integer,float[]> decoded=new HashMap<>();
    private long usedBytes,textureBytes,vertices,indices,animationKeys;
    private int primitives;
    private ClaudeModelLoader(byte[] bytes,Limits limits)throws IOException {
        this.limits=Objects.requireNonNull(limits,"limits");
        if(bytes==null||bytes.length<28||bytes.length>limits.encodedBytes())throw bad("GLB encoded byte limit");
        var input=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if(input.getInt()!=0x46546c67||input.getInt()!=2||Integer.toUnsignedLong(input.getInt())!=bytes.length)
            throw bad("GLB requires version 2 and exact header length");
        int jsonLength=input.getInt(),jsonType=input.getInt();
        if(jsonType!=0x4e4f534a||jsonLength<2||jsonLength>4*1024*1024||jsonLength%4!=0||jsonLength>input.remaining()-8)
            throw bad("Invalid first GLB JSON chunk");
        String text;
        try {text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(input.slice(input.position(),jsonLength)).toString();}
        catch(java.nio.charset.CharacterCodingException e){throw bad("Invalid JSON UTF-8");}
        input.position(input.position()+jsonLength);
        int binLength=input.getInt(),binType=input.getInt();
        if(binType!=0x004e4942||binLength<0||binLength%4!=0||binLength!=input.remaining())
            throw bad("GLB requires exactly one final BIN chunk");
        binary=input.slice().order(ByteOrder.LITTLE_ENDIAN);
        try(var reader=new JsonReader(new StringReader(text))) {
            reader.setLenient(false);
            int[] tokens={0};json=object(readJson(reader,0,tokens),"$","asset","scene","scenes","nodes","meshes","animations",
                    "materials","images","textures","samplers","buffers","bufferViews","accessors","extensionsUsed","extensionsRequired");
            if(reader.peek()!=JsonToken.END_DOCUMENT)throw bad("Trailing JSON content");
        }catch(IllegalStateException|NumberFormatException e){throw new IOException("Malformed model JSON",e);}
    }
    public static ClaudeModel load(byte[] glb)throws IOException { return load(glb,Limits.DEFAULT); }
    public static ClaudeModel load(byte[] glb,Limits limits)throws IOException {
        try{return new ClaudeModelLoader(glb,limits).parse();}
        catch(IllegalArgumentException|IndexOutOfBoundsException e){throw new IOException("Invalid model: "+e.getMessage(),e);}
    }
    /** Reads one validated relative models/*.glb dependency; model files cannot request other files. */
    public static ClaudeModel load(String path,EffectBackend.Dependencies dependencies)throws IOException {
        validatePath(path);return load(dependencies.read(path));
    }
    public static void validatePath(String path)throws IOException {
        if(path==null||path.length()>240||!path.startsWith("models/")||!path.endsWith(".glb"))throw bad("Model path must be models/<name>.glb");
        for(String segment:path.split("/",-1))if(segment.isEmpty()||segment.equals(".")||segment.equals("..")
                ||!segment.matches("[A-Za-z0-9_.-]+"))throw bad("Unsafe model path");
    }
    private ClaudeModel parse()throws IOException {
        var asset=object(required(json,"asset"),"asset","version","minVersion","generator","copyright");
        if(!"2.0".equals(string(asset,"version",null))||!"2.0".equals(string(asset,"minVersion","2.0")))throw bad("Only glTF 2.0 supported");
        if(array(json,"extensionsRequired",0).size()!=0||array(json,"extensionsUsed",0).size()!=0)throw bad("glTF extensions unsupported");
        var buffers=array(json,"buffers",1);if(buffers.size()!=1)throw bad("Exactly one embedded buffer required");
        var buffer=object(buffers.get(0),"buffer","byteLength");int length=integer(buffer,"byteLength",-1,1,limits.encodedBytes());
        if(length>binary.limit()||binary.limit()-length>3)throw bad("Buffer byteLength does not match BIN chunk");
        for(int i=length;i<binary.limit();i++)if(binary.get(i)!=0)throw bad("BIN padding must be zero");
        var rawViews=array(json,"bufferViews",limits.accessors()+64);
        for(var e:rawViews) {
            var o=object(e,"bufferView","buffer","byteOffset","byteLength","byteStride","target");
            if(integer(o,"buffer",-1,0,0)!=0)throw bad("bufferView buffer must be 0");
            int offset=integer(o,"byteOffset",0,0,length),size=integer(o,"byteLength",-1,1,length);
            int stride=integer(o,"byteStride",0,0,252),target=integer(o,"target",0,0,34963);
            if((long)offset+size>length||offset%4!=0||(stride!=0&&(stride<4||stride%4!=0))
                    ||(target!=0&&target!=34962&&target!=34963))throw bad("Invalid bufferView range/alignment/stride/target");
            views.add(new View(offset,size,stride,target));
        }
        for(var e:array(json,"accessors",limits.accessors())) {
            var o=object(e,"accessor","bufferView","byteOffset","componentType","normalized","count","type","min","max");
            View view=views.get(index(o,"bufferView",views.size()));
            int type=integer(o,"componentType",-1,5120,5126),width=componentBytes(type);
            int lanes=switch(string(o,"type",null)){case "SCALAR"->1;case "VEC2"->2;case "VEC3"->3;case "VEC4"->4;default->throw bad("Unsupported accessor type");};
            int count=integer(o,"count",-1,1,Math.max(limits.indices(),Math.max(limits.vertices(),limits.animationKeys())));
            int offset=integer(o,"byteOffset",0,0,view.length());boolean normalized=bool(o,"normalized",false);
            int stride=view.stride()==0?width*lanes:view.stride();
            if((type==5125||type==5126)&&normalized)throw bad("FLOAT/UNSIGNED_INT accessor cannot be normalized");
            if(offset%width!=0||(view.offset()+offset)%width!=0||stride<width*lanes||stride%width!=0
                    ||(long)offset+(long)(count-1)*stride+(long)width*lanes>view.length())throw bad("Accessor range/alignment exceeds bufferView");
            if(o.has("min"))numbers(o.get("min"),lanes,"accessor.min",false);
            if(o.has("max"))numbers(o.get("max"),lanes,"accessor.max",false);
            accessors.add(new Accessor(view,offset,count,type,lanes,normalized,stride));
        }
        List<Image> images=parseImages();List<Sampler> samplers=parseSamplers();List<Texture> textures=parseTextures(images,samplers);
        List<Material> materials=parseMaterials(textures);List<Mesh> meshes=parseMeshes(materials);
        List<Node> nodes=parseNodes(meshes);List<Integer> order=sceneOrder(nodes);
        Map<String,Animation> animations=parseAnimations(nodes);
        return new ClaudeModel(nodes,meshes,materials,images,textures,samplers,animations,order,textureBytes,usedBytes);
    }
    private List<Image> parseImages()throws IOException {
        List<Image> result=new ArrayList<>();
        for(var e:array(json,"images",64)) {
            var o=object(e,"image","bufferView","mimeType");
            if(!"image/png".equals(string(o,"mimeType",null)))throw bad("Only embedded PNG model images supported");
            View v=views.get(index(o,"bufferView",views.size()));
            if(v.stride()!=0||v.target()!=0||v.length()>ClaudeTexture.MAX_PNG_BYTES)throw bad("Invalid embedded image bufferView");
            reserve(v.length());byte[] png=new byte[v.length()];binary.get(v.offset(),png);
            var decoded=ClaudeTexture.decode(png,Math.min(limits.decodedBytes()-usedBytes,64L*1024*1024-textureBytes));
            reserve(decoded.rgba().length);textureBytes+=decoded.rgba().length;
            result.add(new Image(string(o,"name",""),decoded.width(),decoded.height(),png,decoded.rgba()));
        }
        return result;
    }
    private List<Sampler> parseSamplers()throws IOException {
        List<Sampler> result=new ArrayList<>();
        for(var e:array(json,"samplers",64)) {
            var o=object(e,"sampler","magFilter","minFilter","wrapS","wrapT");
            int mag=integer(o,"magFilter",9729,9728,9729),min=integer(o,"minFilter",9987,9728,9987);
            int s=integer(o,"wrapS",10497,0,40000),t=integer(o,"wrapT",10497,0,40000);
            if(!Set.of(9728,9729,9984,9985,9986,9987).contains(min)||!Set.of(33071,33648,10497).contains(s)||!Set.of(33071,33648,10497).contains(t))throw bad("Unsupported texture sampler");
            result.add(new Sampler(mag,min,s,t));
        }
        result.add(new Sampler(9729,9987,10497,10497));return result;
    }
    private List<Texture> parseTextures(List<Image> images,List<Sampler> samplers)throws IOException {
        List<Texture> result=new ArrayList<>();
        for(var e:array(json,"textures",64)) {
            var o=object(e,"texture","source","sampler");
            result.add(new Texture(index(o,"source",images.size()),o.has("sampler")?index(o,"sampler",samplers.size()-1):samplers.size()-1));
        }return result;
    }
    private List<Material> parseMaterials(List<Texture> textures)throws IOException {
        List<Material> result=new ArrayList<>();
        for(var e:array(json,"materials",256)) {
            var o=object(e,"material","pbrMetallicRoughness","alphaMode","alphaCutoff","doubleSided");
            var p=o.has("pbrMetallicRoughness")?object(o.get("pbrMetallicRoughness"),"pbr","baseColorFactor","baseColorTexture","metallicFactor","roughnessFactor"):new JsonObject();
            float[] color=p.has("baseColorFactor")?numbers(p.get("baseColorFactor"),4,"baseColorFactor",true):new float[]{1,1,1,1};
            int texture=-1;
            if(p.has("baseColorTexture")) {
                var info=object(p.get("baseColorTexture"),"baseColorTexture","index","texCoord");
                if(integer(info,"texCoord",0,0,0)!=0)throw bad("Only TEXCOORD_0 supported");texture=index(info,"index",textures.size());
            }
            AlphaMode alpha;
            try{alpha=AlphaMode.valueOf(string(o,"alphaMode","OPAQUE"));}catch(IllegalArgumentException ex){throw bad("Invalid material alpha mode");}
            JsonObject extras=o.has("extras")?object(o.get("extras"),"material.extras","unlit","noOutline"):new JsonObject();
            result.add(new Material(string(o,"name",""),new Color(color[0],color[1],color[2],color[3]),texture,alpha,
                    number(o,"alphaCutoff",.5f,0,1),bool(o,"doubleSided",false),bool(extras,"unlit",false),bool(extras,"noOutline",false),
                    number(p,"metallicFactor",1,0,1),number(p,"roughnessFactor",1,0,1)));
        }
        result.add(new Material("default",new Color(1,1,1,1),-1,AlphaMode.OPAQUE,.5f,false,false,false,1,1));return result;
    }
    private List<Mesh> parseMeshes(List<Material> materials)throws IOException {
        List<Mesh> result=new ArrayList<>();
        for(var e:array(json,"meshes",limits.nodes())) {
            var o=object(e,"mesh","primitives");List<Primitive> parts=new ArrayList<>();
            var raw=array(o,"primitives",limits.primitives());if(raw.isEmpty())throw bad("Mesh requires primitives");
            for(var p:raw) {
                if(++primitives>limits.primitives())throw bad("Model primitive limit");
                var primitive=object(p,"primitive","attributes","indices","material","mode");
                if(integer(primitive,"mode",4,4,4)!=4)throw bad("Only TRIANGLES supported");
                var attrs=object(required(primitive,"attributes"),"attributes","POSITION","NORMAL","COLOR_0","TEXCOORD_0");
                int positionId=index(attrs,"POSITION",accessors.size()),normalId=index(attrs,"NORMAL",accessors.size());
                var a=accessors.get(positionId);require(a,3,5126,false,"POSITION");require(accessors.get(normalId),3,5126,false,"NORMAL");
                int count=a.count();vertices+=count;if(vertices>limits.vertices())throw bad("Model vertex limit");
                if(accessors.get(normalId).count()!=count)throw bad("NORMAL count differs from POSITION");
                reserve((long)count*12*4);float[] pos=floats(positionId).clone(),normal=floats(normalId).clone();
                float[] color=new float[count*4],uv=new float[count*2];Arrays.fill(color,1);
                for(int i=0;i<count;i++) {
                    pos[i*3]=-pos[i*3];normal[i*3]=-normal[i*3];
                    double length=Math.sqrt((double)normal[i*3]*normal[i*3]+(double)normal[i*3+1]*normal[i*3+1]+(double)normal[i*3+2]*normal[i*3+2]);
                    if(length<1e-8||Math.abs(length-1)>.02)throw bad("NORMAL must be unit length");
                    for(int k=0;k<3;k++)normal[i*3+k]/=(float)length;
                }
                if(attrs.has("COLOR_0")) {
                    int id=index(attrs,"COLOR_0",accessors.size());Accessor c=accessors.get(id);
                    if(c.count()!=count||(c.lanes()!=3&&c.lanes()!=4)||!attributeComponent(c))throw bad("Unsupported COLOR_0 accessor");
                    float[] values=floats(id);
                    for(int i=0;i<count;i++)for(int k=0;k<c.lanes();k++){float value=values[i*c.lanes()+k];if(value<0||value>1)throw bad("COLOR_0 outside 0..1");color[i*4+k]=value;}
                }
                if(attrs.has("TEXCOORD_0")) {
                    int id=index(attrs,"TEXCOORD_0",accessors.size());Accessor c=accessors.get(id);
                    if(c.count()!=count||c.lanes()!=2||!attributeComponent(c))throw bad("Unsupported TEXCOORD_0 accessor");
                    float[] values=floats(id);for(int i=0;i<count;i++){uv[i*2]=values[i*2];uv[i*2+1]=1-values[i*2+1];}
                }
                int material=primitive.has("material")?index(primitive,"material",materials.size()-1):materials.size()-1;
                if(materials.get(material).texture()>=0&&!attrs.has("TEXCOORD_0"))throw bad("Textured primitive needs TEXCOORD_0");
                int[] ix;
                if(primitive.has("indices"))ix=indices(index(primitive,"indices",accessors.size()),count);
                else {reserve((long)count*4);ix=new int[count];for(int i=0;i<count;i++)ix[i]=i;}
                indices+=ix.length;if(indices>limits.indices()||ix.length%3!=0)throw bad("Invalid triangle index count/limit");
                for(int i=0;i<ix.length;i+=3){int old=ix[i+1];ix[i+1]=ix[i+2];ix[i+2]=old;}
                parts.add(new Primitive(pos,normal,color,uv,ix,material));
            }
            result.add(new Mesh(string(o,"name",""),parts));
        }return result;
    }
    private List<Node> parseNodes(List<Mesh> meshes)throws IOException {
        var raw=array(json,"nodes",limits.nodes());if(raw.isEmpty())throw bad("Scene requires nodes");
        List<Node> nodes=new ArrayList<>();int[] parents=new int[raw.size()];Arrays.fill(parents,-1);
        for(int i=0;i<raw.size();i++) {
            var o=object(raw.get(i),"node","mesh","children","translation","rotation","scale");
            float[] t=o.has("translation")?numbers(o.get("translation"),3,"translation",false):new float[]{0,0,0};t[0]=-t[0];
            float[] q=o.has("rotation")?numbers(o.get("rotation"),4,"rotation",false):new float[]{0,0,0,1};convertQuaternion(q,0);
            float[] s=o.has("scale")?numbers(o.get("scale"),3,"scale",false):new float[]{1,1,1};
            List<Integer> children=new ArrayList<>();
            for(var c:array(o,"children",limits.nodes())) {
                int child=intValue(c,"child",0,raw.size()-1);
                if(child==i||parents[child]!=-1)throw bad("Cyclic/duplicate/multiple-parent node");parents[child]=i;children.add(child);
            }
            nodes.add(new Node(string(o,"name",""),-1,o.has("mesh")?index(o,"mesh",meshes.size()):-1,
                    new Vec3(t[0],t[1],t[2]),new Quaternion(q[0],q[1],q[2],q[3]),new Vec3(s[0],s[1],s[2]),children));
        }
        for(int i=0;i<nodes.size();i++){var n=nodes.get(i);nodes.set(i,new Node(n.name(),parents[i],n.mesh(),n.translation(),n.rotation(),n.scale(),n.children()));}
        return nodes;
    }
    private List<Integer> sceneOrder(List<Node> nodes)throws IOException {
        var scenes=array(json,"scenes",1);if(scenes.size()!=1||integer(json,"scene",0,0,0)!=0)throw bad("Exactly one default scene supported");
        var scene=object(scenes.get(0),"scene","nodes");List<Integer> order=new ArrayList<>();boolean[] seen=new boolean[nodes.size()];
        for(var r:array(scene,"nodes",limits.nodes())) {
            int root=intValue(r,"scene node",0,nodes.size()-1);if(nodes.get(root).parent()!=-1)throw bad("Scene root has parent");
            visit(root,nodes,seen,order,0);
        }
        if(order.size()!=nodes.size())throw bad("Unreachable/cyclic nodes are unsupported");return order;
    }
    private void visit(int index,List<Node> nodes,boolean[] seen,List<Integer> order,int depth)throws IOException {
        if(depth>128||seen[index])throw bad("Node cycle/duplicate or hierarchy depth limit");seen[index]=true;order.add(index);
        for(int child:nodes.get(index).children())visit(child,nodes,seen,order,depth+1);
    }
    private Map<String,Animation> parseAnimations(List<Node> nodes)throws IOException {
        Map<String,Animation> result=new LinkedHashMap<>();
        for(var e:array(json,"animations",128)) {
            var o=object(e,"animation","samplers","channels");String name=string(o,"name",null);
            if(name.isBlank()||result.containsKey(name))throw bad("Animation names must be nonempty and unique");
            var samplers=array(o,"samplers",limits.nodes()*3);var channels=array(o,"channels",limits.nodes()*3);
            if(samplers.isEmpty()||channels.isEmpty())throw bad("Animation requires samplers and channels");
            List<JsonObject> parsedSamplers=new ArrayList<>();
            for(var raw:samplers) {
                var sampler=object(raw,"animation sampler","input","output","interpolation");
                if(!"LINEAR".equals(string(sampler,"interpolation","LINEAR")))throw bad("Only LINEAR animations supported");
                index(sampler,"input",accessors.size());index(sampler,"output",accessors.size());parsedSamplers.add(sampler);
            }
            List<Channel> parsed=new ArrayList<>();Set<String> targets=new HashSet<>();float duration=0;
            for(var raw:channels) {
                var channel=object(raw,"channel","sampler","target");var target=object(required(channel,"target"),"target","node","path");
                int node=index(target,"node",nodes.size());String path=string(target,"path",null);
                Target kind=switch(path){case "translation"->Target.TRANSLATION;case "rotation"->Target.ROTATION;case "scale"->Target.SCALE;default->throw bad("Only TRS animation targets supported");};
                if(!targets.add(node+":"+path))throw bad("Duplicate animation node/path target");
                var sampler=parsedSamplers.get(index(channel,"sampler",parsedSamplers.size()));
                int input=index(sampler,"input",accessors.size()),output=index(sampler,"output",accessors.size());
                Accessor in=accessors.get(input),out=accessors.get(output);require(in,1,5126,false,"animation times");require(out,kind==Target.ROTATION?4:3,5126,false,"animation values");
                if(in.view().stride()!=0||out.view().stride()!=0||in.count()!=out.count())throw bad("Animation accessor count/stride mismatch");
                animationKeys+=in.count();if(animationKeys>limits.animationKeys())throw bad("Animation key limit");
                float[] times=floats(input);for(int i=0;i<times.length;i++)if(times[i]<0||(i>0&&times[i]<=times[i-1]))throw bad("Animation times must be nonnegative and strictly increasing");
                duration=Math.max(duration,times[times.length-1]);float[] values=floats(output);reserve((long)values.length*4);values=values.clone();
                if(kind==Target.ROTATION)for(int i=0;i<values.length;i+=4)convertQuaternion(values,i);
                else if(kind==Target.TRANSLATION)for(int i=0;i<values.length;i+=3)values[i]=-values[i];
                parsed.add(new Channel(node,kind,times,values));
            }
            result.put(name,new Animation(name,duration,parsed));
        }return result;
    }
    private float[] floats(int index)throws IOException {
        if(decoded.containsKey(index))return decoded.get(index);Accessor a=accessors.get(index);
        long size=(long)a.count()*a.lanes();reserve(size*4);float[] result=new float[Math.toIntExact(size)];int bytes=componentBytes(a.component());
        for(int i=0;i<a.count();i++)for(int k=0;k<a.lanes();k++) {
            int offset=a.view().offset()+a.offset()+i*a.stride()+k*bytes;
            float value=switch(a.component()) {
                case 5120->a.normalized()?Math.max(-1,binary.get(offset)/127f):binary.get(offset);
                case 5121->a.normalized()?Byte.toUnsignedInt(binary.get(offset))/255f:Byte.toUnsignedInt(binary.get(offset));
                case 5122->a.normalized()?Math.max(-1,binary.getShort(offset)/32767f):binary.getShort(offset);
                case 5123->a.normalized()?Short.toUnsignedInt(binary.getShort(offset))/65535f:Short.toUnsignedInt(binary.getShort(offset));
                case 5125->Integer.toUnsignedLong(binary.getInt(offset));case 5126->binary.getFloat(offset);default->throw bad("Unsupported component");
            };
            if(!Float.isFinite(value)||Math.abs(value)>1_000_000)throw bad("Non-finite/unbounded accessor value");result[i*a.lanes()+k]=value;
        }
        decoded.put(index,result);return result;
    }
    private int[] indices(int index,int vertexCount)throws IOException {
        Accessor a=accessors.get(index);
        if(a.lanes()!=1||a.normalized()||a.view().stride()!=0||!Set.of(5121,5123,5125).contains(a.component()))throw bad("Invalid index accessor");
        if(a.count()>limits.indices())throw bad("Index count limit");reserve((long)a.count()*4);int[] result=new int[a.count()];
        for(int i=0;i<a.count();i++) {
            int offset=a.view().offset()+a.offset()+i*a.stride();long value=switch(a.component()){
                case 5121->Byte.toUnsignedInt(binary.get(offset));case 5123->Short.toUnsignedInt(binary.getShort(offset));default->Integer.toUnsignedLong(binary.getInt(offset));};
            if(value>=vertexCount)throw bad("Triangle index outside vertex range");result[i]=(int)value;
        }return result;
    }
    private static boolean attributeComponent(Accessor a) {return a.component()==5126&&!a.normalized()||Set.of(5121,5123).contains(a.component())&&a.normalized();}
    private static void require(Accessor a,int lanes,int component,boolean normalized,String label)throws IOException {
        if(a.lanes()!=lanes||a.component()!=component||a.normalized()!=normalized)throw bad(label+" accessor layout unsupported");
    }
    private static void convertQuaternion(float[] q,int offset)throws IOException {
        double length=Math.sqrt((double)q[offset]*q[offset]+(double)q[offset+1]*q[offset+1]+(double)q[offset+2]*q[offset+2]+(double)q[offset+3]*q[offset+3]);
        if(length<1e-8||Math.abs(length-1)>.01)throw bad("Rotation quaternion must be unit length");
        q[offset]/=(float)length;q[offset+1]/=-(float)length;q[offset+2]/=-(float)length;q[offset+3]/=(float)length;
    }
    private void reserve(long bytes)throws IOException {if(bytes<0||bytes>limits.decodedBytes()-usedBytes)throw bad("Model decoded allocation budget");usedBytes+=bytes;}
    private static int componentBytes(int type)throws IOException{return switch(type){case 5120,5121->1;case 5122,5123->2;case 5125,5126->4;default->throw bad("Unsupported componentType");};}
    private static JsonElement readJson(JsonReader r,int depth,int[] tokens)throws IOException {
        if(depth>48||++tokens[0]>600_000)throw bad("Model JSON nesting/token limit");
        return switch(r.peek()) {
            case BEGIN_OBJECT->{var o=new JsonObject();r.beginObject();while(r.hasNext()){String k=r.nextName();if(k.length()>256||o.has(k))throw bad("Duplicate/oversized JSON key");o.add(k,readJson(r,depth+1,tokens));}r.endObject();yield o;}
            case BEGIN_ARRAY->{var a=new JsonArray();r.beginArray();while(r.hasNext())a.add(readJson(r,depth+1,tokens));r.endArray();yield a;}
            case STRING->{String s=r.nextString();if(s.length()>4096)throw bad("Model JSON string limit");yield new JsonPrimitive(s);}
            case NUMBER->{String n=r.nextString();if(n.length()>64)throw bad("Model JSON number limit");yield new JsonPrimitive(new BigDecimal(n));}
            case BOOLEAN->new JsonPrimitive(r.nextBoolean());case NULL->{r.nextNull();yield JsonNull.INSTANCE;}
            default->throw bad("Malformed model JSON");
        };
    }
    private static JsonObject object(JsonElement e,String label,String... allowed)throws IOException {
        if(e==null||!e.isJsonObject())throw bad(label+" must be an object");JsonObject o=e.getAsJsonObject();Set<String> known=new HashSet<>(List.of(allowed));
        known.add("name");known.add("extras");known.add("extensions");
        for(String key:o.keySet())if(!known.contains(key))throw bad(label+" unsupported property: "+key);
        if(o.has("extensions")&&(!o.get("extensions").isJsonObject()||!o.getAsJsonObject("extensions").isEmpty()))throw bad(label+" extensions unsupported");
        return o;
    }
    private static JsonArray array(JsonObject o,String key,int max)throws IOException {
        if(!o.has(key))return new JsonArray();var e=o.get(key);if(!e.isJsonArray()||e.getAsJsonArray().size()>max)throw bad(key+" array/count limit");return e.getAsJsonArray();
    }
    private static JsonElement required(JsonObject o,String key)throws IOException {if(!o.has(key)||o.get(key).isJsonNull())throw bad("Missing "+key);return o.get(key);}
    private static String string(JsonObject o,String key,String fallback)throws IOException {
        if(!o.has(key)){if(fallback!=null)return fallback;throw bad("Missing "+key);}var e=o.get(key);
        if(!e.isJsonPrimitive()||!e.getAsJsonPrimitive().isString()||e.getAsString().length()>256)throw bad(key+" must be a bounded string");return e.getAsString();
    }
    private static boolean bool(JsonObject o,String key,boolean fallback)throws IOException {
        if(!o.has(key))return fallback;var e=o.get(key);if(!e.isJsonPrimitive()||!e.getAsJsonPrimitive().isBoolean())throw bad(key+" must be boolean");return e.getAsBoolean();
    }
    private static int integer(JsonObject o,String key,int fallback,int min,int max)throws IOException {
        if(!o.has(key)){if(fallback>=min&&fallback<=max)return fallback;throw bad("Missing "+key);}return intValue(o.get(key),key,min,max);
    }
    private static int intValue(JsonElement e,String label,int min,int max)throws IOException {
        try {if(!e.isJsonPrimitive()||!e.getAsJsonPrimitive().isNumber())throw bad(label+" must be integer");int v=e.getAsBigDecimal().intValueExact();if(v<min||v>max)throw bad(label+" integer out of range");return v;}
        catch(ArithmeticException|NumberFormatException ex){throw bad(label+" invalid integer");}
    }
    private static int index(JsonObject o,String key,int size)throws IOException {return integer(o,key,-1,0,size-1);}
    private static float number(JsonObject o,String key,float fallback,float min,float max)throws IOException {
        if(!o.has(key))return fallback;float v=floatValue(o.get(key),key);if(v<min||v>max)throw bad(key+" out of range");return v;
    }
    private static float floatValue(JsonElement e,String label)throws IOException {
        if(!e.isJsonPrimitive()||!e.getAsJsonPrimitive().isNumber())throw bad(label+" must be numeric");float v=e.getAsFloat();if(!Float.isFinite(v)||Math.abs(v)>1_000_000)throw bad(label+" non-finite/unbounded number");return v;
    }
    private static float[] numbers(JsonElement e,int size,String label,boolean unit)throws IOException {
        if(!e.isJsonArray()||e.getAsJsonArray().size()!=size)throw bad(label+" wrong vector dimensions");float[] v=new float[size];
        for(int i=0;i<size;i++){v[i]=floatValue(e.getAsJsonArray().get(i),label);if(unit&&(v[i]<0||v[i]>1))throw bad(label+" outside 0..1");}return v;
    }
    private static IOException bad(String message) { return new IOException("Model GLB: "+message); }
}
