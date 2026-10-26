package school.magiccodex.client;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;
class KyudichiHitboxContractTest {
 ClassNode read(String name)throws Exception{try(var in=getClass().getClassLoader().getResourceAsStream(name+".class")){assertNotNull(in,name);var c=new ClassNode();new ClassReader(in).accept(c,0);return c;}}
 MethodNode method(ClassNode c,String name){return c.methods.stream().filter(m->m.name.equals(name)).findFirst().orElseThrow();}
 List<AbstractInsnNode> code(MethodNode m){var out=new ArrayList<AbstractInsnNode>();for(var i:m.instructions)if(i.getOpcode()>=0)out.add(i);return out;}
 @Test void namedYarn1214SetterExistsAndMixinIsRegisteredExactlyOnce()throws Exception{
  var renderer=read("net/minecraft/client/render/entity/EntityRenderDispatcher");assertTrue(renderer.methods.stream().anyMatch(m->m.name.equals("setRenderHitboxes")&&m.desc.equals("(Z)V")));
  String json=Files.readString(Path.of("src/main/resources/magiccodex.mixins.json"));assertEquals(1,json.split("KyudichiHitboxMixin",-1).length-1);
  var mixin=read("school/magiccodex/mixin/KyudichiHitboxMixin");var injected=method(mixin,"kyudichi$noHitboxes");var annotations=new ArrayList<AnnotationNode>();if(injected.visibleAnnotations!=null)annotations.addAll(injected.visibleAnnotations);if(injected.invisibleAnnotations!=null)annotations.addAll(injected.invisibleAnnotations);
  var a=annotations.stream().filter(x->x.desc.endsWith("/Inject;")).findFirst().orElseThrow();assertTrue(a.values.contains("cancellable"));assertTrue(a.values.contains(Boolean.TRUE));assertTrue(a.values.toString().contains("setRenderHitboxes"));
 }
 @Test void enteringMatchWithHitboxesAlreadyOnActivelyTurnsThemOff()throws Exception{
  var tick=code(method(read("school/magiccodex/client/KyudichiClient"),"tick"));int active=-1,check=-1,set=-1;
  for(int i=0;i<tick.size();i++)if(tick.get(i) instanceof MethodInsnNode call){if(call.name.equals("active")&&active<0)active=i;if(call.name.equals("shouldRenderHitboxes"))check=i;if(call.name.equals("setRenderHitboxes")){set=i;assertEquals(Opcodes.ICONST_0,tick.get(i-1).getOpcode());}}
  assertTrue(active>=0&&check>active&&set>check);assertTrue(tick.get(active+1) instanceof JumpInsnNode);assertEquals(Opcodes.IFEQ,tick.get(active+1).getOpcode());
 }
 @Test void lockIncludesSpectatorsAndDoesNotBlockDisablingOrOutsideMatch()throws Exception{
  var c=read("school/magiccodex/client/KyudichiClient");var lock=code(method(c,"hitboxesLocked"));assertTrue(lock.stream().anyMatch(x->x instanceof MethodInsnNode m&&m.name.equals("active")));assertFalse(lock.stream().anyMatch(x->x instanceof MethodInsnNode m&&m.name.equals("playing")));
  var body=code(method(read("school/magiccodex/mixin/KyudichiHitboxMixin"),"kyudichi$noHitboxes"));assertTrue(body.getFirst() instanceof VarInsnNode v&&v.var==1);assertEquals(Opcodes.IFEQ,body.get(1).getOpcode());assertTrue(body.stream().anyMatch(x->x instanceof MethodInsnNode m&&m.name.equals("cancel")));
 }
}