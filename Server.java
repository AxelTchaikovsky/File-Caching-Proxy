import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.rmi.AlreadyBoundException;
import java.rmi.Naming;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.server.RMISocketFactory;
import java.rmi.server.UnicastRemoteObject;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Server extends UnicastRemoteObject implements RemoteFileHandler {

    private static final int ARG_LEN = 2;
    private static String root;
    /** hash map between absolute path on server and an object lock */
    private final Map<String, Object> masterCopysMap;
    /** hash map between absolute path on server and version number */
    private final Map<String, Long> versionMap;
    /**
     * Creates and exports a new UnicastRemoteObject object using the
     * particular supplied port.
     *
     * <p>The object is exported with a server socket
     * created using the {@link RMISocketFactory} class.
     *
     * @param port the port number on which the remote object receives calls
     *             (if <code>port</code> is zero, an anonymous port is chosen)
     * @param root root path of server file storage
     * @throws RemoteException if failed to export object
     * @since 1.2
     */
    protected Server(int port, String root) throws RemoteException {
        super(port);
        this.root = root;
        masterCopysMap = new ConcurrentHashMap<>();
        versionMap = new ConcurrentHashMap<>();
    }

    /**
     * Read <code>nbytes</code> of the file, starting from a give offset, reader can read
     * at any time, not synchronized
     * @param path relative path pointing to the file
     * @param nbytes denotes how many bytes to read from file
     * @param offset read will be starting from this offset
     * @return nbytes of raw data read form file
     * @throws RemoteException if failed to export object
     */
    @Override
    public RawFile getFile(String path, int nbytes, long offset) throws RemoteException {
        String absPath = root + path;
        File file = new File(absPath);
        byte[] buf = new byte[nbytes];
        masterCopysMap.putIfAbsent(absPath, new Object());
        try {
            RandomAccessFile randomAccessFile = new RandomAccessFile(file, "r");
            randomAccessFile.seek(offset);
            randomAccessFile.read(buf);
        } catch (IOException e) {
            e.printStackTrace();
        }
        /* If it is the first client request on this file */
        versionMap.putIfAbsent(absPath, 0L);
        return new RawFile(buf);
    }

    /**
     * Write bytes on to server file.
     * @param path relative path to file on server
     * @param buf buffer of bytes of content to write
     * @param offset the offset position, measured in bytes from the beginning
     *               of the file
     * @return new version number
     * @throws RemoteException on RMI failure
     */
    @Override
    public long writeFile(String path, byte[] buf, long offset) throws RemoteException {
        String absPath = root + path;
        File file = new File(absPath);
        long newVersion = -1;
        System.err.println("[ Writing to file : " + absPath  + " ]");
        // Mutual exclusion: one writer at a time
        masterCopysMap.putIfAbsent(absPath, new Object());
        synchronized (masterCopysMap.get(absPath)) {
            try {
                RandomAccessFile randomAccessFile = new RandomAccessFile(file, "rw");
                randomAccessFile.seek(offset);
                randomAccessFile.write(buf);
                // Update version number
                newVersion = versionMap.getOrDefault(absPath, -1L) + 1;
                versionMap.put(absPath, newVersion);
                System.err.println("[ Remote Ver.: " + newVersion + " ]");
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return newVersion;
    }

    /**
     * Create an empty file in server, called when proxy open for create and cannot find the
     * file on server
     * @param path relative path to root dir
     * @return true if the file is successfully created
     * @throws IOException when creating file there's and I/O exception or RMI method's
     * remote exception
     */
    @Override
    public boolean creatFile(String path) throws IOException {
        String absPath = root + path;
        System.err.println("[ Creating file : " + absPath  + " ]");
        File file = new File(absPath);
        // Mutual exclusion: one writer at a time
        masterCopysMap.putIfAbsent(absPath, new Object());
        synchronized (masterCopysMap.get(absPath)) {
            return file.createNewFile();
        }
    }

    /**
     * Collect file meta data from server.
     * @param path relative file path on server
     * @return file meta data.
     * @throws RemoteException if RMI call fails
     */
    @Override
    public FileMeta getFileMeta(String path) throws RemoteException {
        String absPath = root + path;
//        System.err.println("[ Getting metadata : " + absPath  + " ]");
        File file = new File(absPath);
        FileMeta fileMeta = new FileMeta();
        fileMeta.setFileExists(file.exists());
        fileMeta.setIsDirectory(file.isDirectory());
        fileMeta.setLength(file.length());
        fileMeta.setVersion(getFileVersion(path));
        return fileMeta;
    }

    /**
     * Get latest file version from server side, uses relative path.
     * @param path relative path
     * @return return -1 if file doesn't exist on server, otherwise return version.
     * @throws RemoteException when RMI fails
     */
    @Override
    public long getFileVersion(String path) throws RemoteException {
        String absPath = root + path;
        if (!Files.exists(Paths.get(absPath))) {
            System.err.println("[ File " + absPath + " does not exist. ]");
            return 0L;
        }
        versionMap.putIfAbsent(absPath, 0L);
        return versionMap.get(absPath);
    }

    /**
     * Delete file from server.
     * @param path relative path to file
     * @throws IOException if delete operation fails
     */
    public void unlink(String path) throws IOException {
        String absPath = root + path;
        File file = new File(absPath);
        masterCopysMap.putIfAbsent(absPath, new Object());
        synchronized (masterCopysMap.get(absPath)) {
            if (file.exists()) {
                Files.delete(file.toPath());
            }
            versionMap.remove(absPath);
        }
    }

    public static void main(String[] args) throws RemoteException,
            MalformedURLException,
            AlreadyBoundException {
        if (args.length < ARG_LEN) {
            System.err.println("Missing arguments: expected 4, got " + args.length + ".");
            return;
        }
        int port = Integer.parseInt(args[0]);
        root = args[1] + "/";
        System.err.println("[ Port: " + port + " ] [ Root: " + root + " ]");
        try {
            LocateRegistry.createRegistry(port);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        Server server = new Server(port, root);
        Naming.bind("//localhost:" + port + "/server", server);

        System.err.println("[ Server starts ... ]");
    }
}
