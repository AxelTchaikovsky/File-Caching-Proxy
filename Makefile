all: Proxy.class Server.class RemoteFileHandler.class FileMeta.class RawFile.class

%.class: %.java
	javac $<

clean:
	rm -f *.class
