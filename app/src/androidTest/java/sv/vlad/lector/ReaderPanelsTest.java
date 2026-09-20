package sv.vlad.lector;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ReaderPanelsTest {
    private View find(View view,String text) {
        if(text.equals(view.getContentDescription()) || (view instanceof TextView && text.contentEquals(((TextView)view).getText())))return view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=find(((ViewGroup)view).getChildAt(i),text);if(found!=null)return found;}
        return null;
    }
    private PagedReaderView web(View view) {
        if(view instanceof PagedReaderView)return (PagedReaderView)view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){PagedReaderView found=web(((ViewGroup)view).getChildAt(i));if(found!=null)return found;}
        return null;
    }
    private String js(ActivityScenario<MainActivity> scenario,String script) throws Exception {
        CountDownLatch latch=new CountDownLatch(1);AtomicReference<String> result=new AtomicReference<>("null");
        scenario.onActivity(a->{PagedReaderView w=web(a.getWindow().getDecorView());if(w==null){latch.countDown();return;}w.evaluateJavascript(script,value->{result.set(value);latch.countDown();});});
        assertTrue("WebView response",latch.await(5,TimeUnit.SECONDS));return result.get();
    }
    private void click(ActivityScenario<MainActivity> scenario,String label) {
        scenario.onActivity(a->{View v=find(a.getWindow().getDecorView(),label);assertNotNull(label,v);assertTrue(v.performClick());});
    }
    private void entry(ZipOutputStream zip,String name,String value) throws Exception {
        zip.putNextEntry(new ZipEntry(name));zip.write(value.getBytes(StandardCharsets.UTF_8));zip.closeEntry();
    }
    @Test public void closingEveryReaderPanelPreservesPageAndViewport() throws Exception {
        Context context=ApplicationProvider.getApplicationContext();
        context.getSharedPreferences("reader",Context.MODE_PRIVATE).edit().clear().putBoolean("gestureHint",true).apply();
        File epub=new File(context.getCacheDir(),"panels-fixture.epub");
        try(ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(epub))) {
            entry(zip,"META-INF/container.xml","<container><rootfile full-path='book.opf'/></container>");
            entry(zip,"book.opf","<package><metadata><dc:title xmlns:dc='http://purl.org/dc/elements/1.1/'>Prueba VladER</dc:title></metadata><manifest><item id='text' href='text.xhtml' media-type='application/xhtml+xml'/></manifest><spine><itemref idref='text'/></spine></package>");
            StringBuilder html=new StringBuilder("<html><body><h1>Lectura de prueba</h1>");
            for(int n=0;n<100;n++)html.append("<p>Una página conserva su posición al abrir y cerrar las opciones de lectura. La voz y el texto siguen juntos.</p>");
            entry(zip,"text.xhtml",html.append("</body></html>").toString());
        }
        Intent intent=new Intent(context,MainActivity.class).setAction(Intent.ACTION_VIEW).setData(Uri.fromFile(epub)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(intent)) {
            JSONObject snapshot=null;long until=SystemClock.uptimeMillis()+15000;
            while(SystemClock.uptimeMillis()<until) {
                String state=js(scenario,"window.Reader ? Reader.snapshot() : null");
                if(!"null".equals(state)){JSONObject s=new JSONObject(state);if(s.optBoolean("ready") && s.optInt("count")>4){snapshot=s;break;}}
                SystemClock.sleep(100);
            }
            assertNotNull("Book loaded",snapshot);
            js(scenario,"Reader.page(3)");scenario.onActivity(MainActivity::toggle);
            JSONObject before=new JSONObject(js(scenario,"Reader.snapshot()"));assertEquals(3,before.getInt("page"));
            for(String menu:new String[]{"Aa","Voces","Índice"})for(int close=0;close<3;close++) {
                click(scenario,menu);
                JSONObject during=new JSONObject(js(scenario,"Reader.snapshot()"));
                assertEquals("Opening "+menu,before.getInt("page"),during.getInt("page"));
                assertEquals("No window resize",before.getInt("height"),during.getInt("height"));
                if(close==0){
                    Bitmap bitmap=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
                    assertNotNull(bitmap);
                    try(OutputStream out=new FileOutputStream(new File(context.getFilesDir(),"panel-"+(menu.equals("Aa")?"aa":menu.equals("Voces")?"voces":"indice")+".png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();
                    click(scenario,"Listo");
                }else if(close==1)click(scenario,"Cerrar panel");
                else scenario.onActivity(MainActivity::onBackPressed);
                SystemClock.sleep(450);
                JSONObject after=new JSONObject(js(scenario,"Reader.snapshot()"));
                assertEquals(menu+" close "+close,before.getInt("page"),after.getInt("page"));
                assertEquals(before.getInt("height"),after.getInt("height"));
            }
        }
    }
}
