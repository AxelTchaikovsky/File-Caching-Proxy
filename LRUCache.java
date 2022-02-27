import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class LRUCache {
    private final int cacheSize;
    private final String cacheRoot;
    private final CacheBlock head;
    private final CacheBlock tail;
    private final Map<String, CacheBlock> cacheBlockMap;

    public LRUCache(int cacheSize, String cacheRoot) {
        this.cacheSize = cacheSize;
        this.cacheRoot = cacheRoot;
        this.head = new CacheBlock();
        this.tail = new CacheBlock();
        head.prev = null;
        head.next = tail;
        tail.prev = head;
        tail.next = null;
        cacheBlockMap = new ConcurrentHashMap<>();
    }

    public synchronized void put(String path, long version) {
        String cachePath = cacheRoot + path;
        CacheBlock cacheBlock = new CacheBlock(cachePath, path, version);
        cacheBlockMap.put(path, cacheBlock);
        addBlock(cacheBlock);
        if (cacheBlockMap.size() > cacheSize) {
            CacheBlock oldBlock = removeTail();
            cacheBlockMap.remove(oldBlock.getPath());
        }
    }

    public CacheBlock get(String path) {
        if (!cacheBlockMap.containsKey(path)) {
            return null;
        }
        CacheBlock cacheBlock = cacheBlockMap.get(path);
        moveToHead(cacheBlock);
        return cacheBlock;
    }

    private void addBlock(CacheBlock cacheBlock) {
        head.next.prev = cacheBlock;
        cacheBlock.next = head.next;
        cacheBlock.prev = head;
        head.next = cacheBlock;
    }

    private void removeBlock(CacheBlock cacheBlock) {
        cacheBlock.prev.next = cacheBlock.next;
        cacheBlock.next.prev = cacheBlock.prev;
        cacheBlock.prev = null;
        cacheBlock.next = null;
    }

    private synchronized void moveToHead(CacheBlock cacheBlock) {
        removeBlock(cacheBlock);
        addBlock(cacheBlock);
    }

    private synchronized CacheBlock removeTail() {
        CacheBlock cacheBlock = tail.prev;
        removeBlock(cacheBlock);
        return cacheBlock;
    }

}
