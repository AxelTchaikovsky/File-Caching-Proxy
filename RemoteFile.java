public class RemoteFile {
    private long length;
    private byte[] rawData;
    private boolean isDirectory;
    private boolean fileExists;
    private boolean isBadFile;
    private long version;

    public RemoteFile(long length) {
        this.length = length;
    }

    public long getLength() {
        return length;
    }

    public boolean isDirectory() {
        return isDirectory;
    }

    public boolean exists() {
        return fileExists;
    }

    public long getVersion() {
        return version;
    }

    public void setRawData(byte[] rawData) {
        this.rawData = rawData;
    }
}
