package sv.vlad.lector;

import android.app.*;
import android.content.*;
import android.media.session.*;
import android.net.Uri;
import android.os.*;
import android.service.notification.StatusBarNotification;
import android.speech.tts.*;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class BackgroundPlaybackTest {
    private static final class FakeVoice extends TextToSpeech {
        String spoken="",id="";
        final Voice voice=new Voice("test-es",new Locale("es","MX"),Voice.QUALITY_NORMAL,Voice.LATENCY_NORMAL,false,Collections.emptySet());
        FakeVoice(Context context){super(context,s->{});}
        @Override public Voice getVoice(){return voice;}
        @Override public Set<Voice> getVoices(){return Collections.singleton(voice);}
        @Override public int speak(CharSequence text,int queue,Bundle params,String id){spoken=text.toString();this.id=id;return SUCCESS;}
        @Override public int setSpeechRate(float rate){return SUCCESS;}
        @Override public int stop(){return SUCCESS;}
    }
    private static Field field(Class<?> type,String name){try{Field f=type.getDeclaredField(name);f.setAccessible(true);return f;}catch(Exception e){throw new AssertionError(e);}}
    private static Object get(Object object,String name){try{return field(object.getClass(),name).get(object);}catch(Exception e){throw new AssertionError(e);}}
    private static void set(Object object,String name,Object value){try{field(object.getClass(),name).set(object,value);}catch(Exception e){throw new AssertionError(e);}}
    private static void ui(Runnable task){InstrumentationRegistry.getInstrumentation().runOnMainSync(task);}
    private static void waitFor(java.util.function.BooleanSupplier condition) throws Exception {long end=SystemClock.uptimeMillis()+15000;while(SystemClock.uptimeMillis()<end){boolean[] done={false};ui(()->done[0]=condition.getAsBoolean());if(done[0])return;SystemClock.sleep(50);}fail("Timed out waiting for playback state");}
    private static void entry(ZipOutputStream zip,String name,String value) throws IOException {zip.putNextEntry(new ZipEntry(name));zip.write(value.getBytes(StandardCharsets.UTF_8));zip.closeEntry();}
    private static File fixture(Context context)throws Exception {
        File file=new File(context.getCacheDir(),"background.epub");
        try(ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(file))){
            entry(zip,"META-INF/container.xml","<container><rootfile full-path='book.opf'/></container>");
            entry(zip,"book.opf","<package><metadata><dc:title xmlns:dc='http://purl.org/dc/elements/1.1/'>Segundo plano</dc:title></metadata><manifest><item id='a' href='a.xhtml' media-type='application/xhtml+xml'/><item id='b' href='b.xhtml' media-type='application/xhtml+xml'/></manifest><spine><itemref idref='a'/><itemref idref='b'/></spine></package>");
            StringBuilder text=new StringBuilder("<html><body><p>");for(int n=0;n<30;n++)text.append("Una lectura larga conserva cada palabra mientras usamos otra aplicación. ");text.append("</p></body></html>");
            entry(zip,"a.xhtml",text.toString());entry(zip,"b.xhtml",text.toString());
        }return file;
    }
    private static StatusBarNotification notification(Context context){for(StatusBarNotification n:context.getSystemService(NotificationManager.class).getActiveNotifications())if(n.getId()==1)return n;return null;}
    private static Notification.Action action(Context context,String title)throws Exception {
        long end=SystemClock.uptimeMillis()+5000;
        while(SystemClock.uptimeMillis()<end){StatusBarNotification n=notification(context);if(n!=null && n.getNotification().actions!=null)for(Notification.Action a:n.getNotification().actions)if(title.contentEquals(a.title))return a;SystemClock.sleep(50);}
        throw new AssertionError("Missing notification action: "+title);
    }
    private static FakeVoice installVoice(Context context,ReaderService reader){
        set(reader,"generation",(Integer)get(reader,"generation")+1);
        TextToSpeech old=(TextToSpeech)get(reader,"tts");if(old!=null)old.shutdown();
        FakeVoice fake=new FakeVoice(context);set(reader,"tts",fake);set(reader,"ready",true);set(reader,"engineInitializing",false);return fake;
    }
    @Test public void backgroundProgressDoesNotRenderOrRepublishAndNotificationResumes()throws Exception {
        Context context=ApplicationProvider.getApplicationContext();
        if(Build.VERSION.SDK_INT>=33)try(InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand("pm grant sv.vlad.lector android.permission.POST_NOTIFICATIONS"))){while(in.read()!=-1){}}
        ReaderService[] reader={null};FakeVoice[] voice={null};
        Intent intent=new Intent(context,MainActivity.class).setAction(Intent.ACTION_VIEW).setData(Uri.fromFile(fixture(context))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(intent)){
            scenario.onActivity(a->reader[0]=(ReaderService)get(a,"reader"));
            long deadline=SystemClock.uptimeMillis()+10000;while(reader[0]==null && SystemClock.uptimeMillis()<deadline){scenario.onActivity(a->reader[0]=(ReaderService)get(a,"reader"));SystemClock.sleep(50);}assertNotNull(reader[0]);
            waitFor(()->{assertFalse(reader[0].status,reader[0].status.startsWith("Error:"));return reader[0].book!=null && !reader[0].busy;});
            ui(()->{reader[0].goChapter(0);voice[0]=installVoice(context,reader[0]);reader[0].play();});waitFor(()->reader[0].playing);action(context,"Pausar");
            scenario.moveToState(Lifecycle.State.CREATED);
            ui(()->assertNull("Hidden Activity must detach rendering",get(reader[0],"listener")));
            long postTime=notification(context).getPostTime();SharedPreferences prefs=context.getSharedPreferences("reader",Context.MODE_PRIVATE);
            AtomicInteger writes=new AtomicInteger();String key=reader[0].currentId()+":speechOffset";
            SharedPreferences.OnSharedPreferenceChangeListener watch=(p,k)->{if(key.equals(k))writes.incrementAndGet();};prefs.registerOnSharedPreferenceChangeListener(watch);
            try {
                ui(()->{set(reader[0],"lastCheckpoint",SystemClock.uptimeMillis());for(int n=0;n<=200;n++)reader[0].utteranceRange(voice[0].id,n);assertEquals(200,reader[0].speechOffset);assertFalse((Boolean)get(reader[0],"progressPending"));});
                SystemClock.sleep(300);assertEquals("No disk writes per word",0,writes.get());assertEquals("No notification updates per word",postTime,notification(context).getPostTime());
                ui(()->{set(reader[0],"lastCheckpoint",SystemClock.uptimeMillis()-5001);reader[0].utteranceRange(voice[0].id,200);});
                SystemClock.sleep(100);assertEquals("Periodic recovery checkpoint",1,writes.get());
            }finally{prefs.unregisterOnSharedPreferenceChangeListener(watch);}
            action(context,"Pausar").actionIntent.send();waitFor(()->!reader[0].playing);action(context,"Reanudar");
            ui(()->{assertFalse((Boolean)get(reader[0],"foreground"));assertFalse(((PowerManager.WakeLock)get(reader[0],"wake")).isHeld());assertEquals(PlaybackState.STATE_PAUSED,((MediaSession)get(reader[0],"session")).getController().getPlaybackState().getState());});
            assertEquals(200,prefs.getInt(key,-1));
            action(context,"Reanudar").actionIntent.send();waitFor(()->reader[0].playing);action(context,"Pausar");
            ui(()->assertEquals(reader[0].book.chapters.get(0).chunks.get(0).substring(200),voice[0].spoken));
            // Emulate chapter advancement while the UI is hidden, then synchronize once on return.
            ui(()->{reader[0].goChapter(1);});waitFor(()->reader[0].playing);
            scenario.moveToState(Lifecycle.State.RESUMED);
            scenario.onActivity(a->{assertEquals(1,((Integer)get(a,"shownChapter")).intValue());assertNotNull(get(reader[0],"listener"));});
            scenario.moveToState(Lifecycle.State.CREATED);
            ui(()->reader[0].utteranceRange(voice[0].id,100));
            action(context,"Pausar").actionIntent.send();waitFor(()->!reader[0].playing);action(context,"Reanudar");
            // Exercise the cold resume path: only the saved file/position is available.
            ui(()->{reader[0].book=null;reader[0].chapter=0;reader[0].chunk=0;reader[0].speechOffset=0;reader[0].locator="";set(reader[0],"narrationIndex",-1);});
            action(context,"Reanudar").actionIntent.send();waitFor(()->reader[0].playing && reader[0].book!=null);
            ui(()->{assertEquals(1,reader[0].chapter);assertEquals(reader[0].book.chapters.get(1).chunks.get(0).substring(100),voice[0].spoken);});
            action(context,"Cerrar").actionIntent.send();waitFor(()->!reader[0].playing);waitFor(()->notification(context)==null);
        }finally{if(reader[0]!=null)ui(()->{if(!(Boolean)get(reader[0],"destroyed"))reader[0].stopPlayback();});}
    }
}
