package com.bonsai.llm;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Runs PrismML's llama-server as a foreground arm64 subprocess. */
public class LlamaService extends Service {
    private static final String TAG = "LlamaService";
    private static final String CHANNEL_ID = "bonsai_inference";
    private static final int NOTIFICATION_ID = 1;
    private static final int STARTUP_TIMEOUT_SECONDS = 600;

    private final IBinder binder = new LocalBinder();
    private final Object processLock = new Object();
    private volatile Process llamaProcess;
    private volatile String modelPath;
    private volatile String projectorPath;
    private volatile String modelName = "Bonsai";
    private volatile String state = "stopped";
    private volatile String lastError;
    private int port = 8080;
    private volatile int threads = 0;
    private volatile int ctxSize = 4096;
    private volatile boolean isReady = false;

    /**
     * Most Android flagships have a small number of fast cores plus efficiency
     * cores. Q1 decode is memory-bandwidth bound, so all-core execution usually
     * stalls on the slow cores rather than increasing token throughput.
     */
    private int autoThreadCount() {
        int cores = Runtime.getRuntime().availableProcessors();
        return Math.min(Math.max(4, cores - 2), 6);
    }

    public class LocalBinder extends Binder {
        LlamaService getService() {
            return LlamaService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            modelPath = intent.getStringExtra("model_path");
            projectorPath = intent.getStringExtra("projector_path");
            port = intent.getIntExtra("port", 8080);
            threads = intent.getIntExtra("threads", 0);
            ctxSize = intent.getIntExtra("ctx_size", 4096);
            String name = intent.getStringExtra("model_name");
            if (name != null) modelName = name;
        }

        startForeground(NOTIFICATION_ID, buildNotification("Starting..."));
        new Thread(this::startLlamaServer, "llama-server-start").start();
        return START_NOT_STICKY;
    }

    public void reloadWithModel(String newModelPath, String newProjectorPath,
                                String newModelName) {
        reloadWithModel(newModelPath, newProjectorPath, newModelName,
                this.threads, this.ctxSize);
    }

    public void reloadWithModel(String newModelPath, String newProjectorPath,
                                String newModelName, int newThreads, int newCtxSize) {
        Log.i(TAG, "Reloading model=" + newModelPath + " ctx=" + newCtxSize);
        isReady = false;
        state = "loading";
        lastError = null;
        updateNotification("Loading model...");
        stopLlamaProcess();

        if (newModelPath != null) {
            this.modelPath = newModelPath;
            // A text-only model deliberately clears a projector left over from
            // a previously loaded multimodal model.
            this.projectorPath = newProjectorPath;
        } else if (newProjectorPath != null) {
            this.projectorPath = newProjectorPath;
        }
        if (newModelName != null) this.modelName = newModelName;
        if (newThreads >= 0) this.threads = newThreads;
        if (newCtxSize > 0) this.ctxSize = newCtxSize;

        new Thread(this::startLlamaServer, "llama-server-reload").start();
    }

    private void startLlamaServer() {
        File model = modelPath == null ? null : new File(modelPath);
        File projector = projectorPath == null ? null : new File(projectorPath);
        if (model == null || !model.isFile()) {
            fail("Model file not found");
            return;
        }
        if (projector != null && !projector.isFile()) {
            fail("Vision projector not found");
            return;
        }

        String nativeLibDir = getApplicationInfo().nativeLibraryDir;
        String serverBinary = nativeLibDir + "/libllama_server.so";
        if (!new File(serverBinary).isFile()) {
            fail("Inference binary not found");
            return;
        }

        int effectiveThreads = threads > 0 ? threads : autoThreadCount();
        List<String> command = new ArrayList<>();
        command.add(serverBinary);
        command.add("-m");
        command.add(model.getAbsolutePath());
        if (projector != null) {
            command.add("--mmproj");
            command.add(projector.getAbsolutePath());
        }
        command.add("--host");
        command.add("127.0.0.1");
        command.add("--port");
        command.add(String.valueOf(port));
        command.add("-c");
        command.add(String.valueOf(ctxSize));
        command.add("-t");
        command.add(String.valueOf(effectiveThreads));
        command.add("--threads-batch");
        command.add(String.valueOf(effectiveThreads));
        command.add("--no-webui");
        command.add("--flash-attn");
        command.add("on");
        command.add("--cache-type-k");
        command.add("q4_0");
        command.add("--cache-type-v");
        command.add("q4_0");

        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            builder.environment().put("LD_LIBRARY_PATH", nativeLibDir);
            Log.i(TAG, "Starting llama-server with " + effectiveThreads
                    + " threads and " + ctxSize + " context tokens");

            Process process = builder.start();
            synchronized (processLock) {
                llamaProcess = process;
            }
            state = "loading";
            lastError = null;

            new Thread(() -> logProcessOutput(process), "llama-server-log").start();
            waitForReady(process);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start llama-server", e);
            fail("Could not start inference: " + safeMessage(e));
        }
    }

    private void logProcessOutput(Process process) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Log.d(TAG, "llama: " + line);
            }
        } catch (Exception e) {
            Log.w(TAG, "llama-server output reader ended", e);
        }
    }

    private void waitForReady(Process process) {
        updateNotification("Loading " + modelName + "...");
        for (int second = 0; second < STARTUP_TIMEOUT_SECONDS; second++) {
            try {
                Thread.sleep(1000);
                if (!isCurrentProcess(process)) return;
                if (!process.isAlive()) {
                    fail("Inference server exited with code " + process.exitValue());
                    return;
                }

                HttpURLConnection connection = (HttpURLConnection)
                        new URL("http://127.0.0.1:" + port + "/health").openConnection();
                connection.setConnectTimeout(1000);
                connection.setReadTimeout(1000);
                try {
                    if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
                        synchronized (processLock) {
                            if (llamaProcess != process) return;
                        }
                        isReady = true;
                        state = "ready";
                        updateNotification("Ready - " + modelName);
                        Log.i(TAG, "llama-server is ready on port " + port);
                        return;
                    }
                } finally {
                    connection.disconnect();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ignored) {
                // Loading or health endpoint not listening yet.
            }
        }
        if (isCurrentProcess(process)) {
            stopLlamaProcess();
            fail("Model loading timed out after 10 minutes");
        }
    }

    private boolean isCurrentProcess(Process process) {
        synchronized (processLock) {
            return llamaProcess == process;
        }
    }

    private void stopLlamaProcess() {
        Process process;
        synchronized (processLock) {
            process = llamaProcess;
            llamaProcess = null;
        }
        if (process == null) return;
        process.destroy();
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private void fail(String message) {
        isReady = false;
        state = "error";
        lastError = message;
        Log.e(TAG, message);
        updateNotification("Error: " + message);
    }

    private String safeMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    public boolean isReady() {
        return isReady;
    }

    public String getState() {
        return state;
    }

    public String getLastError() {
        return lastError;
    }

    public int getThreads() {
        return threads > 0 ? threads : autoThreadCount();
    }

    public int getCtxSize() {
        return ctxSize;
    }

    public int getAutoThreads() {
        return autoThreadCount();
    }

    public int getCpuCores() {
        return Runtime.getRuntime().availableProcessors();
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Shows when Bonsai inference is running");
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification(String text) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pending = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setContentIntent(pending)
                .setOngoing(true)
                .setSilent(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.notify(NOTIFICATION_ID, buildNotification(text));
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopLlamaProcess();
        isReady = false;
        state = "stopped";
    }
}
