package com.artemyasnik.parser;

import com.artemyasnik.model.*;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

/**
 * Разбирает SVG-план по соглашению data-* атрибутов (см. FORMAT_SPEC.md):
 * <ul>
 *     <li>корневой &lt;svg data-scale="0.02" data-floor="1"&gt;</li>
 *     <li>элементы с data-type = room | corridor | door | exit | stairs</li>
 * </ul>
 * Поддерживаемые теги геометрии: rect, circle, ellipse, line, polyline,
 * polygon, path (прямые сегменты).
 */
public class SvgFloorPlanParser {

    public FloorPlan parse(File file) throws IOException, ParserConfigurationException, SAXException {
        try (InputStream is = new java.io.FileInputStream(file)) {
            return parse(is);
        }
    }

    public FloorPlan parse(InputStream input) throws ParserConfigurationException, IOException, SAXException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        // защита от XXE
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(input);

        Element root = doc.getDocumentElement();
        double scale = parseDouble(root.getAttribute("data-scale"), 1.0);
        int floorNumber = (int) parseDouble(root.getAttribute("data-floor"), 1.0);

        FloorPlan floorPlan = new FloorPlan(floorNumber, scale);

        NodeList all = root.getElementsByTagName("*");
        // Сначала комнаты и коридоры (узлы), затем связи — чтобы можно было
        // валидировать data-connects по уже собранным узлам.
        List<Element> nodeElements = new ArrayList<>();
        List<Element> connectorElements = new ArrayList<>();

        for (int i = 0; i < all.getLength(); i++) {
            Node n = all.item(i);
            if (!(n instanceof Element el)) continue;
            String type = el.getAttribute("data-type");
            if (type == null || type.isBlank()) continue;
            FloorElementType t = FloorElementType.fromAttribute(type);
            if (t == FloorElementType.ROOM || t == FloorElementType.CORRIDOR) {
                nodeElements.add(el);
            } else {
                connectorElements.add(el);
            }
        }

        for (Element el : nodeElements) {
            addNode(floorPlan, el, scale);
        }
        for (Element el : connectorElements) {
            addConnector(floorPlan, el);
        }

