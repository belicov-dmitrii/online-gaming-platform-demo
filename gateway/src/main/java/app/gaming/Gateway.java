package app.gaming;

import java.sql.*;
import java.nio.file.Path;
import java.nio.file.Paths;

import app.gaming.util.Env;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

public class Gateway {
    private static final List<OutputStream> clients = new CopyOnWriteArrayList<>();

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/stream", Gateway::handleStream);

            server.createContext("/snapshot", ex -> {
        try {
            ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");

            Path root = Paths.get("").toAbsolutePath();
            String chatDb        = root.resolve("../chat/data/chat.db").toString();
            String statsDb       = root.resolve("../stats/data/stats.db").toString();
            String anticheatDb   = root.resolve("../anticheat/data/anticheat.db").toString();
            String matchmakingDb = root.resolve("../matchmaking/data/matchmaking.db").toString();
            String storeDb       = root.resolve("../store/data/store.db").toString();

            var result = new StringBuilder();
            result.append("{");

            var chatRows = queryRows("jdbc:sqlite:" + chatDb,
                    "SELECT room_id AS room, author_id AS authorId, text, ts FROM messages ORDER BY room_id DESC LIMIT 100");
            result.append("\"chat\":").append(toJsonArray(chatRows)).append(",");

            var statsRows = queryRows("jdbc:sqlite:" + statsDb,
                    "SELECT player_id AS playerId, matches, wins, kills, mmr, updated_at FROM player_stats ORDER BY updated_at DESC LIMIT 100");
            result.append("\"stats\":").append(toJsonArray(statsRows)).append(",");

            var acRows = queryRows("jdbc:sqlite:" + anticheatDb,
                    "SELECT player_id AS playerId, match_id AS matchId, type, score, ts FROM alerts ORDER BY player_id DESC LIMIT 100");
            result.append("\"anticheat\":").append(toJsonArray(acRows)).append(",");

            var mmMatches = queryRows("jdbc:sqlite:" + matchmakingDb,
                    "SELECT lobby_id AS matchId, players, status, created_at FROM lobbies ORDER BY lobby_id DESC LIMIT 100");
            result.append("\"matchmaking\":").append(toJsonArray(mmMatches)).append(",");

            var storeRows = queryRows("jdbc:sqlite:" + storeDb,
                    "SELECT player_id AS playerId, order_id, currency, amount, created_at FROM orders ORDER BY player_id DESC LIMIT 100");
            result.append("\"store\":").append(toJsonArray(storeRows)).append(",");
            var inventoryRows = queryRows("jdbc:sqlite:" + storeDb,
                    "SELECT player_id AS playerId, order_id, item FROM inventory_grants LIMIT 100");
            result.append("\"inventory\":").append(toJsonArray(inventoryRows));

            result.append("}");

            var bytes = result.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, bytes.length);
            try (var os = ex.getResponseBody()) { os.write(bytes); }
        } catch (Exception e) {
            e.printStackTrace();
            byte[] b = ("{\"error\":\"" + escapeJson(e.getMessage()) + "\"}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ex.sendResponseHeaders(500, b.length);
            try (var os = ex.getResponseBody()) { os.write(b); }
        }
    });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.println("Gateway SSE at http://localhost:8080/stream");

        // Kafka consumer thread
        Thread t = new Thread(Gateway::consumeKafka, "gateway-consumer");
        t.setDaemon(true);
        t.start();
        t.join();
    }

private static ArrayList<LinkedHashMap<String,Object>> queryRows(String jdbcUrl, String sql) throws Exception {
    var rows = new ArrayList<LinkedHashMap<String,Object>>();
    try (Connection c = DriverManager.getConnection(jdbcUrl);
         Statement s = c.createStatement();
         ResultSet rs = s.executeQuery(sql)) {
        ResultSetMetaData md = rs.getMetaData();
        int n = md.getColumnCount();
        while (rs.next()) {
            var row = new LinkedHashMap<String,Object>();
            for (int i=1;i<=n;i++) {
                String col = md.getColumnLabel(i);
                Object val = rs.getObject(i);
                row.put(col, val);
            }
            rows.add(row);
        }
    }
    return rows;
}

private static String toJsonArray(ArrayList<LinkedHashMap<String,Object>> rows) {
    var sb = new StringBuilder();
    sb.append("[");
    for (int i=0;i<rows.size();i++) {
        if (i>0) sb.append(",");
        sb.append(toJsonObject(rows.get(i)));
    }
    sb.append("]");
    return sb.toString();
}

private static String toJsonObject(LinkedHashMap<String,Object> row) {
    var sb = new StringBuilder();
    sb.append("{");
    int k = 0;
    for (var e : row.entrySet()) {
        if (k++>0) sb.append(",");
        sb.append("\"").append(escapeJson(e.getKey())).append("\":");
        Object v = e.getValue();
        if (v == null) {
            sb.append("null");
        } else if (v instanceof Number || v instanceof Boolean) {
            sb.append(v.toString());
        } else {
            sb.append("\"").append(escapeJson(String.valueOf(v))).append("\"");
        }
    }
    sb.append("}");
    return sb.toString();
}

private static String escapeJson(String s) {
    return s.replace("\\","\\\\").replace("\"","\\\"")
            .replace("\n","\\n").replace("\r","\\r");
}
// ⬆️ END


    private static void handleStream(HttpExchange ex) {
        try {
            var headers = ex.getResponseHeaders();
            headers.add("Access-Control-Allow-Origin", "*");
            headers.add("Content-Type","text/event-stream");
            headers.add("Cache-Control","no-cache");
            headers.add("Connection","keep-alive");
            ex.sendResponseHeaders(200, 0); // chunked
            OutputStream os = ex.getResponseBody();
            clients.add(os);
            // приветственное событие
            writeSse(os, "{\"hello\":\"sse\"}");
        } catch (Exception e) {
            try { ex.close(); } catch (Exception ignore) {}
        }
    }

    private static void consumeKafka() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, Env.kafkaBootstrap());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "gateway");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        try (KafkaConsumer<String,String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(Arrays.asList(
                    "stats.updates",
                    "anticheat.alerts",
                    "chat.messages",
                    "inventory.granted",
                    "match.created",
                    "match.finished",
                    "telemetry.events"
            ));

            while (true) {
                ConsumerRecords<String,String> recs = consumer.poll(Duration.ofMillis(500));
                recs.forEach(rec -> {
                    String jsonWithTopic = String.format(
                        "{\"topic\":\"%s\",\"payload\":%s}",
                        rec.topic(),
                        rec.value()
                    );
                    broadcast(jsonWithTopic);
                });
            }
        }
    }

    private static void broadcast(String json) {
        String line = "data: " + json + "\n\n";
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        clients.removeIf(os -> {
            try { os.write(bytes); os.flush(); return false; }
            catch (Exception e) { try { os.close(); } catch(Exception ignore){} return true; }
        });
    }

    private static void writeSse(OutputStream os, String json) throws Exception {
        String msg = "data: " + json + "\n\n";
        os.write(msg.getBytes(StandardCharsets.UTF_8));
        os.flush();
    }
}
