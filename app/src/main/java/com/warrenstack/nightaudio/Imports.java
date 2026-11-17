package com.warrenstack.nightaudio;

import android.content.Context;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.text.Spanned;
import android.text.style.URLSpan;
import android.database.Cursor;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI;
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.parsers.DocumentBuilderFactory;

final class Imports {
    private static final int MAX_FILE = 64 * 1024 * 1024;
    private static final int MAX_TEXT = 8 * 1024 * 1024;
    private static final String REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    static LinkedHashSet<String> text(CharSequence text) {
        if (!(text instanceof Spanned styled)) return YouTubeLinks.extract(text);
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        URLSpan[] spans = styled.getSpans(0, styled.length(), URLSpan.class);
        java.util.Arrays.sort(spans, java.util.Comparator.comparingInt(styled::getSpanStart));
        int previous = 0;
        for (URLSpan span : spans) {
            int start = styled.getSpanStart(span);
            int end = styled.getSpanEnd(span);
            if (start < previous) continue;
            ids.addAll(YouTubeLinks.extract(styled.subSequence(previous, end)));
            ids.addAll(YouTubeLinks.extract(span.getURL()));
            previous = end;
        }
        ids.addAll(YouTubeLinks.extract(styled.subSequence(previous, styled.length())));
        return ids;
    }

