package sv.vlad.lector;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

public class EpubReaderTest {
    private void entry(ZipOutputStream z, String name, String text) throws IOException {
        z.putNextEntry(new ZipEntry(name)); z.write(text.getBytes(StandardCharsets.UTF_8)); z.closeEntry();
    }
    @Test public void followsSpineAndRemovesScripts() throws Exception {
        File f = File.createTempFile("book", ".epub");
        try {
            try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(f))) {
                entry(z, "META-INF/container.xml", "<container><rootfiles><rootfile full-path='OPS/book.opf'/></rootfiles></container>");
                entry(z, "OPS/book.opf", "<package xmlns:dc='http://purl.org/dc/elements/1.1/'><metadata><dc:title>Prueba española</dc:title></metadata><manifest><item id='a' href='a.xhtml' media-type='application/xhtml+xml'/><item id='b' href='b%20dos.xhtml#inicio' media-type='application/xhtml+xml'/></manifest><spine><itemref idref='b'/><itemref idref='a'/></spine></package>");
                entry(z, "OPS/a.xhtml", "<html><body><h1>Segundo</h1><p>Adiós.</p></body></html>");
                entry(z, "OPS/b dos.xhtml", "<html><body><h1>Primero</h1><p>Hola &amp; café.</p><script>NO LEER</script><style>NO LEER</style></body></html>");
            }
            EpubReader.Book book = EpubReader.open(f);
            assertEquals("Prueba española", book.title);
            assertEquals(2, book.chapters.size());
            assertEquals("Primero", book.chapters.get(0).title);
            String text = String.join(" ", book.chapters.get(0).chunks);
            assertTrue(text.contains("Hola & café.")); assertFalse(text.contains("NO LEER"));
        } finally { f.delete(); }
    }
    @Test public void chunksDoNotLoseTextOrSplitSurrogatePairs() {
        String source = "hola mundo ".repeat(300) + "😀".repeat(500);
        List<String> parts = EpubReader.split(source);
        assertEquals(source.replace(" ", ""), String.join("", parts).replace(" ", ""));
        for (String part : parts) {
            assertTrue(part.length() <= 700); assertFalse(Character.isHighSurrogate(part.charAt(part.length() - 1)));
        }
    }
    @Test(expected = IOException.class) public void rejectsExternalSpineResources() throws Exception {
        EpubReader.resolve("OPS/book.opf", "https://example.com/chapter.xhtml");
    }
    @Test public void rejectsOversizedTextEntries() throws Exception {
        File f = File.createTempFile("oversized", ".epub");
        try {
            try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(f))) {
                entry(z, "META-INF/container.xml", "x".repeat(EpubReader.ENTRY_LIMIT + 1));
            }
            try { EpubReader.open(f); fail("Must reject an oversized ZIP entry"); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("4 MB")); }
        } finally { f.delete(); }
    }
    @Test public void findsLegacyWrappedAndFallbackCovers() throws Exception {
        String[] declarations = {
            "<metadata><meta name='cover' content='picture'/></metadata>",
            "<guide><reference type='cover' href='title.xhtml'/></guide>",
            "<metadata><meta name='cover' content='title.xhtml'/></metadata>",
            "<metadata><meta name='cover' content='https://invalid.example/a.jpg'/></metadata>",
            ""
        };
        for(String declaration : declarations) {
            File f=File.createTempFile("cover-test", ".epub");
            try {
                try(ZipOutputStream z=new ZipOutputStream(new FileOutputStream(f))) {
                    entry(z,"META-INF/container.xml","<container><rootfile full-path='OPS/book.opf'/></container>");
                    entry(z,"OPS/book.opf","<package>"+declaration+"<manifest><item id='picture' href='images/art%20one.jpg'/><item id='a' href='title.xhtml'/></manifest><spine><itemref idref='a'/></spine></package>");
                    entry(z,"OPS/title.xhtml","<html><body><img src='missing.jpg'/><img src='https://invalid.example/x.jpg'/><svg><image xlink:href='wrap.svg'/></svg></body></html>");
                    entry(z,"OPS/wrap.svg","<svg><image xlink:href='images/art%20one.jpg'/></svg>");
                    entry(z,"OPS/images/art one.jpg","image fixture");
                }
                assertEquals("OPS/images/art one.jpg",EpubReader.findCover(f));
            } finally { f.delete(); }
        }
    }
    @Test public void keepsIllustrationsAndTocButRemovesActiveContent() throws Exception {
        File f=File.createTempFile("illustrated", ".epub");
        try {
            try(ZipOutputStream z=new ZipOutputStream(new FileOutputStream(f))) {
                entry(z,"META-INF/container.xml","<container><rootfile full-path='OPS/book.opf'/></container>");
                entry(z,"OPS/book.opf","<package xmlns:dc='http://purl.org/dc/elements/1.1/'><metadata><dc:title>Ilustrado</dc:title><dc:creator>Autor</dc:creator></metadata><manifest><item id='a' href='a.xhtml' media-type='application/xhtml+xml'/><item id='b' href='b.xhtml' media-type='application/xhtml+xml'/><item id='n' href='nav.xhtml' properties='nav' media-type='application/xhtml+xml'/><item id='cover' href='images/cover.jpg' properties='cover-image'/></manifest><spine><itemref idref='a'/><itemref idref='b'/></spine></package>");
                entry(z,"OPS/images/cover.jpg","image fixture");
                entry(z,"OPS/nav.xhtml","<nav epub:type='toc'><a href='a.xhtml#inicio'>Capítulo del índice</a><a href='b.xhtml'>Lámina</a></nav>");
                entry(z,"OPS/a.xhtml","<html><body><h1 id='inicio'>Texto</h1><p onclick='evil()'>Hola <em>mundo</em>.</p><img src='images/a%20b.png' onerror='evil()'><img src='https://evil.example/a.jpg'><script>evil()</script><iframe src='file:///secret'></iframe><svg><image xlink:href='images/two.jpg'/></svg></body></html>");
                entry(z,"OPS/b.xhtml","<html><body><img src='images/full.jpg'></body></html>");
            }
            EpubReader.Book book=EpubReader.open(f);
            assertEquals("Autor",book.author);assertEquals("OPS/images/cover.jpg",book.cover);
            assertEquals(2,book.chapters.size());assertTrue(book.chapters.get(1).chunks.isEmpty());
            String html=book.chapters.get(0).html;
            assertTrue(html.contains("https://epub.local/asset/OPS/images/a%20b.png"));
            assertTrue(html.contains("OPS/images/two.jpg"));assertTrue(html.contains("<em>"));
            assertTrue(html.contains("data-loc="));assertTrue(html.contains("data-anchor=\"inicio\""));
            assertFalse(html.contains("evil"));assertFalse(html.contains("iframe"));assertFalse(html.contains("onerror"));
            assertEquals("Capítulo del índice",book.toc.get(0).title);assertEquals("inicio",book.toc.get(0).anchor);
            assertEquals(1,book.toc.get(1).chapter);
        }finally{f.delete();}
    }
}
