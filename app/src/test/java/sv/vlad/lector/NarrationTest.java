package sv.vlad.lector;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

public class NarrationTest {
    @Test public void imagesKeepTheirPlaceWithoutRenumberingText(){
        EpubReader.Chapter chapter=new EpubReader.Chapter("Test","", "<span data-chunk='0' data-loc='0'>Antes</span><img data-loc='1'><img data-loc='2'><span data-chunk='1' data-loc='3'>Después</span>",Arrays.asList("Antes","Después"));
        assertEquals(4,chapter.narration.size());assertEquals(0,chapter.narration.get(0).chunk);
        assertTrue(chapter.narration.get(1).image());assertTrue(chapter.narration.get(2).image());assertEquals(1,chapter.narration.get(3).chunk);
        assertEquals(1,Narration.find(chapter.narration,"loc:1",0));
        assertEquals(3,Narration.find(chapter.narration,"chunk:1",1));
        assertEquals(0,Narration.find(chapter.narration,"",0));
    }
    @Test public void pauseResumesOnlyRemainingDelay(){
        Narration.Delay delay=new Narration.Delay();delay.set(3000);assertEquals(3000,delay.start(100));
        delay.pause(1100);assertEquals(2000,delay.remaining());
        delay.pause(9000);assertEquals(2000,delay.start(10000));delay.pause(15000);assertEquals(0,delay.remaining());
    }
}
