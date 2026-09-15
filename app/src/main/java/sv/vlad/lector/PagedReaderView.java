package sv.vlad.lector;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.Uri;
import android.webkit.*;
import java.io.*;
import java.util.*;
import org.json.JSONObject;

/** Only app-owned scripts can reach this bridge; every EPUB document is sanitized. */
@SuppressLint("SetJavaScriptEnabled")
public final class PagedReaderView extends WebView {
    public interface Listener {
        void position(int chapter, int page, int pages, String loc, int offset, int chunk);
        void boundary(int delta);
        void toggle();
        void manual();
    }
    private final Listener listener;
    private volatile File epub;
    private boolean shellReady;
    private int epoch, chapter;
    private String pending = "";
    public PagedReaderView(Context context, Listener listener) {
        super(context); this.listener=listener;
        getSettings().setJavaScriptEnabled(true);
        getSettings().setAllowFileAccess(false); getSettings().setAllowContentAccess(false);
        getSettings().setAllowFileAccessFromFileURLs(false); getSettings().setAllowUniversalAccessFromFileURLs(false);
        getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        getSettings().setDomStorageEnabled(false); getSettings().setDefaultTextEncodingName("UTF-8");
        getSettings().setCacheMode(WebSettings.LOAD_NO_CACHE);
        setVerticalScrollBarEnabled(false);setHorizontalScrollBarEnabled(false);
        addJavascriptInterface(new Bridge(), "AndroidReader");
        setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return true; }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri=request.getUrl();
                try {
                    if(!"https".equals(uri.getScheme()) || !"epub.local".equals(uri.getHost())) return blocked();
                    String path=uri.getPath();
                    if(path == null) return blocked();
                    if(path.equals("/reader.html") || path.equals("/reader.css") || path.equals("/reader.js")) {
                        String mime=path.endsWith(".css")?"text/css":path.endsWith(".js")?"text/javascript":"text/html";
                        return new WebResourceResponse(mime,"UTF-8",getContext().getAssets().open(path.substring(1)));
                    }
                    File file=epub;
                    if(file!=null && path.startsWith("/asset/")) {
                        String name=path.substring(7), lower=name.toLowerCase(Locale.ROOT);
                        String mime=lower.endsWith(".png")?"image/png":lower.endsWith(".jpg")||lower.endsWith(".jpeg")?"image/jpeg":
                            lower.endsWith(".gif")?"image/gif":lower.endsWith(".webp")?"image/webp":lower.endsWith(".svg")?"image/svg+xml":"";
                        if(!mime.isEmpty())return new WebResourceResponse(mime,null,new ByteArrayInputStream(EpubReader.asset(file,name)));
                    }
                }catch(IOException ignored) { }
                return blocked();
            }
            @Override public void onPageFinished(WebView view,String url) {
                shellReady=true; if(!pending.isEmpty())evaluateJavascript(pending,null);
            }
        });
        loadUrl("https://epub.local/reader.html");
    }
    private static WebResourceResponse blocked() {
        return new WebResourceResponse("text/plain","UTF-8",404,"Not found",Collections.emptyMap(),new ByteArrayInputStream(new byte[0]));
    }
    public void chapter(File file,EpubReader.Chapter content,int index,boolean dark,int font,String loc,int offset) {
        epub=file;chapter=index;epoch++;
        pending="Reader.load("+JSONObject.quote(content.html)+","+dark+","+font+","+JSONObject.quote(loc)+","+offset+","+epoch+")";
        if(shellReady)evaluateJavascript(pending,null);
    }
    public void turn(int delta) { evaluateJavascript("Reader.turn("+delta+")",null); }
    public void page(int value) { evaluateJavascript("Reader.page("+value+")",null); }
    public void locate(String loc,int offset) { evaluateJavascript("Reader.locate("+JSONObject.quote(loc)+","+offset+")",null); }
    public void speak(int chunk,int offset) { evaluateJavascript("Reader.speak("+chunk+","+offset+")",null); }
    public void appearance(boolean dark,int font) { evaluateJavascript("Reader.appearance("+dark+","+font+")",null); }
    public void clearSpeech() { evaluateJavascript("Reader.clearSpeech()",null); }
    private final class Bridge {
        private void dispatch(int token,Runnable task) { post(()->{if(token==epoch)task.run();}); }
        @JavascriptInterface public void position(int token,int page,int pages,String loc,int offset,int chunk) {
            dispatch(token,()->listener.position(chapter,page,pages,loc,offset,chunk));
        }
        @JavascriptInterface public void boundary(int token,int delta) { dispatch(token,()->listener.boundary(delta)); }
        @JavascriptInterface public void toggle(int token) { dispatch(token,listener::toggle); }
        @JavascriptInterface public void manual(int token) { dispatch(token,listener::manual); }
    }
}
