package app.gaming;

import java.nio.file.Files;
import java.nio.file.Paths;
import app.gaming.util.Env;
import com.sun.net.httpserver.HttpServer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

public class Matchmaking {
    private static final Deque<String> queue = new ConcurrentLinkedDeque<>();
    private static Connection db;
    private static KafkaProducer<String,String> producer;

    public static void main(String[] args) throws Exception {
        Files.createDirectories(Paths.get(Env.dataDir()));
        db = DriverManager.getConnection("jdbc:sqlite:" + Env.dataDir() + "/matchmaking.db");
        try (Statement st = db.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL;");
            st.execute("CREATE TABLE IF NOT EXISTS lobbies(lobby_id TEXT PRIMARY KEY, players TEXT,status TEXT, created_at DATETIME DEFAULT CURRENT_TIMESTAMP)");
        }

        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, Env.kafkaBootstrap());
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.put(ProducerConfig.ACKS_CONFIG, "all");
        producer = new KafkaProducer<>(p);

        HttpServer server = HttpServer.create(new InetSocketAddress(8081), 0);

        server.createContext("/join", ex -> {

                            var h = ex.getResponseHeaders();
                h.add("Access-Control-Allow-Origin", "*");
                h.add("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
                h.add("Access-Control-Allow-Headers", "Content-Type");
                h.add("Access-Control-Max-Age", "86400");

            String q = ex.getRequestURI().getQuery();
            Map<String,String> params = parse(q);
            String player = params.getOrDefault("player","p-" + UUID.randomUUID());
            queue.add(player);
            respond(ex, 200, "queued:" + player);
        });

        server.createContext("/tick", ex -> {

            
                            var h = ex.getResponseHeaders();
                h.add("Access-Control-Allow-Origin", "*");
                h.add("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
                h.add("Access-Control-Allow-Headers", "Content-Type");
                h.add("Access-Control-Max-Age", "86400");

            int teamSize = 2;
            Map<String,String> params = parse(ex.getRequestURI().getQuery());
            if (params.containsKey("teamSize")) teamSize = Integer.parseInt(params.get("teamSize"));

            if (queue.size() >= teamSize) {
                List<String> team = new ArrayList<>();
                for (int i=0;i<teamSize;i++) team.add(queue.pollFirst());
                String lobbyId = UUID.randomUUID().toString();

                try (PreparedStatement ps = db.prepareStatement("INSERT INTO lobbies(lobby_id,players, status) VALUES(?,?,?)")) {
                    ps.setString(1, lobbyId);
                    ps.setString(2, String.join(",", team));
                    ps.setString(3, "Running");
                    ps.executeUpdate();
                } catch (SQLException se) {
                    respond(ex, 500, "db-error:" + se.getMessage());
                    return;
                }

                String created = "{\"matchId\":\"" + lobbyId + "\",\"status\":\"" + "Running" + "\",\"players\":\"" + team + "\"}";
                producer.send(new ProducerRecord<>("match.created", lobbyId, created));
                respond(ex, 200, "match:" + lobbyId + " -> " + team);
            } else {
                respond(ex, 200, "waiting:" + queue.size());
            }
        });

        server.createContext("/finish", ex -> {

            
                var h = ex.getResponseHeaders();
                h.add("Access-Control-Allow-Origin", "*");
                h.add("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
                h.add("Access-Control-Allow-Headers", "Content-Type");
                h.add("Access-Control-Max-Age", "86400");

            Map<String,String> params = parse(ex.getRequestURI().getQuery());
            String matchId = params.getOrDefault("matchId", UUID.randomUUID().toString());
            String playerId = params.getOrDefault("playerId","p1");
            int kills = Integer.parseInt(params.getOrDefault("kills","3"));
            boolean win = Boolean.parseBoolean(params.getOrDefault("win","true"));
            String json = "{\"matchId\":\"" + matchId + "\",\"players\":[{\"id\":\"" + playerId + "\",\"kills\":" + kills + ",\"win\":" + win + "}]}";

            try (PreparedStatement ps = db.prepareStatement(
                    "UPDATE lobbies " +
                    "SET status = ?" +
                    "WHERE lobby_id = ?")) {
                ps.setString(1, "Finished");
                ps.setString(2, matchId);
                int updated = ps.executeUpdate();
                if (updated == 0) {
                    // не нашли лобби
                    respond(ex, 404, "not-found:lobby " + matchId);
                    return;
                }
                respond(ex, 200, "ok");
            } catch (SQLException se) {
                respond(ex, 500, "db-error:" + se.getMessage());
            }

            producer.send(new ProducerRecord<>("match.finished", matchId, json));
            respond(ex, 200, "finished:" + matchId);
        });

        server.start();
        System.out.println("Matchmaking at http://localhost:8081  (GET /join, /tick, /finish)");
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
