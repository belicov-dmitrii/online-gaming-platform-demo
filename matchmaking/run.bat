@echo off
    set KAFKA_BOOTSTRAP=%KAFKA_BOOTSTRAP%
    if "%KAFKA_BOOTSTRAP%"=="" set KAFKA_BOOTSTRAP=localhost:9092
    set DATA_DIR=%DATA_DIR%
    if "%DATA_DIR%"=="" set DATA_DIR=./data
    ..\gw.bat :matchmaking:run --args="" -DKAFKA_BOOTSTRAP=%KAFKA_BOOTSTRAP% -DDATA_DIR=%DATA_DIR%