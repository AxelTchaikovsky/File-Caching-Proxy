import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * A class object that contains a path, and random access file.
 * This object has one to one mapping with a fd given by the proxy,
 * and is unique to each open-close session.
 */
public class FdObject {
    /** Relative path to file */
    private String path;
    /** Random access file related to the session */
    private RandomAccessFile randomAccessFile;
    private boolean isDirectory;

    /**
     * Initialize the FdObject as a file, with mode specified for the
     * random access file.
     * @param cacheRoot root directory of cache folder
     * @param path relative path
     * @param mode read write permission flag
     * @throws FileNotFoundException when the file could not be found.
     */
    public FdObject(String cacheRoot, String path, String mode) throws FileNotFoundException {
        this.path = path;
        this.randomAccessFile = new RandomAccessFile(cacheRoot + path, mode);
    }

    /**
     * Initialize the FdObject as a directory.
     * @param path relative path
     */
    public FdObject(String path) {
        this.path = path;
        this.isDirectory = true;
    }

    public String getPath() {
        return this.path;
    }

    public RandomAccessFile getRAF() {
        return this.randomAccessFile;
    }

    public boolean isDirectory() {
        return this.isDirectory;
    }

    public boolean closeRAF() {
        if (isDirectory) {
            return false;
        }
        try {
            this.randomAccessFile.close();
        } catch (IOException e) {
            e.printStackTrace(System.err);
            return false;
        }
        return true;
    }
}
