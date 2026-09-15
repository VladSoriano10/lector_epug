package sv.vlad.lector;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.*;
import android.speech.tts.*;
import android.view.*;
import android.widget.*;
import java.util.*;

public class MainActivity extends Activity {
    private ReaderService reader;
    private boolean bound;
    private LinearLayout root;
    private TextView title, subtitle, status, text;
    private Button play;
    private ScrollView scroll;
    private String shown = "";
    private final ServiceConnection connection = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder binder) {
            reader = ((ReaderService.LocalBinder) binder).get(); reader.listener = MainActivity.this::render; render();
        }
        public void onServiceDisconnected(ComponentName name) { reader = null; }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(12)); root.setBackgroundColor(Color.rgb(248, 245, 238));
        setContentView(root);
        title = label("Lector EPUB", 26); title.setTypeface(null, android.graphics.Typeface.BOLD);
        subtitle = label("Tu biblioteca, a tu ritmo", 15);
        LinearLayout top = row();
        button(top, "Importar", () -> {
            if (reader == null || reader.busy) return;
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, 10);
        });
        button(top, "Biblioteca", this::library);
        button(top, "Voces", this::voiceMenu);
        status = label("Preparando biblioteca…", 14);
        scroll = new ScrollView(this);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        text = new TextView(this); text.setTextSize(19); text.setTextColor(Color.rgb(44, 47, 45));
        text.setLineSpacing(dp(5), 1.1f); text.setTextIsSelectable(true); text.setPadding(0, dp(16), 0, dp(16));
        text.setText("Importa un archivo .epub para leerlo y escucharlo.\n\nEn Voces puedes elegir un motor instalado y una voz local en español.\n\nTus libros y tu progreso se guardan en este teléfono.");
        scroll.addView(text);
        LinearLayout chapters = row();
        button(chapters, "Cap. anterior", () -> { if (reader != null) reader.moveChapter(-1); });
        button(chapters, "Capítulos", this::chapters);
        button(chapters, "Cap. siguiente", () -> { if (reader != null) reader.moveChapter(1); });
        LinearLayout controls = row();
        button(controls, "Retroceder", () -> { if (reader != null) reader.moveChunk(-1); });
        play = button(controls, "Escuchar", () -> {
            if (reader == null) return;
            if (reader.playing) reader.pause(); else reader.play();
        });
        button(controls, "Avanzar", () -> { if (reader != null) reader.moveChunk(1); });
        Button speed = new Button(this); speed.setText("Velocidad de lectura"); root.addView(speed);
        speed.setOnClickListener(v -> {
            if (reader == null) return;
            String[] labels = {"0.75×", "1× · Normal", "1.25×", "1.5×", "1.75×", "2×"};
            float[] rates = {.75f, 1f, 1.25f, 1.5f, 1.75f, 2f};
            new AlertDialog.Builder(this).setTitle("Velocidad").setItems(labels, (d, n) -> reader.setRate(rates[n])).show();
        });
        bound = bindService(new Intent(this, ReaderService.class), connection, BIND_AUTO_CREATE);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 11);
    }
    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density); }
    private TextView label(String content, int size) {
        TextView view = new TextView(this); view.setText(content); view.setTextSize(size);
        view.setTextColor(Color.rgb(34, 64, 57)); view.setPadding(0, dp(4), 0, dp(4)); root.addView(view); return view;
    }
    private LinearLayout row() { LinearLayout row = new LinearLayout(this); root.addView(row); return row; }
    private Button button(LinearLayout row, String name, Runnable action) {
        Button b = new Button(this); b.setText(name); b.setTextSize(12); b.setAllCaps(false);
        row.addView(b, new LinearLayout.LayoutParams(0, -2, 1)); b.setOnClickListener(v -> action.run()); return b;
    }
    private void render() {
        if (reader == null) return;
        status.setText(reader.status); play.setText(reader.playing ? "Pausar" : "Escuchar");
        play.setEnabled(reader.book != null && !reader.busy);
        if (reader.book == null) return;
        title.setText(reader.book.title);
        EpubReader.Chapter chapter = reader.book.chapters.get(reader.chapter);
        subtitle.setText((reader.chapter + 1) + " / " + reader.book.chapters.size() + " · " + chapter.title);
        String current = reader.book.hashCode() + ":" + reader.chapter + ":" + reader.chunk;
        if (!current.equals(shown)) {
            shown = current;
            // Display the current speech fragment plus nearby context to keep long books responsive.
            android.text.SpannableStringBuilder display = new android.text.SpannableStringBuilder();
            int first = Math.max(0, reader.chunk - 1), last = Math.min(chapter.chunks.size(), reader.chunk + 5);
            for (int n = first; n < last; n++) {
                int begin = display.length(); display.append(chapter.chunks.get(n));
                if (n == reader.chunk) display.setSpan(new android.text.style.BackgroundColorSpan(0xFFDDEADF), begin, display.length(), 0);
                display.append("\n\n");
            }
            text.setText(display); scroll.scrollTo(0, 0);
        }
    }
    private void library() {
        if (reader == null || reader.busy) return;
        Map<String, String> books = reader.library();
        if (books.isEmpty()) { message("Biblioteca", "Aún no has importado libros."); return; }
        List<String> ids = new ArrayList<>(books.keySet());
        String[] names = ids.stream().map(books::get).toArray(String[]::new);
        new AlertDialog.Builder(this).setTitle("Tus libros").setItems(names, (d, n) -> reader.openSaved(ids.get(n))).show();
    }
    private void chapters() {
        if (reader == null || reader.book == null || reader.busy) return;
        String[] names = reader.book.chapters.stream().map(c -> c.title).toArray(String[]::new);
        new AlertDialog.Builder(this).setTitle("Capítulos").setSingleChoiceItems(names, reader.chapter, (d, n) -> {
            reader.goChapter(n); d.dismiss();
        }).show();
    }
    private void voiceMenu() {
        if (reader == null) return;
        new AlertDialog.Builder(this).setTitle("Voces en español").setItems(new String[]{
            "Elegir motor instalado", "Elegir voz sin conexión", "Ajustes de voz de Android", "Acerca de las voces abiertas"
        }, (d, n) -> {
            if (n == 0) {
                List<TextToSpeech.EngineInfo> engines = reader.engines();
                if (engines.isEmpty()) { message("Motores", "No se encontró un motor TTS instalado."); return; }
                new AlertDialog.Builder(this).setTitle("Motor de voz").setItems(engines.stream().map(e -> e.label).toArray(String[]::new),
                    (dialog, index) -> reader.initEngine(engines.get(index).name)).show();
            } else if (n == 1) {
                List<Voice> voices = reader.voices();
                if (voices.isEmpty()) { message("Voces locales", "No hay voces locales en español disponibles. Descarga una voz dentro de tu motor y vuelve a seleccionarlo aquí."); return; }
                String[] names = voices.stream().map(v -> v.getLocale().getDisplayName(new Locale("es")) + "\n" + v.getName()).toArray(String[]::new);
                new AlertDialog.Builder(this).setTitle("Español · voces locales").setItems(names, (dialog, index) -> reader.selectVoice(voices.get(index))).show();
            } else if (n == 2) {
                try { startActivity(new Intent("com.android.settings.TTS_SETTINGS")); }
                catch (ActivityNotFoundException e) { startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS)); }
            } else message("Voces abiertas", "Puedes instalar un motor TTS compatible, por ejemplo RHVoice, y descargar voces desde su aplicación. Las licencias dependen de cada voz: gratuita no significa necesariamente abierta.\n\nEsta versión muestra las voces en español que el motor declara locales. No incluye modelos Piper ni descarga voces por sí sola.\n\nPrueba la lectura en modo avión después de descargar la voz.");
        }).show();
    }
    private void message(String title, String body) { new AlertDialog.Builder(this).setTitle(title).setMessage(body).setPositiveButton("Entendido", null).show(); }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 10 && result == RESULT_OK && data != null && data.getData() != null && reader != null) reader.importBook(data.getData());
    }
    @Override protected void onDestroy() {
        if (reader != null) reader.listener = null;
        if (bound) unbindService(connection);
        super.onDestroy();
    }
}
