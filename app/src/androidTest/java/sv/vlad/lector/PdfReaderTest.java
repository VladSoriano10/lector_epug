package sv.vlad.lector;

import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.*;
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font;
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.lang.reflect.Field;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PdfReaderTest {
    static File fixture(Context context) throws Exception {
        PDFBoxResourceLoader.init(context);File file=new File(context.getCacheDir(),"novela.pdf");
        try(PDDocument doc=new PDDocument()){
            doc.getDocumentInformation().setTitle("Novela PDF de prueba");
            Bitmap bitmap=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.BLUE);
            for(int n=0;n<3;n++){
                PDPage page=new PDPage();doc.addPage(page);
                try(PDPageContentStream out=new PDPageContentStream(doc,page)){
                    if(n!=1){out.beginText();out.setFont(PDType1Font.HELVETICA,16);out.newLineAtOffset(50,700);out.showText(n==0?"Antes de la ilustracion.":"Final de la novela.");out.endText();}
                    if(n<2)out.drawImage(LosslessFactory.createFromImage(doc,bitmap),50,350,250,250);
                    if(n==0){out.beginText();out.setFont(PDType1Font.HELVETICA,16);out.newLineAtOffset(50,200);out.showText("Despues de la ilustracion.");out.endText();}
                }
            }bitmap.recycle();doc.save(file);
        }return file;
    }
    @Test public void extractsMixedPagesInOrderAndRendersOriginal() throws Exception {
        Context context=ApplicationProvider.getApplicationContext();File file=fixture(context);
        assertTrue(PdfBookReader.isPdf(file));EpubReader.Book book=PdfBookReader.open(context,file,"fallback");
        assertTrue(book.pdf);assertEquals("Novela PDF de prueba",book.title);assertEquals(3,book.chapters.size());
        EpubReader.Chapter first=book.chapters.get(0);assertEquals(3,first.narration.size());
        assertTrue(first.chunks.get(0).contains("Antes"));assertTrue(first.narration.get(1).image());assertTrue(first.chunks.get(1).contains("Despues"));
        assertTrue(book.chapters.get(1).chunks.isEmpty());assertTrue(book.chapters.get(1).narration.get(0).image());
        Bitmap rendered=PdfPageView.render(file,0,600);assertTrue(rendered.getWidth()>0);rendered.recycle();
    }
    private static View find(View v,String label){if(v instanceof TextView && label.contentEquals(((TextView)v).getText()))return v;if(v instanceof ViewGroup)for(int n=0;n<((ViewGroup)v).getChildCount();n++){View found=find(((ViewGroup)v).getChildAt(n),label);if(found!=null)return found;}return null;}
    private static ReaderService service(MainActivity a){try{Field f=MainActivity.class.getDeclaredField("reader");f.setAccessible(true);return (ReaderService)f.get(a);}catch(Exception e){throw new AssertionError(e);}}
    @Test public void importNavigatePanelsAndReopenPdf() throws Exception {
        Context context=ApplicationProvider.getApplicationContext();File file=fixture(context);
        if(Build.VERSION.SDK_INT>=33)try(InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand("pm grant sv.vlad.lector android.permission.POST_NOTIFICATIONS"))){while(in.read()!=-1){}}
        Intent intent=new Intent(context,MainActivity.class).setAction(Intent.ACTION_VIEW).setData(Uri.fromFile(file)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        String[] savedId={""};
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(intent)){
            boolean[] loaded={false};long deadline=SystemClock.uptimeMillis()+20000;
            while(!loaded[0] && SystemClock.uptimeMillis()<deadline){scenario.onActivity(a->{ReaderService r=service(a);loaded[0]=r!=null && !r.busy && r.book!=null && r.book.pdf;});SystemClock.sleep(100);}assertTrue("PDF imported",loaded[0]);
            scenario.onActivity(a->{ReaderService r=service(a);r.goChapter(1);savedId[0]=r.currentId();assertTrue(savedId[0].endsWith(".pdf"));a.toggle();});
            for(String label:new String[]{"Aa","Voces","Índice"}){
                scenario.onActivity(a->{View button=find(a.getWindow().getDecorView(),label);assertNotNull(button);button.performClick();});
                scenario.onActivity(a->{View done=find(a.getWindow().getDecorView(),"Listo");assertNotNull(done);done.performClick();assertEquals(1,service(a).chapter);});
            }
            scenario.onActivity(a->{assertEquals(1,service(a).chapter);service(a).pause();});
        }
        assertEquals(1,context.getSharedPreferences("reader",Context.MODE_PRIVATE).getInt(savedId[0]+":chapter",-1));
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))){
            long deadline=SystemClock.uptimeMillis()+10000;boolean[] bound={false};while(!bound[0] && SystemClock.uptimeMillis()<deadline){scenario.onActivity(a->{bound[0]=service(a)!=null;});SystemClock.sleep(100);}assertTrue(bound[0]);
            scenario.onActivity(a->service(a).openSaved(savedId[0]));boolean[] opened={false};while(!opened[0] && SystemClock.uptimeMillis()<deadline){scenario.onActivity(a->{ReaderService r=service(a);opened[0]=!r.busy && r.book!=null && r.book.pdf;});SystemClock.sleep(100);}assertTrue(opened[0]);scenario.onActivity(a->assertEquals(1,service(a).chapter));
        }
    }
}
