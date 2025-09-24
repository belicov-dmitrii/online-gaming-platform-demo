package app.gaming;

import app.gaming.util.Env;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

public class Stats {

    public static void main(String[] args) throws Exception {
        Files.createDirectories(Paths.get(Env.dataDir()));

        Connection db = DriverManager.getConnection("jdbc:sqlite:" + Env.dataDir() + "/stats.db");
        try (Statement st = db.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL;");
            st.execute(
                "CREATE TABLE IF NOT EXISTS player_stats(" +
                "  player_id TEXT PRIMARY KEY," +
                "  matches   INT  DEFAULT 0," +
                "  wins      INT  DEFAULT 0," +
                "  kills     INT  DEFAULT 0," +
                "  mmr       INT  DEFAULT 1500," +
                "  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP" +
                ")"
            );
        }

        Properties pp = new Properties();
        pp.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, Env.kafkaBootstrap());
        pp.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        pp.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        KafkaProducer<String,String> producer = new KafkaProducer<>(pp);

        Properties cp = new Properties();
        cp.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, Env.kafkaBootstrap());
        cp.put(ConsumerConfig.GROUP_ID_CONFIG, "stats");
        cp.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        cp.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        cp.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        try (KafkaConsumer<String,String> consumer = new KafkaConsumer<>(cp)) {
            consumer.subscribe(Collections.singletonList("match.finished"));
            while (true) {
                ConsumerRecords<String,String> recs = consumer.poll(Duration.ofMillis(500));

                try (PreparedStatement up = db.prepareStatement(
                        "INSERT INTO player_stats(player_id,matches,wins,kills,mmr) VALUES(?,1,?, ?, 1500+?) " +
                        "ON CONFLICT(player_id) DO UPDATE SET " +
                        "  matches = player_stats.matches + 1, " +
                        "  wins    = player_stats.wins    + excluded.wins, " +
                        "  kills   = player_stats.kills   + excluded.kills, " +
                        "  mmr     = player_stats.mmr     + excluded.mmr - 1500, " +
                        "  updated_at = CURRENT_TIMESTAMP"
                )) {
                    for (var r : recs) {
                        List<Player> players = parsePlayers(r.value());
                        for (Player pr : players) {
                            up.setString(1, pr.id);
                            up.setInt(2, pr.win ? 1 : 0);
                            up.setInt(3, pr.kills);
                            up.setInt(4, pr.win ? 25 : -10);
                            up.addBatch();

                            // отправим маленькое уведомление для UI
                            String payload = "{\"playerId\":\"" + pr.id + "\",\"mmrDelta\":" + (pr.win ? 25 : -10) + "}";
                            producer.send(new ProducerRecord<>("stats.updates", pr.id, payload));
                        }
                    }
                    up.executeBatch();
                } catch (SQLException ignored) {
                    // не валим сервис, просто пропускаем партию
                }
            }
        }
    }


    private static List<Player> parsePlayers(String json) {
        ArrayList<Player> list = new ArrayList<>();
        if (json == null) return list;

        int pos = 0;
        while (true) {
            int idKey = json.indexOf("\"id\":\"", pos);
            if (idKey < 0) break;
            int idStart = idKey + 6;
            int idEnd = json.indexOf('"', idStart);
            if (idEnd < 0) break;
            String id = json.substring(idStart, idEnd);

            // kills
            int killsKey = json.indexOf("\"kills\":", idEnd);
            int kills = 0;
            if (killsKey > 0) {
                int i = killsKey + 8;
                StringBuilder num = new StringBuilder();
                while (i < json.length() && Character.isDigit(json.charAt(i))) {
                    num.append(json.charAt(i++));
                }
                if (num.length() > 0) {
                    try { kills = Integer.parseInt(num.toString()); } catch (NumberFormatException ignore) {}
                }
            }

            // win
            int winKey = json.indexOf("\"win\":", idEnd);
            boolean win = false;
            if (winKey > 0) {
                int i = winKey + 6;
                if (json.regionMatches(true, i, "true", 0, 4))  win = true;
            }

            list.add(new Player(id, kills, win));
            pos = idEnd + 1;
        }
        return list;
    }

    private static class Player {
        final String id;
        final int kills;
        final boolean win;
        Player(String id, int kills, boolean win) {
            this.id = id; this.kills = kills; this.win = win;
        }
    }
}
