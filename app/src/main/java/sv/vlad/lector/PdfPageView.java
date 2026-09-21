package sv.vlad.lector;

import android.content.Context;
import android.graphics.*;
import android.graphics.pdf.PdfRenderer;
import android.os.*;
import android.view.*;
import java.io.*;
import java.util.concurrent.*;

/** One original PDF page at a time, with pinch/double-tap zoom and bounded bitmap memory. */
public final class PdfPageView extends View {
    public interface Listener {void turn(int delta);void toggle();}
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final ScaleGestureDetector scaleDetector;private final GestureDetector gestures;
    private Bitmap bitmap;private File file;private int page;private volatile int token;private volatile boolean closed;private boolean modal,dark;
    private long blockedUntil;private float zoom=1,panX,panY;private String error="Cargando página…";
    public PdfPageView(Context context,Listener listener){
        super(context);setContentDescription("Página PDF. Pellizca para ampliar; desliza para cambiar de página.");
        scaleDetector=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            @Override public boolean onScale(ScaleGestureDetector d){zoom=Math.max(1,Math.min(4,zoom*d.getScaleFactor()));clamp();invalidate();return true;}
        });
        gestures=new GestureDetector(context,new GestureDetector.SimpleOnGestureListener(){
            @Override public boolean onDown(MotionEvent e){return true;}
            @Override public boolean onSingleTapConfirmed(MotionEvent e){if(!modal && SystemClock.uptimeMillis()>=blockedUntil){performClick();listener.toggle();}return true;}
            @Override public boolean onDoubleTap(MotionEvent e){zoom=zoom>1?1:2;panX=panY=0;invalidate();return true;}
            @Override public boolean onScroll(MotionEvent a,MotionEvent b,float dx,float dy){if(zoom>1 && !scaleDetector.isInProgress()){panX-=dx;panY-=dy;clamp();invalidate();}return true;}
            @Override public boolean onFling(MotionEvent a,MotionEvent b,float vx,float vy){if(a!=null && zoom<=1 && Math.abs(b.getX()-a.getX())>60 && Math.abs(vx)>Math.abs(vy)*1.4)listener.turn(vx<0?1:-1);return true;}
        });
    }
    public void show(File file,int page,boolean dark){this.file=file;this.page=page;this.dark=dark;zoom=1;panX=panY=0;load();}
    public void appearance(boolean dark){this.dark=dark;invalidate();}
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){if(file!=null)load();}
    private void load(){
        if(closed || file==null || getWidth()==0)return;
        int own=++token,index=page;File source=file;int width=getWidth();error="Cargando página…";bitmap=null;invalidate();
        io.execute(()->{
            if(closed || own!=token)return;
            try{
                Bitmap result=render(source,index,Math.min(2200,width*2));
                post(()->{if(closed || own!=token){result.recycle();return;}bitmap=result;error="";invalidate();});
            }catch(Exception e){post(()->{if(!closed && own==token){error="No se pudo mostrar esta página PDF";invalidate();}});}
        });
    }
    public static Bitmap render(File file,int index,int width) throws IOException {
        try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);PdfRenderer renderer=new PdfRenderer(fd);PdfRenderer.Page page=renderer.openPage(index)){
            float factor=Math.min((float)width/page.getWidth(),2200f/Math.max(page.getWidth(),page.getHeight()));
            Bitmap result=Bitmap.createBitmap(Math.max(1,Math.round(page.getWidth()*factor)),Math.max(1,Math.round(page.getHeight()*factor)),Bitmap.Config.ARGB_8888);
            result.eraseColor(Color.WHITE);page.render(result,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);return result;
        }
    }
    private float fit(){return bitmap==null?1:Math.min((getWidth()-16f)/bitmap.getWidth(),(getHeight()-16f)/bitmap.getHeight());}
    private void clamp(){if(bitmap==null)return;float s=fit()*zoom;float x=Math.max(0,(bitmap.getWidth()*s-getWidth())/2),y=Math.max(0,(bitmap.getHeight()*s-getHeight())/2);panX=Math.max(-x,Math.min(x,panX));panY=Math.max(-y,Math.min(y,panY));}
    @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);canvas.drawColor(dark?0xFF071E25:0xFFE4E7E4);
        if(bitmap==null){paint.setColor(dark?Color.WHITE:Color.DKGRAY);paint.setTextSize(16*getResources().getDisplayMetrics().scaledDensity);canvas.drawText(error,24,getHeight()/2f,paint);return;}
        float s=fit()*zoom,w=bitmap.getWidth()*s,h=bitmap.getHeight()*s;
        canvas.drawBitmap(bitmap,null,new RectF((getWidth()-w)/2+panX,(getHeight()-h)/2+panY,(getWidth()+w)/2+panX,(getHeight()+h)/2+panY),paint);
    }
    public void readerModal(boolean value){modal=value;if(!value)blockedUntil=SystemClock.uptimeMillis()+350;}
    @Override public boolean onTouchEvent(MotionEvent event){if(modal || SystemClock.uptimeMillis()<blockedUntil)return true;scaleDetector.onTouchEvent(event);gestures.onTouchEvent(event);return true;}
    @Override public boolean performClick(){super.performClick();return true;}
    public void close(){closed=true;token++;io.shutdown();bitmap=null;}
}
