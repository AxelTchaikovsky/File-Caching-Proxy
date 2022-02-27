import java.io.File;

public class CacheBlock {
    /** Relative path of cached file */
    private String path;
    /** File object cached, one instance each file */
    private File file;
    /** Flag indicating if the cache has been changed */
    private boolean isDirty;
    /** Version number */
    private long version;
    public CacheBlock prev;
    public CacheBlock next;

    public CacheBlock() {}

    /**
     * Creat cache block with relative path and version number.
     * @param path relative path to file
     * @param version current version number
     */
    public CacheBlock(String cachePath, String path, long version) {
        this.path = path;
        this.version = version;
        this.file = new File(cachePath);
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    public boolean isDirty() {
        return isDirty;
    }

    public void setDirty(boolean dirty) {
        isDirty = dirty;
    }

    public String getPath() {
        return path;
    }
}
