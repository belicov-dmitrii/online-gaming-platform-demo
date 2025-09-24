Install
run ./run-all.sh (I've tried to make it as simple as possible)

go to cd frontend
run npm i (requires Node)
run npm start (Start UI)

cd gateway && ../gw :gateway:run
cd matchmaking && ../gw :matchmaking:run

cd gateway && ..\gw.bat :gateway:run



Kill all gradles

# 0) Stop any Gradle daemons
./gw --stop || true
pkill -f GradleDaemon || true

# 1) Clean project- and repo-local caches (SAFE — they’ll be re-downloaded)
rm -rf .gradle
rm -rf .gradle-cache

# 2) Recreate cache dir and fix perms
mkdir -p .gradle-cache
chmod -R u+rwX .gradle-cache

# 3) Pre-warm dependencies once (no parallel services yet)
./gw --no-daemon --refresh-dependencies build -x test