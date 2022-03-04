import java.io.Serializable;

/**
 * Wrapper for bytes array for file transfer used in RPC calls.
 */
public class RawFile implements Serializable {
    private final byte[] buf;

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
