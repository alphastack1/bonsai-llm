package com.bonsai.llm;

import android.app.*;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Serializes model loads and owns the release-pinned, offline ARM inference process. */
public final class LlamaService extends Service {
    private static final String CHANNEL = "bonsai_inference";
    private final IBinder binder = new LocalBinder();
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final AtomicInteger generation = new AtomicInteger();
    private final Object lock = new Object();
    private volatile Process process;
    private volatile String modelPath, projectorPath, modelName = "Bonsai 2", token;
    private volatile String state = "stopped", error;
    private volatile String engineOutput = "";
    private volatile int threads = 0, context = 16384, port = 8080;
    private volatile boolean closed;
    public final class LocalBinder extends Binder { LlamaService getService() { return LlamaService.this; } }
    @Override public IBinder onBind(Intent intent) { return binder; }
    @Override public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "Local AI", NotificationManager.IMPORTANCE_LOW));
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        modelPath = intent.getStringExtra("model_path"); projectorPath = intent.getStringExtra("projector_path");
        modelName = intent.getStringExtra("model_name"); token = intent.getStringExtra("api_token");
        port = intent.getIntExtra("port", 8080); threads = intent.getIntExtra("threads", 0);
        context = intent.getIntExtra("ctx_size", 16384);
        startForeground(1, notification("Starting local AI…")); enqueue(); return START_NOT_STICKY;
    }
    public void reloadWithModel(String model, String projector, String name) { reloadWithModel(model, projector, name, threads, context); }
    public void reloadWithModel(String model, String projector, String name, int count, int ctx) {
        if (model != null) {modelPath = model; projectorPath = projector;}
        if (name != null) modelName = name;
        threads = count; context = ctx; enqueue();
    }
    private void enqueue() {
        int ticket = generation.incrementAndGet();
        String model = modelPath, projector = projectorPath, name = modelName;
        int count = threads, ctx = context;
        state = "loading"; error = null;
        engineOutput = "";
        loader.execute(() -> {
            stopProcess();
            if (closed || ticket != generation.get()) return;
            try {
                if (model == null || !new File(model).isFile()) throw new IOException("Model file is missing");
                String libs = getApplicationInfo().nativeLibraryDir;
                int n = count > 0 ? count : Math.min(6, Math.max(2, Runtime.getRuntime().availableProcessors() - 2));
                List<String> command = new ArrayList<>(Arrays.asList(libs + "/libllama_server.so", "-m", model,
                        "--host", "127.0.0.1", "--port", String.valueOf(port), "--api-key", token,
                        "--alias", "bonsai", "--ctx-size", String.valueOf(ctx), "--threads", String.valueOf(n),
                        "--threads-batch", String.valueOf(n), "--parallel", "1", "--n-gpu-layers", "0", "--jinja",
                        "--flash-attn", "on", "--cache-type-k", "q8_0", "--cache-type-v", "q8_0",
                        "--reasoning-format", "deepseek", "--no-webui", "--offline"));
                if (projector != null) {command.add("--mmproj"); command.add(projector);}
                ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
                builder.environment().put("LD_LIBRARY_PATH", libs);
                builder.environment().put("GGML_BACKEND_PATH", libs);
                Process started = builder.start();
                synchronized (lock) {process = started;}
                new Thread(() -> logOutput(started, ticket), "bonsai-engine-log").start();
                notifyState("Loading " + name + " locally…");
                for (int i=0; i<300; i++) {
                    if (closed || ticket != generation.get()) return;
                    if (!started.isAlive()) throw new IOException("The local engine exited (" + started.exitValue() + "). " + engineOutput);
                    try {
                        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/health").openConnection();
                        connection.setConnectTimeout(1000); connection.setReadTimeout(1000);
                        connection.setRequestProperty("Authorization", "Bearer " + token);
                        try {if(connection.getResponseCode()==200) {state="ready";notifyState(name + " · offline and ready");return;}}
                        finally {connection.disconnect();}
                    } catch (IOException ignored) {}
                    Thread.sleep(1000);
                }
                throw new IOException("Model loading timed out. Try a smaller model or context.");
            } catch (Exception failure) {
                if (!closed && ticket == generation.get()) {stopProcess();state="error";error=failure.getMessage();notifyState("Could not load local AI");Log.e("Bonsai", "Model load failed", failure);}
            }
        });
    }
    private void logOutput(Process started, int ticket) {
        try (BufferedReader input = new BufferedReader(new InputStreamReader(started.getInputStream()))) {
            String line; while ((line=input.readLine()) != null) {
                if (ticket == generation.get()) engineOutput = line.length() > 400 ? line.substring(line.length()-400) : line;
                Log.d("BonsaiEngine",line);
            }
        } catch (IOException ignored) {}
        if (!closed && ticket==generation.get() && "ready".equals(state)) {state="error";error="The local engine stopped. Load the model again.";}
    }
    private void stopProcess() {
        Process old;
        synchronized (lock) {old=process;process=null;}
        if(old==null)return;
        old.destroy();
        try {if(!old.waitFor(3,TimeUnit.SECONDS)){old.destroyForcibly();old.waitFor(2,TimeUnit.SECONDS);}}
        catch(InterruptedException ignored){old.destroyForcibly();Thread.currentThread().interrupt();}
    }
    public boolean isReady() {Process p=process;return "ready".equals(state)&&p!=null&&p.isAlive();}
    public String getState() {if("ready".equals(state)&&!isReady()){state="error";error="The local engine stopped. Load the model again.";}return state;}
    public String getLastError() {return error;}
    private Notification notification(String text) {
        PendingIntent open = PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this,CHANNEL).setSmallIcon(com.bonsai.llm.R.drawable.ic_launcher)
                .setContentTitle("Bonsai 2 · on this device").setContentText(text).setContentIntent(open).setOngoing(true).setSilent(true).build();
    }
    private void notifyState(String text) {getSystemService(NotificationManager.class).notify(1,notification(text));}
    @Override public void onDestroy() {closed=true;generation.incrementAndGet();loader.shutdownNow();stopProcess();state="stopped";stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();}
}
