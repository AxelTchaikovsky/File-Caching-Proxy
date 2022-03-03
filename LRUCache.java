import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

public class LRUCache {
    private final int cacheCapacity;
    private int currSize = 0;
    private final String cacheRoot;
    private final CacheBlock head;
    private final CacheBlock tail;
    /** Maps relative suffix path to cache block */
    private final Map<String, CacheBlock> cacheBlockMap;
    /** Maps relative orignal path to version */
    private final Map<String, Long> pathVersion;

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
        pathVersion = new ConcurrentHashMap<>();
    }

    public synchronized String putWriteCopy(String path, int code, long version) {
        String writeCopyPath = path + "_write_" + code;
        // Creates write copy in cache dir but not put it in double linked list.
        CacheBlock cacheBlock = new CacheBlock(cacheRoot, path, writeCopyPath, version);
        cacheBlockMap.put(writeCopyPath, cacheBlock);
        currSize += cacheBlock.getFileSize();
        sizeControl();
        return writeCopyPath;
    }

    /**
     * Put a new cached block into lru cache, if the file does not exist, create a
     * new empty file. Set new file to open status. Put (original path, version) key pair
     * into version map.
     * @param origPath relative origPath
     * @param version current version
     */
    public synchronized void put(String origPath, long version) {
        CacheBlock cacheBlock = new CacheBlock(cacheRoot, origPath, version);
        cacheBlockMap.put(cacheBlock.getSuffixPath(), cacheBlock);
        System.err.println("[ Put: " + cacheBlock.getSuffixPath() + " ]");
        setInvalid(origPath, version);
        pathVersion.put(origPath, version);
        currSize += cacheBlock.getFileSize();
        addBlock(cacheBlock);
        sizeControl();
    }

    /**
     * Set previous version invalid, if it is in the cache and is stale.
     * @param origPath relative path on server
     * @param version the newest version for stale check
     */
    private void setInvalid(String origPath, long version) {
        // Set previous version invalid
        if (pathVersion.containsKey(origPath) && pathVersion.get(origPath) < version) {
            cacheBlockMap.get(
                    CacheBlock.genSuffixPath(origPath, pathVersion.get(origPath))).setValid(false);
        }
    }

    private void sizeControl() {
        while (currSize > cacheCapacity) {
            CacheBlock oldBlock = removeTail();
            if (oldBlock == null) {
                System.err.println(" Can evict nothing. ");
                break;
            }
            System.err.println(" Delete: " + oldBlock.getOrigPath());
            cacheBlockMap.remove(oldBlock.getSuffixPath());
            pathVersion.remove(oldBlock.getOrigPath());
            currSize -= oldBlock.getFileSize();
            boolean tmp = (oldBlock.deleteFile());
            assert (tmp);
        }
        System.err.println("[ Size control done, cache usage: " + currSize + "/" + cacheCapacity + " ]");
    }

    public CacheBlock get(String path) {
//        String suffixPath = CacheBlock.genSuffixPath(path, pathVersion.get(path));
        if (!cacheBlockMap.containsKey(path)) {
            return null;
        }
        CacheBlock cacheBlock = cacheBlockMap.get(path);
        moveToHead(cacheBlock);
        return cacheBlock;
    }

    /**
     * Delete file if exist in local cache.
     * @param path relative path on server
     * @throws IOException when <code>deleteIfExists</code> fails
     */
    public void unlinkBlock(String path) throws IOException {
        String suffixPath = CacheBlock.genSuffixPath(path, pathVersion.remove(path));
        if (cacheBlockMap.containsKey(suffixPath)) {
            CacheBlock cacheBlock = cacheBlockMap.remove(suffixPath);
            // Remove from double linked list
            removeBlock(cacheBlock);
            Files.deleteIfExists(cacheBlock.getFile().toPath());
        }
    }

    /**
     * Get version of file, without updating cache sequence.
     * @param path relative original path on server
     * @return version number or -1 if not found in cache
     */
    public long getFileVersion(String path) {
        if (!pathVersion.containsKey(path)) return -1L;
        return pathVersion.get(path);
    }

    /**
     * Check if the cache have the most current version
     * @param path
     * @param serverVersion
     * @return
     */
    public boolean checkIfStale(String path, long serverVersion) {
        String suffixPath = CacheBlock.genSuffixPath(path, serverVersion);
        return cacheBlockMap.containsKey(suffixPath);
    }

    /**
     * Set version of file, without updating cache sequence.
     * Rename the filename suffix, to match current version number.
     * Remap the new suffix path -> cache block entry in hashmap.
     * Remap the original path -> version entry in hashmap.
     * @param path relative suffix path (path + _ + version)
     */
    public void setFileVersion(String path, long newVersion) throws IOException {
        String oldSuffixPath = CacheBlock.genSuffixPath(path, pathVersion.get(path));
        pathVersion.put(path, newVersion);
        if (cacheBlockMap.containsKey(oldSuffixPath)) {
            CacheBlock cacheBlock = cacheBlockMap.get(oldSuffixPath);
            cacheBlock.setVersion(newVersion);
            String newSuffixPath = CacheBlock.genSuffixPath(cacheBlock.getOrigPath(), newVersion);
            System.err.println("[ new suffix path: " + newSuffixPath + " ]");
            File newSuffixFile = new File(cacheRoot + newSuffixPath);
            cacheBlock.renameFile(newSuffixFile);
            cacheBlockMap.put(newSuffixPath, cacheBlockMap.remove(oldSuffixPath));
            System.err.println("[ Set new version file: " + cacheBlock.getFile().getAbsolutePath() + " ]");
        }
    }

    /**
     * ++ ref count of file, without updating cache sequence.
     * @param suffixPath relative path
     */
    public void P(String suffixPath) {
        if (cacheBlockMap.containsKey(suffixPath)) {
            cacheBlockMap.get(suffixPath).P();
        }
    }

    /**
     * -- ref count of file, without updating cache sequence.
     * @param suffixPath relative path
     */
    public void V(String suffixPath) {
        if (cacheBlockMap.containsKey(suffixPath)) {
            cacheBlockMap.get(suffixPath).V();
        }
    }

    /**
     * Get open status of file, without updating cache sequence.
     * @param path relative path
     */
    public boolean getOpenStatus(String path) {
        if (cacheBlockMap.containsKey(path)) {
            return cacheBlockMap.get(path).isOpen();
        }
        return false;
    }

    public boolean isValid(String path) {
        if (!cacheBlockMap.containsKey(path)) {
            return true;
        }
        return cacheBlockMap.get(path).isValid();
    }

    /**
     * Precondition: path is valid in cache. Get the dirty status from
     * cache.
     * @param path relative path
     * @return true if is dirty, false otherwise
     */
    public boolean isFileDirty(String path) {
        if (!cacheBlockMap.containsKey(path)) {
            return false;
        }
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

    /**
     * Get the original path (without suffix and cache root) of a write copy, without updating the LRU order.
     * @param path relative path of a write/read copy
     * @return relative path of an original copy
     */
    public String getOrigPath(String path) {
        return cacheBlockMap.get(path).getOrigPath();
    }

    public String getSuffixPath(String origPath) {
        return cacheBlockMap.get(
                CacheBlock.genSuffixPath(origPath, pathVersion.get(origPath))).getSuffixPath();
    }

    /**
     * Write the write copy file back to the original file, and delete the write copy
     * from cache. Move written block to front of LRU linked list. -- written file open count.
     * @param path relative write copy path
     */
    public void garbageCollectWriteCopy(String path) {
        if (cacheBlockMap.containsKey(path)) {
            CacheBlock obsoleteWrite = cacheBlockMap.get(path);
            if (obsoleteWrite.prev == null && obsoleteWrite.next == null) {
                String writtenFilePath = CacheBlock.genSuffixPath(getOrigPath(path), pathVersion.get(getOrigPath(path)));
                System.err.println("[ written file path: " + writtenFilePath + " ]");
                CacheBlock writtenFileBlock = cacheBlockMap.get(writtenFilePath);
                File origFile = writtenFileBlock.getFile();
                moveToHead(writtenFileBlock);
                V(writtenFilePath);
                File writeCopyFile = cacheBlockMap.get(path).getFile();
                try {
                    Files.copy(writeCopyFile.toPath(), origFile.toPath(), REPLACE_EXISTING);
                } catch (IOException e) {
                    e.printStackTrace(System.err);
                }
                currSize -= writeCopyFile.length();
                cacheBlockMap.remove(path);
                System.err.println("[ Delete write copy: " + writeCopyFile.getAbsolutePath() + " ]");
                writeCopyFile.delete();
            }
        }
    }

    /**
     * Delete stale read copy if it is not open by any client anymore.
     * Would do nothing if path points to a write copy.
     * @param path file path on cache (with suffix write or version)
     */
    public void garbageCollectStaleVersion(String path) {
        if (cacheBlockMap.containsKey(path) && !getOpenStatus(path) && !isValid(path)) {
            CacheBlock staleBlock = cacheBlockMap.remove(path);
            removeBlock(staleBlock);
            currSize -= staleBlock.getFileSize();
            System.err.println("[ Delete stale copy: " + staleBlock.getFile().getAbsolutePath() + " ]");
            staleBlock.getFile().delete();
        }
    }

    public boolean contains(String path) {
        return pathVersion.containsKey(path);
    }

    private void addBlock(CacheBlock cacheBlock) {
        head.next.prev = cacheBlock;
        cacheBlock.next = head.next;
        cacheBlock.prev = head;
        head.next = cacheBlock;
    }

    private void removeBlock(CacheBlock cacheBlock) {
        if (cacheBlock == null || cacheBlock == head) {
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
        if (cacheBlock == null || cacheBlock == head) {
            System.err.println("[ Nothing evictable, all files open. ]");
            return null;
        }
        System.err.println("[ Evicted: " + cacheBlock.getSuffixPath() + " ]");
        return cacheBlock;
    }

    public String getCacheRoot() {
        return  cacheRoot;
    }

}
