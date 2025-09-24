#!/usr/bin/env bash
set -euo pipefail

set +e
if [ -f kafka.pid ]; then
  kill $(cat kafka.pid) 2>/dev/null
  rm -f kafka.pid
fi

echo "[stop-all] Stopping services and Gradle daemons..."

# вежливо
./gw --stop >/dev/null 2>&1 || true

# жёстко (все демоны)
pkill -9 -f "GradleDaemon" || true
pkill -9 -f "org.gradle.launcher.daemon.bootstrap.GradleDaemon" || true

# уберём кеш демонов, чтобы точно не поднимались «зависшие»
rm -rf "${HOME}/.gradle/daemon" 2>/dev/null || true

# наши сервисы
pkill -9 -f "app.gaming.Gateway"     || true
pkill -9 -f "app.gaming.Chat"        || true
pkill -9 -f "app.gaming.Stats"       || true
pkill -9 -f "app.gaming.AntiCheat"   || true
pkill -9 -f "app.gaming.Matchmaking" || true
pkill -9 -f "app.gaming.Store"       || true

# на всякий случай любые java-процессы, запущенные как :run
pkill -9 -f ":gateway:run"     || true
pkill -9 -f ":chat:run"        || true
pkill -9 -f ":stats:run"       || true
pkill -9 -f ":anticheat:run"   || true
pkill -9 -f ":matchmaking:run" || true
pkill -9 -f ":store:run"       || true

echo "[stop-all] Done."
