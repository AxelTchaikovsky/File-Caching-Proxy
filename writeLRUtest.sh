export CLASSPATH=$PWD:$PWD/lib
export proxy15440=127.0.0.1
export proxyport15440=33345
export pin15440=123123123


cat writetest | LD_PRELOAD=./lib/lib440lib.so ./tools/440write A
ls tmp/cache/
cat writetest | LD_PRELOAD=./lib/lib440lib.so ./tools/440write B
ls tmp/cache/
cat writetest | LD_PRELOAD=./lib/lib440lib.so ./tools/440write C
ls tmp/cache/
cat writetest | LD_PRELOAD=./lib/lib440lib.so ./tools/440write B
ls tmp/cache/
cat writetest | LD_PRELOAD=./lib/lib440lib.so ./tools/440write D
ls tmp/cache/
cat writetest | LD_PRELOAD=./lib/lib440lib.so ./tools/440write E
ls tmp/cache/
cat writetest | LD_PRELOAD=./lib/lib440lib.so ./tools/440write B
ls tmp/cache/
cat writetest | LD_PRELOAD=./lib/lib440lib.so ./tools/440write F
ls tmp/cache/
cat writetest | LD_PRELOAD=./lib/lib440lib.so ./tools/440write G
ls tmp/cache/