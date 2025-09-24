package app.gaming;

import java.nio.file.Files;
import java.nio.file.Paths;
import app.gaming.util.Env;
import com.sun.net.httpserver.HttpServer;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executors;

public class AntiCheat {
    private static Connection db;
    private static KafkaProducer<String,String> producer;

    public static void main(String[] args) throws Exception {
        Files.createDirectories(Paths.get(Env.dataDir()));
        db = DriverManager.getConnection("jdbc:sqlite:" + Env.dataDir() + "/anticheat.db");
        try (Statement st = db.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL;");
            st.execute("CREATE TABLE IF NOT EXISTS alerts(" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "player_id TEXT, match_id TEXT, type TEXT, score REAL," +
                    "ts DATETIME DEFAULT CURRENT_TIMESTAMP)");
        }

        Properties pp = new Properties();
        pp.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, Env.kafkaBootstrap());
        pp.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        pp.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producer = new KafkaProducer<>(pp);

        // REST: /telemetry?playerId=p1&matchId=m1&speed=15
        HttpServer http = HttpServer.create(new InetSocketAddress(8085), 0);
        http.createContext("/telemetry", ex -> {

                var h = ex.getResponseHeaders();
                h.add("Access-Control-Allow-Origin", "*");
                h.add("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
                h.add("Access-Control-Allow-Headers", "Content-Type");
                h.add("Access-Control-Max-Age", "86400");
            Map<String,String> params = parse(ex.getRequestURI().getQuery());
            String player = params.getOrDefault("playerId","p1");
            String match  = params.getOrDefault("matchId","m1");
            int speed     = Integer.parseInt(params.getOrDefault("speed","5"));

            String json = "{\"playerId\":\""+player+"\",\"matchId\":\""+match+"\",\"speed\":"+speed+"}";
            producer.send(new ProducerRecord<>("telemetry.events", match, json));
            respond(ex, 200, "ok");
        });
        http.setExecutor(Executors.newCachedThreadPool());
        http.start();
        System.out.println("AntiCheat REST at http://localhost:8085/telemetry");

        // Consumer: create alert if speed>10
        new Thread(() -> consumeTelemetry(), "anticheat-consumer").start();
    }

    private static void consumeTelemetry() {
        Properties cp = new Properties();
        cp.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, Env.kafkaBootstrap());
        cp.put(ConsumerConfig.GROUP_ID_CONFIG, "anticheat");
        cp.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        cp.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        cp.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        try (KafkaConsumer<String,String> c = new KafkaConsumer<>(cp)) {
            c.subscribe(Collections.singletonList("telemetry.events"));
            while (true) {
                var recs = c.poll(Duration.ofMillis(500));
                try (PreparedStatement ps = db.prepareStatement(
                        "INSERT INTO alerts(player_id,match_id,type,score) VALUES(?,?,?,?)")) {
                    recs.forEach(r -> {
                        String v = r.value();
                        int speed = readIntField(v, "speed");
                        
                        if (speed > 10) {
                            String playerId = readStringField(v, "playerId");
                            String matchId  = readStringField(v, "matchId");
                            try {
                                ps.setString(1, playerId);
                                ps.setString(2, matchId);
                                ps.setString(3, "SPEED");
                                ps.setDouble(4, speed);
                                ps.addBatch();
                                String alert = "{\"type\":\"SPEED\",\"playerId\":\""+playerId+"\"}";
                                producer.send(new ProducerRecord<>("anticheat.alerts", playerId, alert));
                            } catch (Exception ignored) {}
                        }
                    });
                    ps.executeBatch();
                } catch (Exception ignored) {}
            }
        }
    }

    private static int readIntField(String json, String key) {
        int i = json.indexOf("\""+key+"\"");
        if (i < 0) return 0;
        int c = json.indexOf(':', i);
        if (c < 0) return 0;
        String tail = json.substring(c+1).trim();
        String num = tail.replaceAll("^([0-9]+).*", "$1");
        try { return Integer.parseInt(num); } catch (Exception e) { return 0; }
    }

    private static String readStringField(String json, String key) {
        int i = json.indexOf("\""+key+"\"");
        if (i < 0) return "";
        int c = json.indexOf(':', i);
        int q1 = json.indexOf('\"', c+1);
        int q2 = json.indexOf('\"', q1+1);
        if (q1 < 0 || q2 < 0) return "";
        return json.substring(q1+1, q2);
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
