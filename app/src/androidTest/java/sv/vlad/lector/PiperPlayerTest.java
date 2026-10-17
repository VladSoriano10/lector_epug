package sv.vlad.lector;

import android.os.SystemClock;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.atomic.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PiperPlayerTest {
    private static void ui(Runnable task){InstrumentationRegistry.getInstrumentation().runOnMainSync(task);}
    private static void until(java.util.function.BooleanSupplier condition)throws Exception {
        long deadline=SystemClock.uptimeMillis()+90000;
        while(SystemClock.uptimeMillis()<deadline){if(condition.getAsBoolean())return;Thread.sleep(40);}
        fail("Piper no completó la síntesis/reproducción local");
    }
    @Test public void bothRealVoicesPlayOfflineAndPauseKeepsAudioPosition()throws Exception {
        PiperPlayer player=new PiperPlayer(ApplicationProvider.getApplicationContext());
        AtomicReference<String> done=new AtomicReference<>(""),error=new AtomicReference<>("");
        AtomicInteger range=new AtomicInteger(-1);
        PiperPlayer.Listener listener=new PiperPlayer.Listener(){
            public void range(String id,int offset){range.set(offset);}
            public void done(String id){done.set(id);}
            public void error(String id){error.set(id);}
        };
        String phrase="Hola, esta es una prueba de lectura con una voz local en español que conserva la pausa.";
        try {
            for(String voice:new String[]{"claude","davefx"}){
                done.set("");error.set("");range.set(-1);
                ui(()->player.speak(voice,phrase,0,voice,1f,"first",listener));
                until(()->!error.get().isEmpty()||player.hasAudio());assertEquals("",error.get());
                until(()->player.playbackFrame()>1000);
                ui(player::pause);Thread.sleep(120);int paused=player.playbackFrame();
                Thread.sleep(250);assertEquals("Audio must remain at the paused sample",paused,player.playbackFrame());
                assertTrue("Retain the same PCM buffer",paused>0);assertEquals("",done.get());
                ui(()->player.speak(voice,phrase,0,voice,1f,"resumed",listener));
                assertTrue("Do not restart at zero",player.playbackFrame()>=paused);
                until(()->!done.get().isEmpty()||!error.get().isEmpty());
                assertEquals("",error.get());assertEquals("resumed",done.get());
                ui(player::cancel);
            }
            // Cancelling preparation must never deliver stale completion into another book.
            done.set("");
            ui(()->{player.speak("old",phrase,0,"claude",1f,"stale",listener);player.cancel();});
            Thread.sleep(500);assertEquals("",done.get());
        }finally{ui(player::close);}
    }
}
