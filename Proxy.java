/* Sample skeleton for proxy */

import java.io.*;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
            } catch (SecurityException e) {
                return Errors.EPERM;
            }
            return currFd;
        }

        public int close( int fd ) {
            if (!fdPath.containsKey(fd)) {
                return Errors.EBADF;
            }
            fdPath.remove(fd);
            fdRAF.remove(fd);
            return 0;
        }

        public long write( int fd, byte[] buf ) {
            if (!fdPath.containsKey(fd)) {
                return Errors.EBADF;
            }

            RandomAccessFile writeFile = fdRAF.get(fd);
            try {
                writeFile.write(buf);
            } catch (IOException e) {
                e.printStackTrace();
                return Errors.EBADF;
            }

            return buf.length;
        }

        public long read( int fd, byte[] buf ) {
            if (!fdPath.containsKey(fd)) {
                return Errors.EBADF;
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

        public long lseek( int fd, long pos, LseekOption o ) {
            if (!fdPath.containsKey(fd)) {
                return Errors.EBADF;
            }
            if (pos < 0) {
                return Errors.EINVAL;
            }

            RandomAccessFile lskFile = fdRAF.get(fd);
            switch (o) {
                case FROM_START:
                    break;
                case FROM_END:
                    try {
                        pos = lskFile.length() + pos;
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    break;
                case FROM_CURRENT:
                    try {
                        pos = lskFile.getFilePointer() + pos;
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    break;
                default:
                    return Errors.EINVAL;
            }
            if (pos < 0) return Errors.EINVAL;
            try {
                lskFile.seek(pos);
                return pos;
            } catch (IOException e) {
                e.printStackTrace();
                return -1;
            }
        }

        public int unlink( String path ) {
            File file = new File(path);
            if (!file.exists()) {
                return Errors.ENOENT;
            }
            if (file.isDirectory()) {
                return Errors.EISDIR;
            }

            try {
                Files.delete(Paths.get(path));
                return 0;
            } catch (IOException e) {
                e.printStackTrace();
                return -1;
            }
        }

        public void clientdone() {
            for (int fd : fdRAF.keySet()) {
                RandomAccessFile rFile = fdRAF.get(fd);
                fdPath.remove(fd);
                fdRAF.remove(fd);
                try {
                    rFile.close();
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
        System.out.println("Hello World");
        (new RPCreceiver(new FileHandlingFactory())).run();
    }
}

