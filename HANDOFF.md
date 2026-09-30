# HANDOFF — передача состояния между моделями

Дата: 2026-09-30. Проект: **Git Flow Ops** — плагин потоковых Git-операций для 1C:EDT.

**Источники истины:**
- `SPEC.md` — ЧТО делаем: архитектура, спецификации команд §5, требования §6,
  критерии приёмки §7, дорожная карта §8, открытые вопросы §9, справочные факты §10.
  **Читать первым.**
- `README.md` — как собирать/ставить.
- Этот файл — ТЕКУЩЕЕ СОСТОЯНИЕ и что делать дальше. После выполнения задачи
  обновляй этот файл (раздел «Статус»).

---

## Статус

- ✅ **Фаза 0 — инфраструктура: ЗАВЕРШЕНА.** Скелет из двух бандлов собирается
  (`mvn verify` → BUILD SUCCESS), jar собираются. Команда-приветствие
  `dev.edt.gitflow.ui.command.hello` (HelloHandler) — оставить до конца фазы 1.
- ⬜ **Фаза 1 — Quick Stash / Quick Pop: НЕ НАЧАТА** (задача ниже, полностью готова).
- Спека обновлена до v0.3: прод — **Windows + EDT 2025.2.6**, разработка на Mac.
- Коммит baseline: см. `git log` (ветка main).

## Среда (проверено, работает)

- Сборка: `.tools/bin/mvn -B verify` из корня. Maven 3.9.9 лежит в `.tools/`
  (в .gitignore). Если удалён — команда восстановления в README.
- JDK: системный Temurin 21 (BREE в манифестах JavaSE-17 — ок).
- Target platform: `releng/edt-2026-1.target` → Directory-location на
  `~/.p2/pool/plugins` (пул EDT 2026.1.1). **Не менять без нужды.**
- ⚠️ Машина Apple Silicon, EDT — x86_64 под Rosetta; классы SWT лежат в фрагменте
  `org.eclipse.swt.cocoa.macosx.x86_64`, поэтому в pom.xml зафиксирована среда резолва
  `macosx/cocoa/x86_64` (target-platform-configuration). Симптом слёта пина —
  ошибка ECJ «missing type Shell».
- Сеть нужна только для закачки Maven/Tycho (один раз); резолв target platform — локальный.

## Правила работы (для любой модели)

1. Не менять `pom.xml`, `releng/`, `.gitignore` без явной причины; причину фиксировать в коммите.
2. После любых правок — `.tools/bin/mvn -B verify` обязан быть BUILD SUCCESS.
3. Только стабильные API: JGit `org.eclipse.jgit.api.*`, EGit публичное
   (`org.eclipse.egit.core.project.RepositoryMapping`). **Без internal-пакетов.**
4. Реализационные решения по спеке (выбор из опций) — фиксировать комментарием в коде
   и строкой в SPEC §9.
5. Код: английские идентификаторы, краткие комментарии на английском; UI-строки —
   русские, через Messages + messages.properties/ru (образец: бандл
   `com._1c.g5.v8.dt.egit.ui`, см. SPEC §10).
6. Коммиты атомарные, сообщения на русском, ссылка на раздел спеки.

## Следующая задача: фаза 1 — Quick Stash / Quick Pop (SPEC §5.2, §5.3, §6.1)

Реализовать ТОЛЬКО это (Smart Pull и остальное — следующими итерациями):

**core** (`dev.edt.gitflow.core`):
- `RepositorySupport`: `resolveFor(IResource)` через `RepositoryMapping.getMapping`;
  `allRepositories()` через `RepositoryMapping` (сигнатуры проверить в
  `~/.p2/pool/plugins/org.eclipse.egit.core_6.8.12*.jar`, при необходимости `javap`);
  `isSafe(Repository)` — `getRepositoryState() == RepositoryState.SAFE`.
