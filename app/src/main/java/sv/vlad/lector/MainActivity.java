package sv.vlad.lector;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.speech.tts.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity implements PagedReaderView.Listener {
    private ReaderService reader;
    private SharedPreferences prefs;
    private boolean bound, reading, dark, controls, opening, navigating;
    private int expectedVersion, font, shownChapter=-1, lastSpoken=-1, pages=1, page;
    private String shownId="", nextLocator=null, restoredId="";
    private Uri pendingUri;
    private boolean resumeIntent;
    private FrameLayout root;
    private LinearLayout home, bookList, top, bottom;
    private TextView homeStatus, heading, subheading, pageLabel;
    private Button play;
    private SeekBar pageSlider;
    private PagedReaderView web;
    private final ExecutorService images=Executors.newSingleThreadExecutor();
    private int libraryGeneration;
    private final ServiceConnection connection=new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name,IBinder binder) {
            reader=((ReaderService.LocalBinder)binder).get(); reader.listener=MainActivity.this::render;
            refreshLibrary();
            if(pendingUri!=null)consumePending();
            else if(resumeIntent && reader.book!=null)showReader();
            else if(!restoredId.isEmpty())openBook(restoredId);
            render();
        }
        @Override public void onServiceDisconnected(ComponentName name) { reader=null; }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs=getSharedPreferences("reader",MODE_PRIVATE);dark=prefs.getBoolean("dark",true);font=prefs.getInt("font",20);
        setTheme(dark?android.R.style.Theme_Material_NoActionBar:android.R.style.Theme_Material_Light_NoActionBar);
        if(state!=null)restoredId=state.getString("readingId","");
        buildHome();receive(getIntent());
        bound=bindService(new Intent(this,ReaderService.class),connection,BIND_AUTO_CREATE);
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},11);
    }
    private int dp(int n) {return Math.round(n*getResources().getDisplayMetrics().density);}
    private int background() {return Color.parseColor(dark?"#071E25":"#F4F5F1");}
    private int foreground() {return Color.parseColor(dark?"#E3ECEE":"#19353D");}
    private int muted() {return Color.parseColor(dark?"#9CB4BB":"#597078");}
    private int teal() {return Color.rgb(0,91,112);}
    private TextView text(String value,int size,int color) {
        TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);return t;
    }
    private Button button(String label,Runnable task) {
        Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(13);b.setTextColor(Color.WHITE);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(teal()));b.setOnClickListener(v->task.run());return b;
    }
    private void rowButton(LinearLayout row,String label,Runnable task) {row.addView(button(label,task),new LinearLayout.LayoutParams(0,dp(48),1));}
    private LinearLayout column() {LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private void fullscreen(boolean value) {
        getWindow().setStatusBarColor(teal());getWindow().setNavigationBarColor(background());
        getWindow().getDecorView().setSystemUiVisibility(value ? View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY:0);
    }
    private void buildHome() {
        reading=false;libraryGeneration++;disposeWeb();fullscreen(false);
        root=new FrameLayout(this);root.setBackgroundColor(background());setContentView(root);
        home=column();root.addView(home,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);bar.setPadding(dp(16),dp(6),dp(12),dp(6));bar.setBackgroundColor(teal());home.addView(bar);
        TextView title=text("Mi biblioteca",24,Color.WHITE);title.setTypeface(null,Typeface.BOLD);
        bar.addView(title,new LinearLayout.LayoutParams(0,dp(56),1));title.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(button("＋",this::pickFile),new LinearLayout.LayoutParams(dp(52),dp(52)));
        bar.addView(button("☾",this::toggleTheme),new LinearLayout.LayoutParams(dp(52),dp(52)));
        TextView intro=text("Tus libros · a tu ritmo",14,muted());intro.setPadding(dp(20),dp(18),dp(20),dp(8));home.addView(intro);
        homeStatus=text("",13,muted());homeStatus.setPadding(dp(20),0,dp(20),dp(10));home.addView(homeStatus);
        ScrollView scroll=new ScrollView(this);home.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        bookList=column();bookList.setPadding(dp(12),0,dp(12),dp(12));scroll.addView(bookList);
        LinearLayout actions=new LinearLayout(this);actions.setPadding(dp(12),dp(4),dp(12),dp(8));home.addView(actions);
        rowButton(actions,"Importar EPUB",this::pickFile);rowButton(actions,"Voces",this::voiceMenu);
        refreshLibrary();
    }
    private void refreshLibrary() {
        if(reading || bookList==null)return;
        int token=++libraryGeneration;bookList.removeAllViews();
        Map<String,String> books=reader==null?new HashMap<>():reader.library();
        if(books.isEmpty()) {
            TextView empty=text("Tu próxima lectura empieza aquí\n\nImporta un EPUB o ábrelo desde WhatsApp o tu gestor de archivos. Quedará guardado en esta biblioteca.",20,foreground());
            empty.setPadding(dp(24),dp(52),dp(24),dp(24));empty.setLineSpacing(dp(6),1.1f);bookList.addView(empty);return;
        }
        List<String> ids=new ArrayList<>(books.keySet());ids.sort((a,b)->Long.compare(prefs.getLong(b+":opened",0),prefs.getLong(a+":opened",0)));
        for(String id:ids) {
            LinearLayout card=new LinearLayout(this);card.setPadding(dp(14),dp(14),dp(14),dp(14));card.setGravity(Gravity.CENTER_VERTICAL);
            GradientDrawable shape=new GradientDrawable();shape.setColor(Color.parseColor(dark?"#1A333B":"#FFFFFF"));shape.setCornerRadius(dp(12));
            card.setBackground(shape);LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.bottomMargin=dp(12);bookList.addView(card,cp);
            FrameLayout cover=new FrameLayout(this);card.addView(cover,new LinearLayout.LayoutParams(dp(88),dp(132)));
            TextView placeholder=text("EPUB\n\n"+books.get(id).substring(0,Math.min(1,books.get(id).length())),22,Color.WHITE);
            placeholder.setGravity(Gravity.CENTER);placeholder.setBackgroundColor(teal());cover.addView(placeholder,new FrameLayout.LayoutParams(-1,-1));
            ImageView image=new ImageView(this);image.setScaleType(ImageView.ScaleType.CENTER_CROP);cover.addView(image,new FrameLayout.LayoutParams(-1,-1));
            images.execute(()->{
                try {
                    String coverPath=prefs.getString("cover:"+id,"");
                    if(prefs.getInt("coverVersion:"+id,0)<3) {
                        coverPath=EpubReader.findCover(new File(getFilesDir(),id));
                        prefs.edit().putString("cover:"+id,coverPath).putInt("coverVersion:"+id,3).apply();
                    }
                    if(coverPath.isEmpty())return;
                    byte[] bytes=EpubReader.asset(new File(getFilesDir(),id),coverPath);
                    BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
                    options.inSampleSize=1;while(options.outWidth/options.inSampleSize>360 || options.outHeight/options.inSampleSize>480)options.inSampleSize*=2;
                    options.inJustDecodeBounds=false;Bitmap bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
                    runOnUiThread(()->{if(!isDestroyed() && token==libraryGeneration && bitmap!=null)image.setImageBitmap(bitmap);});
                }catch(Exception ignored) { }
            });
            LinearLayout details=column();details.setPadding(dp(16),0,0,0);card.addView(details,new LinearLayout.LayoutParams(0,-2,1));
            TextView name=text(books.get(id),19,foreground());name.setMaxLines(3);name.setEllipsize(android.text.TextUtils.TruncateAt.END);details.addView(name);
            TextView author=text(prefs.getString("author:"+id,""),13,muted());author.setPadding(0,dp(6),0,dp(10));details.addView(author);
            int section=prefs.getInt(id+":chapter",0),sections=prefs.getInt(id+":chapters",1),localPage=prefs.getInt(id+":page",0),total=prefs.getInt(id+":pages",1);
            ProgressBar progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(1000);
            progress.setProgress((int)(1000.0*(section+(double)localPage/Math.max(1,total))/Math.max(1,sections)));details.addView(progress,new LinearLayout.LayoutParams(-1,dp(6)));
            TextView location=text(prefs.contains(id+":chapter")?"Sección "+(section+1)+" · Continuar leyendo":"Sin empezar · Abrir libro",13,muted());location.setPadding(0,dp(12),0,0);details.addView(location);
            card.setOnClickListener(v->openBook(id));card.setContentDescription("Abrir "+books.get(id));
        }
    }
    private void pickFile() {
        if(reader==null || reader.busy)return;
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),10);
    }
    private void receive(Intent intent) {
        if(intent==null)return;
        String action=intent.getAction();
        if(Intent.ACTION_VIEW.equals(action))pendingUri=intent.getData();
        else if(Intent.ACTION_SEND.equals(action)) {
            if(Build.VERSION.SDK_INT>=33)pendingUri=intent.getParcelableExtra(Intent.EXTRA_STREAM,Uri.class);
            else pendingUri=intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if(pendingUri==null && intent.getClipData()!=null && intent.getClipData().getItemCount()>0)pendingUri=intent.getClipData().getItemAt(0).getUri();
        }else if("sv.vlad.lector.RESUME".equals(action))resumeIntent=true;
        if(pendingUri!=null && !"content".equals(pendingUri.getScheme()) && !"file".equals(pendingUri.getScheme())) {
            pendingUri=null;message("Archivo no compatible","Selecciona un archivo EPUB guardado en el teléfono.");
        }
        if(reader!=null)consumePending();
    }
    private void consumePending() {
        if(reader==null || reader.busy)return;
        if(pendingUri!=null) {
            Uri uri=pendingUri;pendingUri=null;opening=true;expectedVersion=reader.loadVersion;reader.importBook(uri);
        }else if(resumeIntent) {
            resumeIntent=false;if(reader.book!=null)showReader();else {
                String id=prefs.getString("last","");if(!id.isEmpty())openBook(id);
            }
        }
    }
    @Override protected void onNewIntent(Intent intent) {super.onNewIntent(intent);setIntent(intent);receive(intent);}
    private void openBook(String id) {
        if(reader==null || reader.busy)return;
        if(id.equals(reader.currentId()) && reader.book!=null){showReader();return;}
        opening=true;expectedVersion=reader.loadVersion;reader.openSaved(id);
    }
    private void showReader() {
        if(reader==null || reader.book==null)return;
        reading=true;libraryGeneration++;disposeWeb();fullscreen(true);shownId="";shownChapter=-1;lastSpoken=-1;
        root=new FrameLayout(this);root.setBackgroundColor(background());setContentView(root);
        web=new PagedReaderView(this,this);root.addView(web,new FrameLayout.LayoutParams(-1,-1));
        top=column();top.setPadding(dp(12),dp(10),dp(12),dp(12));top.setBackgroundColor(teal());
        FrameLayout.LayoutParams tp=new FrameLayout.LayoutParams(-1,-2,Gravity.TOP);root.addView(top,tp);
        LinearLayout tools=new LinearLayout(this);top.addView(tools);
        rowButton(tools,"← Libros",this::buildHome);rowButton(tools,"Índice",this::chapters);rowButton(tools,"Voces",this::voiceMenu);rowButton(tools,"Aa",this::appearance);
        heading=text(reader.book.title,18,Color.WHITE);heading.setMaxLines(2);heading.setPadding(dp(6),dp(10),dp(6),dp(6));top.addView(heading);
        subheading=text("",13,Color.rgb(184,217,224));subheading.setPadding(dp(6),0,dp(6),0);top.addView(subheading);
        bottom=column();bottom.setBackgroundColor(teal());bottom.setPadding(dp(12),dp(8),dp(12),dp(12));
        root.addView(bottom,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
        LinearLayout playback=new LinearLayout(this);bottom.addView(playback);
        rowButton(playback,"◀ Página",()->web.turn(-1));
        play=button("Escuchar",()->{if(reader.playing)reader.pause();else web.startSpeech();});playback.addView(play,new LinearLayout.LayoutParams(0,dp(48),1));
        rowButton(playback,"Página ▶",()->web.turn(1));
        pageLabel=text("",14,Color.WHITE);pageLabel.setGravity(Gravity.CENTER);bottom.addView(pageLabel);
        pageSlider=new SeekBar(this);bottom.addView(pageSlider,new LinearLayout.LayoutParams(-1,dp(40)));
        pageSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar s,int value,boolean user){if(user)pageLabel.setText("Página "+(value+1)+" / "+pages);}
            public void onStartTrackingTouch(SeekBar s){reader.pause();}
            public void onStopTrackingTouch(SeekBar s){web.page(s.getProgress());}
        });
        controls=false;showControls();render();
        if(!prefs.getBoolean("gestureHint",false)) {
            Toast.makeText(this,"Desliza para pasar página. Toca para mostrar los controles.",Toast.LENGTH_LONG).show();prefs.edit().putBoolean("gestureHint",true).apply();
        }
    }
    private void render() {
        if(reader==null || isDestroyed() || navigating)return;
        if(opening && reader.loadVersion!=expectedVersion && !reader.busy) {opening=false;showReader();return;}
        if(opening && !reader.busy && reader.status.startsWith("Error:")) {opening=false;message("No se pudo importar",reader.status);}
        if(!reader.busy && pendingUri!=null){consumePending();return;}
        if(!reading){if(homeStatus!=null)homeStatus.setText(reader.busy?reader.status:reader.playing?"Escuchando: "+reader.book.title:reader.status.startsWith("Error:")?reader.status:"");return;}
        if(reader.book==null || web==null)return;
        play.setText(reader.playing?"Pausar":"Escuchar");play.setEnabled(!reader.busy);
        boolean chapterChanged=!shownId.equals(reader.currentId()) || shownChapter!=reader.chapter;
        if(chapterChanged){
            shownId=reader.currentId();shownChapter=reader.chapter;lastSpoken=-1;
            String loc=nextLocator!=null?nextLocator:reader.locator;nextLocator=null;
            web.chapter(reader.currentFile(),reader.book.chapters.get(reader.chapter),reader.chapter,dark,font,loc,reader.locatorOffset);
            heading.setText(reader.book.title);
        }else if(nextLocator!=null){web.locate(nextLocator,0);nextLocator=null;}
        subheading.setText(reader.book.chapters.get(reader.chapter).title+" · "+reader.status);
        if(reader.playing){lastSpoken=reader.chunk;if(!chapterChanged)web.speak(reader.chunk,reader.speechOffset);}
        if(!reader.playing){lastSpoken=-1;web.clearSpeech();}
    }
    @Override public void position(int chapter,int p,int total,String loc,int offset,int chunk) {
        if(!reading || reader==null || reader.chapter!=chapter)return;
        page=p;pages=total;pageLabel.setText("Página "+(p+1)+" / "+total+" · Sección "+(chapter+1)+" / "+reader.book.chapters.size());
        pageSlider.setMax(Math.max(0,total-1));pageSlider.setProgress(p);reader.visualPosition(loc,offset,chunk,p,total);
    }
    @Override public void boundary(int delta) {
        if(reader==null || reader.book==null)return;
        int target=reader.chapter+(delta<0?-1:1);
        if(target<0 || target>=reader.book.chapters.size()){Toast.makeText(this,delta<0?"Inicio del libro":"Fin del libro",Toast.LENGTH_SHORT).show();return;}
        navigating=true;reader.pause();reader.goChapter(target);nextLocator=delta<0?"end":"";
        navigating=false;shownChapter=-1;render();
    }
    @Override public void manual(){if(reader!=null && reader.playing)reader.pause();}
    @Override public void playVisible(int chapter,String loc,int offset,int chunk) {
        if(!reading || reader==null || reader.busy || reader.playing || reader.chapter!=chapter)return;
        reader.visualPosition(loc,offset,chunk,page,pages);
        reader.play();
    }
    @Override public void boundarySpeech() {
        if(reader==null || reader.busy || reader.playing)return;
        if(reader.chapter+1<reader.book.chapters.size()){reader.goChapter(reader.chapter+1);reader.play();}
        else Toast.makeText(this,"No hay más texto después de esta página",Toast.LENGTH_SHORT).show();
    }
    @Override public void toggle(){controls=!controls;showControls();}
    private void showControls(){if(top!=null){top.setVisibility(controls?View.VISIBLE:View.GONE);bottom.setVisibility(controls?View.VISIBLE:View.GONE);}}
    private void toggleTheme(){dark=!dark;prefs.edit().putBoolean("dark",dark).apply();
        setTheme(dark?android.R.style.Theme_Material_NoActionBar:android.R.style.Theme_Material_Light_NoActionBar);
        if(reading){web.appearance(dark,font);root.setBackgroundColor(background());}else buildHome();}
    private void appearance(){
        LinearLayout panel=column();panel.setPadding(dp(24),dp(12),dp(24),dp(12));
        Switch mode=new Switch(this);mode.setText("Modo oscuro");mode.setChecked(dark);panel.addView(mode);
        TextView size=text("Tamaño de letra: "+font,16,foreground());panel.addView(size);
        SeekBar slider=new SeekBar(this);slider.setMax(18);slider.setProgress(font-16);panel.addView(slider);
        mode.setOnCheckedChangeListener((b,value)->{if(value!=dark)toggleTheme();});
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar s,int value,boolean user){if(user){font=value+16;size.setText("Tamaño de letra: "+font);}}
            public void onStartTrackingTouch(SeekBar s){}
            public void onStopTrackingTouch(SeekBar s){prefs.edit().putInt("font",font).apply();if(web!=null)web.appearance(dark,font);}
        });
        new AlertDialog.Builder(this).setTitle("Lectura").setView(panel).setPositiveButton("Listo",null).setNeutralButton("Velocidad de voz",(d,n)->speed()).show();
    }
    private void speed(){if(reader==null)return;String[] labels={"0.75×","1× · Normal","1.25×","1.5×","1.75×","2×"};float[] values={.75f,1,1.25f,1.5f,1.75f,2};new AlertDialog.Builder(this).setTitle("Velocidad").setItems(labels,(d,n)->reader.setRate(values[n])).show();}
    private void chapters(){
        if(reader==null || reader.book==null || reader.busy)return;
        List<EpubReader.TocEntry> entries=reader.book.toc;
        new AlertDialog.Builder(this).setTitle("Índice del libro").setItems(entries.stream().map(e->e.title).toArray(String[]::new),(d,n)->{
            EpubReader.TocEntry entry=entries.get(n);navigating=true;reader.pause();reader.goChapter(entry.chapter);
            nextLocator=entry.anchor.isEmpty()?"":"anchor:"+entry.anchor;navigating=false;shownChapter=-1;render();controls=false;showControls();
        }).show();
    }
    private void voiceMenu(){
        if(reader==null)return;
        new AlertDialog.Builder(this).setTitle("Voces en español").setItems(new String[]{"Elegir motor instalado","Elegir voz sin conexión","Ajustes de voz de Android","Velocidad de lectura"},(d,n)->{
            if(n==0){List<TextToSpeech.EngineInfo> engines=reader.engines();new AlertDialog.Builder(this).setTitle("Motor de voz").setItems(engines.stream().map(e->e.label).toArray(String[]::new),(a,i)->reader.initEngine(engines.get(i).name)).show();}
            else if(n==1){List<Voice> voices=reader.voices();if(voices.isEmpty()){message("Voces locales","Descarga una voz en español en tu motor TTS y vuelve a seleccionarlo.");return;}
                new AlertDialog.Builder(this).setTitle("Voz local").setItems(voices.stream().map(v->v.getLocale().getDisplayName(new Locale("es"))+"\n"+v.getName()).toArray(String[]::new),(a,i)->reader.selectVoice(voices.get(i))).show();}
            else if(n==2){try{startActivity(new Intent("com.android.settings.TTS_SETTINGS"));}catch(ActivityNotFoundException e){startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS));}}
            else speed();
        }).show();
    }
    private void message(String title,String content){new AlertDialog.Builder(this).setTitle(title).setMessage(content).setPositiveButton("Entendido",null).show();}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==10 && result==RESULT_OK && data!=null){pendingUri=data.getData();consumePending();}}
    @Override public void onBackPressed(){if(reading)buildHome();else super.onBackPressed();}
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);if(reading && reader!=null)state.putString("readingId",reader.currentId());}
    private void disposeWeb(){if(web!=null){web.removeJavascriptInterface("AndroidReader");web.destroy();web=null;}}
    @Override protected void onDestroy(){if(reader!=null)reader.listener=null;if(bound)unbindService(connection);disposeWeb();images.shutdownNow();super.onDestroy();}
}
