import java.io.Serializable;

public class RawFile implements Serializable {
    private byte[] buf;

    RawFile(byte[] buf) {
        this.buf = buf;
    }

    public byte[] getBuf() {
        return buf;
    }

    public int length() {
        return this.buf.length;
    }
}
