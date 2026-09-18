package com.artemyasnik.parser;

import com.artemyasnik.model.Point2D;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Разбирает атрибут "d" у &lt;path&gt;. Поддерживаются только прямые сегменты:
 * M/m, L/l, H/h, V/v, Z/z — этого достаточно для планов, нарисованных
 * прямоугольными/многоугольными контурами. Кривые (C, S, Q, T, A) не
 * поддерживаются: их контрольные точки попадут в контур как есть, что исказит
 * bbox — для таких путей лучше рисовать план прямыми линиями или задавать
 * data-area/data-width вручную.
 */
final class SvgPathParser {

    private static final Pattern TOKEN = Pattern.compile("([MLHVZmlhvz])|(-?\\d*\\.?\\d+(?:[eE][-+]?\\d+)?)");

    private SvgPathParser() {
    }

    static List<Point2D> parse(String d) {
        List<Point2D> points = new ArrayList<>();
        if (d == null || d.isBlank()) {
            return points;
        }
        Matcher m = TOKEN.matcher(d);
        char command = 'M';
        double curX = 0, curY = 0;
        double startX = 0, startY = 0;
        List<Double> nums = new ArrayList<>();

        while (m.find()) {
            String cmdTok = m.group(1);
            String numTok = m.group(2);
            if (cmdTok != null) {
                flushNumbers(nums, command, points, new double[]{curX, curY, startX, startY});
                command = cmdTok.charAt(0);
                nums.clear();
                if (command == 'Z' || command == 'z') {
                    if (!points.isEmpty()) {
                        points.add(new Point2D(startX, startY));
                    }
                    curX = startX;
                    curY = startY;
                }
                continue;
            }
            nums.add(Double.parseDouble(numTok));
            int needed = switch (Character.toUpperCase(command)) {
                case 'M', 'L' -> 2;
                case 'H', 'V' -> 1;
                default -> 0;
            };
            if (nums.size() == needed) {
                boolean relative = Character.isLowerCase(command);
                switch (Character.toUpperCase(command)) {
                    case 'M', 'L' -> {
                        double x = relative ? curX + nums.get(0) : nums.get(0);
                        double y = relative ? curY + nums.get(1) : nums.get(1);
                        points.add(new Point2D(x, y));
                        curX = x;
                        curY = y;
                        if (command == 'M' || command == 'm') {
                            startX = x;
                            startY = y;
                            // последующие пары после M без смены команды трактуются как L
                            command = relative ? 'l' : 'L';
                        }
                    }
                    case 'H' -> {
                        double x = relative ? curX + nums.get(0) : nums.get(0);
                        points.add(new Point2D(x, curY));
                        curX = x;
                    }
                    case 'V' -> {
                        double y = relative ? curY + nums.get(0) : nums.get(0);
                        points.add(new Point2D(curX, y));
                        curY = y;
                    }
                    default -> {
                    }
                }
                nums.clear();
            }
        }
        return points;
    }

    private static void flushNumbers(List<Double> nums, char command, List<Point2D> points, double[] state) {
        // числа обрабатываются сразу по мере накопления нужного количества (см. цикл выше),
        // этот метод оставлен для точек расширения (например, поддержки кривых в будущем).
    }
}