import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

public class CacheBlock {
    /** Suffixed path of cached file */
    private String suffixPath;
    /** File object cached, one instance each file */
    private File file;
    /** Flag indicating if the cache has been changed */
    private boolean isDirty;
    /** Indicating if the current version is valid */
    private boolean isValid;
    /** Version number */
    private long version;
    /** Original relative path name */
    private String origPath;
    private int refCnt;
    public CacheBlock prev;
    public CacheBlock next;

    public CacheBlock() {}

    /**
     * Creat cache block with relative path and version number.
     * @param origPath relative path (with version suffix) to file
     * @param version current version number
     */
    public CacheBlock(String cacheRoot, String origPath, long version) {
        this.origPath = origPath;
        this.suffixPath = genSuffixPath(origPath, version);
        this.version = version;
        this.file = new File(cacheRoot + suffixPath);
        this.isDirty = false;
        this.isValid = true;
        this.refCnt = 0;
        try {
            file.createNewFile();
            System.err.println("[ Empty file: " + file.getAbsolutePath() + " created. ]");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Make a write copy of the original file into the cache, not linking it into the
     * double linked list. Write copy's life span is from open() to close().
     * @param cacheRoot cache directory + writeCopyPath
     * @param origPath original relative path
     * @param writeCopyPath relative write copy path
     */
    public CacheBlock(String cacheRoot, String origPath, String writeCopyPath, long version) {
        this.origPath = origPath;
        this.suffixPath = writeCopyPath;
        this.version = -1;
        var cachePath = cacheRoot + writeCopyPath;
        this.file = new File(cachePath);
        this.isDirty = false;
        this.isValid = true;
        this.refCnt = 0;
        File origFile = new File(cacheRoot + origPath + "_" + version);
        try {
            System.err.println(" Copy from [ " + origFile.getAbsolutePath() + " ] to [ " + file.getAbsolutePath() + " ]");
            Files.copy(origFile.toPath(), file.toPath(), REPLACE_EXISTING);
            System.err.println("[ Write Copy created @: " + cachePath + " ]");
        } catch (IOException e) {
            e.printStackTrace();
        }
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

    public String getSuffixPath() {
        return suffixPath;
    }

    public File getFile() {
        return file;
    }

    public long getFileSize() {
        return file.length();
    }

    public String getOrigPath() {
        return origPath;
    }

    /**
     * Delete the file on disk linked to the cache block.
     */
    public boolean deleteFile() {
        System.err.println("[ Deleting file: " + file.getAbsolutePath() + " ]");
        return file.delete();
    }

    public boolean isOpen() {
        System.err.println(refCnt + " clients opening the file. ");
        return refCnt > 0;
    }

    public synchronized void P() {
        refCnt++;
    }

    public synchronized void V() {
        refCnt--;
    }


    /**
     * Generate path + _ + version string.
     * @param path relative path to server file
     * @param version the current version of file
     * @return path with version as its suffix to distinguish from other
     * versions.
     */
    public static String genSuffixPath(String path, long version) {
        return path + "_" + version;
    }

    public boolean isValid() {
        return isValid;
    }

    public void setValid(boolean valid) {
        isValid = valid;
    }

    public void renameFile(File newFile) {
        if (!file.renameTo(newFile)) {
            System.err.println(" Rename error. ");
        }
        System.err.println("Old file exists: " + file.exists());
        System.err.println("New file exists: " + newFile.exists());
        file.delete();
        file = newFile;
        suffixPath = file.getName();
        System.err.println("filename: " + file.getName());
    }
}