        return floorPlan;
    }

    // ---------- узлы: комнаты и коридоры ----------

    private void addNode(FloorPlan plan, Element el, double scale) {
        String type = el.getAttribute("data-type");
        FloorElementType t = FloorElementType.fromAttribute(type);
        String id = requireAttr(el, "data-id");
        List<Point2D> geometry = extractGeometry(el);
        Map<String, String> raw = collectDataAttributes(el);
        BoundingBox bbox = BoundingBox.of(geometry);

        if (t == FloorElementType.ROOM) {
            int capacity = (int) parseDouble(el.getAttribute("data-capacity"), 0);
            double area = el.hasAttribute("data-area")
                    ? parseDouble(el.getAttribute("data-area"), 0)
                    : bbox.width() * bbox.height() * scale * scale;
            String name = el.hasAttribute("data-name") ? el.getAttribute("data-name") : id;
            plan.addRoom(new Room(id, geometry, raw, capacity, area, name));
        } else { // CORRIDOR
            int capacity = (int) parseDouble(el.getAttribute("data-capacity"), 0);
            double width = el.hasAttribute("data-width")
                    ? parseDouble(el.getAttribute("data-width"), 0)
                    : bbox.shorterSide() * scale;
            plan.addCorridor(new Corridor(id, geometry, raw, capacity, width));
        }
    }

    // ---------- связи: двери, выходы, лестницы ----------

    private void addConnector(FloorPlan plan, Element el) {
        String type = el.getAttribute("data-type");
        FloorElementType t = FloorElementType.fromAttribute(type);
        String id = requireAttr(el, "data-id");
        List<Point2D> geometry = extractGeometry(el);
        Map<String, String> raw = collectDataAttributes(el);
        BoundingBox bbox = BoundingBox.of(geometry);

        String connectsRaw = requireAttr(el, "data-connects");
        List<String> connects = Arrays.stream(connectsRaw.split(","))
                .map(String::trim)
                .toList();

        double throughput = parseDouble(el.getAttribute("data-throughput"), 1.0);
        Double width = el.hasAttribute("data-width")
                ? parseDouble(el.getAttribute("data-width"), 0)
                : (bbox.width() > 0 || bbox.height() > 0 ? bbox.shorterSide() * plan.getScaleMetersPerUnit() : null);

        switch (t) {
            case DOOR -> plan.addDoor(new Door(id, geometry, raw, connects, throughput, width));
            case EXIT -> plan.addExit(new EmergencyExit(id, geometry, raw, connects, throughput, width));
            case STAIRS -> {
                int[] floors = parseStairFloors(connects);
                String direction = el.hasAttribute("data-direction") ? el.getAttribute("data-direction") : "both";
                plan.addStairs(new Stairs(id, geometry, raw, connects, throughput, width,
                        floors[0], floors[1], direction));
            }
            default -> throw new IllegalStateException("Неожиданный тип связи: " + t);
        }
    }

    private int[] parseStairFloors(List<String> connects) {
        // ожидается формат "baseId@floor", например "C1@1"
        int[] result = new int[]{1, 2};
        for (int i = 0; i < Math.min(2, connects.size()); i++) {
            String part = connects.get(i);
            int at = part.indexOf('@');
            if (at >= 0) {
                result[i] = Integer.parseInt(part.substring(at + 1).trim());
            }
        }
        return result;
    }

    // ---------- геометрия тегов ----------

    private List<Point2D> extractGeometry(Element el) {
        String tag = el.getTagName().toLowerCase();
        return switch (tag) {
            case "rect" -> rectPoints(el);
            case "circle" -> circlePoints(el);
            case "ellipse" -> ellipsePoints(el);
            case "line" -> linePoints(el);
            case "polyline", "polygon" -> polyPoints(el);
            case "path" -> SvgPathParser.parse(el.getAttribute("d"));
            default -> throw new IllegalArgumentException(
                    "Тег <" + tag + "> не поддерживается для элементов плана (id=" + el.getAttribute("data-id") + ")");
        };
    }

    private List<Point2D> rectPoints(Element el) {
        double x = parseDouble(el.getAttribute("x"), 0);
        double y = parseDouble(el.getAttribute("y"), 0);
        double w = parseDouble(el.getAttribute("width"), 0);
        double h = parseDouble(el.getAttribute("height"), 0);
        return List.of(
                new Point2D(x, y), new Point2D(x + w, y),
                new Point2D(x + w, y + h), new Point2D(x, y + h));
    }

    private List<Point2D> circlePoints(Element el) {
        double cx = parseDouble(el.getAttribute("cx"), 0);
        double cy = parseDouble(el.getAttribute("cy"), 0);
        double r = parseDouble(el.getAttribute("r"), 0);
        return List.of(new Point2D(cx - r, cy - r), new Point2D(cx + r, cy + r));
    }

    private List<Point2D> ellipsePoints(Element el) {
        double cx = parseDouble(el.getAttribute("cx"), 0);
        double cy = parseDouble(el.getAttribute("cy"), 0);
        double rx = parseDouble(el.getAttribute("rx"), 0);
        double ry = parseDouble(el.getAttribute("ry"), 0);
        return List.of(new Point2D(cx - rx, cy - ry), new Point2D(cx + rx, cy + ry));
    }

    private List<Point2D> linePoints(Element el) {
        double x1 = parseDouble(el.getAttribute("x1"), 0);
        double y1 = parseDouble(el.getAttribute("y1"), 0);
        double x2 = parseDouble(el.getAttribute("x2"), 0);
        double y2 = parseDouble(el.getAttribute("y2"), 0);
        return List.of(new Point2D(x1, y1), new Point2D(x2, y2));
    }

    private List<Point2D> polyPoints(Element el) {
        String pointsAttr = el.getAttribute("points").trim();
        List<Point2D> pts = new ArrayList<>();
        if (pointsAttr.isEmpty()) return pts;
        String[] pairs = pointsAttr.split("\\s+");
        for (String pair : pairs) {
            String[] xy = pair.split(",");
            if (xy.length == 2) {
                pts.add(new Point2D(Double.parseDouble(xy[0]), Double.parseDouble(xy[1])));
            }
        }
        return pts;
    }

    // ---------- утилиты ----------

    private Map<String, String> collectDataAttributes(Element el) {
        Map<String, String> map = new HashMap<>();
        var attrs = el.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Node a = attrs.item(i);
            if (a.getNodeName().startsWith("data-")) {
                map.put(a.getNodeName(), a.getNodeValue());
            }
        }
        return map;
    }

    private String requireAttr(Element el, String name) {
        if (!el.hasAttribute(name) || el.getAttribute(name).isBlank()) {
            throw new IllegalArgumentException(
                    "Отсутствует обязательный атрибут " + name + " у элемента <" + el.getTagName() + ">");
        }
        return el.getAttribute(name);
    }

    private double parseDouble(String s, double def) {
        if (s == null || s.isBlank()) return def;
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}