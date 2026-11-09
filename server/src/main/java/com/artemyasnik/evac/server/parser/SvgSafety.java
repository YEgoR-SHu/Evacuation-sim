package com.artemyasnik.evac.server.parser;

import org.w3c.dom.*;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Shared, offline SVG boundary for storage, rendering and calculation. */
public final class SvgSafety {
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final String SVG_NS = "http://www.w3.org/2000/svg";
    private static final Set<String> TAGS = Set.of("svg", "g", "defs", "symbol", "use", "clipPath", "mask",
            "rect", "circle", "ellipse", "line", "polyline", "polygon", "path", "text", "tspan",
            "title", "desc", "style", "linearGradient", "radialGradient", "stop", "pattern");
    private static final Pattern URL = Pattern.compile("url\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PRESENTATION = Pattern.compile("(?:fill(?:-opacity|-rule)?|stroke(?:-width|-opacity|-linecap|-linejoin|-miterlimit|-dasharray|-dashoffset)?|opacity|font(?:-family|-size|-weight|-style)?|text-anchor|dominant-baseline|display|visibility|vector-effect|paint-order|clip-path|mask)", Pattern.CASE_INSENSITIVE);

    private SvgSafety() { }

    public static Document read(String svg) {
        if (svg == null || svg.isBlank()) throw new IllegalArgumentException("SVG-содержимое обязательно");
        if (svg.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException("SVG слишком большой: максимум 2 МиБ");
        }
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler() {
                @Override public void error(SAXParseException e) throws SAXException { throw e; }
                @Override public void fatalError(SAXParseException e) throws SAXException { throw e; }
            });
            var document = builder.parse(new InputSource(new StringReader(svg)));
            var root = document.getDocumentElement();
            if (!"svg".equals(root.getLocalName()) || (root.getNamespaceURI() != null && !SVG_NS.equals(root.getNamespaceURI()))) {
                throw new IllegalArgumentException("Корневой элемент должен быть SVG");
            }
            clean(root);
            if (root.hasAttribute("transform")) {
                var group = document.createElementNS(SVG_NS, "g");
                group.setAttribute("transform", root.getAttribute("transform"));
                root.removeAttribute("transform");
                while (root.hasChildNodes()) group.appendChild(root.getFirstChild());
                root.appendChild(group);
            }
            for (Node node = document.getFirstChild(); node != null;) {
                Node next = node.getNextSibling();
                if (node.getNodeType() == Node.PROCESSING_INSTRUCTION_NODE) document.removeChild(node);
                node = next;
            }
            if (!root.hasAttribute("xmlns")) root.setAttribute("xmlns", SVG_NS);
            return document;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Некорректный SVG: " + e.getMessage(), e);
        }
    }

    public static String sanitize(String svg) { return serialize(read(svg)); }

    public static String serialize(Document document) {
        try {
            var factory = TransformerFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            var transformer = factory.newTransformer();
            transformer.setOutputProperty("omit-xml-declaration", "yes");
            var out = new StringWriter();
            transformer.transform(new DOMSource(document), new StreamResult(out));
            return out.toString();
        } catch (Exception e) { throw new IllegalArgumentException("Не удалось обработать SVG", e); }
    }

    private static void clean(Element element) {
        var attrs = element.getAttributes();
        for (int i = attrs.getLength() - 1; i >= 0; i--) {
            var attr = attrs.item(i);
            String name = attr.getNodeName().toLowerCase(Locale.ROOT);
            String local = attr.getLocalName() == null ? name : attr.getLocalName().toLowerCase(Locale.ROOT);
            String value = attr.getNodeValue().trim();
            if (local.startsWith("on") || name.equals("xml:base") || local.equals("src")
                    || (local.equals("href") && !value.startsWith("#")) || !safeCss(value)) {
                element.removeAttributeNode((Attr) attr);
            } else if (local.equals("style")) {
                element.setAttribute("style", declarations(value));
            }
        }
        if ("style".equals(element.getLocalName())) {
            String css = element.getTextContent();
            if (!safeCss(css) || css.contains("@") || css.contains("/*")) element.setTextContent("");
            else {
                var rules = Pattern.compile("([^{}]+)\\{([^{}]*)}").matcher(css);
                StringBuilder safe = new StringBuilder();
                while (rules.find()) safe.append(rules.group(1)).append('{').append(declarations(rules.group(2))).append('}');
                element.setTextContent(safe.toString());
            }
        }
        for (Node node = element.getFirstChild(); node != null;) {
            Node next = node.getNextSibling();
            if (node instanceof Element child) {
                if (!TAGS.contains(child.getLocalName()) || (child.getNamespaceURI() != null && !SVG_NS.equals(child.getNamespaceURI()))) {
                    element.removeChild(child);
                } else clean(child);
            } else if (node.getNodeType() == Node.PROCESSING_INSTRUCTION_NODE) element.removeChild(node);
            node = next;
        }
    }

    private static boolean safeCss(String value) {
        String compact = value.toLowerCase(Locale.ROOT).replaceAll("\\s", "");
        if (compact.contains("@import") || compact.contains("javascript:") || compact.contains("expression(")
                || compact.contains("-moz-binding") || compact.contains("behavior:") || compact.contains("\\")) return false;
        var matcher = URL.matcher(value);
        while (matcher.find()) {
            String reference = matcher.group(1).trim().replace("\"", "").replace("'", "");
            if (!reference.matches("#[A-Za-z_][A-Za-z0-9_.:-]*")) return false;
        }
        return true;
    }

    private static String declarations(String css) {
        StringBuilder safe = new StringBuilder();
        for (String declaration : css.split(";")) {
            int colon = declaration.indexOf(':');
            if (colon < 0) continue;
            String property = declaration.substring(0, colon).trim();
            String value = declaration.substring(colon + 1).trim();
            if (property.equalsIgnoreCase("transform") || property.equalsIgnoreCase("transform-origin")) {
                throw new IllegalArgumentException("CSS transform не поддерживается: используйте SVG-атрибут transform");
            }
            if (PRESENTATION.matcher(property).matches() && safeCss(value)) safe.append(property).append(':').append(value).append(';');
        }
        return safe.toString();
    }
}
