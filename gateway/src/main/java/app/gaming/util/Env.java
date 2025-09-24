package app.gaming.util;

public final class Env {
    public static String kafkaBootstrap() {
        String sys = System.getProperty("KAFKA_BOOTSTRAP");
        String env = System.getenv("KAFKA_BOOTSTRAP");
        return sys != null ? sys : (env != null ? env : "localhost:9092");
    }
    public static String dataDir() {
        String sys = System.getProperty("DATA_DIR");
        String env = System.getenv("DATA_DIR");
        return sys != null ? sys : (env != null ? env : "./data");
    }
}