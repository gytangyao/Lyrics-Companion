package wemew.lrc.sync;

import android.content.Context;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoWSD;

/**
 * The LAN-facing server: one port serves the packaged display page, the protocol document, the
 * REST fallback and the {@code /ws} WebSocket endpoint. Only packaged assets are ever read,
 * each by exact name — there is no path translation, so directory traversal is impossible.
 * The same page's source of truth is the repo's web-test-client directory.
 */
final class WebDisplayServer extends NanoWSD {

    /** JSON sources implemented by {@link WebStatePublisher}. */
    interface RestSource {
        String stateSnapshot();

        String trackSnapshot();

        byte[] coverBytes(int requestedVersion);
    }

    private final Context context;
    private final WebSocketClients clients;
    private final WebSyncSocket.Hooks socketHooks;
    private final RestSource rest;

    WebDisplayServer(int port, Context context, WebSocketClients clients,
                     WebSyncSocket.Hooks socketHooks, RestSource rest) {
        super(port);
        this.context = context.getApplicationContext();
        this.clients = clients;
        this.socketHooks = socketHooks;
        this.rest = rest;
    }

    @Override protected boolean isWebsocketRequested(NanoHTTPD.IHTTPSession session) {
        return "/ws".equals(pathOf(session)) && super.isWebsocketRequested(session);
    }

    @Override protected WebSocket openWebSocket(NanoHTTPD.IHTTPSession handshake) {
        return new WebSyncSocket(handshake, clients, socketHooks);
    }

    @Override protected Response serveHttp(NanoHTTPD.IHTTPSession session) {
        Response response = route(session);
        // The page may be embedded from a car portal page on another origin; the protocol
        // doc promises these headers on every REST response.
        response.addHeader("Access-Control-Allow-Origin", "*");
        return response;
    }

    /**
     * The app never serves the display page (the reference client lives in the repo's
     * web-test-client directory by design); this landing text only points integrators at it.
     */
    private static final String LANDING_PAGE = "<!doctype html>\n"
            + "<html><head><meta charset=\"utf-8\"><meta name=\"viewport\""
            + " content=\"width=device-width,initial-scale=1\">\n"
            + "<title>微喵歌词同步</title></head>\n"
            + "<body style=\"margin:0;background:#0B1524;color:#F2F7FF;"
            + "font-family:sans-serif;display:flex;align-items:center;"
            + "justify-content:center;height:100vh\">\n"
            + "<div style=\"max-width:560px;padding:32px;line-height:1.8\">\n"
            + "<h1 style=\"color:#6EE7F2;margin:0 0 12px\">微喵歌词同步服务</h1>\n"
            + "<p>本端口是调试通道。正式显示页在仓库的 <b>web-test-client</b> 目录"
            + "（支持云端门店号连接与逐字高亮）。</p>"
            + "<p>协议端点：<code>/ws</code>、<code>/api/state</code>、"
            + "<code>/api/track</code>、<code>/cover?v=N</code>；"
            + "文档见 <code>/protocol</code> 与仓库 docs/web-protocol.md。</p>\n"
            + "</div></body></html>\n";

    private Response route(NanoHTTPD.IHTTPSession session) {
        String path = pathOf(session);
        if ("/".equals(path) || "/index.html".equals(path)) {
            return NanoHTTPD.newFixedLengthResponse(Response.Status.OK,
                    "text/html; charset=utf-8", LANDING_PAGE);
        }
        if ("/protocol".equals(path) || "/protocol.md".equals(path)) {
            return assetResponse("web/protocol.md", "text/markdown; charset=utf-8");
        }
        if ("/api/state".equals(path)) {
            return json(rest.stateSnapshot());
        }
        if ("/api/track".equals(path)) {
            return json(rest.trackSnapshot());
        }
        if ("/cover".equals(path)) {
            return coverResponse(session);
        }
        return error(Response.Status.NOT_FOUND, "not found");
    }

    private Response assetResponse(String assetPath, String mime) {
        InputStream input;
        long length;
        try {
            input = context.getAssets().open(assetPath);
            length = input.available();
        } catch (Exception error) {
            return error(Response.Status.NOT_FOUND, "asset not packaged");
        }
        Response response = NanoHTTPD.newFixedLengthResponse(Response.Status.OK, mime, input,
                length);
        response.addHeader("Cache-Control", "no-cache");
        return response;
    }

    private Response coverResponse(NanoHTTPD.IHTTPSession session) {
        int version = 0;
        try {
            version = Integer.parseInt(session.getParms().get("v") == null
                    ? "0" : session.getParms().get("v"));
        } catch (NumberFormatException ignored) { }
        byte[] bytes = rest.coverBytes(version);
        if (bytes == null) return error(Response.Status.NOT_FOUND, "no art");
        Response response = NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "image/jpeg",
                new ByteArrayInputStream(bytes), bytes.length);
        // The version in the URL is the cache key: once it is current it cannot change.
        response.addHeader("Cache-Control", "max-age=30");
        return response;
    }

    private Response json(String body) {
        Response response = NanoHTTPD.newFixedLengthResponse(Response.Status.OK,
                "application/json; charset=utf-8", body == null ? "{}" : body);
        return response;
    }

    private Response error(Response.Status status, String message) {
        return NanoHTTPD.newFixedLengthResponse(status, "text/plain; charset=utf-8", message);
    }

    private static String pathOf(NanoHTTPD.IHTTPSession session) {
        String uri = session.getUri();
        if (uri == null) return "/";
        int query = uri.indexOf('?');
        return query >= 0 ? uri.substring(0, query) : uri;
    }
}
