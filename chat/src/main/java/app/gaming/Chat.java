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

public class Chat {
    private static KafkaProducer<String,String> producer;

    public static void main(String[] args) throws Exception {
        Files.createDirectories(Paths.get(Env.dataDir()));
        String dbPath = Paths.get(Env.dataDir(), "chat.db").toAbsolutePath().toString();

        try (Connection init = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             Statement st = init.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL;");
            st.execute(
                "CREATE TABLE IF NOT EXISTS messages(" +
                " id INTEGER PRIMARY KEY AUTOINCREMENT," +
                " room_id TEXT, author_id TEXT, text TEXT," +
                " ts DATETIME DEFAULT CURRENT_TIMESTAMP)"
            );
        }

        Properties pp = new Properties();
        pp.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, Env.kafkaBootstrap());
        pp.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        pp.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        pp.put(ProducerConfig.ACKS_CONFIG, "all");
        producer = new KafkaProducer<>(pp);

        HttpServer http = HttpServer.create(new InetSocketAddress(8082), 0);
        http.createContext("/send", ex -> {
            try {
                Map<String,String> params = parse(ex.getRequestURI().getQuery());
                String room   = params.getOrDefault("room","general");
                String author = params.getOrDefault("author","anon");
                String text   = params.getOrDefault("text","");

                String dbUrl = "jdbc:sqlite:" + Paths.get(Env.dataDir(), "chat.db").toAbsolutePath();
                try (Connection conn = DriverManager.getConnection(dbUrl);
                    PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO messages(room_id, author_id, text) VALUES (?,?,?)")) {
                    try (Statement st = conn.createStatement()) {
                        st.execute("PRAGMA busy_timeout=5000;");
                        st.execute("PRAGMA journal_mode=WAL;");
                    }
                    ps.setString(1, room);
                    ps.setString(2, author);
                    ps.setString(3, text);
                    int n = ps.executeUpdate();
                    System.out.println("[CHAT] Inserted rows (direct): " + n);
                }

                String json = String.format(
                    "{\"room\":\"%s\",\"authorId\":\"%s\",\"text\":\"%s\"}",
                    room, author, text.replace("\"","'")
                );
                RecordMetadata md = producer
                    .send(new ProducerRecord<>("chat.messages", room, json))
                    .get();
                producer.flush();
                System.out.printf("[CHAT] Kafka sent %s-%d@%d%n",
                        md.topic(), md.partition(), md.offset());

                respond(ex, 200, "ok");
            } catch (Exception e) {
                e.printStackTrace();
                respond(ex, 500, "error: " + e.getMessage());
            }
        });
        http.setExecutor(Executors.newCachedThreadPool());
        http.start();
        System.out.println("Chat REST at http://localhost:8082/send");

        new Thread(Chat::consume, "chat-consumer").start();
    }

    private static void consume() {
        System.out.println("[CHAT] Consumer starting...");
        while (true) {
            try {
                Properties props = new Properties();
                props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, Env.kafkaBootstrap());
                props.put(ConsumerConfig.GROUP_ID_CONFIG, "chat");
                props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
                props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
                props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

                try (KafkaConsumer<String,String> c = new KafkaConsumer<>(props)) {
                    c.subscribe(Collections.singletonList("chat.messages"));
                    String dbUrl = "jdbc:sqlite:" + Paths.get(Env.dataDir(), "chat.db").toAbsolutePath();
                    try (Connection conn = DriverManager.getConnection(dbUrl)) {
                        try (Statement st = conn.createStatement()) {
                            st.execute("PRAGMA busy_timeout=5000;");
                            st.execute("PRAGMA journal_mode=WAL;");
                        }
                        conn.setAutoCommit(false);
                        String sql = "INSERT INTO messages(room_id,author_id,text) VALUES(?,?,?)";
                        try (PreparedStatement ps = conn.prepareStatement(sql)) {
                            while (true) {
                                ConsumerRecords<String,String> recs = c.poll(Duration.ofMillis(500));
                                if (recs.isEmpty()) continue;

                                int bat = 0;
                                for (ConsumerRecord<String,String> r : recs) {
                                    String room   = extract(r.value(), "room");
                                    String author = extract(r.value(), "authorId");
                                    String text   = extract(r.value(), "text");
                                    ps.setString(1, room);
                                    ps.setString(2, author);
                                    ps.setString(3, text);
                                    ps.addBatch();
                                    bat++;
                                }
                                ps.executeBatch();
                                conn.commit();
                                System.out.println("[CHAT] Consumer wrote rows: " + bat);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("[CHAT] Consumer error — restarting in 2s:");
                e.printStackTrace();
                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
            }
        }
    }

    private static String extract(String json, String key) {
        int i = json.indexOf("\"" + key + "\"");
        if (i < 0) return "";
        int c = json.indexOf(':', i);
        int q1 = json.indexOf('"', c + 1);
        int q2 = json.indexOf('"', q1 + 1);
        return (q1 >= 0 && q2 > q1) ? json.substring(q1 + 1, q2) : "";
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
