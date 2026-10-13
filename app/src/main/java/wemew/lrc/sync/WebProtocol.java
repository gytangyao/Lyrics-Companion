package wemew.lrc.sync;

/**
 * Constants of the web sync protocol (see docs/web-protocol.md). The protocol is a public
 * contract: shipped pages are only reference clients, so every name here is neutral, stable
 * and additive — never rename, only extend.
 */
final class WebProtocol {
    /** Protocol version of this build; missing {@code v} on an inbound frame means 1. */
    static final int PROTOCOL_VERSION = 1;
    static final String SERVER_NAME = "wemew-lrc-sync";

    /** Server → client message types. */
    static final String TYPE_WELCOME = "welcome";
    static final String TYPE_TRACK = "track";
    static final String TYPE_STATE = "state";
    static final String TYPE_LINE = "line";
    static final String TYPE_PONG = "pong";
    static final String TYPE_ERROR = "error";

    /** Client → server message types. */
    static final String TYPE_HELLO = "hello";
    static final String TYPE_PING = "ping";

    /** Capabilities advertised in {@code welcome}; see the protocol doc for their meaning. */
    static final String[] CAPABILITIES = {"track", "state", "line", "cover", "ping"};

    /** Default LAN port; auto mode probes this and the next nine ports on bind failure. */
    static final int DEFAULT_PORT = 8765;

    private WebProtocol() {}
}
