#!/usr/bin/env bash
set -euo pipefail
IFS=$'\n\t'

# Optional: stop anything left from a previous run
if [[ -x "./stop-all.sh" ]]; then
  ./stop-all.sh || true
fi

# --- Paths ---------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KAFKA_DIR="$SCRIPT_DIR/kafka"
CONFIG="$KAFKA_DIR/config/kraft/server.properties"

# --- Ensure Kafka scripts are executable and LF --------------
# (if you ever edited them on Windows, CRLF can break shebangs)
chmod +x "$KAFKA_DIR"/bin/*.sh || true

# --- Prepare Kafka storage ----------------------------------
mkdir -p "$KAFKA_DIR/kafka-data"
if [[ ! -f "$KAFKA_DIR/kafka-data/meta.properties" ]]; then
  CID=$("$KAFKA_DIR/bin/kafka-storage.sh" random-uuid)
  "$KAFKA_DIR/bin/kafka-storage.sh" format -t "$CID" -c "$CONFIG"
fi

# --- Start Kafka --------------------------------------------
"$KAFKA_DIR/bin/kafka-server-start.sh" "$CONFIG" > "$SCRIPT_DIR/kafka.log" 2>&1 &
echo $! > "$SCRIPT_DIR/kafka.pid"

# --- Env & Gradle wrapper -----------------------------------
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
export KAFKA_BOOTSTRAP="${KAFKA_BOOTSTRAP:-localhost:9092}"
export DATA_DIR="${DATA_DIR:-$SCRIPT_DIR/data}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$SCRIPT_DIR/.gradle-cache}"
FLAGS="--no-daemon -Dorg.gradle.jvmargs=-Xms64m"

mkdir -p "$DATA_DIR"
chmod +x "$SCRIPT_DIR/gw" || true
echo "Using KAFKA_BOOTSTRAP=$KAFKA_BOOTSTRAP DATA_DIR=$DATA_DIR"

# --- Launch services ----------------------------------------
( cd "$SCRIPT_DIR/gateway"     && ../gw $FLAGS :gateway:run     -DKAFKA_BOOTSTRAP="$KAFKA_BOOTSTRAP" -DDATA_DIR="$DATA_DIR" ) &
sleep 2
( cd "$SCRIPT_DIR/chat"        && ../gw $FLAGS :chat:run        -DKAFKA_BOOTSTRAP="$KAFKA_BOOTSTRAP" -DDATA_DIR="$DATA_DIR" ) &
( cd "$SCRIPT_DIR/stats"       && ../gw $FLAGS :stats:run       -DKAFKA_BOOTSTRAP="$KAFKA_BOOTSTRAP" -DDATA_DIR="$DATA_DIR" ) &
( cd "$SCRIPT_DIR/anticheat"   && ../gw $FLAGS :anticheat:run   -DKAFKA_BOOTSTRAP="$KAFKA_BOOTSTRAP" -DDATA_DIR="$DATA_DIR" ) &
( cd "$SCRIPT_DIR/matchmaking" && ../gw $FLAGS :matchmaking:run -DKAFKA_BOOTSTRAP="$KAFKA_BOOTSTRAP" -DDATA_DIR="$DATA_DIR" ) &
( cd "$SCRIPT_DIR/store"       && ../gw $FLAGS :store:run       -DKAFKA_BOOTSTRAP="$KAFKA_BOOTSTRAP" -DDATA_DIR="$DATA_DIR" ) &

echo "Launched. Open http://localhost:8080/stream"
wait
