import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
public final class NoUnixLoomAgent {
    public static void premain(String args, Instrumentation instrumentation) {
        System.err.println("PortableVFX build: unavailable Unix sockets disabled in Loom probe");
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader loader, String name, Class<?> type,
                    ProtectionDomain domain, byte[] original) {
                if (name == null || !(name.equals("net/fabricmc/loom/util/CurrentPlatform")
                        || name.startsWith("net/fabricmc/loom/util/CurrentPlatform$"))) return null;
                try (var input = NoUnixLoomAgent.class.getResourceAsStream("/replacement/" + name + ".class")) {
                    if (input == null) throw new IllegalStateException("Missing replacement " + name);
                    System.err.println("Loom platform capability replacement: " + name);
                    return input.readAllBytes();
                } catch (java.io.IOException exception) { throw new IllegalStateException(exception); }
            }
        }, true);
    }
}