- `StashOperations`:
  - `hasUncommittedChanges(Repository)` — JGit `StatusCommand`;
  - `quickStash(repo, includeUntracked, monitor)` — `StashCreateCommand`, имя
    `WIP @ <ветка> <yyyyMMdd-HHmm>` (java.time); результат enum/record:
    CREATED(имя) / NO_CHANGES / ERROR(сообщение);
  - `quickPop(repo, monitor)` — `StashListCommand` → последний; пусто → NOT_FOUND;
    `StashApplyCommand` → успех: `StashDropCommand` того же стеша; конфликт → стеш
    НЕ удалять; результаты: APPLIED / NOT_FOUND / CONFLICTS(msg) / ERROR(msg).
  - Без UI-зависимостей; GitAPIException → result-объект с читаемым сообщением.

**ui** (`dev.edt.gitflow.ui`):
- `Repositories` (резолвер §6.1): selection из `HandlerUtil.getCurrentSelection` →
  adapt к `IResource` → core `resolveFor`; fallback — единственный репозиторий
  воркспейса; иначе null → хендлер показывает «выделите проект/файл из git-репозитория».
- `QuickStashHandler`, `QuickPopHandler` (AbstractHandler): операции в `Job`
  (правило — `IWorkspaceRoot`); перед операцией проверка `isSafe` (иначе диалог
  «репозиторий занят rebase/merge…»); после успеха — `refreshLocal(DEPTH_INFINITE)`
  затронутых проектов; результаты — MessageDialog + лог через getLog().log(Status).
- plugin.xml: команды `dev.edt.gitflow.ui.command.quickStash` («Быстрый стеш») и
  `dev.edt.gitflow.ui.command.quickPop` («Вернуть последний стеш») в меню
  `dev.edt.gitflow.ui.mainMenu` (после hello); подписи через %ключи в
  plugin.properties / plugin_ru.properties.

**Критично (§6.4):** при конфликте apply стеш сохраняется; никаких drop без
успешного apply; при ошибке репозиторий в валидном состоянии, пользователю —
понятное сообщение.

**Критерий готовности фазы 1:** BUILD SUCCESS + команды видны в меню «Git Flow Ops»
(проверяется установкой jar в dropins EDT, см. README; сабагент может проверить
только сборку — UI-проверку делает пользователь).

## После фазы 1 (порядок)

1. Smart Pull §5.1 (решить вопрос нативный autostash vs явный stash→pull→pop, §9.1).
2. Фаза 2: §5.4–5.7 (веточные операции). Фаза 3: §5.8–5.12.
3. Тесты: headless JUnit для core (§6.7) — отдельным бандлом `dev.edt.gitflow.core.tests`.
4. Параллельно пользователю: поставить EDT 2025.2.6 на Mac через 1cedtstart
   (нужен дистрибутив macOS с releases.1c.ru) → `releng/edt-2025-2.target` (§9.5);
   ручной чек-лист §6.7 на Windows EDT 2025.2.6.

## Известные не-проблемы / факты (не тратить время)

- Интерактивный ребейз в EDT **уже есть** (EGit 1С-сборки: `RebaseInteractiveCurrent`) — не делаем.
- Stash-API в JGit есть: `StashCreateCommand/StashApplyCommand/StashListCommand/StashDropCommand`.
- JGit умеет `rebase --autostash` (§5.1).
- 1С скрыла Reset/MergeTool и др. из UI (activity) — Undo Last Commit (§5.6) поэтому ценен.
- `its.1c.ru` контент недоступен простым fetch (JS) — документация: edt.1c.ru/dev/ru.
- Плагин-образец для структуры: `com._1c.g5.v8.dt.egit.ui_1.2.200` в пуле
  (`unzip -p jar plugin.xml`).

## История обрывов

- 2026-09-30: сабагент (coder) с брифом фазы 1 упёрся в лимит квоты провайдера,
  **ничего не записал** — фаза 1 не начата, состояние чистое (фаза 0).
