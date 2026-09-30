# Git Flow Ops

Плагин потоковых Git-операций для 1C:EDT. **Спека — `SPEC.md`** (архитектура,
спецификации команд, критерии приёмки, дорожная карта). Этот README — только
по установке и сборке.

## Требования

- JDK 17+ (для сборки подойдёт 17–21).
- Maven 3.9+ — системный или локальный из `.tools/` (см. ниже).
- Локальная установка EDT 2026.1 (пути — в `releng/edt-2026-1.target` и `SPEC.md §3`).

## Сборка

```bash
# вариант 1: системный Maven
mvn verify

# вариант 2: локальный Maven (если не установлен)
curl -fsSL https://archive.apache.org/dist/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.tar.gz \
  | tar -xz -C .tools --strip-components=1
.tools/bin/mvn verify
```

Target platform берётся из p2-пула EDT (`releng/edt-2026-1.target`,
Directory-location на `~/.p2/pool/plugins`). При переносе на другую машину
поправьте путь в этом файле.

> **Архитектура**: EDT 2026.1 на этой машине — x86_64 (Rosetta). В p2-пуле
> SWT-классы лежат в платформенном фрагменте `org.eclipse.swt.cocoa.macosx.x86_64`,
> поэтому в `pom.xml` среда резолва зафиксирована `macosx/cocoa/x86_64`. Если EDT
> станет нативным arm64 — поменять `<arch>` в `target-platform-configuration`.

## Установка в EDT (dropins)

Для локальной проверки на Windows закройте EDT, откройте архив
`dist/gitflow-ops-0.1.0-local.zip` и скопируйте находящуюся в нём папку `eclipse` в
`<каталог EDT>\1cedt\dropins\gitflow\`. При стандартной установке через EDT Start
каталог EDT находится в `%LOCALAPPDATA%\1C\1cedtstart\installations\<версия EDT>`.
Итоговый путь к JAR: `<каталог EDT>\1cedt\dropins\gitflow\eclipse\plugins\`.
После перезапуска EDT
в верхнем меню появится **Git Flow Ops → Быстрый стеш / Вернуть последний стеш**.
Откройте Git-проект или выберите его файл; при нескольких репозиториях без
выделения появится окно выбора. Команда «О Git Flow Ops» служит для проверки
загрузки плагина.

Архив можно пересобрать из двух JAR после `mvn verify`; вариант установки без
архива:

```bash
# после сборки
mkdir -p <EDT>/1cedt.app/Contents/Eclipse/dropins/gitflow/eclipse/plugins
cp dev.edt.gitflow.core/target/dev.edt.gitflow.core-0.1.0-SNAPSHOT.jar \
   dev.edt.gitflow.ui/target/dev.edt.gitflow.ui-0.1.0-SNAPSHOT.jar \
   <EDT>/1cedt.app/Contents/Eclipse/dropins/gitflow/eclipse/plugins/
# перезапустить EDT
```

## Отладка (PDE)

1. Импортировать проекты в workspace EDT (Import → Existing Projects).
2. Открыть `releng/edt-2026-1.target` → Set as Target Platform (дождаться резолва).
3. Run → Run Configurations → Eclipse Application → продукт
   `com._1c.g5.v8.dt.product.application.rcp` → Run (поднимется второй экземпляр EDT
   с плагином из workspace).

## Структура

```
dev.edt.gitflow.core   — headless-логика (JGit), без UI
dev.edt.gitflow.ui     — команды, меню, диалоги
releng/                — .target-платформа, launch-конфигурации
SPEC.md                — спецификация (читать первым)
```

## Совместимость

Сборка проверена с EDT 2026.1.1 на macOS. Целевая прод-среда — EDT 2025.2.6
на Windows; проверка установки и сценариев в ней ещё требуется.
