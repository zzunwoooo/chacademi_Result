package school.magiccodex.client;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

/** Tests the pinned Minecraft integration boundary without starting a client or server. */
class ItemGuiRenderContractTest {
    private ClassNode read(String name) throws Exception {
        try (var in = getClass().getResourceAsStream("/" + name + ".class")) {
            assertNotNull(in, name);
            var node = new ClassNode();
            new ClassReader(in).accept(node, 0);
            return node;
        }
    }
    private List<MethodInsnNode> calls(MethodNode method) {
        var calls = new ArrayList<MethodInsnNode>();
        for (var instruction : method.instructions)
            if (instruction instanceof MethodInsnNode call) calls.add(call);
        return calls;
    }
    @Test void hookRunsAfterExactlyOneVanillaModelResolutionAndBeforeAnyMatrixPush() throws Exception {
        var vanilla = read("net/minecraft/client/gui/DrawContext");
        var method = vanilla.methods.stream().filter(m -> m.name.equals("drawItem") && m.desc.equals(
                "(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/world/World;Lnet/minecraft/item/ItemStack;IIII)V")).findFirst().orElseThrow();
        var calls = calls(method);
        var updates = calls.stream().filter(c -> c.owner.equals("net/minecraft/client/item/ItemModelManager") && c.name.equals("update")).toList();
        assertEquals(1, updates.size());
        assertEquals("(Lnet/minecraft/client/render/item/ItemRenderState;Lnet/minecraft/item/ItemStack;Lnet/minecraft/item/ModelTransformationMode;ZLnet/minecraft/world/World;Lnet/minecraft/entity/LivingEntity;I)V", updates.getFirst().desc);
        var push = calls.stream().filter(c -> c.owner.endsWith("/MatrixStack") && c.name.equals("push")).findFirst().orElseThrow();
        assertTrue(calls.indexOf(updates.getFirst()) < calls.indexOf(push));
        assertTrue(vanilla.fields.stream().anyMatch(f -> f.name.equals("itemRenderState") && f.desc.equals("Lnet/minecraft/client/render/item/ItemRenderState;")));
        assertFalse(calls.stream().anyMatch(c -> c.owner.contains("Framebuffer")), "Vanilla has no 16px GUI staging FBO");
    }
    @Test void guiReplacementUsesResolvedModelTransformAndGlintWithoutWorldShaderOrFramebuffer() throws Exception {
        var node = read("school/magiccodex/client/ItemIconTextures");
        var method = node.methods.stream().filter(m -> m.name.equals("drawResolved")).findFirst().orElseThrow();
        var calls = calls(method);
        assertTrue(calls.stream().anyMatch(c -> c.name.equals("magiccodex$singleFlatModel")));
        assertTrue(calls.stream().anyMatch(c -> c.name.equals("getTransformation")));
        assertTrue(calls.stream().anyMatch(c -> c.name.equals("hasDepth")));
        assertTrue(calls.stream().anyMatch(c -> c.name.equals("hasGlint")));
        assertTrue(calls.stream().anyMatch(c -> c.name.equals("drawGlint")));
        assertFalse(calls.stream().anyMatch(c -> c.name.equals("renderWorld") || c.name.equals("resolveTexture") || c.owner.contains("Framebuffer") || c.owner.contains("ImageIO")));
    }
    @Test void sharedGuiHookIsRequiredAndUsesResolvedState() throws Exception {
        var node = read("school/magiccodex/mixin/ItemIconMixin");
        var method = node.methods.stream().filter(m -> m.name.equals("magiccodex$highResIcon")).findFirst().orElseThrow();
        var annotations = new ArrayList<AnnotationNode>();
        if (method.visibleAnnotations != null) annotations.addAll(method.visibleAnnotations);
        if (method.invisibleAnnotations != null) annotations.addAll(method.invisibleAnnotations);
        var inject = annotations.stream().filter(a -> a.desc.endsWith("/Inject;")).findFirst().orElseThrow();
        int require = inject.values.indexOf("require");
        assertTrue(require >= 0);
        assertEquals(1, inject.values.get(require + 1));
        assertTrue(calls(method).stream().anyMatch(c -> c.name.equals("drawResolved")));
    }
}
