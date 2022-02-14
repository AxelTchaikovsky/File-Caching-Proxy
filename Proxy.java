/* Sample skeleton for proxy */

import java.io.*;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Proxy {

    private static Integer fd = 9;
    private static Map<Integer, String> fdPath = new ConcurrentHashMap<Integer, String>();
    private static Map<Integer, RandomAccessFile> fdRAF = new ConcurrentHashMap<Integer, RandomAccessFile>();

    private static class FileHandler implements FileHandling {
        /**
         * Handle open() request from client
         * @param path path of target file
         * @param o open option
         * @return file descriptor or -errno
         */
        public int open( String path, OpenOption o ) {
            Integer currFd;
            path = normalize(path);
            File file = new File(path);
            if (!file.exists() && (o == OpenOption.READ || o == OpenOption.WRITE)) {
                return Errors.ENOENT;
            } else if (file.isDirectory()) {
                // May be wrong condition
                return Errors.EISDIR;
            }
            try {
                switch (o) {
                    case READ:
                        currFd = fetchFd();
                        fdPath.put(currFd, path);
                        fdRAF.put(currFd, new RandomAccessFile(file, "r"));
                        break;
                    case WRITE:
                        currFd = fetchFd();
                        fdPath.put(currFd, path);
                        fdRAF.put(currFd, new RandomAccessFile(file, "w"));
                        break;
                    case CREATE:
                        currFd = fetchFd();
                        fdPath.put(currFd, path);
                        fdRAF.put(currFd, new RandomAccessFile(file, "rw"));
                        break;
                    case CREATE_NEW:
                        if (file.exists()) return Errors.EEXIST;
                        currFd = fetchFd();
                        fdPath.put(currFd, path);
                        fdRAF.put(currFd, new RandomAccessFile(file, "rw"));
                        break;
                    default:
                        return Errors.EINVAL;
                }
            } catch (FileNotFoundException e) {
                return Errors.ENOENT;
            }
            return Errors.ENOSYS;
        }

        public int close( int fd ) {
            return Errors.ENOSYS;
        }

        public long write( int fd, byte[] buf ) {
            return Errors.ENOSYS;
        }

        public long read( int fd, byte[] buf ) {
            return Errors.ENOSYS;
        }

        public long lseek( int fd, long pos, LseekOption o ) {
            return Errors.ENOSYS;
        }

        public int unlink( String path ) {
            return Errors.ENOSYS;
        }

        public void clientdone() {
            return;
        }

        /**
         * Normalize possible redundant path.
         * @param OrigPath Original path
         * @return Normalized absolute path
         */
        private String normalize(String OrigPath) {
            return FileSystems.getDefault().getPath(OrigPath).normalize().toString();
        }

        private Integer fetchFd() {
            return fd++;
        }

    }

    private static class FileHandlingFactory implements FileHandlingMaking {
        public FileHandling newclient() {
            return new FileHandler();
        }
    }

    public static void main(String[] args) throws IOException {
        System.out.println("Hello World");
        (new RPCreceiver(new FileHandlingFactory())).run();
    }
}

