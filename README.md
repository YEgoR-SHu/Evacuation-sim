# Evacuation-sim
2D-приложение на Java для симуляции эвакуации людей из здания при пожаре.

Загружает SVG-план (слои `walls` / `rooms` / `doors`), строит навигационный

граф, рассчитывает кратчайшие пути до выходов (A* _/ Dijkstra) и_

_пересчитывает их в реальном времени при установке очага возгорания._

_Дополнительно оценивает нагрузку на двери и предупреждает о заторах._

  

## Стек

  

- Java 17+

- JavaFX 21 (UI / 2D-рендеринг)

- Apache Batik (парсинг SVG)

- JUnit 5 (тесты)

- Maven

  

## Структура проекта

  

```

src/main/java/com/evacsim/

├── domain/

│   ├── physical/   # геометрия плана: точки, полигоны, стены, комнаты, двери

│   ├── graph/       # навигационный граф: узлы, рёбра, веса

│   ├── crowd/       # модель населения и пропускной способности

├── service/

│   ├── parsing/       # SvgImportService — Batik -> physical-модель

│   ├── graphbuilder/  # physical-модель -> NavigationGraph

│   ├── pathfinding/   # PathfindingStrategy: AStarStrategy

│   ├── crowd/         # CrowdAnalyticsService — расчёт, заторы

│   └── evacuation/    # EvacuationManager — фасад-оркестратор

├── ui/

│   ├── view/          # MainView, PlanCanvasRenderer (JavaFX Canvas)

│   ├── viewmodel/      # EvacuationViewModel (MVVM, ObservableMap/List)


||||
│   └── controller/     # PlanClickController — обработка кликов по плану
||||


├── config/

│   └── AppContext.java # ручная DI-сборка сервисов

└── util/                # GeometryMath, VectorUtils

```

  

## Запуск

  

```bash

mvn clean javafx:run

```

  

По умолчанию приложение загружает пример плана

`src/main/resources/plans/floor1.svg` (три помещения, два внутренних

дверных проёма и один аварийный выход).


## Тесты

  

```bash

mvn **test**

```

  

Покрыты: `AStarStrategy` (поиск пути, блокировка рёбер),

`FireSimulationService` (блокировка/восстановление весов),

`CrowdAnalyticsService` (расчёт нагрузки на двери и детект затора).

  

## Формат входного SVG

  

```xml

**<g** id**=**"walls"**>**

  **<line** x1**=**"50" y1**=**"50" x2**=**"950" y2**=**"50" **/>**

  ...

**</g>**

  

**<g** id**=**"rooms"**>**

  **<polygon** id**=**"room_305" data-label**=**"Ауд. 305" points**=**"60,60 340,60 340,740 60,740" **/>**

  ...

**</g>**

  

**<g** id**=**"doors"**>**

  **<circle** id**=**"door_305_corridor" cx**=**"350" cy**=**"400" r**=**"4"

          data-width**=**"0.9" data-room-a**=**"room_305" data-room-b**=**"corridor" data-exit**=**"false" **/>**

  **<circle** id**=**"exit_main" cx**=**"950" cy**=**"400" r**=**"4"

          data-width**=**"1.5" data-room-a**=**"room_306" data-exit**=**"true" **/>**

**</g>**

```

  

## Расширение алгоритма

  

`PathfindingStrategy` — интерфейс; для замены алгоритма достаточно

реализовать его и передать в `EvacuationManager.setPathfindingStrategy(...)`

или в `AppContext` — без изменений в остальном коде (Strategy pattern,

принцип Open/Closed).