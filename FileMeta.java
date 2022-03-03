import java.io.Serializable;

/**
 * Server file meta data.
 */
public class FileMeta implements Serializable {
    private boolean isDirectory;
    private boolean fileExists;
    private boolean isBadFile;
    private long version;
    private long length;

    public boolean isDirectory() {
        return isDirectory;
    }

    public boolean exists() {
        return fileExists;
    }

    public long getVersion() {
        return version;
    }

    public void setFileExists(boolean fileExists) {
        this.fileExists = fileExists;
    }

    public void setBadFile(boolean badFile) {
        isBadFile = badFile;
    }

    public void setIsDirectory(boolean directory) {
        isDirectory = directory;
    }

    public long getLength() {
        return length;
    }

    public void setLength(long length) {
        this.length = length;
    }

    public void setVersion(long version) {
        this.version = version;
    }
}
