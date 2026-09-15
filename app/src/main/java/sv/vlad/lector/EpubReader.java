package sv.vlad.lector;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** Reads local EPUB spine order without executing scripts or fetching resources. */
public final class EpubReader {
    public static final class Chapter {
        public final String title;
        public final List<String> chunks;
        Chapter(String title, List<String> chunks) { this.title = title; this.chunks = chunks; }
    }
    public static final class Book {
        public final String title;
        public final List<Chapter> chapters;
        Book(String title, List<Chapter> chapters) { this.title = title; this.chapters = chapters; }
    }
    static final int ENTRY_LIMIT = 4 * 1024 * 1024;
    static final int TEXT_LIMIT = 16 * 1024 * 1024;
    private static byte[] read(ZipFile zip, String path) throws IOException {
        ZipEntry entry = zip.getEntry(path);
        if (entry == null) throw new IOException("Falta un archivo del EPUB: " + path);
        if (entry.getSize() > ENTRY_LIMIT) throw new IOException("El capítulo supera el límite de 4 MB.");
        try (InputStream in = zip.getInputStream(entry); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) != -1) {
                if (out.size() + n > ENTRY_LIMIT) throw new IOException("Contenido demasiado grande.");
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }
    private static Document xml(ZipFile zip, String path) throws IOException {
        return Jsoup.parse(new ByteArrayInputStream(read(zip, path)), null, "", Parser.xmlParser());
    }
    static String resolve(String opf, String href) throws IOException {
        try {
            URI base = new URI(null, null, "/" + opf, null);
            URI uri = base.resolve(href).normalize();
            if (uri.isAbsolute() || uri.getAuthority() != null || uri.getPath() == null || uri.getPath().contains(".."))
                throw new IOException("Referencia externa o inválida en el EPUB.");
            return uri.getPath().replaceFirst("^/", "");
        } catch (java.net.URISyntaxException | IllegalArgumentException e) {
            throw new IOException("Ruta inválida en el EPUB.", e);
        }
    }
    public static Book open(File file) throws IOException {
        try (ZipFile zip = new ZipFile(file)) {
            Document container = xml(zip, "META-INF/container.xml");
            Element root = container.selectFirst("rootfile");
            if (root == null) throw new IOException("No se encontró el índice del EPUB.");
            String opf = root.attr("full-path");
            Document packageDoc = xml(zip, opf);
            Element title = packageDoc.getElementsByTag("dc:title").first();
            Map<String, Element> manifest = new HashMap<>();
            for (Element item : packageDoc.select("manifest > item")) manifest.put(item.id(), item);
            List<Chapter> chapters = new ArrayList<>(); int total = 0;
            for (Element ref : packageDoc.select("spine > itemref")) {
                if ("no".equals(ref.attr("linear"))) continue;
                Element item = manifest.get(ref.attr("idref"));
                if (item == null) throw new IOException("Índice incompleto en el EPUB.");
                String media = item.attr("media-type");
                if (!media.contains("html")) continue;
                byte[] data = read(zip, resolve(opf, item.attr("href")));
                total += data.length;
                if (total > TEXT_LIMIT) throw new IOException("El texto del libro supera 16 MB.");
                Document html = Jsoup.parse(new ByteArrayInputStream(data), null, "");
                html.select("script, style, nav, [hidden]").remove();
                Element heading = html.selectFirst("h1,h2,h3");
                String chapterTitle = heading != null ? heading.text() : html.title();
                if (chapterTitle.trim().isEmpty()) chapterTitle = "Capítulo " + (chapters.size() + 1);
                // Preserve paragraph boundaries in speech and the text view.
                html.outputSettings().prettyPrint(false);
                html.select("p, div, h1, h2, h3, li, br").before("\n");
                String text = html.body().wholeText().replace('\u00a0', ' ');
                List<String> chunks = split(text);
                if (!chunks.isEmpty()) chapters.add(new Chapter(chapterTitle, chunks));
            }
            if (chapters.isEmpty()) throw new IOException("No se encontró texto legible. Puede ser un EPUB protegido o de imágenes.");
            return new Book(title == null ? "Libro sin título" : title.text(), chapters);
        } catch (ZipException e) { throw new IOException("El archivo no es un EPUB válido.", e); }
    }
    static List<String> split(String text) {
        List<String> chunks = new ArrayList<>();
        for (String paragraph : text.split("\\n+")) {
            String remaining = paragraph.replaceAll("\\s+", " ").trim();
            while (!remaining.isEmpty()) {
                int end = Math.min(700, remaining.length());
                if (end < remaining.length()) {
                    int sentence = Math.max(remaining.lastIndexOf(". ", end), remaining.lastIndexOf("? ", end));
                    int space = remaining.lastIndexOf(' ', end);
                    if (sentence > 150) end = sentence + 1;
                    else if (space > 0) end = space;
                    if (Character.isHighSurrogate(remaining.charAt(end - 1))) end--;
                }
                chunks.add(remaining.substring(0, end).trim());
                remaining = remaining.substring(end).trim();
            }
        }
        return chunks;
    }
}
