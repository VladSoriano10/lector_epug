package sv.vlad.lector;

import android.content.Context;
import android.graphics.PointF;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.contentstream.PDFGraphicsStreamEngine;
import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.pdmodel.*;
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage;
import com.tom_roush.pdfbox.text.*;
import com.tom_roush.pdfbox.util.Matrix;
import java.io.*;
import java.util.*;

/** PDF extraction is isolated from EPUB parsing; original pages are rendered by Android. */
public final class PdfBookReader {
    private static final class Block {
        final float top,left; final String text;
        Block(float top,float left,String text){this.top=top;this.left=left;this.text=text;}
    }
    public static boolean isPdf(File file) throws IOException {
        try(InputStream in=new FileInputStream(file)){
            byte[] header=new byte[1024];int n=in.read(header);
            return n>4 && new String(header,0,n,java.nio.charset.StandardCharsets.ISO_8859_1).contains("%PDF-");
        }
    }
    public static EpubReader.Book open(Context context,File file,String fallback) throws IOException {
        PDFBoxResourceLoader.init(context.getApplicationContext());
        try(PDDocument doc=PDDocument.load(file,MemoryUsageSetting.setupMixed(16L*1024*1024))) {
            if(doc.isEncrypted())throw new IOException("Este PDF está protegido. Usa una copia sin contraseña.");
            int pages=doc.getNumberOfPages();
            if(pages<1 || pages>1500)throw new IOException("El PDF debe tener entre 1 y 1500 páginas.");
            List<EpubReader.Chapter> chapters=new ArrayList<>();List<EpubReader.TocEntry> toc=new ArrayList<>();
            long total=0;
            for(int p=0;p<pages;p++){
                if(Thread.currentThread().isInterrupted())throw new IOException("Lectura cancelada");
                List<Block> blocks=new ArrayList<>();
                PDFTextStripper stripper=new PDFTextStripper(){
                    @Override protected void writeString(String text,List<TextPosition> positions){
                        if(!text.trim().isEmpty() && !positions.isEmpty()){
                            TextPosition first=positions.get(0);blocks.add(new Block(first.getYDirAdj(),first.getXDirAdj(),text));
                        }
                    }
                };
                stripper.setSortByPosition(true);stripper.setStartPage(p+1);stripper.setEndPage(p+1);stripper.getText(doc);
                PDPage page=doc.getPage(p);
                new Images(page,blocks).processPage(page);
                blocks.sort(Comparator.comparingDouble((Block b)->b.top).thenComparingDouble(b->b.left));
                List<String> chunks=new ArrayList<>();List<Narration.Item> narration=new ArrayList<>();
                StringBuilder paragraph=new StringBuilder();int image=0;
                for(Block block:blocks){
                    if(block.text==null){append(paragraph,chunks,narration);narration.add(Narration.Item.image("pdf-image:"+(image++)));}
                    else {paragraph.append(block.text).append(' ');total+=block.text.length();}
                }
                append(paragraph,chunks,narration);
                if(total>16L*1024*1024)throw new IOException("El PDF contiene demasiado texto para importarlo.");
                chapters.add(new EpubReader.Chapter("Página "+(p+1),chunks,narration));
                toc.add(new EpubReader.TocEntry("Página "+(p+1),p,""));
            }
            String title=doc.getDocumentInformation().getTitle(),author=doc.getDocumentInformation().getAuthor();
            if(title==null || title.trim().isEmpty())title=fallback;
            EpubReader.Book book=new EpubReader.Book(title,author==null?"":author,"",chapters,toc);book.pdf=true;return book;
        } catch(com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException e){throw new IOException("El PDF necesita contraseña. Usa una copia sin protección.",e);}
    }
    private static void append(StringBuilder text,List<String> chunks,List<Narration.Item> narration){
        for(String part:EpubReader.split(text.toString())){narration.add(Narration.Item.text(chunks.size(),"chunk:"+chunks.size()));chunks.add(part);}text.setLength(0);
    }
    /** Detects substantial raster illustrations, including images nested in Form XObjects. */
    private static final class Images extends PDFGraphicsStreamEngine {
        final PDPage page;final List<Block> blocks;private PointF point=new PointF();
        Images(PDPage page,List<Block> blocks){super(page);this.page=page;this.blocks=blocks;}
        @Override public void drawImage(PDImage image){
            Matrix m=getGraphicsState().getCurrentTransformationMatrix();
            float area=Math.abs(m.getScaleX()*m.getScaleY()-m.getShearX()*m.getShearY());
            float pageArea=page.getCropBox().getWidth()*page.getCropBox().getHeight();
            if(image.getWidth()<64 || image.getHeight()<64 || area<pageArea*.01f)return;
            PointF[] corners={m.transformPoint(0,0),m.transformPoint(1,0),m.transformPoint(0,1),m.transformPoint(1,1)};
            float top=Float.MAX_VALUE,left=Float.MAX_VALUE;
            for(PointF p:corners){float x=p.x-page.getCropBox().getLowerLeftX(),y=p.y-page.getCropBox().getLowerLeftY();
                float tx=x,ty=page.getCropBox().getHeight()-y;
                switch((page.getRotation()%360+360)%360){case 90:tx=y;ty=x;break;case 180:tx=page.getCropBox().getWidth()-x;ty=y;break;case 270:tx=page.getCropBox().getHeight()-y;ty=page.getCropBox().getWidth()-x;break;default:break;}
                top=Math.min(top,ty);left=Math.min(left,tx);
            }
            blocks.add(new Block(top,left,null));
        }
        @Override public void appendRectangle(PointF a,PointF b,PointF c,PointF d){point=a;}
        @Override public void clip(android.graphics.Path.FillType windingRule){}
        @Override public void moveTo(float x,float y){point=new PointF(x,y);}
        @Override public void lineTo(float x,float y){point=new PointF(x,y);}
        @Override public void curveTo(float x1,float y1,float x2,float y2,float x3,float y3){point=new PointF(x3,y3);}
        @Override public PointF getCurrentPoint(){return point;}
        @Override public void closePath(){}
        @Override public void endPath(){}
        @Override public void strokePath(){}
        @Override public void fillPath(android.graphics.Path.FillType windingRule){}
        @Override public void fillAndStrokePath(android.graphics.Path.FillType windingRule){}
        @Override public void shadingFill(com.tom_roush.pdfbox.cos.COSName name){}
    }
}
