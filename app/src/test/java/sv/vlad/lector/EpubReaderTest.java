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
}
