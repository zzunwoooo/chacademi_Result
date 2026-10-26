package school.magiccodex.client;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

/** Small bounded cuboid .bbmodel reader; no scripts/Molang are evaluated. */
public final class PetBbModel {
    public record Face(int texture,float[] uv,int rotation,float[][] points){}
    public record Cube(float[] origin,float[] rotation,List<Face> faces){}
    public record Key(float time,float[] value,String interpolation){}
    public record Track(String node,String channel,List<Key> keys){}
    public record Node(String id,float[] origin,float[] rotation,List<Node> children,Cube cube){}
    public record Texture(byte[] png,int uvWidth,int uvHeight){}
    /** 애니메이션 하나: 길이(초)와 트랙들. {@link #byNode}: 노드 id → [rotation, position, scale] 트랙 (없으면 null). */
    public record Clip(float length,List<Track> tracks,Map<String,Track[]> byNode){
        static final Clip EMPTY=new Clip(0,List.of(),Map.of());
        static Clip of(float length,List<Track> tracks){
            var map=new HashMap<String,Track[]>();
            for(var t:tracks){int i=channelIndex(t.channel());if(i>=0)map.computeIfAbsent(t.node(),k->new Track[3])[i]=t;}
            return new Clip(length,List.copyOf(tracks),Map.copyOf(map));
        }
    }
    /** 상태별 애니메이션 종류 (동행 펫, 2026-10-26). */
    public static final int IDLE=0,WALK=1,FLY=2;
    public final List<Node> roots;public final List<Texture> textures;public final List<Track> tracks;
    public final float length;public final List<String> warnings;public final float[] center;public final float span;
    /** idle 은 항상 있음(애니메이션이 없으면 빈 Clip). walk / fly 는 해당 이름이 없으면 idle 과 같은 객체. */
    public final Clip idle,walk,fly;
    /** 모델 바닥(가장 낮은 y, 픽셀 단위) — 월드에서 발밑을 땅에 붙일 때 씀. */
    public final float bottom;
    private PetBbModel(List<Node> r,List<Texture> t,Clip idle,Clip walk,Clip fly,List<String> w,float[] center,float span,float bottom){roots=r;textures=t;tracks=idle.tracks();length=idle.length();this.idle=idle;this.walk=walk;this.fly=fly;warnings=w;this.center=center;this.span=span;this.bottom=bottom;}
    public Clip clip(int kind){return kind==WALK?walk:kind==FLY?fly:idle;}
    static int channelIndex(String channel){return switch(channel){case "rotation"->0;case "position"->1;case "scale"->2;default->-1;};}
    /**
     * 애니메이션 이름 → 종류. fly/flying/glide 가 들어가면 FLY, walk/move/run 이면 WALK, idle 이면 IDLE, 그 밖은 -1.
     * (fly_move 처럼 둘 다 있으면 FLY 가 먼저.)
     */
    public static int kind(String name){
        String n=name==null?"":name.toLowerCase(Locale.ROOT);
        if(n.contains("fly")||n.contains("glide")||n.contains("swim"))return FLY;
        if(n.contains("walk")||n.contains("move")||n.matches(".*(^|[_ .-])run([_ .-]|$).*"))return WALK;
        if(n.contains("idle"))return IDLE;
        return -1;
    }
    private static final long MAX_FILE=16L*1024*1024;
    public static PetBbModel load(Path root,String id)throws Exception{
        if(!school.magiccodex.protocol.PetProtocol.validId(id))throw new IllegalArgumentException("잘못된 펫 ID");
        Path base=root.toRealPath(),file=base.resolve(id+".bbmodel").normalize();
        // 2026-10-25: 펫 ID와 파일 이름이 다를 때 models/aliases.json {"펫ID":"파일이름(확장자 없이)"} 로 연결 (바닐라 몹 펫 → 기존 bbmodel).
        if(!Files.exists(file)){String alias=alias(base,id);if(alias!=null)file=base.resolve(alias+".bbmodel").normalize();}
        if(!file.toRealPath().startsWith(base)||Files.size(file)>MAX_FILE)throw new IllegalArgumentException("모델 경로 또는 용량 오류");
        return parse(JsonParser.parseString(Files.readString(file)).getAsJsonObject(),file,base);
    }
    /** models/aliases.json lookup; null when absent, unreadable or the target name is not a safe pet-style id. */
    static String alias(Path base,String id){
        try{
            Path file=base.resolve("aliases.json");
            if(!Files.isRegularFile(file)||Files.size(file)>262144)return null;
            var json=JsonParser.parseString(Files.readString(file));
            if(!json.isJsonObject())return null;
            var value=json.getAsJsonObject().get(id);
            if(value==null||!value.isJsonPrimitive())return null;
            String name=value.getAsString().trim();
            if(name.endsWith(".bbmodel"))name=name.substring(0,name.length()-8);
            return school.magiccodex.protocol.PetProtocol.validId(name)&&!name.equals(id)?name:null;
        }catch(Exception e){return null;}
    }
    public static PetBbModel parse(JsonObject json,Path file,Path root)throws Exception{
        var warnings=new LinkedHashSet<String>();var textures=new ArrayList<Texture>();
        var res=obj(json,"resolution");int width=(int)num(res,"width",16),height=(int)num(res,"height",16);
        var texs=array(json,"textures");if(texs.size()>32)throw new IllegalArgumentException("텍스처는 최대 32장입니다.");
        long bytes=0;
        for(var value:texs){
            var tex=value.getAsJsonObject();String source=str(tex,"source","");
            byte[] png;
            if(source.startsWith("data:image/png;base64,"))png=Base64.getDecoder().decode(source.substring(source.indexOf(',')+1));
            else{
                String path=str(tex,"relative_path",str(tex,"path",str(tex,"name","")));
                Path candidate=file.getParent().resolve(path).normalize();
                if(!candidate.startsWith(root)||!Files.isRegularFile(candidate)){
                    String name=str(tex,"name","");
                    candidate=root.resolve("textures").resolve(Path.of(name).getFileName()).normalize();
                }
                if(!candidate.toRealPath().startsWith(root)||Files.size(candidate)>8L*1024*1024)throw new IllegalArgumentException("텍스처 경로/용량 오류");
                png=Files.readAllBytes(candidate);
            }
            bytes+=png.length;if(png.length>8L*1024*1024||bytes>16L*1024*1024)throw new IllegalArgumentException("텍스처 용량 초과");
            // PNG header dimensions are validated before native GPU allocation.
            if(png.length<24||png[0]!=(byte)137||png[1]!=80||png[2]!=78||png[3]!=71)throw new IllegalArgumentException("PNG 텍스처만 지원합니다.");
            var buf=java.nio.ByteBuffer.wrap(png);int pw=buf.getInt(16),ph=buf.getInt(20);
            if(pw<1||ph<1||pw>2048||ph>2048)throw new IllegalArgumentException("텍스처 최대 크기는 2048입니다.");
            int uw=(int)num(tex,"uv_width",width),uh=(int)num(tex,"uv_height",height);
            if(uw<1||uh<1||uw>8192||uh>8192)throw new IllegalArgumentException("UV 크기 오류");
            textures.add(new Texture(png,uw,uh));
        }
        if(textures.isEmpty())throw new IllegalArgumentException("텍스처를 포함해 bbmodel을 저장해 주세요.");
        var groups=new HashMap<String,JsonObject>();for(var g:array(json,"groups")){var o=g.getAsJsonObject();groups.put(str(o,"uuid",""),o);}
        var hidden=new HashSet<String>();for(var entry:array(json,"outliner"))collectHidden(entry,groups,hidden,false,0);
        var cubes=new HashMap<String,Cube>();var elements=array(json,"elements");
        if(elements.size()>1024)throw new IllegalArgumentException("모델 요소는 최대 1024개입니다.");
        float[] min={Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY},max={Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY};
        for(var value:elements){
            var e=value.getAsJsonObject();String type=str(e,"type","cube");
            if(type.equals("locator")||!bool(e,"visibility",true)||!bool(e,"export",true)||hidden.contains(str(e,"uuid","")))continue;
            if(!type.equals("cube"))throw new IllegalArgumentException("현재 미리보기는 큐브 모델만 지원합니다: "+type);
            float[] from=vec(e.get("from"),new float[]{0,0,0}),to=vec(e.get("to"),new float[]{0,0,0});
            float inflate=num(e,"inflate",0);for(int j=0;j<3;j++){from[j]-=inflate;to[j]+=inflate;min[j]=Math.min(min[j],from[j]);max[j]=Math.max(max[j],to[j]);}
            var faces=new ArrayList<Face>();var fs=obj(e,"faces");
            String[] names={"north","south","east","west","up","down"};
            float x=from[0],y=from[1],z=from[2],X=to[0],Y=to[1],Z=to[2];
            float[][][] points={{{X,Y,z},{x,Y,z},{x,y,z},{X,y,z}},{{x,Y,Z},{X,Y,Z},{X,y,Z},{x,y,Z}},{{X,Y,Z},{X,Y,z},{X,y,z},{X,y,Z}},{{x,Y,z},{x,Y,Z},{x,y,Z},{x,y,z}},{{x,Y,z},{X,Y,z},{X,Y,Z},{x,Y,Z}},{{x,y,Z},{X,y,Z},{X,y,z},{x,y,z}}};
            if(fs.size()==0)continue;
            for(int i=0;i<names.length;i++){
                if(!fs.has(names[i]))continue;var f=fs.getAsJsonObject(names[i]);
                if(!f.has("texture")||f.get("texture").isJsonNull())continue;
                int ti=(int)num(f,"texture",-1);if(ti<0)continue;if(ti>=textures.size())throw new IllegalArgumentException("텍스처 번호 오류");
                var uv=array(f,"uv");if(uv.size()!=4)throw new IllegalArgumentException("면 UV 오류");
                float[] values=new float[4];for(int j=0;j<4;j++)values[j]=finite(uv.get(j));
                int rot=(int)num(f,"rotation",0);faces.add(new Face(ti,values,Math.floorMod(rot/90,4),points[i]));
            }
            if(faces.isEmpty())continue;
            String uuid=str(e,"uuid","");if(uuid.isEmpty()||cubes.put(uuid,new Cube(vec(e.get("origin"),new float[]{0,0,0}),vec(e.get("rotation"),new float[]{0,0,0}),List.copyOf(faces)))!=null)throw new IllegalArgumentException("요소 UUID 오류");
        }
        if(cubes.isEmpty())throw new IllegalArgumentException("표시할 큐브가 없습니다.");
        var visited=new HashSet<String>();var nodes=new ArrayList<Node>();
        for(var e:array(json,"outliner")){var n=node(e,cubes,groups,visited,hidden,0);if(n!=null)nodes.add(n);}
        for(var e:cubes.entrySet())if(!visited.contains(e.getKey()))nodes.add(new Node(e.getKey(),new float[]{0,0,0},new float[]{0,0,0},List.of(),e.getValue()));
        // idle: 이름에 idle 이 들어간 첫 애니메이션, 없으면 첫 애니메이션 (예전과 같음). walk / fly: 이름이 맞는 첫 애니메이션.
        JsonObject idleAnim=null,walkAnim=null,flyAnim=null,first=null;
        for(var a:array(json,"animations")){
            var o=a.getAsJsonObject();if(first==null)first=o;
            switch(kind(str(o,"name",""))){
                case IDLE->{if(idleAnim==null)idleAnim=o;}
                case WALK->{if(walkAnim==null)walkAnim=o;}
                case FLY->{if(flyAnim==null)flyAnim=o;}
                default->{}
            }
        }
        if(idleAnim==null)idleAnim=first;
        boolean legacy=!str(obj(json,"meta"),"format_version","4").startsWith("5");
        Clip idle=clip(idleAnim,legacy,warnings);
        Clip walk=walkAnim==null||walkAnim==idleAnim?idle:clip(walkAnim,legacy,warnings);
        Clip fly=flyAnim==null||flyAnim==idleAnim?idle:clip(flyAnim,legacy,warnings);
        Arrays.fill(min,Float.POSITIVE_INFINITY);Arrays.fill(max,Float.NEGATIVE_INFINITY);
        for(var n:nodes)bounds(n,new org.joml.Matrix4f(),min,max);
        float[] center={(min[0]+max[0])/2,(min[1]+max[1])/2,(min[2]+max[2])/2};
        float span=Math.max(1,Math.max(max[0]-min[0],Math.max(max[1]-min[1],max[2]-min[2])));
        return new PetBbModel(List.copyOf(nodes),List.copyOf(textures),idle,walk,fly,List.copyOf(warnings),center,span,Float.isFinite(min[1])?min[1]:0);
    }
    private static Clip clip(JsonObject animation,boolean legacy,Set<String> warnings){
        if(animation==null)return Clip.EMPTY;
        var tracks=new ArrayList<Track>();
        float len=Math.clamp(num(animation,"length",0),0,3600);
        var animators=obj(animation,"animators");
        if(animators.size()>1024)throw new IllegalArgumentException("애니메이터 개수 초과");
        for(var entry:animators.entrySet()){
            var channels=new HashMap<String,List<Key>>();
            var keys=array(entry.getValue().getAsJsonObject(),"keyframes");
            if(keys.size()>4096)throw new IllegalArgumentException("키프레임 개수 초과");
            for(var key:keys){
                var k=key.getAsJsonObject();String channel=str(k,"channel","");
                if(!Set.of("rotation","position","scale").contains(channel))continue;
                var data=array(k,"data_points");if(data.isEmpty())continue;
                try{
                    var point=data.get(0).getAsJsonObject();float[] v={finite(point.get("x")),finite(point.get("y")),finite(point.get("z"))};
                    if(legacy){if(channel.equals("position"))v[0]*=-1;if(channel.equals("rotation")){v[0]*=-1;v[1]*=-1;}}
                    String interpolation=str(k,"interpolation","linear");
                    if(!Set.of("linear","step").contains(interpolation))warnings.add("곡선 보간은 선형으로 표시됩니다.");
                    if(data.size()>1)warnings.add("복수 data_points는 첫 값으로 표시됩니다.");
                    channels.computeIfAbsent(channel,s->new ArrayList<>()).add(new Key(num(k,"time",0),v,interpolation));
                }catch(IllegalArgumentException ex){warnings.add("Molang 수식 키프레임은 생략됩니다.");}
            }
            for(var c:channels.entrySet()){c.getValue().sort(Comparator.comparing(Key::time));tracks.add(new Track(entry.getKey(),c.getKey(),List.copyOf(c.getValue())));}
        }
        return Clip.of(len,tracks);
    }
    private static void bounds(Node n,org.joml.Matrix4f parent,float[] min,float[] max){
        var m=transform(new org.joml.Matrix4f(parent),n.origin,n.rotation);
        if(n.cube!=null){var cm=transform(new org.joml.Matrix4f(m),n.cube.origin,n.cube.rotation);for(var f:n.cube.faces)for(var p:f.points){var v=cm.transformPosition(new org.joml.Vector3f(p[0],p[1],p[2]));for(int i=0;i<3;i++){min[i]=Math.min(min[i],v.get(i));max[i]=Math.max(max[i],v.get(i));}}}
        for(var c:n.children)bounds(c,m,min,max);
    }
    private static org.joml.Matrix4f transform(org.joml.Matrix4f m,float[] o,float[] r){return m.translate(o[0],o[1],o[2]).rotateZYX((float)Math.toRadians(r[2]),(float)Math.toRadians(r[1]),(float)Math.toRadians(r[0])).translate(-o[0],-o[1],-o[2]);}
    private static void collectHidden(JsonElement element,Map<String,JsonObject> groups,Set<String> hidden,boolean inherited,int depth){
        if(depth>64)throw new IllegalArgumentException("모델 계층이 너무 깊습니다.");
        if(element.isJsonPrimitive()){if(inherited)hidden.add(element.getAsString());return;}
        var o=element.getAsJsonObject();String id=str(o,"uuid","");var data=groups.getOrDefault(id,o);
        String name=str(data,"name","").toLowerCase(Locale.ROOT);
        // ModelEngine's reserved collision bone is editor-visible geometry, never a visual mesh.
        boolean hide=inherited||!bool(data,"visibility",true)||!bool(data,"export",true)||name.equals("hitbox");
        if(hide)hidden.add(id);
        for(var child:array(o,"children"))collectHidden(child,groups,hidden,hide,depth+1);
    }
    private static Node node(JsonElement element,Map<String,Cube> cubes,Map<String,JsonObject> groups,Set<String> seen,Set<String> hidden,int depth){
        if(depth>64)throw new IllegalArgumentException("모델 계층이 너무 깊습니다.");
        if(element.isJsonPrimitive()&&hidden.contains(element.getAsString()))return null;
        if(element.isJsonPrimitive()){String id=element.getAsString();Cube cube=cubes.get(id);if(cube==null)return null;if(!seen.add(id))throw new IllegalArgumentException("중복 요소");return new Node(id,new float[]{0,0,0},new float[]{0,0,0},List.of(),cube);}
        var o=element.getAsJsonObject();String id=str(o,"uuid","");if(hidden.contains(id))return null;if(!seen.add(id))throw new IllegalArgumentException("중복 그룹");
        var data=groups.getOrDefault(id,o);var children=new ArrayList<Node>();
        for(var child:array(o,"children")){var n=node(child,cubes,groups,seen,hidden,depth+1);if(n!=null)children.add(n);}
        return new Node(id,vec(data.get("origin"),new float[]{0,0,0}),vec(data.get("rotation"),new float[]{0,0,0}),List.copyOf(children),null);
    }
    public static float[] sample(List<Key> keys,float time){return sample(keys,time,new float[3]);}
    /** 할당 없는 버전: out 에 써서 돌려줌. 키가 없으면 0,0,0. */
    public static float[] sample(List<Key> keys,float time,float[] out){
        if(keys.isEmpty()){out[0]=out[1]=out[2]=0;return out;}Key a=keys.getFirst(),b=a;
        for(int i=0,n=keys.size();i<n;i++){var k=keys.get(i);if(k.time<=time)a=k;else{b=k;break;}b=a;}
        float mix=b.time<=a.time||a.interpolation.equals("step")?0:Math.clamp((time-a.time)/(b.time-a.time),0,1);
        out[0]=a.value[0]+(b.value[0]-a.value[0])*mix;out[1]=a.value[1]+(b.value[1]-a.value[1])*mix;out[2]=a.value[2]+(b.value[2]-a.value[2])*mix;
        return out;
    }
    private static JsonObject obj(JsonObject o,String key){return o.has(key)&&o.get(key).isJsonObject()?o.getAsJsonObject(key):new JsonObject();}
    private static JsonArray array(JsonObject o,String key){return o.has(key)&&o.get(key).isJsonArray()?o.getAsJsonArray(key):new JsonArray();}
    private static String str(JsonObject o,String key,String def){return o.has(key)&&!o.get(key).isJsonNull()?o.get(key).getAsString():def;}
    private static boolean bool(JsonObject o,String key,boolean def){return !o.has(key)||o.get(key).isJsonNull()?def:o.get(key).getAsBoolean();}
    private static float num(JsonObject o,String key,float def){return !o.has(key)||o.get(key).isJsonNull()?def:finite(o.get(key));}
    private static float finite(JsonElement e){if(e==null||e.isJsonNull())throw new IllegalArgumentException("숫자 값 누락");float v=e.getAsFloat();if(!Float.isFinite(v)||Math.abs(v)>100000)throw new IllegalArgumentException("숫자 값 오류");return v;}
    private static float[] vec(JsonElement e,float[] def){if(e==null||!e.isJsonArray())return def.clone();var a=e.getAsJsonArray();if(a.size()!=3)throw new IllegalArgumentException("벡터 오류");return new float[]{finite(a.get(0)),finite(a.get(1)),finite(a.get(2))};}
}
