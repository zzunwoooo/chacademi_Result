package school.magiccodex.client;
import org.junit.jupiter.api.Test;import org.objectweb.asm.*;import org.objectweb.asm.tree.*;import static org.junit.jupiter.api.Assertions.*;
/** Guards the world/HUD shader boundary without claiming a GPU/Iris visual test. */
class NameplateShaderContractTest {
 @Test void nameplateBackgroundUsesVanillaDepthTestedTextLayerAndLightAttribute()throws Exception{
  var node=new ClassNode();try(var in=getClass().getResourceAsStream("/school/magiccodex/client/HudTextureCache.class")){new ClassReader(in).accept(node,0);}var method=node.methods.stream().filter(m->m.name.equals("drawWorld")).findFirst().orElseThrow();boolean text=false,light=false;
  for(var insn:method.instructions){if(insn instanceof MethodInsnNode call){text|=call.owner.equals("net/minecraft/client/render/RenderLayer")&&call.name.equals("getText");light|=call.name.equals("light");assertFalse(call.name.contains("SeeThrough"));assertFalse(call.owner.equals("com/mojang/blaze3d/systems/RenderSystem"),"world pass must not bind custom HUD shader or alter global depth/framebuffer state");}}
  assertTrue(text);assertTrue(light);
 }
}
