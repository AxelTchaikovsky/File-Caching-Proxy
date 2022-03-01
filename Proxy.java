/* Sample skeleton for proxy */

import java.io.*;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.rmi.Naming;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Proxy {

    private static int fd = 9;
    private static int cacheSize;
    private static final int ARG_LEN = 4;
    private static String cacheRoot = "cache/default/";
    private static RemoteFileHandler server;
    private static LRUCache lruCache;
    private static final Object versionLock = new Object();
    /** A thread-safe relative path to version map */
    private static final Map<String, Long> pathVersion = new ConcurrentHashMap<>();

    private static class FileHandler implements FileHandling {
        private final Object dirtLock = new Object();
        /** A thread-safe hashmap mapping fd to {@link FdObject} */
        private final Map<Integer, FdObject> fdObjectMap = new ConcurrentHashMap<>();
        /** A thread-safe relative path to if-cache-dirty map */
        private final Map<String, Boolean> pathDirty = new ConcurrentHashMap<>();
        private static final int MAX_CHUNK_SIZE = (int) 1e6;

        /**
         * Download file from server, if file too big, get file by chunks. In the meantime,
         * sync the version number with server.
         * @param path relative path to file
         * @param cachePath absolute path on client's cache
         */
        private synchronized void getFileFromServer(String path, String cachePath, FileMeta fileMeta) {
            System.err.println("[ Download file from server to cache ]");
            long offset = 0;
            RandomAccessFile randomAccessFile;
            try {
                if (!fileMeta.exists()) {
                    System.err.println("[ File doesn't exist in server ]");
                    return;
                }
                randomAccessFile = new RandomAccessFile(cachePath, "rw");
                RawFile rawFile;
                while (offset < fileMeta.getLength() - MAX_CHUNK_SIZE) {
                    rawFile = server.getFile(path, MAX_CHUNK_SIZE, offset);
//                    System.err.println("[ Raw file content: " + Arrays.toString(rawFile.getBuf()) + " ]");
                    System.err.println("[ Raw file size: " + rawFile.getBuf().length + " ]");

                    offset += rawFile.length();
                    randomAccessFile.write(rawFile.getBuf());
                    randomAccessFile.seek(offset);
                }
                rawFile = server.getFile(path, (int) (fileMeta.getLength() - offset), offset);
                System.err.println(" "+offset+" "+fileMeta.getLength());
//                System.err.println("[ Raw file content: " + Arrays.toString(rawFile.getBuf()) + " ]");
                System.err.println("[ Raw file size: " + rawFile.getBuf().length + " ]");
                randomAccessFile.write(rawFile.getBuf());
                randomAccessFile.close();
                synchronized (versionLock) {
                    lruCache.put(path, fileMeta.getVersion());
                    System.err.println("[ Updated current version: " + fileMeta.getVersion() + " ]");
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        /**
         * Handle open() request from client, distribute a fd to path.
         * @param path path of target file
         * @param o open option
         * @return file descriptor or -errno
         */
        public int open(String path, OpenOption o) {
            /*--------------------  DEBUG PRINT MESSAGE S  --------------------*/
            System.err.println("Open: " + path + "\n");
            switch (o) {
                case READ: System.err.println("MODE: Read\n"); break;
                case WRITE: System.err.println("MODE: Write\n"); break;
                case CREATE: System.err.println("MODE: Create\n"); break;
                case CREATE_NEW: System.err.println("MODE: Creat_new\n"); break;
            }
            /*--------------------  DEBUG PRINT MESSAGE E  --------------------*/

            Integer currFd;
            path = normalize(path);
            cacheRoot = normalize(cacheRoot) + "/";
            String cachePath = normalize(cacheRoot + path);
            System.err.println("[ Cache root: " + cacheRoot + " ]");
            System.err.println("[ File path: " + cachePath  + " ]");

            // Check file permission, no file outside cache root directory can be read
            if (!cachePath.contains(cacheRoot)) {
                return Errors.EPERM;
            }

            FileMeta fileMeta = new FileMeta();

            // Check to server every time we call open()
            try {
                fileMeta = server.getFileMeta(path);
            } catch (RemoteException e) {
                e.printStackTrace();
            }

            if (fileMeta.exists() && o == OpenOption.CREATE_NEW) return Errors.EEXIST;
            if (!fileMeta.exists()) {
                if (o == OpenOption.READ || o == OpenOption.WRITE) {
                    System.err.println("Error: ENOENT1");
                    return Errors.ENOENT;
                }
            } else if (fileMeta.isDirectory() && o != OpenOption.READ) {
                System.err.println("Error: EISDIR");
                return Errors.EISDIR;
            }

            /*--------------------  DEBUG PRINT MESSAGE S  --------------------*/
            if (!fileMeta.exists()) {
                System.err.println("File doesn't exist remotely.\n");
            }
            if (fileMeta.isDirectory()) {
                System.err.println("file is directory\n");
            }
            /*--------------------  DEBUG PRINT MESSAGE E  --------------------*/

            try {
                if (!lruCache.contains(path)) {
                    System.err.println("Local file: " + path + " doesn't exist.");
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
                            // If remote file exists then fetch from server, put into cache
                            // and update version number
                            getFileFromServer(path, cachePath, fileMeta);
                        }
                    }
                } else {
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
                            getFileFromServer(path, cachePath, fileMeta);
                        }
                    } else {
                        System.err.println(path + " already up to date. ");
                    }
                }
            } catch (IOException e) {
                e.printStackTrace(System.err);
            }

            /* Once updated, focus on local cache. */
            CacheBlock cacheBlock = lruCache.get(path);
            File fileLocal;
            if (!fileMeta.isDirectory()) {
                fileLocal = cacheBlock.getFile();
            } else {
                fileLocal = new File(cachePath);
            }

            String openOption = "";
            lruCache.setOpenStatus(path, true);
            try {
                switch (o) {
                    case READ:
                        currFd = fetchFd();
                        if (!fileLocal.isDirectory()) {
                            fdObjectMap.put(currFd, new FdObject(cacheRoot, path, "r"));
                        } else {
                            fdObjectMap.put(currFd, new FdObject(path));
                        }
                        break;
                    case WRITE:
                        currFd = fetchFd();
                        fdObjectMap.put(currFd, new FdObject(cacheRoot, path, "w"));
                        break;
                    case CREATE:
                    case CREATE_NEW:
                        currFd = fetchFd();
                        fdObjectMap.put(currFd, new FdObject(cacheRoot, path, "rw"));
                        break;
                    default:
                        return Errors.EINVAL;
                }
//                currFd = fetchFd();
//                if (!openOption.equals("")) {
//                    fdObjectMap.put(currFd, new FdObject(cacheRoot, path, openOption));
//                } else {
//                    fdObjectMap.put(currFd, new FdObject(path));
//                }
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

        public synchronized int close( int fd ) {
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
            lruCache.setOpenStatus(path, false);
            try {
                /* If path marked dirty cache, then write back to server. */
                if (lruCache.isFileDirty(path)) {
                    /* Upload file from cache to server */
                    System.err.println("[ Upload file from cache to server ]");
                    RandomAccessFile randomAccessFile =
                            new RandomAccessFile(normalize(cacheRoot + path), "r");
                    long offset = 0;
                    byte[] buf = new byte[MAX_CHUNK_SIZE];
                    while (offset < randomAccessFile.length() - MAX_CHUNK_SIZE) {
                        randomAccessFile.seek(offset);
                        randomAccessFile.read(buf);
                        server.writeFile(path, buf, offset);
                        offset += MAX_CHUNK_SIZE;
                    }
                    buf = new byte[(int)(randomAccessFile.length() - offset)];
                    System.err.println("[ buf:" + buf.length + " ]");
                    randomAccessFile.seek(offset);
                    randomAccessFile.read(buf);
                    long newVersion = server.writeFile(path, buf, offset);
                    // TODO: Maybe we do not need synchronized here, close() is already synchronized
                    synchronized (versionLock) {
                        lruCache.setFileVersion(path, newVersion);
                        System.err.println("[ Server distributed " + path + " version: " + newVersion + " ]");
                    }
                    synchronized (dirtLock) {
                        lruCache.setDirtyStatus(path, false);
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            fdObjectMap.remove(fd);
            return 0;
        }

        /**
         * Write to the random access file, record cache to be dirty
         * @param fd File descriptor
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
                    lruCache.setDirtyStatus(fdObjectMap.get(fd).getPath(), true);
                }
            } catch (IOException e) {
                e.printStackTrace(System.err);
                return Errors.EBADF;
            }

            return buf.length;
        }

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

        public int unlink(String path) {
            System.err.println("[ Unlinking path: " + path + " ]");
            path = normalize(path);
            cacheRoot = normalize(cacheRoot) + "/";
            String cachePath = normalize(cacheRoot + path);
            System.err.println("[ Cache root: " + cacheRoot + " ]");
            System.err.println("[ File path: " + cachePath  + " ]");

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
         * @param OrigPath Original path
         * @return Normalized absolute path
         */
        private String normalize(String OrigPath) {
            return FileSystems.getDefault().getPath(OrigPath).normalize().toString();
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

    public static void main(String[] args) throws IOException {
        if (args.length < ARG_LEN) {
            System.err.println("Missing arguments: expected 4, got " + args.length + ".");
            return;
        }

        String serverIP = args[0];
        int port = Integer.parseInt(args[1]);
        cacheRoot = args[2] + "/";
        cacheSize = Integer.parseInt(args[3]);
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
}

