package app.gaming;

import java.nio.file.Files;
import java.nio.file.Paths;
import app.gaming.util.Env;
import com.sun.net.httpserver.HttpServer;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringSerializer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.Executors;

public class Store {
    private static Connection db;
    private static KafkaProducer<String,String> producer;

    public static void main(String[] args) throws Exception {
        Files.createDirectories(Paths.get(Env.dataDir()));
        db = DriverManager.getConnection("jdbc:sqlite:" + Env.dataDir() + "/store.db");
        try (Statement st = db.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL;");
            st.execute("CREATE TABLE IF NOT EXISTS orders(order_id TEXT PRIMARY KEY, player_id TEXT, amount REAL, currency TEXT, created_at DATETIME DEFAULT CURRENT_TIMESTAMP)");
            st.execute("CREATE TABLE IF NOT EXISTS inventory_grants(grant_id TEXT PRIMARY KEY, order_id TEXT UNIQUE, player_id TEXT, item TEXT, granted_at DATETIME DEFAULT CURRENT_TIMESTAMP)");
            st.execute("CREATE TABLE IF NOT EXISTS outbox(id INTEGER PRIMARY KEY AUTOINCREMENT, topic TEXT, key TEXT, payload TEXT, status TEXT DEFAULT 'PENDING', created_at DATETIME DEFAULT CURRENT_TIMESTAMP)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_outbox_status ON outbox(status)");
        }

        Properties pp = new Properties();
        pp.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, Env.kafkaBootstrap());
        pp.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        pp.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        pp.put(ProducerConfig.ACKS_CONFIG, "all");
        producer = new KafkaProducer<>(pp);

        HttpServer http = HttpServer.create(new InetSocketAddress(8084), 0);
        http.createContext("/order", ex -> {

                                        var h = ex.getResponseHeaders();
                h.add("Access-Control-Allow-Origin", "*");
                h.add("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
                h.add("Access-Control-Allow-Headers", "Content-Type");
                h.add("Access-Control-Max-Age", "86400");
            try {
                Map<String,String> params = parse(ex.getRequestURI().getQuery());
                String player = params.getOrDefault("playerId","p1");
                String item = params.getOrDefault("item","skin_basic");
                double amount = Double.parseDouble(params.getOrDefault("amount","1.0"));
                String currency = params.getOrDefault("currency","USD");

                String orderId = UUID.randomUUID().toString();
                try (PreparedStatement ps = db.prepareStatement(
                        "INSERT OR IGNORE INTO orders(order_id,player_id,amount,currency) VALUES(?,?,?,?)")) {
                    ps.setString(1, orderId);
                    ps.setString(2, player);
                    ps.setDouble(3, amount);
                    ps.setString(4, currency);
                    ps.executeUpdate();
                }

                // idempotent grant + outbox
                String grantId = UUID.randomUUID().toString();
                boolean granted = false;
                db.setAutoCommit(false);
                try (PreparedStatement ps = db.prepareStatement(
                        "INSERT OR IGNORE INTO inventory_grants(grant_id,order_id,player_id,item) VALUES(?,?,?,?)")) {
                    ps.setString(1, grantId);
                    ps.setString(2, orderId);
                    ps.setString(3, player);
                    ps.setString(4, item);
                    int rows = ps.executeUpdate();
                    if (rows == 1) {
                        try (PreparedStatement out = db.prepareStatement(
                                "INSERT INTO outbox(topic,key,payload) VALUES(?,?,?)")) {
                            out.setString(1, "inventory.granted");
                            out.setString(2, player);
                            out.setString(3, "{\"orderId\":\""+orderId+"\",\"playerId\":\""+player+"\",\"item\":\""+item+"\"}");
                            out.executeUpdate();
                        }
                        granted = true;
                    }
                    db.commit();
                } catch (Exception e) {
                    db.rollback();
                    throw e;
                } finally {
                    db.setAutoCommit(true);
                }

                respond(ex, 200, "orderId=" + orderId + " granted=" + granted);
            } catch (Exception e) {
                respond(ex, 500, "error:" + e.getMessage());
            }
        });
        http.setExecutor(Executors.newCachedThreadPool());
        http.start();
        System.out.println("Store at http://localhost:8084/order");

        // Outbox → Kafka
        new Thread(() -> {
            while (true) {
                try (PreparedStatement sel = db.prepareStatement(
                            "SELECT id,topic,key,payload FROM outbox WHERE status='PENDING' LIMIT 200");
                     PreparedStatement upd = db.prepareStatement(
                            "UPDATE outbox SET status='SENT' WHERE id=?")) {
                    var rs = sel.executeQuery();
                    while (rs.next()) {
                        long id = rs.getLong(1);
                        String topic = rs.getString(2);
                        String key = rs.getString(3);
                        String payload = rs.getString(4);
                        producer.send(new ProducerRecord<>(topic, key, payload));
                        upd.setLong(1, id);
                        upd.addBatch();
                    }
                    upd.executeBatch();
                } catch (Exception ignored) {}
                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
            }
        }, "outbox-publisher").start();
    }

    private static Map<String,String> parse(String q) {
        Map<String,String> m = new HashMap<>();
        if (q == null) return m;
        for (String s : q.split("&")) {
            String[] kv = s.split("=",2);
            if (kv.length==2) m.put(kv[0], kv[1]);
        }
        return m;
    }

    private static void respond(com.sun.net.httpserver.HttpExchange ex, int code, String body) throws java.io.IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }
}
