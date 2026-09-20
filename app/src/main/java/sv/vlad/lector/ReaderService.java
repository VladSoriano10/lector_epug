package sv.vlad.lector;

import android.app.*;
import android.content.*;
import android.media.*;
import android.media.session.*;
import android.net.Uri;
import android.os.*;
import android.speech.tts.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

public class ReaderService extends Service {
    public final class LocalBinder extends Binder { ReaderService get() { return ReaderService.this; } }
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final LocalBinder binder = new LocalBinder();
    private TextToSpeech tts;
    private SharedPreferences prefs;
    private AudioManager audio;
    private AudioFocusRequest focus;
    private MediaSession session;
    private PowerManager.WakeLock wake;
    private boolean ready, foreground, destroyed;
    private int generation;
    private String utterance = "", fileId = "";
    private int utteranceStart;
    private final SpeechResumeState speechState = new SpeechResumeState();
    public EpubReader.Book book;
    public int chapter, chunk;
    public int loadVersion, speechOffset;
    public String locator = "";
    public int locatorOffset;
    public boolean playing, busy;
    public String status = "Importa un EPUB para comenzar";
    public Runnable listener;

    @Override public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("reader", MODE_PRIVATE);
        audio = getSystemService(AudioManager.class);
        AudioAttributes attrs = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener(change -> { if (change < 0) pause(); }, main).build();
        wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lector:voz");
        getSystemService(NotificationManager.class).createNotificationChannel(
            new NotificationChannel("reading", "Lectura en voz alta", NotificationManager.IMPORTANCE_LOW));
        session = new MediaSession(this, "VladER");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { play(); }
            @Override public void onPause() { pause(); }
            @Override public void onStop() { pause(); stopSelf(); }
            @Override public void onSkipToNext() { moveChapter(1); }
            @Override public void onSkipToPrevious() { moveChapter(-1); }
        });
        session.setActive(true);
        IntentFilter audioFilter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisy, audioFilter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(noisy, audioFilter);
        initEngine(prefs.getString("engine", ""));
    }
    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { pause(); }
    };
    @Override public IBinder onBind(Intent intent) { return binder; }
    public void changed() {
        if (listener != null) listener.run();
        session.setPlaybackState(new PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY |
            PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP |
            PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
            .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                PlaybackState.PLAYBACK_POSITION_UNKNOWN, playing ? rate() : 0).build());
        if (book != null) session.setMetadata(new MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, book.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, book.chapters.get(chapter).title).build());
        if (foreground) getSystemService(NotificationManager.class).notify(1, notification());
    }
    public void initEngine(String name) {
        pause(); ready = false; generation++;
        int current = generation;
        if (tts != null) tts.shutdown();
        prefs.edit().putString("engine", name).apply();
        status = "Preparando voces…"; changed();
        tts = new TextToSpeech(this, result -> main.post(() -> {
            if (destroyed || generation != current) return;
            ready = result == TextToSpeech.SUCCESS;
            if (ready) {
                tts.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    public void onStart(String id) { }
                    @Override public void onRangeStart(String id,int start,int end,int frame) { main.post(() -> {
                        if(!playing || !id.equals(utterance) || book==null)return;
                        speechOffset=utteranceStart+start;
                        locator="chunk:"+chunk;locatorOffset=speechOffset;
                        save();changed();
                    }); }
                    public void onDone(String id) { main.post(() -> {
                        if (!playing || !id.equals(utterance)) return;
                        chunk++;
                        if (chunk >= book.chapters.get(chapter).chunks.size()) { chunk = 0; chapter++; }
                        if (chapter >= book.chapters.size()) {
                            chapter = book.chapters.size() - 1;
                            chunk = Math.max(0, book.chapters.get(chapter).chunks.size() - 1);
                            pause(); speechState.clear(); status = "Libro terminado"; changed(); return;
                        }
                        speechOffset = 0; save(); speak();
                    }); }
                    public void onError(String id) { main.post(() -> {
                        if (!id.equals(utterance)) return;
                        pause(); status = "No se pudo leer. Revisa que la voz esté descargada."; changed();
                    }); }
                });
                List<Voice> voices = voices();
                String saved = prefs.getString("voice:" + name, "");
                Voice chosen = null;
                for (Voice v : voices) if (v.getName().equals(saved)) chosen = v;
                if (chosen == null && !voices.isEmpty()) chosen = voices.get(0);
                if (chosen != null) tts.setVoice(chosen);
                status = voices.isEmpty() ? "Instala o descarga una voz local en español desde Voces." : "Voz lista";
            } else status = "No se pudo iniciar el motor de voz. Selecciona otro desde Voces.";
            changed();
        }), name.isEmpty() ? null : name);
    }
    public List<TextToSpeech.EngineInfo> engines() {
        return tts == null ? Collections.emptyList() : tts.getEngines();
    }
    public List<Voice> voices() {
        List<Voice> result = new ArrayList<>();
        if (ready && tts.getVoices() != null) for (Voice v : tts.getVoices())
            if ("es".equals(v.getLocale().getLanguage()) && !v.isNetworkConnectionRequired()) result.add(v);
        result.sort(Comparator.comparing((Voice v) -> "ES".equals(v.getLocale().getCountry()))
            .thenComparing(v -> v.getLocale().toLanguageTag()).thenComparing(Voice::getName));
        return result;
    }
    public void selectVoice(Voice voice) {
        pause();
        if (tts.setVoice(voice) == TextToSpeech.SUCCESS) {
            prefs.edit().putString("voice:" + prefs.getString("engine", ""), voice.getName()).apply();
            status = "Voz: " + voice.getLocale().getDisplayName(new Locale("es"));
        } else status = "La voz no está disponible; descárgala en su motor.";
        changed();
    }
    public float rate() { return prefs.getFloat("rate", 1f); }
    public void setRate(float value) { prefs.edit().putFloat("rate", value).apply(); if (playing) { pause(); play(); } changed(); }
    public void importBook(Uri uri) {
        if (busy) return;
        pause(); busy = true; status = "Importando libro…"; changed();
        io.execute(() -> {
            File temporary = null;
            try {
                temporary = File.createTempFile("import-", ".epub", getCacheDir());
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (InputStream in = getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(temporary)) {
                    if (in == null) throw new IOException("No se pudo abrir el archivo.");
                    byte[] buffer = new byte[8192]; int n; long total = 0;
                    while ((n = in.read(buffer)) != -1) {
                        total += n;
                        if (total > 100L * 1024 * 1024) throw new IOException("El EPUB supera 100 MB.");
                        digest.update(buffer, 0, n); out.write(buffer, 0, n);
                    }
                }
                EpubReader.Book parsed = EpubReader.open(temporary);
                StringBuilder hash = new StringBuilder();
                for (byte b : digest.digest()) hash.append(String.format(Locale.ROOT, "%02x", b));
                String id = hash + ".epub";
                File destination = new File(getFilesDir(), id);
                if (!destination.exists() && !temporary.renameTo(destination)) throw new IOException("No se pudo guardar el EPUB.");
                prefs.edit().putString("title:" + id, parsed.title).putString("author:" + id, parsed.author)
                    .putString("cover:" + id, parsed.cover).putInt(id + ":chapters", parsed.chapters.size()).apply();
                main.post(() -> loaded(parsed, id));
            } catch (Exception e) { fail(e); }
            finally { if (temporary != null) temporary.delete(); }
        });
    }
    public void openSaved(String id) {
        if (busy) return;
        if (!id.matches("[a-f0-9]{64}\\.epub")) return;
        pause(); busy = true; status = "Abriendo libro…"; changed();
        io.execute(() -> {
            try { EpubReader.Book parsed = EpubReader.open(new File(getFilesDir(), id)); main.post(() -> loaded(parsed, id)); }
            catch (Exception e) { fail(e); }
        });
    }
    private void loaded(EpubReader.Book parsed, String id) {
        if (destroyed) return;
        book = parsed; fileId = id; busy = false; speechState.clear();
        chapter = Math.max(0, Math.min(prefs.getInt(id + ":chapter", 0), book.chapters.size() - 1));
        chunk = Math.max(0, Math.min(prefs.getInt(id + ":chunk", 0), book.chapters.get(chapter).chunks.size() - 1));
        locator = prefs.getString(id + ":locator", "chunk:" + chunk);
        locatorOffset = prefs.getInt(id + ":offset", 0); speechOffset = prefs.getInt(id + ":speechOffset", 0);
        prefs.edit().putString("last", id).putString("author:" + id, parsed.author).putString("cover:" + id, parsed.cover)
            .putInt(id + ":chapters", parsed.chapters.size()).putLong(id + ":opened", System.currentTimeMillis()).apply();
        loadVersion++; status = "Libro listo"; changed();
    }
    private void fail(Exception e) { main.post(() -> { if (!destroyed) { busy = false; status = "Error: " + e.getMessage(); changed(); } }); }
    public Map<String, String> library() {
        Map<String, String> books = new TreeMap<>();
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet())
            if (e.getKey().startsWith("title:")) books.put(e.getKey().substring(6), e.getValue().toString());
        return books;
    }
    private void save() {
        if (book != null) prefs.edit().putInt(fileId + ":chapter", chapter).putInt(fileId + ":chunk", chunk)
            .putString(fileId + ":locator", locator).putInt(fileId + ":offset", locatorOffset)
            .putInt(fileId + ":speechOffset", speechOffset).apply();
    }
    public String currentId() { return fileId; }
    public File currentFile() { return new File(getFilesDir(), fileId); }
    public void visualPosition(String loc, int offset, int firstChunk, int page, int pages) {
        if (book == null || busy) return;
        prefs.edit().putInt(fileId + ":page", page).putInt(fileId + ":pages", pages).apply();
        if (speechState.protectsPosition(playing)) return;
        locator = loc; locatorOffset = Math.max(0, offset);
        chunk = Math.max(0, Math.min(firstChunk, book.chapters.get(chapter).chunks.size() - 1));
        speechOffset = locatorOffset; save();
    }
    public void play() {
        if (busy || book == null || playing) return;
        if (!ready || voices().isEmpty() || tts.getVoice() == null || tts.getVoice().isNetworkConnectionRequired()
                || !"es".equals(tts.getVoice().getLocale().getLanguage())) {
            status = "Selecciona una voz local en español desde Voces."; changed(); return;
        }
        startForegroundService(new Intent(this, ReaderService.class).setAction("PLAY"));
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "" : intent.getAction();
        if ("PLAY".equals(action)) {
            foreground = true; startForeground(1, notification());
            if (!ready || book == null || busy) { pause(); return START_NOT_STICKY; }
            if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                pause(); status = "El audio está ocupado. Intenta de nuevo."; changed(); return START_NOT_STICKY;
            }
            speechState.clear(); playing = true; speak();
        } else if ("PAUSE".equals(action)) pause();
        else if ("NEXT".equals(action)) moveChapter(1);
        else if ("PREVIOUS".equals(action)) moveChapter(-1);
        return START_NOT_STICKY;
    }
    private void speak() {
        if (!playing || book == null) return;
        while (book.chapters.get(chapter).chunks.isEmpty()) {
            if (chapter + 1 >= book.chapters.size()) { pause(); speechState.clear(); status = "Libro terminado"; changed(); return; }
            chapter++; chunk = 0; speechOffset = 0;
        }
        locator = "chunk:" + chunk; locatorOffset = speechOffset; save();
        if (wake.isHeld()) wake.release();
        wake.acquire(10 * 60 * 1000L);
        utterance = UUID.randomUUID().toString();
        status = "Leyendo · fragmento " + (chunk + 1) + " de " + book.chapters.get(chapter).chunks.size();
        tts.setSpeechRate(rate());
        String phrase = book.chapters.get(chapter).chunks.get(chunk);
        int start = Math.max(0, Math.min(speechOffset, phrase.length() - 1));
        utteranceStart=start;
        if (tts.speak(phrase.substring(start), TextToSpeech.QUEUE_FLUSH, null, utterance) == TextToSpeech.ERROR) {
            pause(); status = "El motor no pudo reproducir esta voz.";
        }
        changed();
    }
    public void pause() {
        speechState.paused(playing);
        playing = false; utterance = "";
        if (tts != null) tts.stop();
        if (wake != null && wake.isHeld()) wake.release();
        if (audio != null && focus != null) audio.abandonAudioFocusRequest(focus);
        save(); status = "En pausa";
        if (foreground) { foreground = false; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); }
        if (session != null) changed();
    }
    public void moveChapter(int delta) {
        if (book != null) goChapter(Math.max(0, Math.min(chapter + delta, book.chapters.size() - 1)));
    }
    public boolean canResumeSpeech() { return speechState.canResume(); }
    public void manualNavigation() { pause(); speechState.clear(); }
    public void goChapter(int index) {
        if (book == null || busy) return;
        boolean resume = playing; manualNavigation(); chapter = Math.max(0, Math.min(index, book.chapters.size() - 1));
        chunk = 0; speechOffset = 0; locator = ""; locatorOffset = 0; save(); changed(); if (resume) play();
    }
    public void moveChunk(int delta) {
        if (book == null || busy) return;
        boolean resume = playing; manualNavigation();
        chunk = Math.max(0, Math.min(chunk + delta, book.chapters.get(chapter).chunks.size() - 1));
        speechOffset = 0; locator = "chunk:" + chunk; locatorOffset = 0;
        save(); changed(); if (resume) play();
    }
    private PendingIntent command(String action) {
        return PendingIntent.getService(this, action.hashCode(), new Intent(this, ReaderService.class).setAction(action), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class)
            .setAction("sv.vlad.lector.RESUME").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "reading").setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(book == null ? "VladER" : book.title)
            .setContentText(book == null ? "Preparando lectura" : book.chapters.get(chapter).title)
            .setContentIntent(open).setOngoing(playing).setOnlyAlertOnce(true)
            .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_previous, "Anterior", command("PREVIOUS")).build())
            .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause, "Pausar", command("PAUSE")).build())
            .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_next, "Siguiente", command("NEXT")).build())
            .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0, 1, 2)).build();
    }
    @Override public void onDestroy() {
        destroyed = true; listener = null; pause();
        if (tts != null) tts.shutdown();
        session.release(); unregisterReceiver(noisy); io.shutdownNow(); super.onDestroy();
    }
}
