package sv.vlad.lector;

import android.content.*;
import android.os.*;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PiperNarrationTest {
    private static void ui(Runnable task){InstrumentationRegistry.getInstrumentation().runOnMainSync(task);}
    private static void speak(ReaderService service){try{Method m=ReaderService.class.getDeclaredMethod("speak");m.setAccessible(true);m.invoke(service);}catch(Exception e){throw new AssertionError(e);}}
    private static void until(java.util.function.BooleanSupplier condition)throws Exception {
        long deadline=SystemClock.uptimeMillis()+90000;
        while(SystemClock.uptimeMillis()<deadline){boolean[] result={false};ui(()->result[0]=condition.getAsBoolean());if(result[0])return;Thread.sleep(40);}
        fail("La secuencia Piper no terminó");
    }
    @Test public void piperTraversesEpubAndPdfImagesAndPreservesPausedWait()throws Exception {
        Context context=ApplicationProvider.getApplicationContext();CountDownLatch bound=new CountDownLatch(1);ReaderService[] value={null};
        ServiceConnection connection=new ServiceConnection(){public void onServiceConnected(ComponentName n,IBinder b){value[0]=((ReaderService.LocalBinder)b).get();bound.countDown();}public void onServiceDisconnected(ComponentName n){}};
        assertTrue(context.bindService(new Intent(context,ReaderService.class),connection,Context.BIND_AUTO_CREATE));assertTrue(bound.await(10,TimeUnit.SECONDS));
        ReaderService service=value[0];
        Field fileId=ReaderService.class.getDeclaredField("fileId");fileId.setAccessible(true);
        String originalId=(String)fileId.get(service);
        ui(()->{service.manualNavigation();try{fileId.set(service,"piper-narration-test");}catch(Exception e){throw new AssertionError(e);}});
        try {
            for(boolean pdf:new boolean[]{false,true}){
                ui(()->{
                    service.manualNavigation();service.selectPiper(pdf?"davefx":"claude");service.illustrationSettings(true,2);
                    EpubReader.Chapter first=new EpubReader.Chapter("Primera",Arrays.asList("Antes.","Después."),Arrays.asList(Narration.Item.text(0,"loc:0"),Narration.Item.image("loc:1"),Narration.Item.text(1,"loc:2")));
                    EpubReader.Chapter second=new EpubReader.Chapter("Segunda",Collections.singletonList("Final."),Collections.singletonList(Narration.Item.text(0,"loc:0")));
                    service.book=new EpubReader.Book("Prueba","","",Arrays.asList(first,second),Collections.emptyList());service.book.pdf=pdf;
                    service.chapter=0;service.chunk=0;service.speechOffset=0;service.locator="";service.playing=true;speak(service);
                });
                until(()->service.status.equals("Ilustración · pausa antes de continuar"));
                ui(service::pause);Thread.sleep(2200);
                ui(()->{assertTrue(service.speakingIllustration());assertEquals(0,service.chapter);assertTrue(service.canResumeSpeech());service.playing=true;speak(service);});
                until(()->service.status.equals("Libro terminado")||service.status.startsWith("No se pudo"));
                ui(()->{assertEquals("Libro terminado",service.status);assertEquals(1,service.chapter);assertFalse(service.playing);});
            }
        }finally{ui(()->{service.manualNavigation();service.selectAndroid();service.illustrationSettings(true,3);service.book=null;try{fileId.set(service,originalId);}catch(Exception e){throw new AssertionError(e);}});context.unbindService(connection);}
    }
}
