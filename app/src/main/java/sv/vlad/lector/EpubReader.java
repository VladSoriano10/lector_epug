package sv.vlad.lector;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** Reads local EPUB spine order without executing scripts or fetching resources. */
public final class EpubReader {
    public static final class Chapter {
        public final String title, path, html;
        public final List<String> chunks;
        Chapter(String title, String path, String html, List<String> chunks) {
            this.title = title; this.path = path; this.html = html; this.chunks = chunks;
        }
    }
    public static final class TocEntry {
        public final String title, anchor;
        public final int chapter;
        TocEntry(String title, int chapter, String anchor) { this.title = title; this.chapter = chapter; this.anchor = anchor; }
    }
    public static final class Book {
        public final String title, author, cover;
        public final List<Chapter> chapters;
        public final List<TocEntry> toc;
        Book(String title, String author, String cover, List<Chapter> chapters, List<TocEntry> toc) {
            this.title = title; this.author = author; this.cover = cover; this.chapters = chapters; this.toc = toc;
        }
    }
    static final int ENTRY_LIMIT = 4 * 1024 * 1024;
    static final int TEXT_LIMIT = 16 * 1024 * 1024;
    /** Bounded asset reads; ZIP contents never get extracted as filesystem paths. */
    public static byte[] asset(File file, String path) throws IOException {
        try (ZipFile zip = new ZipFile(file)) { return read(zip, path); }
    }
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
            if (uri.isAbsolute() || uri.getAuthority() != null || uri.getPath() == null ||
                    Arrays.asList(uri.getPath().split("/")).contains(".."))
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
            Element author = packageDoc.getElementsByTag("dc:creator").first();
            Map<String, Element> manifest = new HashMap<>();
            for (Element item : packageDoc.select("manifest > item")) manifest.put(item.id(), item);
            String cover = "";
            for (Element item : manifest.values()) if (Arrays.asList(item.attr("properties").split(" ")).contains("cover-image"))
                cover = resolve(opf, item.attr("href"));
            Element coverMeta = packageDoc.selectFirst("meta[name=cover]");
            if (cover.isEmpty() && coverMeta != null && manifest.containsKey(coverMeta.attr("content")))
                cover = resolve(opf, manifest.get(coverMeta.attr("content")).attr("href"));
            List<Chapter> chapters = new ArrayList<>(); int total = 0;
            for (Element ref : packageDoc.select("spine > itemref")) {
                if ("no".equals(ref.attr("linear"))) continue;
                Element item = manifest.get(ref.attr("idref"));
                if (item == null) throw new IOException("Índice incompleto en el EPUB.");
                String media = item.attr("media-type");
                if (!media.contains("html")) continue;
                String chapterPath = resolve(opf, item.attr("href"));
                byte[] data = read(zip, chapterPath);
                total += data.length;
                if (total > TEXT_LIMIT) throw new IOException("El texto del libro supera 16 MB.");
                Document html = Jsoup.parse(new ByteArrayInputStream(data), null, "");
                html.select("script, style, nav, iframe, object, form, [hidden]").remove();
                // Many EPUB illustrations use an SVG wrapper around a raster image.
                for (Element svg : html.select("svg")) {
                    Element image = svg.selectFirst("image");
                    if (image != null) svg.replaceWith(new Element("img").attr("src",
                        image.hasAttr("href") ? image.attr("href") : image.attr("xlink:href")));
                    else svg.remove();
                }
                Element heading = html.selectFirst("h1,h2,h3");
                String chapterTitle = heading != null ? heading.text() : html.title();
                if (chapterTitle.trim().isEmpty()) chapterTitle = "Capítulo " + (chapters.size() + 1);
                // Rebuild trusted markup, keeping illustrations and basic text formatting.
                for (Element img : html.select("img")) {
                    try { img.attr("src", "https://epub.local/asset/" + encodePath(resolve(chapterPath, img.attr("src")))); }
                    catch (IOException e) { img.remove(); }
                }
                Safelist allowed = new Safelist().addTags("p", "div", "span", "h1", "h2", "h3", "h4", "h5", "h6",
                    "em", "strong", "b", "i", "u", "s", "br", "hr", "blockquote", "ul", "ol", "li", "img",
                    "figure", "figcaption", "a", "sub", "sup", "table", "thead", "tbody", "tr", "th", "td")
                    .addAttributes(":all", "id").addAttributes("img", "src", "alt")
                    .addProtocols("img", "src", "https");
                Document clean = new Cleaner(allowed).clean(html);
                clean.outputSettings().prettyPrint(false);
                // Preserve original anchors under data-anchor; generated IDs cannot collide.
                for (Element el : clean.select("[id]")) { el.attr("data-anchor", el.id()); el.removeAttr("id"); }
                List<String> chunks = new ArrayList<>();
                mark(clean.body(), chunks, new int[]{0});
                if (!chunks.isEmpty() || !clean.select("img").isEmpty())
                    chapters.add(new Chapter(chapterTitle, chapterPath, clean.body().html(), chunks));
            }
            if (chapters.isEmpty()) throw new IOException("No se encontró contenido legible. Puede ser un EPUB protegido.");
            List<TocEntry> toc = new ArrayList<>();
            for (Element item : manifest.values()) {
                boolean nav = Arrays.asList(item.attr("properties").split(" ")).contains("nav");
                boolean ncx = "application/x-dtbncx+xml".equals(item.attr("media-type"));
                if (!nav && !ncx) continue;
                if (ncx && !toc.isEmpty()) continue;
                try {
                    String path = resolve(opf, item.attr("href")); Document index = xml(zip, path);
                    if (nav) {
                        Element tocNav = null;
                        for (Element n : index.select("nav")) if (n.attr("epub:type").contains("toc") || "doc-toc".equals(n.attr("role"))) tocNav = n;
                        if (tocNav == null) tocNav = index.selectFirst("nav");
                        if (tocNav != null) {
                            toc.clear();
                            for (Element a : tocNav.select("a[href]")) addToc(toc, chapters, path, a.attr("href"), a.text());
                        }
                    } else for (Element point : index.getElementsByTag("navPoint")) {
                        Element content = point.getElementsByTag("content").first();
                        Element label = point.getElementsByTag("text").first();
                        if (content != null && label != null) addToc(toc, chapters, path, content.attr("src"), label.text());
                    }
                } catch (IOException ignored) { /* malformed optional TOC: use spine fallback */ }
            }
            if (toc.isEmpty()) for (int n = 0; n < chapters.size(); n++) toc.add(new TocEntry(chapters.get(n).title, n, ""));
            return new Book(title == null ? "Libro sin título" : title.text(), author == null ? "" : author.text(), cover, chapters, toc);
        } catch (ZipException e) { throw new IOException("El archivo no es un EPUB válido.", e); }
    }
    public static String encodePath(String path) throws IOException {
        try { return new URI(null, null, "/" + path, null).getRawPath().substring(1); }
        catch (java.net.URISyntaxException e) { throw new IOException(e); }
    }
    private static void addToc(List<TocEntry> toc, List<Chapter> chapters, String path, String href, String label) throws IOException {
        String resolved = resolve(path, href);
        for (int n = 0; n < chapters.size(); n++) if (chapters.get(n).path.equals(resolved)) {
            String anchor = "";
            try { String fragment = new URI(href).getFragment(); if (fragment != null) anchor = fragment; }
            catch (java.net.URISyntaxException ignored) { }
            toc.add(new TocEntry(label, n, anchor)); break;
        }
    }
    private static void mark(Element parent, List<String> chunks, int[] location) {
        for (Node node : new ArrayList<>(parent.childNodes())) {
            if (node instanceof TextNode) {
                TextNode text = (TextNode) node;
                List<String> parts = split(text.getWholeText());
                for (String part : parts) {
                    Element span = new Element("span").attr("id", "c" + chunks.size())
                        .attr("data-chunk", String.valueOf(chunks.size())).attr("data-loc", String.valueOf(location[0]++));
                    span.text(part + " "); chunks.add(part); node.before(span);
                }
                if (!parts.isEmpty()) node.remove();
            } else if (node instanceof Element) {
                Element el = (Element) node;
                if (el.tagName().equals("img")) el.attr("data-loc", String.valueOf(location[0]++));
                else mark(el, chunks, location);
            }
        }
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
