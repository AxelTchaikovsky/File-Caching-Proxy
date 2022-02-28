all: Proxy.class Server.class RemoteFileHandler.class FileMeta.class RawFile.class FdObject.class CacheBlock.class LRUCache.class

%.class: %.java
	javac $<

clean:
	rm -f *.class
