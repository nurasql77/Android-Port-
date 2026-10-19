package net.openbgi.android;

import android.content.res.AssetManager;

import org.nanohttpd.protocols.http.NanoHTTPD;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Serves the build-time OpenBGI HTML/JS/WASM assets only on loopback. */
public final class AssetHttpServer extends NanoHTTPD {
    private final AssetManager assets;
    private final Map<String, String> mimeTypes;

    public AssetHttpServer(AssetManager assets, int port) {
        super("127.0.0.1", port);
        this.assets = assets;
        Map<String, String> types = new HashMap<>();
        types.put("openbgi.html", "text/html; charset=utf-8");
        types.put("openbgi.js", "application/javascript; charset=utf-8");
        types.put("openbgi.wasm", "application/wasm");
        types.put("OPENBGI-COPYING", "text/plain; charset=utf-8");
        mimeTypes = Collections.unmodifiableMap(types);
    }

    @Override
    public Response serve(IHTTPSession session) {
        String path = session.getUri();
        if (path == null || "/".equals(path) || "/index.html".equals(path)) {
            path = "/openbgi.html";
        }
        String name = path.startsWith("/") ? path.substring(1) : path;
        String mime = mimeTypes.get(name);
        if (mime == null || name.contains("..") || name.contains("/")) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain; charset=utf-8", "Not found");
        }
        try {
            InputStream input = assets.open(name, AssetManager.ACCESS_STREAMING);
            Response response = newChunkedResponse(Response.Status.OK, mime, input);
            response.addHeader("Cache-Control", "no-store");
            response.addHeader("X-Content-Type-Options", "nosniff");
            return response;
        } catch (IOException ex) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain; charset=utf-8", "Asset not found");
        }
    }
}
