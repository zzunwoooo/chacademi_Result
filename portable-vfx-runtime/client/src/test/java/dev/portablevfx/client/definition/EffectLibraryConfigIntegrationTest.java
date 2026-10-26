package dev.portablevfx.client.definition;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class EffectLibraryConfigIntegrationTest {
    @TempDir Path root;
    private void copySample(String name,String folder)throws Exception {
        Path source=Path.of(getClass().getResource("/claude/samples/"+name+"/vfx.json").toURI()).getParent();
        try(var paths=Files.walk(source)) { for(Path path:paths.filter(Files::isRegularFile).toList()) {
            Path target=root.resolve(folder).resolve(source.relativize(path));Files.createDirectories(target.getParent());Files.copy(path,target);
        }}
    }
    @Test void admitsThreeAuthenticPacksWithoutLegacyAssets()throws Exception {
        for(String name:List.of("Fireball","FeatherFall","TidalWave"))copySample(name,name);
        var library=new EffectLibrary(()->{},root);library.reload(null);
        assertEquals(List.of(),library.configErrors());assertEquals(6,library.ids().size());
        assertEquals("attached",library.claudeRole(Identifier.of("claude:featherfall/aura")));
        assertTrue(library.snapshot().assets().keySet().stream().allMatch(id->id.getNamespace().equals("claude")));
        long generation=library.snapshot().generation();library.reload(null);assertEquals(generation+1,library.snapshot().generation());
    }
    @Test void catalogAdmitsMoreThan32PacksWithoutAggregateDecodedRejection()throws Exception {
        for(int i=0;i<40;i++)copySample("Fireball","pack"+i);
        var library=new EffectLibrary(()->{},root);library.reload(null);
        assertEquals(List.of(),library.configErrors());assertEquals(80,library.ids().size());
    }
    @Test void reloadWaitsForWarmupCompletion()throws Exception {
        copySample("Fireball","Fireball");var library=new EffectLibrary(()->{},root);var pending=new CompletableFuture<Void>();
        library.warmup(new EffectLibrary.Warmup(){
            public Object prepare(EffectLibrary.Snapshot s){assertEquals(0,library.snapshot().generation());return s;}
            public CompletableFuture<Void> apply(EffectLibrary.Snapshot s,Object p,java.util.concurrent.Executor e){assertSame(s,p);return pending;}
        });
        var data=library.load(null,Runnable::run).join();var apply=library.apply(data,null,Runnable::run);
        assertFalse(apply.isDone());pending.complete(null);apply.join();assertEquals(2,library.ids().size());
    }
    @Test void authoredMixedCaseSystemIdsHaveCanonicalResourceIds()throws Exception {
        copySample("Fireball","Fireball");Path json=root.resolve("Fireball/vfx.json");
        Files.writeString(json,Files.readString(json).replace("\"id\": \"projectile\"","\"id\": \"projO\"").replace("projectile/","projO/"));
        var library=new EffectLibrary(()->{},root);library.reload(null);
        assertEquals(List.of(),library.configErrors());
        var definition=library.get(Identifier.of("claude:fireball/projo"));assertNotNull(definition);
        String selected=new String(library.snapshot().read(Identifier.of(definition.effect())),java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(selected.contains("\"portableVfxSystem\":\"projO\""));
    }

    @Test void loadLimitsAreBoundedAndConfigurable()throws Exception {
        Path file=root.resolve("limits.properties");Files.writeString(file,"resident-rgba-mib=256\nprewarm-rgba-mib=192\nmax-packs=700\n");
        var limits=LoadLimits.load(file);assertEquals(700,limits.maxPacks());assertEquals(256*LoadLimits.MIB,limits.residentRgbaBytes());
        Files.writeString(file,"resident-rgba-mib=999999\n");assertThrows(java.io.IOException.class,()->LoadLimits.load(file));
    }
}
