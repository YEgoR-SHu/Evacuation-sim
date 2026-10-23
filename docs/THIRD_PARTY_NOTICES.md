# Сторонние компоненты v0.2.0

Перечень получен из `mvn -f evac_sim/pom.xml dependency:tree -Dscope=runtime`. Все версии зафиксированы в Maven-модели; библиотечный код не изменяется. В исполняемый JAR включены следующие компоненты.

| Артефакты | Версия | Лицензия | Тексты в JAR |
|---|---|---|---|
| `org.apache.xmlgraphics:batik-parser`, `batik-awt-util`, `batik-shared-resources`, `batik-util`, `batik-constants`, `batik-i18n`, `batik-xml` | 1.20 | Apache 2.0; дополнительные notices внутри поставляемого LICENSE | Объединённые `META-INF/LICENSE`, `META-INF/NOTICE` |
| `org.apache.xmlgraphics:xmlgraphics-commons` | 2.12 | Apache 2.0; дополнительные notices внутри поставляемого LICENSE | `META-INF/LICENSE`, `META-INF/NOTICE` |
| `commons-logging:commons-logging` | 1.3.0 | Apache 2.0 | `META-INF/LICENSE.txt`, `META-INF/NOTICE.txt` |
| `xml-apis:xml-apis-ext` | 1.3.04 | Условия Apache и W3C из оригинальной поставки | `license/LICENSE`, `license/NOTICE`, `license/LICENSE.dom-documentation.txt`, `license/LICENSE.dom-software.txt`, `license/LICENSE.sac.html`, `license/README.dom.txt` |
| `org.jgrapht:jgrapht-core` | 1.5.3 | Двойная LGPL 2.1-or-later / EPL 2.0; для этой поставки выбран вариант EPL 2.0 | `licenses/jgrapht-EPL-2.0.txt` |
| `org.jheaps:jheaps` | 0.14 | Apache 2.0 | `licenses/jheaps-Apache-2.0.txt` |
| `org.apfloat:apfloat` | 1.14.0 | MIT | `licenses/apfloat-MIT.txt` |
| `com.fasterxml.jackson.core:jackson-databind`, `jackson-core` | 2.22.4 | Apache 2.0 и notices встроенных алгоритмов | `META-INF/LICENSE`, `META-INF/NOTICE`, `META-INF/FastDoubleParser-LICENSE`, `META-INF/FastDoubleParser-ThirdParty-LICENSE`, `META-INF/Schubfach-LICENSE` |
| `com.fasterxml.jackson.core:jackson-annotations` | 2.22 | Apache 2.0 | `META-INF/LICENSE`, `META-INF/NOTICE` |

BOM Jackson 2.22.4 выбирает `jackson-annotations:2.22`; отсутствие номера patch у этого артефакта является версией upstream.

`JUnit Jupiter 5.10.2` (EPL 2.0), JUnit Platform, API Guardian и OpenTest4J используются только при тестировании и в исполняемый JAR не включаются. Maven и его плагины относятся к инструментам сборки.

## Источники недостающих в исходных JAR текстов

Некоторые upstream JAR не содержат файла лицензии. Их неизменённые тексты дополнительно сохранены в ресурсах приложения:

- JGraphT: [EPL 2.0, версия 1.5.3](https://github.com/jgrapht/jgrapht/blob/jgrapht-1.5.3/license-EPL.txt) → [локальная копия](../evac_sim/src/main/resources/licenses/jgrapht-EPL-2.0.txt). Copyright 2003-2026, by Barak Naveh and Contributors. Исходники JGraphT доступны в указанном репозитории; изменений в библиотеку не внесено.
- JHeaps: [Apache 2.0, версия 0.14](https://github.com/d-michail/jheaps/blob/jheaps-0.14/LICENSE) → [локальная копия](../evac_sim/src/main/resources/licenses/jheaps-Apache-2.0.txt). Copyright (C) 2014-2020 Dimitrios Michail.
- Apfloat: [MIT, версия 1.14.0](https://github.com/mtommila/apfloat/blob/1.14.0/LICENSE.txt) → [локальная копия](../evac_sim/src/main/resources/licenses/apfloat-MIT.txt). Copyright (c) 2024 Mikko Tommila.

Информация о сторонних правообладателях сохраняется для соблюдения условий библиотек и не означает авторство собственного кода Evacuation-sim со стороны этих проектов. Лицензия собственного кода проекта определяется отдельно.
