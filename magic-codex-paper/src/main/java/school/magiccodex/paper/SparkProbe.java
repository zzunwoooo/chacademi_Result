package school.magiccodex.paper;

import java.lang.reflect.Method;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * spark API 를 컴파일 의존성 없이 리플렉션으로 읽는다 (메모 #92 하트비트용). spark 가 없거나 API 가 다르면 null → Bukkit 값 사용.
 *
 * <p>찾는 순서: Bukkit 서비스({@code me.lucko.spark.api.Spark}) → spark 플러그인 클래스로더의 {@code SparkProvider.get()}
 * → 서버 클래스로더(Paper 내장 spark). 못 찾으면 60초 뒤 다시 찾는다. 하트비트 IO 스레드에서만 부른다.
 */
final class SparkProbe {
    record Values(double tps10s, double tps1m, double tps5m, double tps15m,
                  double msptMean, double msptMax, double msptP95, double cpuProcess, double cpuSystem) { }

    private static final String API = "me.lucko.spark.api.Spark";
    private static final String WINDOW = "me.lucko.spark.api.statistic.StatisticWindow$";
    private final JavaPlugin plugin;
    private Object spark;
    private Class<?> api;
    private long retryAt;
    private boolean warned;

    SparkProbe(JavaPlugin plugin) { this.plugin = plugin; }

    Values poll() {
        try {
            if (spark == null && !resolve()) return null;
            Object tps = call(spark, api, "tps");
            Object mspt = call(spark, api, "mspt");
            Object cpuP = call(spark, api, "cpuProcess");
            Object cpuS = call(spark, api, "cpuSystem");
            ClassLoader cl = api.getClassLoader();
            double t10 = num(pollWindow(tps, window(cl, "TicksPerSecond", "SECONDS_10")));
            double t1 = num(pollWindow(tps, window(cl, "TicksPerSecond", "MINUTES_1")));
            double t5 = num(pollWindow(tps, window(cl, "TicksPerSecond", "MINUTES_5")));
            double t15 = num(pollWindow(tps, window(cl, "TicksPerSecond", "MINUTES_15")));
            double mean = -1, max = -1, p95 = -1;
            Object info = pollWindow(mspt, window(cl, "MillisPerTick", "SECONDS_10"));
            if (info == null) info = pollWindow(mspt, window(cl, "MillisPerTick", "MINUTES_1"));
            if (info != null) {
                mean = num(invoke(info, "mean"));
                max = num(invoke(info, "max"));
                p95 = num(invoke(info, "percentile95th"));
            }
            double cp = num(pollWindow(cpuP, window(cl, "CpuUsage", "SECONDS_10")));
            double cs = num(pollWindow(cpuS, window(cl, "CpuUsage", "SECONDS_10")));
            if (t10 < 0 && mean < 0) return null;
            return new Values(t10, t1, t5, t15, mean, max, p95, cp, cs);
        } catch (Exception | LinkageError error) {
            if (!warned) { warned = true; plugin.getLogger().info("운영 하트비트: spark 값을 못 읽어 Bukkit 값을 씁니다 (" + error.getClass().getSimpleName() + ")"); }
            spark = null;
            retryAt = System.currentTimeMillis() + 60_000;
            return null;
        }
    }

    private boolean resolve() {
        long now = System.currentTimeMillis();
        if (now < retryAt) return false;
        retryAt = now + 60_000;
        // 1) Bukkit 서비스
        try {
            for (Class<?> c : plugin.getServer().getServicesManager().getKnownServices()) {
                if (!API.equals(c.getName())) continue;
                Object s = plugin.getServer().getServicesManager().load(c);
                if (s != null) { spark = s; api = c; return true; }
            }
        } catch (RuntimeException | LinkageError ignored) { }
        // 2) spark 플러그인 / 3) 서버 클래스로더 (Paper 내장)
        ClassLoader[] loaders = {
                pluginLoader(),
                plugin.getServer().getClass().getClassLoader(),
                Thread.currentThread().getContextClassLoader()
        };
        for (ClassLoader cl : loaders) {
            if (cl == null) continue;
            try {
                Class<?> provider = Class.forName("me.lucko.spark.api.SparkProvider", true, cl);
                Object s = provider.getMethod("get").invoke(null);
                if (s != null) { spark = s; api = Class.forName(API, true, cl); return true; }
            } catch (Exception | LinkageError ignored) { }
        }
        return false;
    }

    private ClassLoader pluginLoader() {
        var p = plugin.getServer().getPluginManager().getPlugin("spark");
        return p == null ? null : p.getClass().getClassLoader();
    }

    private static Object call(Object target, Class<?> type, String name) throws Exception {
        Method m = type.getMethod(name);
        return m.invoke(target);
    }

    private static Object invoke(Object target, String name) throws Exception {
        for (Class<?> i : target.getClass().getInterfaces()) {
            try { return i.getMethod(name).invoke(target); } catch (NoSuchMethodException ignored) { }
        }
        Method m = target.getClass().getMethod(name);
        m.setAccessible(true);
        return m.invoke(target);
    }

    private static Object window(ClassLoader cl, String type, String constant) {
        try {
            Class<?> c = Class.forName(WINDOW + type, true, cl);
            for (Object e : c.getEnumConstants()) if (((Enum<?>) e).name().equals(constant)) return e;
        } catch (Exception | LinkageError ignored) { }
        return null;
    }

    /** {@code statistic.poll(window)}. 메서드는 공개 인터페이스에서 찾는다 (구현 클래스는 접근 불가일 수 있음). */
    private static Object pollWindow(Object statistic, Object window) {
        if (statistic == null || window == null) return null;
        for (Class<?> i : allInterfaces(statistic.getClass())) {
            for (Method m : i.getMethods()) {
                if (!m.getName().equals("poll") || m.getParameterCount() != 1) continue;
                if (!m.getParameterTypes()[0].isInstance(window)) continue;
                try { return m.invoke(statistic, window); } catch (Exception ignored) { return null; }
            }
        }
        return null;
    }

    private static java.util.List<Class<?>> allInterfaces(Class<?> c) {
        var out = new java.util.ArrayList<Class<?>>();
        for (Class<?> k = c; k != null; k = k.getSuperclass()) collect(k, out);
        return out;
    }

    private static void collect(Class<?> k, java.util.List<Class<?>> out) {
        for (Class<?> i : k.getInterfaces()) { if (!out.contains(i)) { out.add(i); collect(i, out); } }
    }

    private static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : -1;
    }
}