    static LinkedHashSet<String> file(Context context, Uri uri) throws Exception {
        String type = context.getContentResolver().getType(uri);
        String filename = displayName(context, uri).toLowerCase(Locale.ROOT);
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("Unable to open the selected file.");
            boolean pdf = filename.endsWith(".pdf") || "application/pdf".equals(type);
            boolean office = filename.matches(".*\\.(xlsx|docx|pptx)$")
                    || (type != null && type.contains("openxmlformats-officedocument"));
            if (!pdf && !office) {
                boolean plain = filename.matches(".*\\.(txt|csv|tsv|md|log|url|html?)$")
                        || (type != null && type.startsWith("text/"));
                if (!plain) throw new IOException("Choose a text, Excel, Word, PowerPoint, or PDF file.");
                byte[] bytes = read(input, MAX_TEXT);
                String content = decode(bytes);
                if (filename.matches(".*\\.html?$") || "text/html".equals(type)) {
                    return text(android.text.Html.fromHtml(content, android.text.Html.FROM_HTML_MODE_LEGACY));
                }
                return YouTubeLinks.extract(content);
            }
            File temp = File.createTempFile("import-", ".document", context.getCacheDir());
            try {
                try (OutputStream out = new FileOutputStream(temp)) {
                    copy(input, out, MAX_FILE);
                }
                return pdf ? pdf(context, temp) : office(temp);
            } finally {
                temp.delete();
            }
        }
    }

    private static String displayName(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0);
        } catch (RuntimeException ignored) {}
        return uri.getLastPathSegment() == null ? "" : uri.getLastPathSegment();
    }

    private static LinkedHashSet<String> pdf(Context context, File file) throws Exception {
        PDFBoxResourceLoader.init(context.getApplicationContext());
        MemoryUsageSetting memory = MemoryUsageSetting.setupMixed(12 * 1024 * 1024, 192L * 1024 * 1024);
        memory.setTempDir(context.getCacheDir());
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        try (PDDocument doc = PDDocument.load(file, memory)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            for (int number = 1; number <= doc.getNumberOfPages(); number++) {
                stripper.setStartPage(number);
                stripper.setEndPage(number);
                ids.addAll(YouTubeLinks.extract(stripper.getText(doc)));
                for (var annotation : doc.getPage(number - 1).getAnnotations()) {
                    if (annotation instanceof PDAnnotationLink link && link.getAction() instanceof PDActionURI target) {
                        ids.addAll(YouTubeLinks.extract(target.getURI()));
                    }
                }
            }
        }
        return ids;
    }

    private static LinkedHashSet<String> office(File file) throws Exception {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        try (ZipFile zip = new ZipFile(file)) {
            if (zip.getEntry("xl/workbook.xml") != null) {
                workbook(zip, ids);
            } else if (zip.getEntry("word/document.xml") != null) {
                word(zip, ids, "word/document.xml");
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement().getName();
                    if (name.matches("word/(header\\d+|footer\\d+|footnotes|endnotes)\\.xml")) {
                        word(zip, ids, name);
                    }
                }
            } else if (zip.getEntry("ppt/presentation.xml") != null) {
                Element presentation = xml(zip, "ppt/presentation.xml");
                Map<String, String> slides = relationships(zip, "ppt/presentation.xml");
                for (Element slide : elements(presentation, "sldId")) {
                    String target = slides.get(ref(slide));
                    if (target != null) word(zip, ids, relative("ppt/presentation.xml", target));
                }
            } else {
                throw new IOException("This Office file could not be read.");
            }
        }
        return ids;
    }

    private static void workbook(ZipFile zip, LinkedHashSet<String> ids) throws Exception {
        Element workbook = xml(zip, "xl/workbook.xml");
        var sheets = relationships(zip, "xl/workbook.xml");
        ArrayList<String> shared = new ArrayList<>();
        for (Element item : elements(xml(zip, "xl/sharedStrings.xml"), "si")) {
            shared.add(joinText(item));
        }
        for (Element sheet : elements(workbook, "sheet")) {
            String target = sheets.get(ref(sheet));
            if (target == null) continue;
            String name = relative("xl/workbook.xml", target);
            Element root = xml(zip, name);
            Map<String, String> relationships = relationships(zip, name);
            Map<String, String> linksByCell = new HashMap<>();
            for (Element hyperlink : elements(root, "hyperlink")) {
                String link = relationships.get(ref(hyperlink));
                if (link == null) link = hyperlink.getAttribute("location");
                if (link != null) linksByCell.put(hyperlink.getAttribute("ref").split(":")[0], link);
            }
            for (Element cell : elements(root, "c")) {
                String data = firstText(cell, "v");
                if ("s".equals(cell.getAttribute("t")) && data.matches("\\d{1,8}")) {
                    int index = Integer.parseInt(data);
                    if (index < shared.size()) ids.addAll(YouTubeLinks.extract(shared.get(index)));
                } else if ("inlineStr".equals(cell.getAttribute("t"))) {
                    ids.addAll(YouTubeLinks.extract(joinText(cell)));
                } else {
                    ids.addAll(YouTubeLinks.extract(data));
                }
                ids.addAll(YouTubeLinks.extract(firstText(cell, "f")));
                ids.addAll(YouTubeLinks.extract(linksByCell.remove(cell.getAttribute("r"))));
            }
            for (Element hyperlink : elements(root, "hyperlink")) {
                ids.addAll(YouTubeLinks.extract(linksByCell.remove(hyperlink.getAttribute("ref").split(":")[0])));
            }
        }
    }

    private static void word(ZipFile zip, LinkedHashSet<String> ids, String part) throws Exception {
        Element root = xml(zip, part);
        Map<String, String> relationships = relationships(zip, part);
        for (Element paragraph : elements(root, "p")) {
            StringBuilder pending = new StringBuilder();
            paragraph(paragraph, relationships, ids, pending);
            ids.addAll(YouTubeLinks.extract(pending));
        }
    }

    private static void paragraph(Node node, Map<String, String> relationships,
                                  LinkedHashSet<String> ids, StringBuilder pending) {
        if (node instanceof Element element) {
            String local = element.getLocalName();
            String link = relationships.get(ref(element));
            if (link != null && (local.equals("hyperlink") || local.equals("hlinkClick"))) {
                ids.addAll(YouTubeLinks.extract(pending));
                pending.setLength(0);
                ids.addAll(YouTubeLinks.extract(link));
            }
            if (local.equals("t") || local.equals("instrText")) {
                pending.append(element.getTextContent());
                return;
            }
            if (local.equals("tab") || local.equals("br")) pending.append(' ');
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            paragraph(child, relationships, ids, pending);
        }
    }

    private static String ref(Element element) {
        String id = element.getAttributeNS(REL, "id");
        return id.isEmpty() ? element.getAttribute("r:id") : id;
    }

    private static Map<String, String> relationships(ZipFile zip, String part) throws Exception {
        int slash = part.lastIndexOf('/');
        String path = part.substring(0, slash + 1) + "_rels/" + part.substring(slash + 1) + ".rels";
        Map<String, String> targets = new HashMap<>();
        for (Element relation : elements(xml(zip, path), "Relationship")) {
            targets.put(relation.getAttribute("Id"), relation.getAttribute("Target"));
        }
        return targets;
    }

    private static String relative(String part, String target) {
        return java.net.URI.create("/" + part).resolve(target).normalize().getPath().replaceFirst("^/", "");
    }

    private static String joinText(Element part) {
        StringBuilder combined = new StringBuilder();
        for (Element text : elements(part, "t")) combined.append(text.getTextContent());
        return combined.toString();
    }

    private static String firstText(Element part, String name) {
        NodeList items = part.getElementsByTagNameNS("*", name);
        return items.getLength() > 0 ? items.item(0).getTextContent() : "";
    }

    private static ArrayList<Element> elements(Element start, String localName) {
        ArrayList<Element> result = new ArrayList<>();
        if (start != null) {
            NodeList nodes = start.getElementsByTagNameNS("*", localName);
            for (int i = 0; i < nodes.getLength(); i++) result.add((Element) nodes.item(i));
        }
        return result;
    }

    private static Element xml(ZipFile zip, String path) throws Exception {
        ZipEntry entry = zip.getEntry(path);
        if (entry == null) return null;
        byte[] bytes;
        try (InputStream in = zip.getInputStream(entry)) {
            bytes = read(in, MAX_TEXT);
        }
        if (decode(bytes).contains("<!DOCTYPE")) throw new IOException("Unexpected document type in Office file.");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setExpandEntityReferences(false);
        var builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
        return builder.parse(new ByteArrayInputStream(bytes)).getDocumentElement();
    }

    private static String decode(byte[] bytes) {
        if (bytes.length >= 2 && ((bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xfe)
                || (bytes[0] == (byte) 0xfe && bytes[1] == (byte) 0xff))) {
            return new String(bytes, StandardCharsets.UTF_16);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static byte[] read(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        copy(in, out, limit);
        return out.toByteArray();
    }

    private static void copy(InputStream in, OutputStream out, int limit) throws IOException {
        int total = 0;
        byte[] buffer = new byte[16384];
        int count;
        while ((count = in.read(buffer)) != -1) {
            total += count;
            if (total > limit) throw new IOException("The document is too large to import.");
            out.write(buffer, 0, count);
        }
    }

    private Imports() {}
}
