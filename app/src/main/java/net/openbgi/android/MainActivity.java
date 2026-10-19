package net.openbgi.android;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.net.UnknownHostException;

public final class MainActivity extends Activity {
    private static final int REQUEST_GAME_FOLDER = 402;
    private static final int HTTP_PORT = 18764;
    private static final int WEBSOCKET_PORT = 18765;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private Button chooseFolder;
    private WebView webView;
    private AssetHttpServer httpServer;
    private GameFileServer fileServer;
    private Uri selectedTree;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                             WindowManager.LayoutParams.FLAG_FULLSCREEN);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xff101010);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setPadding(8, 6, 8, 6);
        toolbar.setBackgroundColor(0xff202020);

        status = new TextView(this);
        status.setTextColor(0xffeeeeee);
        status.setTextSize(13);
        status.setText("OpenBGI Android — экспериментальная сборка");
        toolbar.addView(status, new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        chooseFolder = new Button(this);
        chooseFolder.setText("Папка игры");
        chooseFolder.setOnClickListener(v -> chooseGameFolder());
        toolbar.addView(chooseFolder, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(toolbar, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        webView.setBackgroundColor(0xff111111);
        webView.setVerticalScrollBarEnabled(false);
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(ConsoleMessage message) {
                android.util.Log.d("OpenBGI-WebView", message.message() + " (" +
                    message.sourceId() + ":" + message.lineNumber() + ")");
                return true;
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onReceivedError(WebView view, WebResourceRequest request,
                                                  WebResourceError error) {
                if (request.isForMainFrame()) {
                    status.setText("Не удалось загрузить движок: " + error.getDescription());
                }
            }
        });
        root.addView(webView, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);

        try {
            httpServer = new AssetHttpServer(getAssets(), HTTP_PORT);
            httpServer.start();
            status.setText("Выбери папку игры для запуска");
        } catch (Exception e) {
            status.setText("Ошибка запуска локального HTTP-сервера: " + e.getMessage());
            chooseFolder.setEnabled(false);
        }
    }

    private void chooseGameFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_GAME_FOLDER);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_GAME_FOLDER || resultCode != RESULT_OK ||
            data == null || data.getData() == null) {
            return;
        }

        Uri tree = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(
                tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // The current Activity grant may still be enough for this session.
        }

        stopFileServer();
        selectedTree = tree;
        try {
            fileServer = new GameFileServer(this, selectedTree, WEBSOCKET_PORT);
            fileServer.start();
            status.setText("Папка выбрана. Запускается OpenBGI WebAssembly…");
            chooseFolder.setText("Сменить папку");
            // Give the loopback WebSocket listener time to bind before the engine connects.
            handler.postDelayed(this::loadEngine, 400);
        } catch (UnknownHostException | RuntimeException e) {
            status.setText("Не удалось открыть локальный файловый сервер: " + e.getMessage());
            Toast.makeText(this, "Ошибка запуска сервера игры", Toast.LENGTH_LONG).show();
        }
    }

    private void loadEngine() {
        if (isFinishing() || selectedTree == null || fileServer == null) return;
        webView.loadUrl("http://127.0.0.1:" + HTTP_PORT +
            "/openbgi.html?cache=192&fs=ws%3A%2F%2F127.0.0.1%3A" + WEBSOCKET_PORT + "%2Ffs");
    }

    private void stopFileServer() {
        if (fileServer != null) {
            try {
                fileServer.stop(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception ignored) { }
            fileServer = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        stopFileServer();
        if (httpServer != null) {
            try {
                httpServer.stop();
            } catch (Exception ignored) { }
            httpServer = null;
        }
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
