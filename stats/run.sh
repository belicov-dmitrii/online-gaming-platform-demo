#!/usr/bin/env bash
    set -euo pipefail
    export KAFKA_BOOTSTRAP="${KAFKA_BOOTSTRAP:-localhost:9092}"
    export DATA_DIR="${DATA_DIR:-./data}"
    ../gw :stats:run --args="" -DKAFKA_BOOTSTRAP="$KAFKA_BOOTSTRAP" -DDATA_DIR="$DATA_DIR"