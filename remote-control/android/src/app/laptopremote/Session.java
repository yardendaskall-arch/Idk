package app.laptopremote;

/**
 * Holds the one live connection so it survives moving between screens.
 * {@link #FORWARDER} is the listener given to RemoteClient; it hands events to whichever
 * activity is currently showing ({@link #target}).
 */
final class Session {
    static volatile RemoteClient client;
    static volatile RemoteClient.ServerInfo info;
    static volatile RemoteClient.Listener target;
    /** Why the last session ended unexpectedly; shown once on the connect screen. */
    static volatile String closeReason;

    static final RemoteClient.Listener FORWARDER = new RemoteClient.Listener() {
        public void onFrame(byte[] jpeg) {
            RemoteClient.Listener t = target;
            if (t != null) t.onFrame(jpeg);
        }

        public void onNotice(String kind, String message) {
            RemoteClient.Listener t = target;
            if (t != null) t.onNotice(kind, message);
        }

        public void onClosed(String reason) {
            closeReason = reason;
            client = null;
            info = null;
            RemoteClient.Listener t = target;
            if (t != null) t.onClosed(reason);
        }
    };

    private Session() {
    }

    static void end() {
        RemoteClient c = client;
        client = null;
        info = null;
        target = null;
        if (c != null) c.close();
    }
}
