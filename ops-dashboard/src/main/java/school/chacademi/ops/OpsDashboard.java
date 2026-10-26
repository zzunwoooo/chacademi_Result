package school.chacademi.ops;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.ZoneId;
import java.util.concurrent.Executors;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * 차카데미 운영 대시보드 (메모 #92). 게임 서버와 따로 도는 프로세스라 서버가 죽어도 열린다.
 *
 * <pre>java -jar chacademi-ops-dashboard-0.1.0.jar C:\Chacademi\ops-dashboard\ops-dashboard.properties</pre>
 * 비밀번호 해시 만들기: {@code java -jar ... --hash 비밀번호}
 */
public final class OpsDashboard {
    private OpsDashboard() { }

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("--hash")) { System.out.println(Auth.sha256Hex(args[1])); return; }
        Path file = Path.of(args.length > 0 ? args[0] : "ops-dashboard.properties").toAbsolutePath();
        if (!Files.exists(file)) {
            System.err.println("설정 파일이 없습니다: " + file + " (ops-dashboard.properties.example 을 복사해 채우세요)");
            System.exit(2);
        }
        Config config = Config.load(file);
        Path data = config.dataDir.isAbsolute() ? config.dataDir : file.getParent().resolve(config.dataDir);
        ActionLog actions = new ActionLog(data, ZoneId.of(config.zone));
        Db db = null;
        try { db = Db.open(config); }
        catch (Exception e) { System.err.println("DB 설정 오류 (하트비트·기록 검색 없이 계속): " + e.getMessage()); }
        Monitor monitor = new Monitor(config, db);
        Control control = new Control(config, monitor, actions);
        AiAnalyst ai = new AiAnalyst(config, monitor, actions);
        monitor.onNewAlarms(ai::onAlarms);
        AuditSearch audit = new AuditSearch(config, db);
        Auth auth = new Auth(config.passwordSha256, config.sessionMillis, config.maxFailures, config.lockMillis, System::currentTimeMillis);
        Web web = new Web(config, auth, monitor, control, audit, ai, actions);

        InetSocketAddress addr = new InetSocketAddress(config.bind, config.port);
        HttpServer server;
        if (config.tlsKeystore != null) {
            HttpsServer https = HttpsServer.create(addr, 64);
            https.setHttpsConfigurator(new HttpsConfigurator(tls(config)));
            server = https;
        } else {
            server = HttpServer.create(addr, 64);
        }
        server.createContext("/", web);
        server.setExecutor(Executors.newFixedThreadPool(8, r -> Monitor.daemon(r, "ops-http")));
        monitor.start();
        server.start();
        actions.write("-", "대시보드 시작", "", config.bind + ":" + config.port, "");
        System.out.println("차카데미 운영 대시보드: " + (config.tlsKeystore != null ? "https" : "http") + "://" + config.bind + ":" + config.port + "/  (서버 " + config.servers.size() + "개)");
        Db closeDb = db;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(1);
            monitor.close();
            control.close();
            ai.close();
            if (closeDb != null) closeDb.close();
        }, "ops-shutdown"));
        Thread.currentThread().join();
    }

    private static SSLContext tls(Config c) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        char[] pw = c.tlsPassword.toCharArray();
        try (InputStream in = Files.newInputStream(c.tlsKeystore)) { ks.load(in, pw); }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, pw);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        return ctx;
    }
}
