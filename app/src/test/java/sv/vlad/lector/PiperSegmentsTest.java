package sv.vlad.lector;

import org.junit.Test;
import static org.junit.Assert.*;

public class PiperSegmentsTest {
    @Test public void segmentsPreserveAllTextAndUnicode(){
        String text="¡Hola, México! ¿Cómo estás?\n"+"Una novela con ilustraciones y acentos. ".repeat(30)+"🎉".repeat(300);
        int start=0;StringBuilder result=new StringBuilder();
        while(start<text.length()){
            int end=PiperSegments.end(text,start);assertTrue(end>start);assertTrue(end-start<=220);
            assertFalse(Character.isHighSurrogate(text.charAt(end-1)));
            result.append(text,start,end);start=end;
        }
        assertEquals(text,result.toString());
    }
    @Test public void stopsAtSentenceNotDecimal(){
        String text="Cuesta 3.50 euros. Después continuamos.";
        assertEquals("Cuesta 3.50 euros.",text.substring(0,PiperSegments.end(text,0)));
    }
}
