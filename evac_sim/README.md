# Модуль evac-sim

Рабочий Maven-модуль приложения. Актуальные команды запуска, модель и API описаны в [корневом README](../README.md).

```bash
# Из каталога evac_sim:
mvn clean verify
java -Djava.awt.headless=true -jar target/evac-sim-0.2.0.jar --serve 8080
```

Основные пакеты:

- `model` — геометрия и атрибуты помещений/проходов.
- `parser` — проверка XML и разбор геометрии через Apache Batik.
- `graph` — исходная модель графа для консольного просмотра.
- `simulation` — граф JGraphT и событийный расчёт движения/очередей.
- `web` — локальный HTTP-адаптер и JSON Jackson.
- `src/main/resources/static/index.html` — интерфейс основного расчёта.
- `src/main/resources/static/{login,admin,viewer}.html` — сохранённая самостоятельная демонстрация.

Компиляция одним `javac` без зависимостей больше не поддерживается. Входной формат: [format_spec.md](format_spec.md). Многоэтажная сборка и полноценная модель толпы пока не реализованы.
