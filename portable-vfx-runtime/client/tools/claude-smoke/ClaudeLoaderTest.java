import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.portablevfx.client.claude.ClaudeConfigLoader;
import dev.portablevfx.client.claude.ClaudeFrames;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Tests the real loader and real handoff data, without Minecraft or OpenGL. */
public final class ClaudeLoaderTest {
    private static int assertions;
    private static Path sample, work;
    private interface Checked { void run() throws Exception; }
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: ClaudeLoaderTest FIREBALL_FOLDER");
        sample=Path.of(args[0]).toRealPath();
        work=Files.createTempDirectory("portablevfx-claude-loader-");
        try {
            validFireball(); unsafeRelativePaths(); symlinks(); missingAndOversized();
            folderCaseCollision(); textureCaseCollision(); decodedTextureLimits(); malformedSchema(); atomicFolderFailure();
            bases();
            System.out.println("Claude loader/frame tests passed ("+assertions+" assertions).");
        } finally { try(var paths=Files.walk(work)){ for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p); } }
    }
    private static void validFireball() throws Exception {
        Path root=fixture("valid");
        var result=ClaudeConfigLoader.load(root);
        check(result.errors().isEmpty(),"valid Fireball errors: "+result.errors());
        check(result.definitions().size()==2,"projectile and impact must both load");
        Set<String> ids=new HashSet<>();
        for(var def:result.definitions()) {
            ids.add(def.id()); check(def.durationTicks()>0&&def.durationTicks()<=1200,"duration is bounded");
            String key=def.asset().substring(def.asset().indexOf(':')+1);
            check(result.assets().containsKey(key),"definition resolves to asset");
            var envelope=JsonParser.parseString(new String(result.assets().get(key),StandardCharsets.UTF_8)).getAsJsonObject();
            check(envelope.get("portableVfxSystem").getAsString().equals(def.id().substring(def.id().lastIndexOf('/')+1)),"envelope selects its own system");
        }
        check(ids.equals(Set.of("claude:fireball/projectile","claude:fireball/impact")),"stable resource-safe IDs");
        Set<String> textures=new HashSet<>();
        for(var value:json(root.resolve("Fireball")).getAsJsonObject("materials").asMap().values()) {
            String ref=value.getAsJsonObject().get("texture").getAsString();
            String key="claude/fireball/"+ref.toLowerCase(Locale.ROOT);textures.add(key);
            check(Arrays.equals(Files.readAllBytes(sample.resolve(ref)),result.assets().get(key)),"texture preserved: "+ref);
        }
        check(result.assets().size()==textures.size()+2,"no textures or system assets omitted");
        check(ClaudeConfigLoader.load(work.resolve("does-not-exist")).errors().isEmpty(),"absent optional directory is harmless");
        System.out.println("PASS real Fireball: 2 system definitions and "+textures.size()+" texture assets");
    }
    private static void unsafeRelativePaths() throws Exception {
        Path folder=fixture("unsafe").resolve("Fireball");
        for(String ref:new String[]{"../outside.png","/tmp/outside.png","textures\\T_Glow.png","https://example.com/a.png","file:///tmp/a.png","textures/../vfx.json","textures/./T_Glow.png","textures//T_Glow.png","textures/","","C:/outside.png","a".repeat(241)})
            rejects(()->ClaudeConfigLoader.readRelative(folder,ref,4096),"unsafe resource "+ref);
        rejects(()->ClaudeConfigLoader.readRelative(folder,null,4096),"null resource");
        byte[] actual=Files.readAllBytes(folder.resolve("vfx.json"));
        check(Arrays.equals(actual,ClaudeConfigLoader.readRelative(folder,"vfx.json",actual.length)),"exact byte budget admitted");
        rejects(()->ClaudeConfigLoader.readRelative(folder,"vfx.json",actual.length-1),"byte budget +1 rejected");
        System.out.println("PASS relative-path traversal, absolute paths, URL, backslash, empty components and byte boundary");
    }
    private static void symlinks() throws Exception {
        Path root=fixture("symlink-file"), folder=root.resolve("Fireball");
        Files.createSymbolicLink(folder.resolve("linked.png"),sample.resolve("textures/T_Glow.png"));
        rejects(()->ClaudeConfigLoader.readRelative(folder,"linked.png",ClaudeConfigLoader.MAX_FILE_BYTES),"symlink file");
        Files.createSymbolicLink(folder.resolve("linked-dir"),sample.resolve("textures"));
        rejects(()->ClaudeConfigLoader.readRelative(folder,"linked-dir/T_Glow.png",ClaudeConfigLoader.MAX_FILE_BYTES),"symlink parent directory");
        Path rootLink=work.resolve("root-link");Files.createSymbolicLink(rootLink,root);
        rejected(ClaudeConfigLoader.load(rootLink),"symlink root");
        Path effects=Files.createDirectory(work.resolve("symlink-folder"));Files.createSymbolicLink(effects.resolve("Fireball"),sample);
        rejected(ClaudeConfigLoader.load(effects),"symlink effect directory");
        System.out.println("PASS root, effect-folder, nested-folder and file symlinks");
    }
    private static void missingAndOversized() throws Exception {
        Path missing=fixture("missing"), folder=missing.resolve("Fireball");
        Files.delete(folder.resolve(firstTexture(folder)));
        rejected(ClaudeConfigLoader.load(missing),"missing material texture");
        Path oversized=fixture("oversized");folder=oversized.resolve("Fireball");
        Files.write(folder.resolve(firstTexture(folder)),new byte[ClaudeConfigLoader.MAX_FILE_BYTES+1]);
        rejected(ClaudeConfigLoader.load(oversized),"oversized material texture");
        Path oversizedJson=fixture("oversized-json");
        Files.write(oversizedJson.resolve("Fireball/vfx.json"),new byte[ClaudeConfigLoader.MAX_JSON_BYTES+1]);
        rejected(ClaudeConfigLoader.load(oversizedJson),"oversized config JSON");
        System.out.println("PASS missing assets and 4 MiB per-file limits");
    }
    private static void folderCaseCollision() throws Exception {
        Path root=fixture("folder-collision");copyFolder(sample,root.resolve("fireball"));
        var result=ClaudeConfigLoader.load(root);
        check(result.errors().stream().anyMatch(e->e.contains("collision")),"folder case collision reported");
        check(result.definitions().size()==2,"colliding folder cannot overwrite admitted definitions");
        check(result.definitions().stream().map(ClaudeConfigLoader.Definition::id).distinct().count()==2,"IDs remain unique");
    }
    private static void textureCaseCollision() throws Exception {
        Path root=fixture("texture-collision"),folder=root.resolve("Fireball");JsonObject data=json(folder);
        String original=firstTexture(folder), lower=original.toLowerCase(Locale.ROOT);
        check(!original.equals(lower),"fixture has case-sensitive texture spelling");
        Files.copy(folder.resolve(original),folder.resolve(lower));
        JsonObject extra=data.getAsJsonObject("materials").entrySet().iterator().next().getValue().getAsJsonObject().deepCopy();
        extra.addProperty("texture",lower);data.getAsJsonObject("materials").add("case_collision_fixture",extra);writeJson(folder,data);
        var result=ClaudeConfigLoader.load(root);rejected(result,"texture case collision");
        check(result.errors().stream().anyMatch(e->e.toLowerCase(Locale.ROOT).contains("collision")),"texture case collision has useful error");
        System.out.println("PASS case-insensitive folder and texture collisions");
    }
    private static void decodedTextureLimits() throws Exception {
        Path tooWide=fixture("texture-wide"),folder=tooWide.resolve("Fireball");
        Files.write(folder.resolve(firstTexture(folder)),png(4096,1));
        var dimensionResult=ClaudeConfigLoader.load(tooWide);
        rejected(dimensionResult,"4096-pixel texture rejected before upload");
        check(dimensionResult.errors().stream().anyMatch(e->e.contains("2048")),"dimension error names 2048 cap");
        Path tooMany=fixture("decoded-total");folder=tooMany.resolve("Fireball");
        byte[] largePng=png(2048,2048);Set<String> textures=new HashSet<>();
        for(var material:json(folder).getAsJsonObject("materials").asMap().values())
            textures.add(material.getAsJsonObject().get("texture").getAsString());
        int count=0;for(String ref:textures){if(count++==5)break;Files.write(folder.resolve(ref),largePng);}
        var totalResult=ClaudeConfigLoader.load(tooMany);
        rejected(totalResult,"decoded texture total rejected before upload");
        check(totalResult.errors().stream().anyMatch(e->e.toLowerCase(Locale.ROOT).contains("texel")||e.toLowerCase(Locale.ROOT).contains("budget")),"aggregate allocation error is useful");
        System.out.println("PASS valid PNG dimension and aggregate decoded-texture limits");
    }
    private static byte[] png(int width,int height) throws Exception {
        var output=new java.io.ByteArrayOutputStream();
        check(javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(width,height,java.awt.image.BufferedImage.TYPE_INT_ARGB),"png",output),"PNG writer available");
        return output.toByteArray();
    }
    private static void malformedSchema() throws Exception {
        Path root=fixture("bad-major"),folder=root.resolve("Fireball");JsonObject data=json(folder);data.addProperty("schemaVersion","2.0.0");writeJson(folder,data);
        rejected(ClaudeConfigLoader.load(root),"unsupported schema major");
        Path malformed=fixture("malformed-json");Files.writeString(malformed.resolve("Fireball/vfx.json"),"{not JSON");
        rejected(ClaudeConfigLoader.load(malformed),"malformed JSON");
        System.out.println("PASS malformed JSON and unsupported schema major");
    }
    private static void atomicFolderFailure() throws Exception {
        Path root=fixture("atomic"),folder=root.resolve("Fireball");JsonObject data=json(folder);
        // The valid projectile comes first; a bad second system must not admit half a folder.
        data.getAsJsonArray("systems").get(1).getAsJsonObject().addProperty("id","Impact/Unsafe");writeJson(folder,data);
        rejected(ClaudeConfigLoader.load(root),"invalid second-system ID rolls back entire folder");
        System.out.println("PASS atomic per-folder admission");
    }
    private static void bases() throws Exception {
        for(double[] direction:new double[][]{{0,0,1},{1,2,3},{0,1,0},{0,-1,0},{0,.999999,0.000001}}) {
            float[] basis=ClaudeFrames.projectile(direction);orthonormal(basis,"projectile");
            aligned(basis,6,direction,"projectile forward follows travel");
        }
        for(double[][] pair:new double[][][]{{{0,1,0},{1,-1,2}},{{1,0,0},{-1,0,0}},{{0,0,1},{0,0,-1}},{{0,-1,0},{0,1,0}},{{.3,.4,.5},{1,2,3}}}) {
            float[] basis=ClaudeFrames.impact(pair[0],pair[1]);orthonormal(basis,"impact");
            aligned(basis,3,pair[0],"impact up follows surface normal");
        }
        for(double[] bad:new double[][]{null,{}, {1,2}, {0,0,0}, {Double.NaN,0,1}, {0,Double.POSITIVE_INFINITY,0}}) {
            rejects(()->ClaudeFrames.projectile(bad),"invalid projectile direction");
            rejects(()->ClaudeFrames.impact(bad,new double[]{0,0,1}),"invalid surface normal");
        }
        rejects(()->ClaudeFrames.impact(new double[]{0,1,0},new double[]{0,0,0}),"zero incoming direction");
        System.out.println("PASS ground/wall/vertical bases, parallel fallback, handedness and invalid vectors");
    }
    private static void orthonormal(float[] b,String name) {
        check(b.length==9,name+" matrix size");for(float v:b)check(Float.isFinite(v),name+" finite");
        for(int i=0;i<3;i++)for(int j=i;j<3;j++){double d=0;for(int k=0;k<3;k++)d+=b[i*3+k]*b[j*3+k];near(d,i==j?1:0,name+" orthonormal");}
        double det=b[0]*(b[4]*b[8]-b[5]*b[7])-b[3]*(b[1]*b[8]-b[2]*b[7])+b[6]*(b[1]*b[5]-b[2]*b[4]);near(det,1,name+" right-handed");
    }
    private static void aligned(float[] b,int offset,double[] v,String name){double n=Math.sqrt(v[0]*v[0]+v[1]*v[1]+v[2]*v[2]);for(int k=0;k<3;k++)near(b[offset+k],v[k]/n,name);}
    private static void near(double actual,double wanted,String label){check(Math.abs(actual-wanted)<1e-5,label+": "+actual+" versus "+wanted);}
    private static Path fixture(String name)throws Exception{Path root=Files.createDirectory(work.resolve(name));copyFolder(sample,root.resolve("Fireball"));return root;}
    private static void copyFolder(Path source,Path target)throws Exception{try(var paths=Files.walk(source)){for(Path from:paths.toList()){Path to=target.resolve(source.relativize(from));if(Files.isDirectory(from))Files.createDirectories(to);else Files.copy(from,to);}}}
    private static JsonObject json(Path folder)throws Exception{return JsonParser.parseString(Files.readString(folder.resolve("vfx.json"))).getAsJsonObject();}
    private static String firstTexture(Path folder)throws Exception{return json(folder).getAsJsonObject("materials").entrySet().iterator().next().getValue().getAsJsonObject().get("texture").getAsString();}
    private static void writeJson(Path folder,JsonObject data)throws Exception{Files.writeString(folder.resolve("vfx.json"),data.toString());}
    private static void rejected(ClaudeConfigLoader.Result r,String reason){check(!r.errors().isEmpty(),reason+" reports error");check(r.definitions().isEmpty(),reason+" admits no definitions");check(r.assets().isEmpty(),reason+" admits no assets");}
    private static void rejects(Checked action,String reason)throws Exception{try{action.run();}catch(IOException|IllegalArgumentException expected){assertions++;return;}throw new AssertionError(reason+" was accepted");}
    private static void check(boolean condition,String message){assertions++;if(!condition)throw new AssertionError(message);}
}
