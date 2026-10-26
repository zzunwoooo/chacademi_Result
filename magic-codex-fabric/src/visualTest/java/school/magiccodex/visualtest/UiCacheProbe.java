package school.magiccodex.visualtest;

import java.util.*;
import java.nio.file.*;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import school.magiccodex.client.UiResources;

/** Development-only wall-time samples; not a guarantee about frame times on other machines. */
final class UiCacheProbe {
    private static final Map<String,List<Double>> render=new TreeMap<>(),init=new TreeMap<>();
    private static long initStart;
    static void register(){
        ScreenEvents.BEFORE_INIT.register((c,s,w,h)->initStart=System.nanoTime());
        ScreenEvents.AFTER_INIT.register((c,s,w,h)->{
            if(!s.getClass().getName().startsWith("school.magiccodex.client."))return;
            String name=s.getClass().getSimpleName();init.computeIfAbsent(name,k->new ArrayList<>()).add((System.nanoTime()-initStart)/1e6);
            long[] start={0};ScreenEvents.beforeRender(s).register((screen,ctx,mx,my,d)->start[0]=System.nanoTime());
            ScreenEvents.afterRender(s).register((screen,ctx,mx,my,d)->render.computeIfAbsent(name,k->new ArrayList<>()).add((System.nanoTime()-start[0])/1e6));
        });
    }
    static void report()throws Exception{report("social-017");}
    static void report(String directory)throws Exception{
        StringBuilder out=new StringBuilder("Screen CPU render samples (includes cold first use; excludes world rendering and swap)\n");
        for(var e:render.entrySet()){
            var values=e.getValue();values.sort(Double::compare);
            out.append(String.format(Locale.ROOT,"%s: frames=%d p50=%.3fms p95=%.3fms max=%.3fms init=%s%n",e.getKey(),values.size(),values.get(values.size()/2),values.get((int)((values.size()-1)*.95)),values.getLast(),init.get(e.getKey())));
        }
        out.append("Shared texture uploads: ").append(UiResources.images().uploadCount()).append(" retained bytes: ").append(UiResources.images().retainedBytes()).append('\n');
        var path=Path.of("visual-check",directory,"cache-timings.txt");Files.createDirectories(path.getParent());Files.writeString(path,out);System.out.println(out);
    }
}
