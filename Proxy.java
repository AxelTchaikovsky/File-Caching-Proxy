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

    private static class FileHandler implements FileHandling {
        private final Object versionLock = new Object();
        /** A thread-safe hashmap mapping fd to file path */
        private final Map<Integer, String> fdPath = new ConcurrentHashMap<>();
        /** A thread-safe hashmap mapping fd to random access file */
        private final Map<Integer, RandomAccessFile> fdRAF = new ConcurrentHashMap<>();
        /** A thread-safe relative path to version map */
        private final Map<String, Long> pathVersion = new ConcurrentHashMap<>();

        private static final int MAX_CHUNCK_SIZE = (int) 1e6;

        private synchronized void getFileFromServer(String path, String cachePath) {
            System.err.println("[ Download file from server to cache ]");
            long offset = 0;
            RandomAccessFile randomAccessFile;
            try {
                FileMeta fileMeta = server.getFileMeta(path);
                if (!fileMeta.exists()) {
                    System.err.println("[ File doesn't exist in server ]");
                    return;
                }
                randomAccessFile = new RandomAccessFile(cachePath, "rw");
                RawFile rawFile;
                while (offset < fileMeta.getLength() - MAX_CHUNCK_SIZE) {
                    rawFile = server.getFile(path, MAX_CHUNCK_SIZE, offset);
                    System.err.println("[ Raw file content: " + Arrays.toString(rawFile.getBuf()) + " ]");
                    System.err.println("[ Raw file size: " + rawFile.getBuf().length + " ]");

                    offset += rawFile.length();
                    randomAccessFile.write(rawFile.getBuf());
                    randomAccessFile.seek(offset);
                }
                rawFile = server.getFile(path, (int) (fileMeta.getLength() - offset), offset);
                System.err.println(" "+offset+" "+fileMeta.getLength());
                System.err.println("[ Raw file content: " + Arrays.toString(rawFile.getBuf()) + " ]");
                System.err.println("[ Raw file size: " + rawFile.getBuf().length + " ]");
                randomAccessFile.write(rawFile.getBuf());
                randomAccessFile.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        /**
         * Handle open() request from client
         * @param path path of target file
         * @param o open option
         * @return file descriptor or -errno
         */
        public int open( String path, OpenOption o ) {
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

            File fileLocal = new File(cachePath);
            FileMeta fileMeta = new FileMeta();

            // TODO: Do we need to check to server every time we call open()?
            try {
                fileMeta = server.getFileMeta(path);
            } catch (RemoteException e) {
                e.printStackTrace();
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
                long localVersion = pathVersion.getOrDefault(path, -1L);
                long remoteVersion = server.getFileVersion(path);
                if ((localVersion < remoteVersion || !fileLocal.exists()) && remoteVersion != -1L) {
                    System.err.println("Updating local file/directory ...");
                    if (fileMeta.isDirectory()) {
                        if (!fileLocal.mkdir()) {
                            System.err.println("Error creating local directory. ");
                        }
                    } else {
                        getFileFromServer(path, cachePath);
                    }
                } else {
                    // TODO: if conditions are not logically clear
                    if (!fileMeta.exists()) {
                        /* Create empty file when file is not on server */
                        if (!server.creatFile(path)) {
                            System.err.println(path + " failed to be created in Server. ");
                        }
                    }
                    System.err.println(path + " already up to date. ");
                }
            } catch (IOException e) {
                e.printStackTrace(System.err);
            }

            /* Once updated, focus on local cache. */
            if (!fileLocal.exists()) {
                if (o == OpenOption.READ || o == OpenOption.WRITE) {
                    System.err.println("Error: ENOENT1");
                    return Errors.ENOENT;
                }
            } else if (fileLocal.isDirectory() && o != OpenOption.READ) {
                // May be wrong condition
                System.err.println("Error: EISDIR");
                return Errors.EISDIR;
            }

            try {
                switch (o) {
                    case READ:
                        currFd = fetchFd();
                        fdPath.put(currFd, path);
                        if (!fileLocal.isDirectory()) {
                            fdRAF.put(currFd, new RandomAccessFile(cachePath, "r"));
                        }
                        pathVersion.putIfAbsent(path, 0L);
                        break;
                    case WRITE:
                        currFd = fetchFd();
                        fdPath.put(currFd, path);
                        fdRAF.put(currFd, new RandomAccessFile(cachePath, "w"));
                        pathVersion.putIfAbsent(path, 0L);
                        break;
                    case CREATE:
                        currFd = fetchFd();
                        fdPath.put(currFd, path);
                        fdRAF.put(currFd, new RandomAccessFile(cachePath, "rw"));
                        pathVersion.putIfAbsent(path, 0L);
                        break;
                    case CREATE_NEW:
                        if (fileLocal.exists()) return Errors.EEXIST;
                        currFd = fetchFd();
                        fdPath.put(currFd, path);
                        fdRAF.put(currFd, new RandomAccessFile(cachePath, "rw"));
                        pathVersion.putIfAbsent(path, 0L);
                        break;
                    default:
                        return Errors.EINVAL;
                }
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
            if (!fdPath.containsKey(fd)) {
                return Errors.EBADF;
            } else if (!fdRAF.containsKey(fd)) {
                /* When the file linked to fd is a directory */
                fdPath.remove(fd);
                pathVersion.remove(fdPath.get(fd));
                return 0;
            }

            /*
             * Already dealt with fd being a directory,
             * following fds is valid file, not directory.
             */
            String path = fdPath.get(fd);
            try {
                long localVersion = pathVersion.get(path);
                long remoteVersion = server.getFileVersion(path);
                System.err.println("[ Local Ver.: " + localVersion + " ]");
                System.err.println("[ Remote Ver.: " + remoteVersion + " ]");

                if (localVersion > remoteVersion) {
                    /* Upload file from cache to server */
                    System.err.println("[ Upload file from cache to server ]");
                    RandomAccessFile randomAccessFile =
                            new RandomAccessFile(normalize(cacheRoot + path), "r");
                    long offset = 0;
                    byte[] buf = new byte[MAX_CHUNCK_SIZE];
                    while (offset < randomAccessFile.length() - MAX_CHUNCK_SIZE) {
                        // TODO: casted offset from long to int, correctness depend on file length < MAX_INT
                        randomAccessFile.seek(offset);
                        randomAccessFile.read(buf);
                        server.writeFile(path, buf, offset);
                        offset += MAX_CHUNCK_SIZE;
                    }
                    buf = new byte[(int)(randomAccessFile.length() - offset)];
                    System.err.println("[ buf:" + buf.length + " ]");
                    randomAccessFile.seek(offset);
                    randomAccessFile.read(buf);
                    server.writeFile(path, buf, offset);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            fdPath.remove(fd);
            fdRAF.remove(fd);
            return 0;
        }

        public long write(int fd, byte[] buf) {
            if (!fdPath.containsKey(fd)) {
                return Errors.EBADF;
            }

            RandomAccessFile writeFile = fdRAF.get(fd);
            try {
                writeFile.write(buf);
                synchronized (versionLock) {
                    pathVersion.put(fdPath.get(fd),
                            pathVersion.getOrDefault(fdPath.get(fd), -1L) + 1);
                }
            } catch (IOException e) {
                e.printStackTrace();
                return Errors.EBADF;
            }

            return buf.length;
        }

        public long read(int fd, byte[] buf) {
            if (!fdPath.containsKey(fd)) {
                return Errors.EBADF;
            }

            if (!fdRAF.containsKey(fd)) {
                return Errors.EISDIR;
            }

            RandomAccessFile readFile = fdRAF.get(fd);
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
            if (!fdPath.containsKey(fd)) {
                return Errors.EBADF;
            }
            if (pos < 0) {
                return Errors.EINVAL;
            }

            RandomAccessFile randomAccessFile = fdRAF.get(fd);
            switch (o) {
                case FROM_START:
                    break;
                case FROM_END:
                    try {
                        pos = randomAccessFile.length() + pos;
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    break;
                case FROM_CURRENT:
                    try {
                        pos = randomAccessFile.getFilePointer() + pos;
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    break;
                default:
                    return Errors.EINVAL;
            }
            if (pos < 0) return Errors.EINVAL;
            try {
                randomAccessFile.seek(pos);
                return pos;
            } catch (IOException e) {
                e.printStackTrace();
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
            File file = new File(cachePath);
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
            } catch (IOException e) {
                e.printStackTrace(System.err);
            }
            /*
            if (!file.exists()) {
                return Errors.ENOENT;
            }
            if (file.isDirectory()) {
                return Errors.EISDIR;
            }
             */
            try {
                Files.deleteIfExists(file.toPath());
            } catch (IOException e) {
                e.printStackTrace(System.err);
                return -1;
            }
            return 0;
        }

        public void clientdone() {
            for (int fd : fdRAF.keySet()) {
                RandomAccessFile randomAccessFile = fdRAF.get(fd);
                fdPath.remove(fd);
                fdRAF.remove(fd);
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
        cacheRoot = args[2];
        cacheSize = Integer.parseInt(args[3]);

        // TODO: is the last part of the url valid? "/server"
        String url = "//" + serverIP + ":" + port + "/server";

        try {
            server = (RemoteFileHandler) Naming.lookup(url);
        } catch (NotBoundException e) {
            e.printStackTrace();
        }

        (new RPCreceiver(new FileHandlingFactory())).run();
    }
}

