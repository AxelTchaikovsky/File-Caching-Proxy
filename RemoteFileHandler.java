import java.io.IOException;
import java.rmi.Remote;
import java.rmi.RemoteException;

public interface RemoteFileHandler extends Remote {

    RawFile getFile(String path, int nbytes, long offset) throws RemoteException;

    boolean creatFile(String path) throws IOException;

    long writeFile(String path, byte[] buf, long offset) throws RemoteException;

    FileMeta getFileMeta(String path) throws RemoteException;

    long getFileVersion(String path) throws RemoteException;

    void unlink(String path) throws IOException;

}
