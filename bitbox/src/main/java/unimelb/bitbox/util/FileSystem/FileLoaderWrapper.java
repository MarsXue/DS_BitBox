package unimelb.bitbox.util.FileSystem;


import unimelb.bitbox.util.ConnectionUtils.Connection;
import unimelb.bitbox.Constants;
import unimelb.bitbox.protocol.Protocol;
import unimelb.bitbox.protocol.ProtocolFactory;
import unimelb.bitbox.protocol.ProtocolField;
import unimelb.bitbox.util.Configuration;
import unimelb.bitbox.util.MessageHandler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.logging.Logger;


/**
 *
 *
 * @author Wenqing Xue (813044)
 * @author Weizhi Xu (752454)
 * @author Zijie Shen (741404)
 * @author Zijun Chen (813190)
 */
public class FileLoaderWrapper {
    private static Logger log = Logger.getLogger(Connection.class.getName());

    private static final long BLOCK_SIZE =
            Long.parseLong(Configuration.getConfigurationValue(Constants.CONFIG_FIELD_BLOCKSIZE));
    private static final int REQUEST_LIMIT = 10;
    private static final long TIMEOUT_IN_MILLIS = 20000;

    private final LinkedList<ProtocolField.FilePosition> pending = new LinkedList<>();
    private final HashMap<Connection, ConnectionInfo> connectionInfoMap = new HashMap<>();

    private ProtocolField.FileDes fileDes;
    private FileSystemManager fileSystemManager;


    public FileLoaderWrapper(ProtocolField.FileDes fileDes, FileSystemManager fileSystemManager, Connection conn) {

        this.fileDes = fileDes;
        this.fileSystemManager = fileSystemManager;

        long base = 0, remaining = fileDes.fileSize;

        synchronized (this) {

            connectionInfoMap.put(conn, new ConnectionInfo());

            while (remaining > 0) {
                ProtocolField.FilePosition pos = new ProtocolField.FilePosition();
                pos.pos = base;
                pos.len = Math.min(remaining, BLOCK_SIZE);
                base += pos.len;
                remaining -= pos.len;
                pending.addLast(pos);
            }
        }

        send(REQUEST_LIMIT, conn);
    }


    public boolean checkMd5(String md5) {
        return this.fileDes.md5.equals(md5);
    }


    /**
     * Add a new connection to transmitting the same file
     * @param conn the connection wants to transmit the file
     */
    public void addNewConnection(Connection conn) {

        synchronized (this) {
            if (connectionInfoMap.containsKey(conn)) return;
            ConnectionInfo connectionInfo = new ConnectionInfo();
            connectionInfoMap.put(conn, connectionInfo);
        }

        send(REQUEST_LIMIT, conn);
    }


    public void received(Protocol.FileBytesResponse fileBytesResponse, Connection conn) {
        String filePath = fileBytesResponse.fileDes.path;

        if (!fileDes.md5.equals(fileBytesResponse.fileDes.md5)) {
            return;
        }

        ProtocolField.FilePosition pos = new ProtocolField.FilePosition();
        pos.len = fileBytesResponse.fileContent.len;
        pos.pos = fileBytesResponse.fileContent.pos;

        ConnectionInfo connectionInfo;

        synchronized (this) {
            connectionInfo = connectionInfoMap.get(conn);

            if (connectionInfo == null || !connectionInfo.waiting.contains(pos)) {
                return;
            }
            connectionInfo.lastActiveTime = System.currentTimeMillis();
        }

        ProtocolField.FileContent fc = fileBytesResponse.fileContent;
        ByteBuffer src = ByteBuffer.wrap(Base64.getDecoder().decode(fc.content));
        try {
            if (!fileSystemManager.writeFile(filePath, src, fc.pos)) {
                cancel();
                return;
            }
        } catch (IOException e) {
            log.warning(e.toString());
            return;
        }


        send(1, conn);

        synchronized (this) {
            connectionInfo.waiting.remove(pos);

            // only check complete when there is nothing in the pending list or waiting sets
            if (!pending.isEmpty()) return;
            for (ConnectionInfo info : connectionInfoMap.values()) {
                if (!info.waiting.isEmpty()) return;
            }
        }

        try {
            fileSystemManager.checkWriteComplete(fileBytesResponse.fileDes.path);
        } catch (NoSuchAlgorithmException | IOException ignored) {
            cancel();
        }

        MessageHandler.removeFileLoaderWrapper(this, fileDes.path);
    }


    private void send(int limit, Connection conn) {
        ArrayList<ProtocolField.FilePosition> posList = new ArrayList<>();

        synchronized (this) {
            ConnectionInfo connectionInfo = connectionInfoMap.get(conn);
            if (connectionInfo == null) return;

            for (int i = 0; i < limit; i++) {
                ProtocolField.FilePosition sendPos;
                sendPos = pending.pollFirst();
                if (sendPos != null) {
                    connectionInfo.waiting.add(sendPos);
                    posList.add(sendPos);
                } else {
                    break;
                }
            }
        }

        for (ProtocolField.FilePosition pos : posList) {
            SendFileByteRequest(pos, conn);
        }
    }


    private void SendFileByteRequest(ProtocolField.FilePosition filePosition, Connection conn) {
        Protocol.FileBytesRequest fileBytesRequest = new Protocol.FileBytesRequest();

        fileBytesRequest.fileDes = this.fileDes;
        fileBytesRequest.filePos = filePosition;

        conn.send(ProtocolFactory.marshalProtocol(fileBytesRequest));
    }


    private void cancel() {
        try {
            fileSystemManager.cancelFileLoader(this.fileDes.path);
        } catch (Exception ignored) {
        }

        MessageHandler.removeFileLoaderWrapper(this, fileDes.path);
    }


    public void clean() {
        // not accurate since this will be triggered roughly every syncInterval and with low priority
        synchronized (this) {

            Iterator<Map.Entry<Connection, ConnectionInfo>> it = connectionInfoMap.entrySet().iterator();

            while (it.hasNext()) {
                Map.Entry<Connection, ConnectionInfo> entry = it.next();
                // time out, remove this connection and add everything back to pending list
                if (System.currentTimeMillis() - entry.getValue().lastActiveTime > TIMEOUT_IN_MILLIS) {
                    pending.addAll(entry.getValue().waiting);
                    it.remove();

                    log.info("Connection cleaned, path:" + fileDes.path
                            + ", Connection: " + entry.getKey().getHostPort().toString());
                }
            }

            // no active connection, cancel
            if (connectionInfoMap.isEmpty()) {
                log.info("Task cleaned, path:" + fileDes.path);
                cancel();
            }
        }
    }


    private static class ConnectionInfo {
        HashSet<ProtocolField.FilePosition> waiting;
        long lastActiveTime;


        public ConnectionInfo() {
            waiting = new HashSet<>();
            lastActiveTime = System.currentTimeMillis();
        }
    }

}
