@echo off
setlocal enabledelayedexpansion

REM ==== Конфигурация ====
if "%KAFKA_BOOTSTRAP%"=="" set KAFKA_BOOTSTRAP=localhost:9092
if "%DATA_DIR%"=="" set DATA_DIR=.\data
set FLAGS=--no-daemon -Dorg.gradle.jvmargs=-Xms64m

echo Using KAFKA_BOOTSTRAP=%KAFKA_BOOTSTRAP% DATA_DIR=%DATA_DIR%

if not exist "%DATA_DIR%" mkdir "%DATA_DIR%"


REM ==== Gateway ====
cd gateway
..\gw.bat %FLAGS% :gateway:run -DKAFKA_BOOTSTRAP=%KAFKA_BOOTSTRAP% -DDATA_DIR=%DATA_DIR% ^
   > ..\gateway.log 2>&1 &
cd ..

REM ==== Chat ====
cd chat
..\gw.bat %FLAGS% :chat:run -DKAFKA_BOOTSTRAP=%KAFKA_BOOTSTRAP% -DDATA_DIR=%DATA_DIR% ^
   > ..\chat.log 2>&1 &
cd ..

REM ==== Stats ====
cd stats
..\gw.bat %FLAGS% :stats:run -DKAFKA_BOOTSTRAP=%KAFKA_BOOTSTRAP% -DDATA_DIR=%DATA_DIR% ^
   > ..\stats.log 2>&1 &
cd ..

REM ==== AntiCheat ====
cd anticheat
..\gw.bat %FLAGS% :anticheat:run -DKAFKA_BOOTSTRAP=%KAFKA_BOOTSTRAP% -DDATA_DIR=%DATA_DIR% ^
   > ..\anticheat.log 2>&1 &
cd ..

REM ==== Matchmaking ====
cd matchmaking
..\gw.bat %FLAGS% :matchmaking:run -DKAFKA_BOOTSTRAP=%KAFKA_BOOTSTRAP% -DDATA_DIR=%DATA_DIR% ^
   > ..\matchmaking.log 2>&1 &
cd ..

REM ==== Store ====
cd store
..\gw.bat %FLAGS% :store:run -DKAFKA_BOOTSTRAP=%KAFKA_BOOTSTRAP% -DDATA_DIR=%DATA_DIR% ^
   > ..\store.log 2>&1 &
cd ..

echo.
echo All services launched. Check logs: *.log
echo Gateway SSE available at http://localhost:8080/stream
echo.
pause
