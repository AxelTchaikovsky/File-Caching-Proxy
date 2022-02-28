export CLASSPATH=$PWD:$PWD/lib
export proxy15440=127.0.0.1
export proxyport15440=33345
export pin15440=123123123

javac Proxy.java
java Proxy 127.0.0.1 11822 ./tmp/cache 5


