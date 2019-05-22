package unimelb.bitbox.util.ConnectionUtils.Peer;

import unimelb.bitbox.protocol.IResponse;
import unimelb.bitbox.protocol.Protocol;
import unimelb.bitbox.util.HostPort;

public abstract class Connection {

    protected Connection(ConnectionType type) {
        this.type = type;
    }

    /**
     * enum for connection types
     */
    public enum ConnectionType {
        INCOMING,
        OUTGOING
    }

    protected HostPort hostPort;
    protected static final int MAX_LOG_LEN = 250;

    public final ConnectionType type;


    public abstract void sendAsync(Protocol protocol);

    // default is allow reconnect
    public void close() {
        this.close(true);
    }

    public abstract void close(Boolean reconnect);

    public abstract void abortWithInvalidProtocol(String additionalMsg);

    // get a string that represents this connection
    protected String currentHostPort() {
        return (hostPort == null) ? "[Unknown]" : "[" + hostPort.toString() + "]";
    }

    public HostPort getHostPort() {
        return hostPort;
    }

    public abstract void markRequestAsDone(IResponse response);

    public abstract boolean allowInvalidMessage();
}
