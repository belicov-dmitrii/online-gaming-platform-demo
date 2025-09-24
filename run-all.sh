#!/usr/bin/env bash

bash ./stop-all.sh
set -euo pipefail

mkdir -p data

echo "Starting Kafka..."
KAFKA_DIR="$(dirname "$0")/kafka"
pushd "$KAFKA_DIR" >/dev/null

CONFIG="config/kraft/server.properties"

# форматируем ТОЛЬКО если НЕТ meta.properties
if [ ! -f "kafka-data/meta.properties" ]; then
  CID=$(bin/kafka-storage.sh random-uuid)
  bin/kafka-storage.sh format -t "$CID" -c "$CONFIG"
fi

# запускаем всегда из каталога kafka (чтобы относительные пути в конфиге работали)
bin/kafka-server-start.sh "$CONFIG" > ../kafka.log 2>&1 &
echo $! > ../kafka.pid

popd >/dev/null


export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 
export KAFKA_BOOTSTRAP="${KAFKA_BOOTSTRAP:-localhost:9092}"
export DATA_DIR="${DATA_DIR:-./data}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-.gradle-cache}"
FLAGS="--no-daemon -Dorg.gradle.jvmargs=-Xms64m"

mkdir -p "$DATA_DIR"
chmod +x gw || true

echo "Using KAFKA_BOOTSTRAP=$KAFKA_BOOTSTRAP DATA_DIR=$DATA_DIR"

# стартуем по одному, чтобы не забить память
( cd gateway     && ../gw $FLAGS :gateway:run     -DKAFKA_BOOTSTRAP="$KAFKA_BOOTSTRAP" -DDATA_DIR="$DATA_DIR" ) &
sleep 2
( cd chat        && ../gw $FLAGS :chat:run        -DKAFKA_BOOTSTRAP="$KAFKA_BOOTSTRAP" -DDATA_DIR="$DATA_DIR" ) &
( cd stats       && ../gw $FLAGS :stats:run       -DKAFKA_BOOTSTRAP="$KAFKA_BOOTSTRAP" -DDATA_DIR="$DATA_DIR" ) &
( cd anticheat   && ../gw $FLAGS :anticheat:run   -DKAFKA_BOOTSTRAP="$KAFKA_BOOTSTRAP" -DDATA_DIR="$DATA_DIR" ) &
( cd matchmaking && ../gw $FLAGS :matchmaking:run -DKAFKA_BOOTSTRAP="$KAFKA_BOOTSTRAP" -DDATA_DIR="$DATA_DIR" ) &
( cd store       && ../gw $FLAGS :store:run       -DKAFKA_BOOTSTRAP="$KAFKA_BOOTSTRAP" -DDATA_DIR="$DATA_DIR" ) &

echo "Launched. Open http://localhost:8080/stream"
wait
