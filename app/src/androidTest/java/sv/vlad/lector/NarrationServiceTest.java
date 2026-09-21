package sv.vlad.lector;

import android.content.*;
import android.os.*;
import android.speech.tts.TextToSpeech;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Drives real service completion callbacks with a deterministic speech sink (no installed voice required). */
@RunWith(AndroidJUnit4.class)
public class NarrationServiceTest {
    private static final class Voice extends TextToSpeech {
        String text,id;long silence=-1;
        Voice(Context context){super(context,status->{});}
        @Override public int speak(CharSequence text,int queue,Bundle params,String id){this.text=text.toString();this.id=id;silence=-1;return SUCCESS;}
        @Override public int playSilentUtterance(long duration,int queue,String id){this.text="";this.id=id;silence=duration;return SUCCESS;}
        @Override public int setSpeechRate(float rate){return SUCCESS;}
        @Override public int stop(){return SUCCESS;}
    }
    private static Field field(String name) throws Exception {Field f=ReaderService.class.getDeclaredField(name);f.setAccessible(true);return f;}
    private static void speak(ReaderService service) throws Exception {Method m=ReaderService.class.getDeclaredMethod("speak");m.setAccessible(true);m.invoke(service);}
    @Test public void textImagesPauseResumeAndEndKeepTheirOrder() throws Exception {
        Context context=ApplicationProvider.getApplicationContext();CountDownLatch bound=new CountDownLatch(1);ReaderService[] value={null};
        ServiceConnection connection=new ServiceConnection(){public void onServiceConnected(ComponentName name,IBinder binder){value[0]=((ReaderService.LocalBinder)binder).get();bound.countDown();}public void onServiceDisconnected(ComponentName name){}};
        assertTrue(context.bindService(new Intent(context,ReaderService.class),connection,Context.BIND_AUTO_CREATE));assertTrue(bound.await(10,TimeUnit.SECONDS));
        Throwable[] failure={null};
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            Voice fake=null;
            try {
                ReaderService service=value[0];service.pause();field("generation").setInt(service,field("generation").getInt(service)+1);
                TextToSpeech old=(TextToSpeech)field("tts").get(service);if(old!=null)old.shutdown();fake=new Voice(context);field("tts").set(service,fake);
                EpubReader.Chapter first=new EpubReader.Chapter("Texto","","<span data-loc='0' data-chunk='0'>Antes</span><img data-loc='1'><span data-loc='2' data-chunk='1'>Después</span>",Arrays.asList("Antes","Después"));
                EpubReader.Chapter imageOnly=new EpubReader.Chapter("Lámina","","<img data-loc='0'>",Collections.emptyList());
                EpubReader.Chapter last=new EpubReader.Chapter("Final","","<span data-loc='0' data-chunk='0'>Final</span>",Collections.singletonList("Final"));
                service.book=new EpubReader.Book("Test","","",Arrays.asList(first,imageOnly,last),Collections.emptyList());service.chapter=0;service.chunk=0;service.locator="";service.speechOffset=0;
                service.illustrationSettings(true,3);field("narrationIndex").setInt(service,-1);service.playing=true;speak(service);assertEquals("Antes",fake.text);
                service.utteranceDone(fake.id);assertEquals("Ilustración",fake.text);assertTrue(service.speakingIllustration());
                service.utteranceDone(fake.id);assertEquals(3000,fake.silence);String canceled=fake.id;
                service.pause();service.utteranceDone(canceled);assertTrue(service.canResumeSpeech());assertEquals(0,service.chapter);
                service.playing=true;speak(service);assertTrue(fake.silence>=0 && fake.silence<=3000);
                service.utteranceDone(canceled);assertEquals("",fake.text); // late callback must not skip the active wait
                service.utteranceDone(fake.id);assertEquals("Después",fake.text);
                service.utteranceDone(fake.id);assertEquals(1,service.chapter);assertEquals("Ilustración",fake.text);
                service.utteranceDone(fake.id);assertEquals(3000,fake.silence);
                service.utteranceDone(fake.id);assertEquals(2,service.chapter);assertEquals("Final",fake.text);
                service.utteranceDone(fake.id);assertFalse(service.playing);assertEquals("Libro terminado",service.status);
                service.illustrationSettings(false,0);service.goChapter(1);service.playing=true;speak(service);assertEquals(2,service.chapter);assertEquals("Final",fake.text);
                service.manualNavigation();assertFalse(service.canResumeSpeech());
            }catch(Throwable t){failure[0]=t;}finally{value[0].pause();value[0].illustrationSettings(true,3);}
        });
        context.unbindService(connection);if(failure[0]!=null)throw new AssertionError(failure[0]);
    }
}
