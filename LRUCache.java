import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class LRUCache {
    private final int cacheCapacity;
    private int currSize = 0;
    private final String cacheRoot;
    private final CacheBlock head;
    private final CacheBlock tail;
    /** Maps relative path to cache block */
    private final Map<String, CacheBlock> cacheBlockMap;

    public LRUCache(int cacheCapacity, String cacheRoot) {
        this.cacheCapacity = cacheCapacity;
        this.cacheRoot = cacheRoot;
        this.head = new CacheBlock();
        this.tail = new CacheBlock();
        head.prev = null;
        head.next = tail;
        tail.prev = head;
        tail.next = null;
        cacheBlockMap = new ConcurrentHashMap<>();
    }

    public synchronized void putWriteCopy(String path, int code) {
        String writeCopyPath = path + "_write_" + code;
        String origPath = cacheRoot + path;
        String cachePath = cacheRoot + writeCopyPath;
        // Creates write copy in cache dir but not put it in double linked list.
        CacheBlock cacheBlock = new CacheBlock(cachePath, origPath, writeCopyPath);
        cacheBlockMap.put(writeCopyPath, cacheBlock);
        currSize += cacheBlock.getFileSize();
        sizeControl();
    }

    /**
     * Put a new cached block into lru cache, if the file does not exist, create a
     * new empty file.
     * @param path relative path
     * @param version current version
     */
    public synchronized void put(String path, long version) {
        String cachePath = cacheRoot + path;
        CacheBlock cacheBlock = new CacheBlock(cachePath, path, version);
        cacheBlockMap.put(path, cacheBlock);
        currSize += cacheBlock.getFileSize();
        addBlock(cacheBlock);
        sizeControl();
    }

    private void sizeControl() {
        while (currSize > cacheCapacity) {
            CacheBlock oldBlock = removeTail();
            cacheBlockMap.remove(oldBlock.getPath());
            currSize -= oldBlock.getFileSize();
            boolean tmp = (oldBlock.deleteFile());
            assert (tmp);
        }
        System.err.println("[ Size control done, cache usage: " + currSize + "/" + cacheCapacity + " ]");
    }

    public CacheBlock get(String path) {
        if (!cacheBlockMap.containsKey(path)) {
            return null;
        }
        CacheBlock cacheBlock = cacheBlockMap.get(path);
        moveToHead(cacheBlock);
        return cacheBlock;
    }

    /**
     * Delete file if exist in local cache.
     * @param path relative path
     * @throws IOException when <code>deleteIfExists</code> fails
     */
    public void unlinkBlock(String path) throws IOException {
        if (cacheBlockMap.containsKey(path)) {
            CacheBlock cacheBlock = cacheBlockMap.get(path);
            // Remove from double linked list
            removeBlock(cacheBlock);
            // Remove from hash map
            cacheBlockMap.remove(path);
            Files.deleteIfExists(cacheBlock.getFile().toPath());
        }
    }

    /**
     * Get version of file, without updating cache sequence.
     * @param path relative path
     * @return version number or -1 if not found in cache
     */
    public long getFileVersion(String path) {
        if (!cacheBlockMap.containsKey(path)) return -1L;
        return cacheBlockMap.get(path).getVersion();
    }

    /**
     * Set version of file, without updating cache sequence.
     * @param path relative path
     */
    public void setFileVersion(String path, long version) {
        if (cacheBlockMap.containsKey(path)) {
            cacheBlockMap.get(path).setVersion(version);
        }
    }

    /**
     * Set open status of file, without updating cache sequence.
     * @param path relative path
     */
    public void setOpenStatus(String path, boolean isOpen) {
        if (cacheBlockMap.containsKey(path)) {
            cacheBlockMap.get(path).setOpen(isOpen);
        }
    }

    /**
     * Precondition: path is valid in cache. Get the dirty status from
     * cache.
     * @param path relative path
     * @return true if is dirty, false otherwise
     */
    public boolean isFileDirty(String path) {
        return cacheBlockMap.get(path).isDirty();
    }

    /**
     * Precondition: path is valid in cache. Set the dirty status from
     * cache.
     * @param path relative path
     */
    public void setDirtyStatus(String path, boolean isDirty) {
        cacheBlockMap.get(path).setDirty(isDirty);
    }

    public boolean contains(String path) {
        return cacheBlockMap.containsKey(path);
    }

    private void addBlock(CacheBlock cacheBlock) {
        head.next.prev = cacheBlock;
        cacheBlock.next = head.next;
        cacheBlock.prev = head;
        head.next = cacheBlock;
    }

    private void removeBlock(CacheBlock cacheBlock) {
        if (cacheBlock == null) {
            return;
        }
        cacheBlock.prev.next = cacheBlock.next;
        cacheBlock.next.prev = cacheBlock.prev;
        cacheBlock.prev = null;
        cacheBlock.next = null;
    }

    private synchronized void moveToHead(CacheBlock cacheBlock) {
        removeBlock(cacheBlock);
        addBlock(cacheBlock);
    }

    /**
     * Remove the least recently used block that is not currently open.
     * @return the removed block, or null if all cache are open.
     */
    private synchronized CacheBlock removeTail() {
        CacheBlock cacheBlock = tail.prev;
        while (cacheBlock != null && cacheBlock.isOpen()) {
            cacheBlock = cacheBlock.prev;
        }
        removeBlock(cacheBlock);
        if (cacheBlock == null) {
            System.err.println("[ Nothing evictable, all files open. ]");
            return null;
        }
        System.err.println("[ Evicted: " + cacheBlock.getPath() + " ]");
        return cacheBlock;
    }

}
