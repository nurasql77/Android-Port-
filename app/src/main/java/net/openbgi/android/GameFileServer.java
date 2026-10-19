package net.openbgi.android;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.text.TextUtils;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Local WebSocket file service for OpenBGI's existing WebAssembly backend.
 * Only the three read-only protocol operations documented by OpenBGI are exposed.
 * Game files remain in the user-selected SAF folder; the app does not copy them.
 */
public final class GameFileServer extends WebSocketServer {
    private static final int OP_STAT = 1;
    private static final int OP_READ = 2;
    private static final int OP_LIST = 3;
    private static final int ATTR_READONLY = 0x01;
    private static final int ATTR_HIDDEN = 0x02;
    private static final int ATTR_DIRECTORY = 0x10;
    private static final int ATTR_NORMAL = 0x80;
    private static final int MAX_READ = 8 * 1024 * 1024;
    private static final long FILETIME_EPOCH_MS = 11644473600000L;

    private final ContentResolver resolver;
    private final Uri treeUri;

    public GameFileServer(Context context, Uri treeUri, int port) throws UnknownHostException {
        super(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
        this.resolver = context.getApplicationContext().getContentResolver();
        this.treeUri = treeUri;
    }

    @Override public void onOpen(WebSocket conn, ClientHandshake handshake) { }

    @Override public void onMessage(WebSocket conn, String message) { }

    @Override public void onMessage(WebSocket conn, ByteBuffer message) {
        byte[] request = new byte[message.remaining()];
        message.get(request);
        if (request.length < 5) {
            return;
        }

        int requestId = readInt(request, 0);
        int operation = request[4] & 0xff;
        try {
            if (operation == OP_STAT) {
                handleStat(conn, requestId, decodePath(request, 5));
            } else if (operation == OP_READ) {
                if (request.length < 17) {
                    reply(conn, requestId, -1, new byte[0]);
                    return;
                }
                long low = Integer.toUnsignedLong(readInt(request, 5));
                long high = Integer.toUnsignedLong(readInt(request, 9));
                long offset = (high << 32) | low;
                int length = readInt(request, 13);
                handleRead(conn, requestId, decodePath(request, 17), offset, length);
            } else if (operation == OP_LIST) {
                handleList(conn, requestId, decodePath(request, 5));
            } else {
                reply(conn, requestId, -1, new byte[0]);
            }
        } catch (Exception ignored) {
            // The original engine expects missing/unavailable files as a failed operation.
            reply(conn, requestId, -1, new byte[0]);
        }
    }

    @Override public void onClose(WebSocket conn, int code, String reason, boolean remote) { }

    @Override public void onError(WebSocket conn, Exception ex) { }

    @Override public void onStart() { }

    private void handleStat(WebSocket conn, int id, String path) throws Exception {
        Node node = resolve(path);
        if (node == null) {
            reply(conn, id, -1, new byte[0]);
            return;
        }
        int attrs = attributes(node);
        long size = Math.max(0L, node.size);
        long fileTime = (Math.max(0L, node.modified) + FILETIME_EPOCH_MS) * 10000L;
        ByteBuffer body = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        body.putInt(attrs);
        body.putInt((int) size);
        body.putInt((int) (size >>> 32));
        body.putInt((int) fileTime);
        body.putInt((int) (fileTime >>> 32));
        reply(conn, id, 0, body.array());
    }

    private void handleRead(WebSocket conn, int id, String path, long offset, int length) throws Exception {
        if (offset < 0 || length < 0 || length > MAX_READ) {
            reply(conn, id, -1, new byte[0]);
            return;
        }
        Node node = resolve(path);
        if (node == null || node.isDirectory) {
            reply(conn, id, -1, new byte[0]);
            return;
        }
        byte[] bytes = readRange(node.uri, offset, length);
        reply(conn, id, bytes == null ? -1 : bytes.length, bytes == null ? new byte[0] : bytes);
    }

    private void handleList(WebSocket conn, int id, String path) throws Exception {
        Node directory = resolve(path);
        if (directory == null || !directory.isDirectory) {
            reply(conn, id, -1, new byte[0]);
            return;
        }
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, directory.documentId);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int count = 0;
        String[] columns = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        };
        try (Cursor cursor = resolver.query(children, columns, null, null, null)) {
            if (cursor == null) {
                reply(conn, id, -1, new byte[0]);
                return;
            }
            int idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
            int sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE);
            int modCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED);
            while (cursor.moveToNext()) {
                if (idCol < 0 || nameCol < 0) continue;
                String docId = cursor.getString(idCol);
                String name = cursor.getString(nameCol);
                if (TextUtils.isEmpty(docId) || TextUtils.isEmpty(name) || ".".equals(name) || "..".equals(name)) continue;
                String mime = mimeCol >= 0 ? cursor.getString(mimeCol) : null;
                long size = sizeCol >= 0 && !cursor.isNull(sizeCol) ? cursor.getLong(sizeCol) : 0L;
                long modified = modCol >= 0 && !cursor.isNull(modCol) ? cursor.getLong(modCol) : 0L;
                Node node = new Node(docId, name, mime, size, modified,
                    DocumentsContract.buildDocumentUriUsingTree(treeUri, docId));
                byte[] utf8 = name.getBytes(StandardCharsets.UTF_8);
                if (utf8.length > 65535) continue;
                putInt(bytes, attributes(node));
                putInt(bytes, (int) Math.max(0L, size));
                putInt(bytes, (int) (Math.max(0L, size) >>> 32));
                putShort(bytes, utf8.length);
                bytes.write(utf8);
                count++;
            }
        }
        reply(conn, id, count, bytes.toByteArray());
    }

    private Node resolve(String rawPath) throws Exception {
        List<String> parts = normalizedParts(rawPath);
        if (parts == null) return null;
        String rootId = DocumentsContract.getTreeDocumentId(treeUri);
        Node current = queryNode(rootId);
        if (current == null) return null;
        for (String part : parts) {
            if (!current.isDirectory) return null;
            current = findChild(current.documentId, part);
            if (current == null) return null;
        }
        return current;
    }

    private List<String> normalizedParts(String raw) {
        if (raw == null || raw.indexOf('\0') >= 0) return null;
        String path = raw.replace('\\', '/');
        if (path.length() >= 2 && Character.isLetter(path.charAt(0)) && path.charAt(1) == ':') {
            path = path.substring(2);
        }
        List<String> result = new ArrayList<>();
        for (String part : path.split("/+")) {
            if (part.isEmpty() || ".".equals(part)) continue;
            if ("..".equals(part)) {
                if (result.isEmpty()) return null;
                result.remove(result.size() - 1);
            } else {
                result.add(part);
            }
        }
        return result;
    }

    private Node findChild(String parentId, String wanted) throws Exception {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId);
        String[] columns = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        };
        Node folded = null;
        try (Cursor cursor = resolver.query(children, columns, null, null, null)) {
            if (cursor == null) return null;
            int idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
            int sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE);
            int modCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED);
            while (cursor.moveToNext()) {
                if (idCol < 0 || nameCol < 0) continue;
                String name = cursor.getString(nameCol);
                if (name == null || !name.equalsIgnoreCase(wanted)) continue;
                String docId = cursor.getString(idCol);
                String mime = mimeCol >= 0 ? cursor.getString(mimeCol) : null;
                long size = sizeCol >= 0 && !cursor.isNull(sizeCol) ? cursor.getLong(sizeCol) : 0L;
                long modified = modCol >= 0 && !cursor.isNull(modCol) ? cursor.getLong(modCol) : 0L;
                Node candidate = new Node(docId, name, mime, size, modified,
                    DocumentsContract.buildDocumentUriUsingTree(treeUri, docId));
                if (name.equals(wanted)) return candidate;
                if (folded == null) folded = candidate;
            }
        }
        return folded;
    }

    private Node queryNode(String documentId) throws Exception {
        Uri uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);
        String[] columns = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        };
        try (Cursor cursor = resolver.query(uri, columns, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) return null;
            int idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
            int sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE);
            int modCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED);
            String id = idCol >= 0 ? cursor.getString(idCol) : documentId;
            String name = nameCol >= 0 ? cursor.getString(nameCol) : "";
            String mime = mimeCol >= 0 ? cursor.getString(mimeCol) : null;
            long size = sizeCol >= 0 && !cursor.isNull(sizeCol) ? cursor.getLong(sizeCol) : 0L;
            long modified = modCol >= 0 && !cursor.isNull(modCol) ? cursor.getLong(modCol) : 0L;
            return new Node(id, name, mime, size, modified, uri);
        }
    }

    private byte[] readRange(Uri uri, long offset, int length) throws IOException {
        if (length == 0) return new byte[0];
        try (android.os.ParcelFileDescriptor pfd = resolver.openFileDescriptor(uri, "r")) {
            if (pfd == null) return null;
            try (FileInputStream input = new FileInputStream(pfd.getFileDescriptor())) {
                FileChannel channel = input.getChannel();
                channel.position(offset);
                byte[] result = new byte[length];
                ByteBuffer target = ByteBuffer.wrap(result);
                while (target.hasRemaining()) {
                    int n = channel.read(target);
                    if (n < 0) break;
                    if (n == 0) break;
                }
                int count = target.position();
                if (count == result.length) return result;
                byte[] shortResult = new byte[count];
                System.arraycopy(result, 0, shortResult, 0, count);
                return shortResult;
            }
        } catch (IOException | UnsupportedOperationException ex) {
            // Some document providers expose streams instead of seekable files.
            try (InputStream input = resolver.openInputStream(uri)) {
                if (input == null) return null;
                long remaining = offset;
                while (remaining > 0) {
                    long skipped = input.skip(remaining);
                    if (skipped <= 0) {
                        if (input.read() < 0) return new byte[0];
                        skipped = 1;
                    }
                    remaining -= skipped;
                }
                byte[] result = new byte[length];
                int count = 0;
                while (count < length) {
                    int n = input.read(result, count, length - count);
                    if (n < 0) break;
                    if (n == 0) break;
                    count += n;
                }
                if (count == length) return result;
                byte[] shortResult = new byte[count];
                System.arraycopy(result, 0, shortResult, 0, count);
                return shortResult;
            }
        }
    }

    private int attributes(Node node) {
        int attrs = 0;
        if (node.name != null && node.name.startsWith(".")) attrs |= ATTR_HIDDEN;
        if (node.isDirectory) attrs |= ATTR_DIRECTORY;
        else if (attrs == 0) attrs = ATTR_NORMAL;
        // The server is read-only, so marking regular files read-only is accurate.
        if (!node.isDirectory) attrs |= ATTR_READONLY;
        return attrs;
    }

    private static String decodePath(byte[] bytes, int offset) {
        return new String(bytes, offset, bytes.length - offset, StandardCharsets.UTF_8);
    }

    private static int readInt(byte[] bytes, int offset) {
        return ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static void reply(WebSocket connection, int id, int status, byte[] body) {
        ByteBuffer response = ByteBuffer.allocate(8 + body.length).order(ByteOrder.LITTLE_ENDIAN);
        response.putInt(id);
        response.putInt(status);
        response.put(body);
        response.flip();
        if (connection != null && connection.isOpen()) connection.send(response);
    }

    private static void putInt(ByteArrayOutputStream out, int value) {
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
        out.write((value >>> 16) & 0xff);
        out.write((value >>> 24) & 0xff);
    }

    private static void putShort(ByteArrayOutputStream out, int value) {
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
    }

    private static final class Node {
        final String documentId;
        final String name;
        final String mimeType;
        final long size;
        final long modified;
        final Uri uri;
        final boolean isDirectory;

        Node(String documentId, String name, String mimeType, long size, long modified, Uri uri) {
            this.documentId = documentId;
            this.name = name;
            this.mimeType = mimeType;
            this.size = size;
            this.modified = modified;
            this.uri = uri;
            this.isDirectory = DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType);
        }
    }
}
