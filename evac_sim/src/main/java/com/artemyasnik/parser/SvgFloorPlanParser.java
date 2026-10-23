package com.artemyasnik.parser;

import com.artemyasnik.model.*;
import org.apache.batik.parser.AWTTransformProducer;
import org.apache.batik.parser.DefaultPointsHandler;
import org.apache.batik.parser.PointsParser;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/** SVG-план с data-* разметкой; Batik разбирает пути, точки и transform. */
public class SvgFloorPlanParser {
    public FloorPlan parse(File file) throws IOException, ParserConfigurationException, SAXException {
        try (InputStream input = new java.io.FileInputStream(file)) {
            return parse(input);
        }
    }

    public FloorPlan parse(InputStream input) throws ParserConfigurationException, IOException, SAXException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
            @Override public void error(org.xml.sax.SAXParseException ex) throws SAXException { throw ex; }
            @Override public void fatalError(org.xml.sax.SAXParseException ex) throws SAXException { throw ex; }
        });
        Document document = builder.parse(input);
        Element root = document.getDocumentElement();
        if (!"svg".equals(root.getLocalName())) {
            throw new IllegalArgumentException("Корневой элемент плана должен быть <svg>");
        }
        String namespace = root.getNamespaceURI();
        if (namespace != null && !"http://www.w3.org/2000/svg".equals(namespace)) {
            throw new IllegalArgumentException("Некорректное пространство имён SVG");
        }
        double scale = positive(number(root, "data-scale", 1.0), "data-scale");
        int floorNumber = integer(root, "data-floor", 1, false);
        FloorPlan plan = new FloorPlan(floorNumber, scale);

        List<Element> nodeElements = new ArrayList<>();
        List<Element> connectorElements = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        NodeList all = root.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            if (!(all.item(i) instanceof Element element)) continue;
            String type = element.getAttribute("data-type");
            if (type.isBlank()) continue;
            FloorElementType parsedType = FloorElementType.fromAttribute(type);
            String id = requireAttr(element, "data-id");
            if (EmergencyExit.OUTSIDE_NODE_ID.equals(id) || id.contains("@") || id.contains(",")) {
                throw new IllegalArgumentException("Недопустимый data-id '" + id + "': OUTSIDE, @ и запятая зарезервированы");
            }
            if (!ids.add(id)) throw new IllegalArgumentException("Повторяющийся data-id '" + id + "'");
            if (parsedType == FloorElementType.ROOM || parsedType == FloorElementType.CORRIDOR) {
                nodeElements.add(element);
            } else {
                connectorElements.add(element);
            }
        }
        for (Element element : nodeElements) addNode(plan, element);
        for (Element element : connectorElements) addConnector(plan, element);
        return plan;
    }

    private void addNode(FloorPlan plan, Element element) {
        FloorElementType type = FloorElementType.fromAttribute(element.getAttribute("data-type"));
        String id = requireAttr(element, "data-id");
        List<Point2D> geometry = extractGeometry(element);
        Map<String, String> attributes = collectDataAttributes(element);
        BoundingBox box = BoundingBox.of(geometry);
        int capacity = integer(element, "data-capacity", null, true);
        double scale = plan.getScaleMetersPerUnit();
        if (type == FloorElementType.ROOM) {
            // Для совместимости площадь по умолчанию остаётся площадью bbox.
            double area = positive(number(element, "data-area", box.width() * box.height() * scale * scale),
                    "data-area комнаты '" + id + "'");
            String name = element.hasAttribute("data-name") ? element.getAttribute("data-name") : id;
            plan.addRoom(new Room(id, geometry, attributes, capacity, area, name));
        } else {
            double width = positive(number(element, "data-width", box.shorterSide() * scale),
                    "data-width коридора '" + id + "'");
            plan.addCorridor(new Corridor(id, geometry, attributes, capacity, width));
        }
    }

    private void addConnector(FloorPlan plan, Element element) {
        FloorElementType type = FloorElementType.fromAttribute(element.getAttribute("data-type"));
        String id = requireAttr(element, "data-id");
        List<Point2D> geometry = extractGeometry(element);
        Map<String, String> attributes = collectDataAttributes(element);
        String[] parts = requireAttr(element, "data-connects").split(",", -1);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException("Связь '" + id + "': data-connects должен содержать ровно два id через запятую");
        }
        List<String> connects = List.of(parts[0].trim(), parts[1].trim());
        if (connects.get(0).equals(connects.get(1))) {
            throw new IllegalArgumentException("Связь '" + id + "': data-connects должен соединять разные узлы");
        }
        double throughput = positive(number(element, "data-throughput", 1.0), "data-throughput связи '" + id + "'");
        double width = positive(number(element, "data-width", widthFromGeometry(geometry, plan.getScaleMetersPerUnit())),
                "data-width связи '" + id + "'");
        if (type == FloorElementType.STAIRS) {
            int[] floors = parseStairFloors(id, connects);
            String direction = element.hasAttribute("data-direction") ? element.getAttribute("data-direction").trim() : "both";
            if (!Set.of("up", "down", "both").contains(direction)) {
                throw new IllegalArgumentException("Лестница '" + id + "': data-direction должен быть up, down или both");
            }
            for (int i = 0; i < 2; i++) {
                String reference = connects.get(i);
                if (floors[i] == plan.getFloorNumber()) {
                    requireNode(plan, id, reference.substring(0, reference.lastIndexOf('@')));
                }
            }
            if (floors[0] != plan.getFloorNumber() && floors[1] != plan.getFloorNumber()) {
                throw new IllegalArgumentException("Лестница '" + id + "' не соединена с текущим этажом");
            }
            plan.addStairs(new Stairs(id, geometry, attributes, connects, throughput, width,
                    floors[0], floors[1], direction));
        } else {
            boolean outside = connects.contains(EmergencyExit.OUTSIDE_NODE_ID);
            if ((type == FloorElementType.EXIT) != outside) {
                throw new IllegalArgumentException("Связь '" + id + "': только выход должен соединяться с OUTSIDE");
            }
            for (String reference : connects) {
                if (!EmergencyExit.OUTSIDE_NODE_ID.equals(reference)) requireNode(plan, id, reference);
            }
            if (type == FloorElementType.DOOR) {
                plan.addDoor(new Door(id, geometry, attributes, connects, throughput, width));
            } else {
                plan.addExit(new EmergencyExit(id, geometry, attributes, connects, throughput, width));
            }
        }
    }

    private void requireNode(FloorPlan plan, String connectorId, String reference) {
        if (!plan.getRooms().containsKey(reference) && !plan.getCorridors().containsKey(reference)) {
            throw new IllegalArgumentException("Связь '" + connectorId + "' ссылается на неизвестный узел '" + reference + "'");
        }
    }

    private double widthFromGeometry(List<Point2D> geometry, double scale) {
        if (geometry.size() == 2) {
            Point2D a = geometry.get(0), b = geometry.get(1);
            return Math.hypot(b.x() - a.x(), b.y() - a.y()) * scale;
        }
        return BoundingBox.of(geometry).shorterSide() * scale;
    }

    private int[] parseStairFloors(String id, List<String> connects) {
        int[] floors = new int[2];
        for (int i = 0; i < 2; i++) {
            String reference = connects.get(i);
            int at = reference.lastIndexOf('@');
            if (at <= 0 || reference.indexOf('@') != at || reference.substring(at + 1).isBlank()) {
                throw new IllegalArgumentException("Лестница '" + id + "': data-connects требует baseId@floor,baseId@floor");
            }
            try {
                floors[i] = Integer.parseInt(reference.substring(at + 1));
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Лестница '" + id + "': некорректный номер этажа в '" + reference + "'", ex);
            }
        }
        if (floors[0] == floors[1]) throw new IllegalArgumentException("Лестница '" + id + "' должна соединять разные этажи");
        return floors;
    }

    private List<Point2D> extractGeometry(Element element) {
        String tag = element.getLocalName();
        Shape shape = switch (tag) {
            case "rect" -> new Rectangle2D.Double(number(element, "x", 0.0), number(element, "y", 0.0),
                    positive(number(element, "width", 0.0), "width"), positive(number(element, "height", 0.0), "height"));
            case "circle" -> ellipse(element, true);
            case "ellipse" -> ellipse(element, false);
            case "line" -> new Line2D.Double(number(element, "x1", 0.0), number(element, "y1", 0.0),
                    number(element, "x2", 0.0), number(element, "y2", 0.0));
            case "polyline", "polygon" -> polygon(element, tag.equals("polygon"));
            case "path" -> SvgPathParser.shape(requireAttr(element, "d"));
            default -> throw new IllegalArgumentException("Тег <" + tag + "> не поддерживается для элементов плана");
        };
        return SvgPathParser.flatten(shape, inheritedTransform(element));
    }

    private Shape ellipse(Element element, boolean circle) {
        double x = number(element, "cx", 0.0), y = number(element, "cy", 0.0);
        double rx = positive(number(element, circle ? "r" : "rx", 0.0), circle ? "r" : "rx");
        double ry = circle ? rx : positive(number(element, "ry", 0.0), "ry");
        return new Ellipse2D.Double(x - rx, y - ry, 2 * rx, 2 * ry);
    }

    private Shape polygon(Element element, boolean closed) {
        Path2D.Double path = new Path2D.Double();
        int[] count = {0};
        PointsParser parser = new PointsParser();
        parser.setPointsHandler(new DefaultPointsHandler() {
            @Override public void point(float x, float y) {
                if (!Float.isFinite(x) || !Float.isFinite(y)) throw new IllegalArgumentException("Некорректные points");
                if (count[0]++ == 0) path.moveTo(x, y); else path.lineTo(x, y);
                if (count[0] > 50_000) throw new IllegalArgumentException("Слишком много points");
            }
        });
        try { parser.parse(requireAttr(element, "points")); }
        catch (RuntimeException ex) { throw new IllegalArgumentException("Некорректные SVG points: " + ex.getMessage(), ex); }
        if (count[0] < 2) throw new IllegalArgumentException("SVG points должен содержать минимум две точки");
        if (closed) path.closePath();
        return path;
    }

    private AffineTransform inheritedTransform(Element element) {
        List<Element> ancestors = new ArrayList<>();
        for (Node node = element; node instanceof Element ancestor; node = node.getParentNode()) ancestors.add(ancestor);
        Collections.reverse(ancestors);
        AffineTransform transform = new AffineTransform();
        for (int i = 0; i < ancestors.size(); i++) {
            Element ancestor = ancestors.get(i);
            if ((i > 0 && "svg".equals(ancestor.getLocalName()))
                    || Set.of("defs", "symbol", "clipPath", "mask").contains(ancestor.getLocalName())) {
                throw new IllegalArgumentException("Элементы модели не поддерживаются внутри <" + ancestor.getLocalName() + ">");
            }
            if (ancestor.getAttribute("style").matches("(?is).*\\btransform\\s*:.*")) {
                throw new IllegalArgumentException("CSS transform не поддерживается; используйте SVG-атрибут transform");
            }
            String specification = ancestor.getAttribute("transform");
            if (!specification.isBlank()) {
                try { transform.concatenate(AWTTransformProducer.createAffineTransform(specification)); }
                catch (RuntimeException ex) { throw new IllegalArgumentException("Некорректный SVG transform: " + specification, ex); }
            }
        }
        return transform;
    }

    private Map<String, String> collectDataAttributes(Element element) {
        Map<String, String> attributes = new HashMap<>();
        var raw = element.getAttributes();
        for (int i = 0; i < raw.getLength(); i++) {
            Node attribute = raw.item(i);
            if (attribute.getNodeName().startsWith("data-")) attributes.put(attribute.getNodeName(), attribute.getNodeValue());
        }
        return attributes;
    }

    private String requireAttr(Element element, String name) {
        String value = element.getAttribute(name).trim();
        if (value.isEmpty()) throw new IllegalArgumentException("Отсутствует обязательный атрибут " + name + " у <" + element.getTagName() + ">");
        return value;
    }

    private double number(Element element, String name, Double defaultValue) {
        if (!element.hasAttribute(name)) {
            if (defaultValue != null) return defaultValue;
            throw new IllegalArgumentException("Отсутствует обязательный атрибут " + name);
        }
        String value = element.getAttribute(name).trim();
        try {
            double parsed = Double.parseDouble(value);
            if (Double.isFinite(parsed)) return parsed;
        } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException("Атрибут " + name + " должен быть конечным числом, получено: '" + value + "'");
    }

    private int integer(Element element, String name, Integer defaultValue, boolean nonNegative) {
        double value = number(element, name, defaultValue == null ? null : defaultValue.doubleValue());
        if (value != Math.rint(value) || value > Integer.MAX_VALUE || value < (nonNegative ? 0 : Integer.MIN_VALUE)) {
            throw new IllegalArgumentException("Атрибут " + name + " должен быть " + (nonNegative ? "неотрицательным " : "") + "целым числом");
        }
        return (int) value;
    }

    private double positive(double value, String name) {
        if (!(value > 0) || !Double.isFinite(value)) throw new IllegalArgumentException("Атрибут " + name + " должен быть конечным числом > 0");
        return value;
    }
}
