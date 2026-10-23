package com.artemyasnik.parser;

import com.artemyasnik.model.Point2D;
import org.apache.batik.parser.AWTPathProducer;
import org.apache.batik.parser.PathParser;

import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.util.ArrayList;
import java.util.List;

/** Разбирает полный синтаксис SVG path через Batik, включая кривые и дуги. */
final class SvgPathParser {
    private static final int MAX_POINTS = 50_000;

    private SvgPathParser() { }

    static List<Point2D> parse(String d) {
        return flatten(shape(d), new AffineTransform());
    }

    static Shape shape(String d) {
        if (d == null || d.isBlank()) {
            throw new IllegalArgumentException("Пустой SVG path: требуется атрибут d");
        }
        try {
            PathParser parser = new PathParser();
            AWTPathProducer producer = new AWTPathProducer();
            producer.setWindingRule(Path2D.WIND_NON_ZERO);
            parser.setPathHandler(producer);
            parser.parse(d);
            return producer.getShape();
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Некорректный SVG path: " + ex.getMessage(), ex);
        }
    }

    /** Ломаная с допуском 0.1 единицы SVG в итоговой системе координат. */
    static List<Point2D> flatten(Shape shape, AffineTransform transform) {
        List<Point2D> points = new ArrayList<>();
        PathIterator iterator = shape.getPathIterator(transform, 0.1);
        double[] coordinates = new double[6];
        while (!iterator.isDone()) {
            int segment = iterator.currentSegment(coordinates);
            if (segment != PathIterator.SEG_CLOSE) {
                if (!Double.isFinite(coordinates[0]) || !Double.isFinite(coordinates[1])) {
                    throw new IllegalArgumentException("Геометрия содержит бесконечные или неопределённые координаты");
                }
                points.add(new Point2D(coordinates[0], coordinates[1]));
                if (points.size() > MAX_POINTS) {
                    throw new IllegalArgumentException("Слишком сложная геометрия: более " + MAX_POINTS + " точек");
                }
            }
            iterator.next();
        }
        if (points.isEmpty()) throw new IllegalArgumentException("Пустая геометрия элемента плана");
        return points;
    }
}
