package com.bonsai.llm;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.webkit.WebViewAssetLoader;

import java.io.File;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "BonsaiLLM";
    private static final int LLAMA_PORT = 8080;
    private static final String PREFS = "bonsai2_engine";
    private static final String PREF_THREADS = "threads";
    private static final String PREF_CTX = "ctx_size";

    private WebView webView;
    private volatile LlamaService llamaService;
    private volatile boolean serviceBound = false;
    private volatile boolean serviceStarting = false;
    private volatile String serviceStartError;
    private ModelManager modelManager;
    private final String apiToken = java.util.UUID.randomUUID().toString() + java.util.UUID.randomUUID();
    public String getApiToken() { return apiToken; }
    private ValueCallback<Uri[]> pendingFileChooser;

    private final ActivityResultLauncher<String> imagePicker = registerForActivityResult(
            new ActivityResultContracts.GetContent(), uri -> {
                if (pendingFileChooser == null) return;
                pendingFileChooser.onReceiveValue(uri == null ? null : new Uri[]{uri});
                pendingFileChooser = null;
            });

    public boolean isLlamaReady() {
        return serviceBound && llamaService != null && llamaService.isReady();
    }

    public LlamaService getLlamaService() {
        return llamaService;
    }

    public String getLlamaState() {
        if (llamaService != null) return llamaService.getState();
        if (serviceStarting) return "loading";
        return serviceStartError == null ? "stopped" : "error";
    }

    public String getLlamaError() {
        return llamaService == null ? serviceStartError : llamaService.getLastError();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Returns the persisted thread override (0 = automatic). */
    public int getThreadsPref() {
        return prefs().getInt(PREF_THREADS, 0);
    }

    /** A 4K default leaves room for an image prompt without excessive phone RAM use. */
    public int getCtxSizePref() {
        return prefs().getInt(PREF_CTX, 16384);
    }

    public void applyEngineSettings(int newThreads, int newCtxSize) {
        prefs().edit()
                .putInt(PREF_THREADS, newThreads)
                .putInt(PREF_CTX, newCtxSize)
                .apply();

        if (serviceBound && llamaService != null) {
            llamaService.reloadWithModel(null, null, null, newThreads, newCtxSize);
        }
    }

    /** Called by ModelManager after an install or when the user taps Load. */
    public void reloadLlamaService(String modelPath, String projectorPath, String modelName) {
        if (serviceBound && llamaService != null) {
            llamaService.reloadWithModel(modelPath, projectorPath, modelName);
            return;
        }
        if (serviceStarting) {
            Log.i(TAG, "Inference service is already starting");
            return;
        }

        Intent intent = new Intent(this, LlamaService.class);
        intent.putExtra("model_path", modelPath);
        intent.putExtra("projector_path", projectorPath);
        intent.putExtra("model_name", modelName);
        intent.putExtra("port", LLAMA_PORT);
        intent.putExtra("api_token", apiToken);
        intent.putExtra("threads", getThreadsPref());
        intent.putExtra("ctx_size", getCtxSizePref());
        serviceStarting = true;
        serviceStartError = null;
        try {
            startForegroundService(intent);
            if (!bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)) {
                serviceStarting = false;
                serviceStartError = "Could not bind to the inference service";
                stopService(intent);
            }
        } catch (RuntimeException error) {
            serviceStarting = false;
            serviceStartError = "Could not start inference: "
                    + (error.getMessage() == null
                    ? error.getClass().getSimpleName() : error.getMessage());
            Log.e(TAG, serviceStartError, error);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(0x00000000);
        getWindow().setNavigationBarColor(0x00000000);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        WindowInsetsControllerCompat controller =
                new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        controller.setAppearanceLightStatusBars(true);
        controller.setAppearanceLightNavigationBars(true);

        webView = new WebView(this);
        setContentView(webView);
        setupWebView();
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleAppBack();
            }
        });

        View root = findViewById(android.R.id.content);
        root.setBackgroundColor(0xFFF8F9F5);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                            | WindowInsetsCompat.Type.displayCutout());
            Insets ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime());
            int bottom = Math.max(bars.bottom, ime.bottom);
            view.setPadding(bars.left, bars.top, bars.right, bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }

        // Start a previously installed model immediately. On a fresh install,
        // ModelManager extracts the included Bonsai 2 2B model in the background and
        // starts it as soon as checksum verification completes.
        startDownloadedModelIfAvailable();
    }

    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);

        final WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .setHttpAllowed(true)
                .setDomain("127.0.0.1")
                .addPathHandler("/assets/",
                        new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        webView.setBackgroundColor(0xFFF8F9F5);
        webView.setClipToPadding(false);
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view,
                                             ValueCallback<Uri[]> filePathCallback,
                                             FileChooserParams fileChooserParams) {
                if (pendingFileChooser != null) {
                    pendingFileChooser.onReceiveValue(null);
                }
                pendingFileChooser = filePathCallback;
                imagePicker.launch("image/*");
                return true;
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(
                    WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(
                    WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("http".equalsIgnoreCase(uri.getScheme())
                        && "127.0.0.1".equals(uri.getHost()) && uri.getPort() == LLAMA_PORT
                        && uri.getPath() != null && uri.getPath().startsWith("/assets/")) {
                    return false;
                }
                if ("http".equalsIgnoreCase(uri.getScheme())
                        || "https".equalsIgnoreCase(uri.getScheme())) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, uri));
                    } catch (RuntimeException error) {
                        Log.w(TAG, "No app could open link: " + uri, error);
                    }
                }
                return true;
            }
        });

        modelManager = new ModelManager(this);
        webView.addJavascriptInterface(modelManager, "BonsaiNative");
        // UI assets are intercepted locally at the engine's exact origin.
        // Same-origin streaming avoids mixed content, private-network
        // preflights and cross-origin Authorization-header restrictions.
        webView.loadUrl("http://127.0.0.1:" + LLAMA_PORT + "/assets/index.html");
    }

    private void startDownloadedModelIfAvailable() {
        File model = modelManager.getActiveModelFile();
        File projector = modelManager.getActiveProjectorFile();
        if (model == null) {
            Log.i(TAG, "Active Bonsai model is not installed yet");
            return;
        }
        reloadLlamaService(model.getAbsolutePath(),
                projector == null ? null : projector.getAbsolutePath(),
                modelManager.getActiveModelName());
    }

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            LlamaService.LocalBinder localBinder = (LlamaService.LocalBinder) binder;
            llamaService = localBinder.getService();
            serviceBound = true;
            serviceStarting = false;
            serviceStartError = null;
            Log.i(TAG, "Bound to LlamaService");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
            serviceStarting = false;
            llamaService = null;
        }
    };

    private void handleAppBack() {
        if (webView == null) {
            finish();
            return;
        }
        webView.evaluateJavascript(
                "typeof handleAppBack === 'function' && handleAppBack()",
                handled -> {
                    if ("true".equals(handled)) return;
                    if (webView.canGoBack()) webView.goBack();
                    else finish();
                });
    }

    @Override
    protected void onDestroy() {
        if (pendingFileChooser != null) {
            pendingFileChooser.onReceiveValue(null);
            pendingFileChooser = null;
        }
        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
        }
        serviceStarting = false;
        stopService(new Intent(this, LlamaService.class));
        if (webView != null) {
            webView.removeJavascriptInterface("BonsaiNative");
            webView.destroy();
            webView = null;
        }
        if (modelManager != null) modelManager.close();
        super.onDestroy();
    }
}
