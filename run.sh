export CLASSPATH=$PWD:$PWD/lib
export proxy15440=127.0.0.1
export proxyport15440=33345
export pin15440=123123123


LD_PRELOAD=./lib/lib440lib.so ./tools/440read foo
LD_PRELOAD=./lib/lib440lib.so ./tools/440read ./lib/
cat README | LD_PRELOAD=./lib/lib440lib.so ./tools/440write output.txt