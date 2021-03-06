/* Sample skeleton for proxy */

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.FileSystems;
import java.rmi.Naming;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Proxy {

    private static final int ARG_LEN = 4;
    private static final Object versionLock = new Object();
    private static int fd = 9;
    private static RemoteFileHandler server;
    private static LRUCache lruCache;

    public static void main(String[] args) throws IOException {
        if (args.length < ARG_LEN) {
            System.err.println(
                    "Missing arguments: expected 4, got " + args.length + ".");
            return;
        }

        String serverIP = args[0];
        int port = Integer.parseInt(args[1]);
        String cacheRoot = args[2] + "/";
        int cacheSize = Integer.parseInt(args[3]);
        System.err.println("[ Cache size: " + cacheSize + " ]");

        // Initialize cache
        lruCache = new LRUCache(cacheSize, cacheRoot);

        String url = "//" + serverIP + ":" + port + "/server";

        try {
            server = (RemoteFileHandler) Naming.lookup(url);
        } catch (NotBoundException e) {
            e.printStackTrace();
        }

        (new RPCreceiver(new FileHandlingFactory())).run();
    }

    private static class FileHandler implements FileHandling {
        private static final int MAX_CHUNK_SIZE = 64000;
        private final Object dirtLock = new Object();
        /**
         * A thread-safe hashmap mapping fd to {@link FdObject}
         */
        private final Map<Integer, FdObject> fdObjectMap = new ConcurrentHashMap<>();
        /**
         * A thread-safe relative path to if-cache-dirty map
         */
        private final Map<String, Boolean> pathDirty = new ConcurrentHashMap<>();
//        private static final int MAX_CHUNK_SIZE = 128;

        /**
         * Download file from server, if file too big, get file by chunks. In the meantime,
         * sync the version number with server.
         *
         * @param path relative path to file
         */
        private synchronized void getFileFromServer(String path, FileMeta fileMeta) {
            if (lruCache.getFileVersion(path) >= fileMeta.getVersion()) return;
            System.err.println("[ Download file from server to cache ]");
            try {
                if (!fileMeta.exists()) {
                    System.err.println("[ File doesn't exist in server ]");
                    return;
                }
                String cachePath = lruCache.getCacheRoot() + path + "_" + fileMeta.getVersion();
                writeToLocal(path, fileMeta, cachePath);
                synchronized (versionLock) {
                    lruCache.put(path, fileMeta.getVersion());
                    System.err.println("[ Updated current version: " + fileMeta.getVersion() + " ]");
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        /**
         * Write to local file. Using chunking.
         *
         * @param path      relative path on server
         * @param fileMeta  meta information on server file
         * @param cachePath the absolute cache path with version suffix
         * @throws IOException when write error occurs
         */
        private void writeToLocal(String path, FileMeta fileMeta, String cachePath) throws IOException {
            long offset = 0;
            RandomAccessFile randomAccessFile;
            randomAccessFile = new RandomAccessFile(cachePath, "rw");
            RawFile rawFile;
            while (offset < fileMeta.getLength() - MAX_CHUNK_SIZE) {
                rawFile = server.getFile(path, MAX_CHUNK_SIZE, offset);

                offset += rawFile.length();
                randomAccessFile.write(rawFile.getBuf());
                randomAccessFile.seek(offset);
            }
            rawFile = server.getFile(path,
                    (int) (fileMeta.getLength() - offset),
                    offset);
            randomAccessFile.write(rawFile.getBuf());
            randomAccessFile.close();
        }

        /**
         * Handle open() request from client, distribute a fd to path.
         *
         * @param path path of target file
         * @param o    open option
         * @return file descriptor or -errno
         */
        public int open(String path, OpenOption o) {
            FileMeta fileMeta = new FileMeta();
            // Check to server every time we call open()
            try {
                fileMeta = server.getFileMeta(path);
            } catch (RemoteException e) {
                e.printStackTrace();
            }

            Integer currFd;
            path = normalize(path);
            String cacheRoot = lruCache.getCacheRoot();
            String normCacheRoot = normalize(cacheRoot);
            String cachePath = normalize(cacheRoot + path);
            System.err.println("[ Cache root: " + cacheRoot + " ]");
            System.err.println("[ File path: " + cachePath + " ]");

            // Check file permission, no file outside cache root directory can
            // be read
            if (!cachePath.contains(normCacheRoot)) {
                return Errors.EPERM;
            }


            if (fileMeta.exists() && o == OpenOption.CREATE_NEW) {
                return Errors.EEXIST;
            }
            if (!fileMeta.exists()) {
                if (o == OpenOption.READ || o == OpenOption.WRITE) {
                    System.err.println("Error: ENOENT1");
                    return Errors.ENOENT;
                }
            } else if (fileMeta.isDirectory() && o != OpenOption.READ) {
                System.err.println("Error: EISDIR");
                return Errors.EISDIR;
            }

            try {
                if (!lruCache.contains(path)) {
                    renderCacheMiss(path, fileMeta, cachePath);
                } else {
                    renderCacheHit(path, fileMeta, cachePath);
                }
            } catch (IOException e) {
                e.printStackTrace(System.err);
            }

            /* Once updated, focus on local cache. */
            CacheBlock cacheBlock = lruCache.get(path + "_" + fileMeta.getVersion());
            // TODO: Maybe in the wrong place
            lruCache.P(lruCache.getSuffixPath(path));
            File fileLocal;
            if (!fileMeta.isDirectory()) {
                fileLocal = cacheBlock.getFile();
            } else {
                fileLocal = new File(cachePath);
            }

            String openOption = "";
            try {
                if (o == OpenOption.READ) {
                    if (!fileLocal.isDirectory()) {
                        openOption = "r";
                    }
                } else if (o == OpenOption.WRITE) {
                    openOption = "w";
                } else if (o == OpenOption.CREATE || o == OpenOption.CREATE_NEW) {
                    openOption = "rw";
                } else {
                    return Errors.EINVAL;
                }

                currFd = linkReadWriteCopy(path,
                        fileMeta,
                        cacheRoot,
                        openOption);
            } catch (FileNotFoundException e) {
                e.printStackTrace(System.err);
                System.err.println("Error: ENOENT2");
                return Errors.ENOENT;
            } catch (SecurityException e) {
                e.printStackTrace(System.err);
                System.err.println("Error: EPERM");
                return Errors.EPERM;
            }
            return currFd;
        }

        /**
         * If the desired file is not cached:
         * 1. If non-existent on server, create new empty file both remote and
         * locally.
         * 2. If file exists on server, locally create all missing parent
         * directories, get file from server.
         *
         * @param path      relative path on server
         * @param fileMeta  Meta information about file on server
         * @param cachePath local cachePath, without suffix
         * @throws IOException when file IO fails
         */
        private void renderCacheMiss(String path,
                                     FileMeta fileMeta,
                                     String cachePath) throws IOException {
            if (!fileMeta.exists()) {
                // Create empty file when file is not on server
                if (!server.creatFile(path)) {
                    System.err.println(path + " failed to be created in Server. ");
                }
                // Creat empty file locally
                lruCache.put(path, 0L);
            } else {
                File file = new File(cachePath);
                File parentDirectory = file.getParentFile();

                while (parentDirectory != null && !parentDirectory.exists()) {
                    parentDirectory.mkdir();
                    parentDirectory = parentDirectory.getParentFile();
                }

                if (fileMeta.isDirectory()) {
                    File dirFile = new File(cachePath);
                    if (!dirFile.mkdir()) {
                        System.err.println("Error creating local directory. 1");
                    }
                } else {
                    // If remote file exists then fetch from server, put into
                    // cache and update version number
                    getFileFromServer(path, fileMeta);
                }
            }
        }

        /**
         * On a cache hit,
         * check if local cache copy is stale, if stale, fetch from server.
         *
         * @param path      relative original path on server
         * @param fileMeta  Meta information about file on server
         * @param cachePath absolute cache path without suffix
         */
        private void renderCacheHit(String path,
                                    FileMeta fileMeta,
                                    String cachePath) {
            long localVersion = lruCache.getFileVersion(path);
            long remoteVersion = fileMeta.getVersion();
            System.err.println("[ Local version: " + localVersion + " ]");
            System.err.println("[ Remote version: " + remoteVersion + " ]");
            if (localVersion < remoteVersion) {
                // If local cached version is stale, fetch file from server
                System.err.println("Updating local file/directory ...");
                if (fileMeta.isDirectory()) {
                    if (!new File(cachePath).mkdir()) {
                        System.err.println("Error creating local directory. ");
                    }
                } else {
                    getFileFromServer(path, fileMeta);
                }
            } else {
                System.err.println(path + " already up to date. ");
            }
        }

        /**
         * Link file descriptor with read copy (at most one for each version),
         * or write copy (one for each writer), by putting the pair into
         * hashmap. Responsibility includes creating write copy.
         *
         * @param path       relative path on server
         * @param fileMeta   meta information about file on server
         * @param cacheRoot  local cache root directory
         * @param openOption permission flag for random access file
         * @return the file descriptor associated with curr session.
         * @throws FileNotFoundException
         */
        private Integer linkReadWriteCopy(String path,
                                          FileMeta fileMeta,
                                          String cacheRoot,
                                          String openOption) throws FileNotFoundException {
            Integer currFd;
            currFd = fetchFd();

            if (openOption.equals("")) {
                fdObjectMap.put(currFd, new FdObject(path));
            } else if (openOption.contains("w")) {
                /*
                 * If current session have "write" permission:
                 * 1. Make new file: write copy in cache
                 * 2. Put the fd -> write copy RAF connection into fd object map
                 */
                var writeCopyPath =
                        lruCache.putWriteCopy(path, currFd, fileMeta.getVersion());
                fdObjectMap.put(currFd,
                        new FdObject(cacheRoot, writeCopyPath, openOption));
            } else {
                // Read only situation
                String readCopyPath = path + "_" + fileMeta.getVersion();
                fdObjectMap.put(currFd,
                        new FdObject(cacheRoot, readCopyPath, openOption));
            }
            return currFd;
        }

        /**
         * Handle close() RPC call from client. Decrement the reference counter
         * on the file. On read close: Check if the file is still being open
         * (referenced). If still open, do nothing. If not begin open and is
         * invalidated, then do garbage collection,
         * remove from cache and delete on disk.
         *
         * @param fd file descriptor
         * @return 0 on success,
         */
        public synchronized int close(int fd) {
            System.err.println("[ Closing fd: " + fd + " ]");
            /*---------- Errors handling ----------*/
            if (!fdObjectMap.containsKey(fd)) return Errors.EBADF;
            if (fdObjectMap.get(fd).isDirectory()) {
                fdObjectMap.remove(fd);
                return 0;
            }
            /*-------------------------------------*/

            /*
             * Already dealt with fd being a directory,
             * following fds is valid file, not directory.
             */
            String path = fdObjectMap.get(fd).getPath();
            fdObjectMap.get(fd).closeRAF();
            if (!lruCache.isFileDirty(path)) {
                lruCache.get(path);
                lruCache.V(path);
            }

            lruCache.garbageCollectStaleVersion(path);
            try {
                /* If path marked dirty cache, then write back to server. */
                if (lruCache.isFileDirty(path)) {
                    FileMeta fileMeta = server.getFileMeta(lruCache.getOrigPath(path));
                    /* If the file in server has not been deleted */
                    if (fileMeta.exists()) {
                        /* Upload file from cache to server */
                        System.err.println("[ Upload file from cache to server ]");
                        RandomAccessFile randomAccessFile =
                                new RandomAccessFile(
                                        normalize(lruCache.getCacheRoot() + path),
                                        "r");
                        long offset = 0;
                        byte[] buf = new byte[MAX_CHUNK_SIZE];
                        while (offset < randomAccessFile.length() - MAX_CHUNK_SIZE) {
                            randomAccessFile.seek(offset);
                            randomAccessFile.read(buf);
                            server.writeFile(lruCache.getOrigPath(path), buf, offset);
                            offset += MAX_CHUNK_SIZE;
                        }
                        buf = new byte[(int) (randomAccessFile.length() - offset)];
                        System.err.println("[ buf:" + buf.length + " ]");
                        randomAccessFile.seek(offset);
                        randomAccessFile.read(buf);
                        long newVersion =
                                server.writeFile(lruCache.getOrigPath(path),
                                        buf,
                                        offset);
                        synchronized (versionLock) {
                            lruCache.setFileVersion(lruCache.getOrigPath(path),
                                    newVersion);
                            System.err.println("[ Server distributed "
                                    + lruCache.getOrigPath(path)
                                    + " version: "
                                    + newVersion
                                    + " ]");
                        }
                        lruCache.garbageCollectWriteCopy(path);
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            fdObjectMap.remove(fd);
            lruCache.printCache();
            return 0;
        }

        /**
         * Write to the random access file, record cache to be dirty
         *
         * @param fd  File descriptor
         * @param buf content to be written to file
         * @return length written to file
         */
        public long write(int fd, byte[] buf) {
            /*---------- Errors handling ----------*/
            if (!fdObjectMap.containsKey(fd)) return Errors.EBADF;
            /*-------------------------------------*/

            RandomAccessFile writeFile = fdObjectMap.get(fd).getRAF();
            try {
                writeFile.write(buf);
                synchronized (dirtLock) {
                    lruCache.setDirtyStatus(fdObjectMap.get(fd).getPath(),
                            true);
                }
            } catch (IOException e) {
                e.printStackTrace(System.err);
                return Errors.EBADF;
            }

            return buf.length;
        }

        /**
         * Read file into buf.
         *
         * @param fd  file descriptor
         * @param buf buffer for read content
         * @return the total number of bytes read into the buffer, or 0 if
         * there is no more data because the end of this file has been reached
         */
        public long read(int fd, byte[] buf) {
            /*---------- Errors handling ----------*/
            if (!fdObjectMap.containsKey(fd)) return Errors.EBADF;
            if (fdObjectMap.get(fd).isDirectory()) return Errors.EISDIR;
            /*-------------------------------------*/

            RandomAccessFile readFile = fdObjectMap.get(fd).getRAF();
            try {
                int rd = readFile.read(buf);
                if (rd == -1) return 0;
                return rd;
            } catch (IOException e) {
                e.printStackTrace();
                return Errors.EBADF;
            }
        }

        /**
         * Jump to position in file.
         *
         * @param fd  file descriptor
         * @param pos the offset position, measured in bytes
         * @param o   lseek option flag
         * @return position
         */
        public long lseek(int fd, long pos, FileHandling.LseekOption o) {
            /*---------- Errors handling ----------*/
            if (!fdObjectMap.containsKey(fd)) return Errors.EBADF;
            if (pos < 0) return Errors.EINVAL;
            /*-------------------------------------*/

            RandomAccessFile randomAccessFile = fdObjectMap.get(fd).getRAF();
            try {
                switch (o) {
                    case FROM_START:
                        break;
                    case FROM_END:
                        pos = randomAccessFile.length() + pos;
                        break;
                    case FROM_CURRENT:
                        pos = randomAccessFile.getFilePointer() + pos;
                        break;
                    default:
                        return Errors.EINVAL;
                }
                if (pos < 0) return Errors.EINVAL;
                randomAccessFile.seek(pos);
                return pos;
            } catch (IOException e) {
                e.printStackTrace(System.err);
                return -1;
            }
        }

        /**
         * Unlinking a file, if still being open, invalidate it. The file will
         * be garbage collected if not being open on next close().
         *
         * @param path relative path on server.
         * @return 0 for successful invalidation or unlink, -1 for error.
         */
        public int unlink(String path) {
            System.err.println("[ Unlinking path: " + path + " ]");
            path = normalize(path);
            String cacheRoot = lruCache.getCacheRoot();
            String cachePath = normalize(cacheRoot + path);
            System.err.println("[ Cache root: " + cacheRoot + " ]");
            System.err.println("[ File path: " + cachePath + " ]");

            // Check file permission, no file outside cache root directory can be read
            if (!cachePath.contains(cacheRoot)) {
                return Errors.EPERM;
            }

            FileMeta fileMeta;
            try {
                fileMeta = server.getFileMeta(path);
                if (fileMeta.exists()) {
                    if (fileMeta.isDirectory()) {
                        return Errors.ENOENT;
                    }
                    server.unlink(path);
                } else {
                    return Errors.ENOENT;
                }
                lruCache.unlinkBlock(path);
            } catch (IOException e) {
                e.printStackTrace(System.err);
                return -1;
            }

            return 0;
        }

        /**
         * Clean up after client has finished procedure.
         */
        public void clientdone() {
            for (int fd : fdObjectMap.keySet()) {
                RandomAccessFile randomAccessFile = fdObjectMap.get(fd).getRAF();
                fdObjectMap.remove(fd);
                try {
                    randomAccessFile.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        /**
         * Normalize possible redundant path.
         *
         * @param OrigPath Original path
         * @return Normalized absolute path
         */
        private String normalize(String OrigPath) {
            return FileSystems.getDefault()
                    .getPath(OrigPath)
                    .normalize()
                    .toString();
        }

        private synchronized Integer fetchFd() {
            return fd++;
        }

    }

    private static class FileHandlingFactory implements FileHandlingMaking {
        public FileHandling newclient() {
            return new FileHandler();
        }
    }
}

