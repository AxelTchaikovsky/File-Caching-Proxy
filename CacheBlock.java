import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

public class CacheBlock {
    /** Relative path of cached file */
    private String path;
    /** File object cached, one instance each file */
    private File file;
    /** Flag indicating if the cache has been changed */
    private boolean isDirty;
    /** Flag indicating if the file in cache is open by a client */
    private boolean isOpen;
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
        this.isOpen = false;
        this.isDirty = false;
        try {
            file.createNewFile();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Make a write copy of the original file into the cache, not linking it into the
     * double linked list. Write copy's life span is from open() to close().
     * @param cachePath cache directory + writeCopyPath
     * @param origPath cache directory + original relative path
     * @param writeCopyPath relative write copy path
     */
    public CacheBlock(String cachePath, String origPath, String writeCopyPath) {
        this.path = writeCopyPath;
        this.version = -1;
        this.file = new File(cachePath);
        this.isOpen = true;
        this.isDirty = false;
        File origFile = new File(origPath);
        try {
            Files.copy(origFile.toPath(), file.toPath(), REPLACE_EXISTING);
            System.err.println("[ Write Copy created @: " + cachePath + " ]");
        } catch (IOException e) {
            e.printStackTrace();
        }
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

    public File getFile() {
        return file;
    }

    public long getFileSize() {
        return file.length();
    }

    /**
     * Delete the file on disk linked to the cache block.
     */
    public boolean deleteFile() {
        System.err.println("[ Deleting file: " + file.getAbsolutePath() + " ]");
        return file.delete();
    }

    public boolean isOpen() {
        return isOpen;
    }

    public void setOpen(boolean open) {
        isOpen = open;
    }
}
