package wemew.lrc.sync;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Registry of connected web sockets. Broadcasts never throw: a dead socket is closed and
 * reaped by its own {@link WebSyncSocket#trySend} on the next write.
 */
final class WebSocketClients {
    private final CopyOnWriteArrayList<WebSyncSocket> clients = new CopyOnWriteArrayList<>();

    void add(WebSyncSocket socket) {
        if (socket != null) clients.addIfAbsent(socket);
    }

    void remove(WebSyncSocket socket) {
        if (socket != null) clients.remove(socket);
    }

    int size() {
        return clients.size();
    }

    void broadcast(String message) {
        if (message == null || clients.isEmpty()) return;
        for (WebSyncSocket socket : clients) {
            socket.trySend(message);
        }
    }
}
