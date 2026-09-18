# floorplan-parser

Java-парсер SVG-планов зданий (в специальном формате разметки, см.
`format_spec.md`) для дальнейшего моделирования эвакуации.

Зависимостей нет — только стандартный JDK (`javax.xml`, DOM). Собирается
`javac`, Maven/Gradle не требуется.

## Сборка и запуск

```bash
cd floorplan-parser
mkdir -p out
javac -d out $(find src -name "*.java")
java -cp out com.artemyasnik.Main examples/example.svg
```

## Структура

```
model/    — Room, Corridor, Door, EmergencyExit, Stairs, FloorPlan, Building
parser/   — SvgFloorPlanParser (DOM), SvgPathParser (d="...")
graph/    — EvacGraph, EvacGraphNode, EvacGraphEdge, EvacGraphBuilder
demo/     — Main — печатает сводку по плану и граф эвакуации
examples/ — example.svg
```

`SvgFloorPlanParser.parse(file)` → `FloorPlan` (комнаты, коридоры, двери,
выходы, лестницы одного этажа).

`EvacGraphBuilder.build(plan)` → `EvacGraph` — граф с узлами (комнаты,
коридоры, псевдо-узел `OUTSIDE`) и рёбрами (двери/выходы/лестницы с
пропускной способностью, чел/с), готовый для дальнейшего расчёта потока
эвакуации (например, max-flow / симуляция по времени).

## Что дальше

- Сборка `Building` из нескольких этажей и связывание через `Stairs`.
- Алгоритм расчёта времени эвакуации по графу (например, модификация
  Форда-Фалкерсона с учётом времени, или посекундная симуляция потока).
- Валидатор SVG (проверка, что все `data-connects` ссылаются на
  существующие id, что у каждой комнаты есть путь до `OUTSIDE`, и т.д.).