# Доходит ли блок перестроения индекса до скрипта

Дата: 2026-08-03. Ветка `neo`, ядро `111ddf8e3b0f8e1550eb4fb41f2ef9d524da3ad3`.

## Вопрос

`PgIndex.appendAlterSQL:69-92` при несовместимом изменении индекса и
`settings.isConcurrentlyMode() == true` пишет во временный `SQLScript` блок:
создание с временным именем (`tmp<random>_<name>`), `BEGIN TRANSACTION`, `DROP`
старого индекса, `ALTER INDEX ... RENAME TO`, `COMMIT TRANSACTION` — и сразу
после этого возвращает `ObjectState.RECREATE` (строка 91). Попадает ли
содержимое этого блока в итоговый скрипт, который видит пользователь?

## Как мерили

Тест-зонд `PgDiffTest#PROBE_concurrentlyRebuildShape`: `CoreSettings` с
`setConcurrentlyMode(true)`, старая и новая база отличаются только колонкой
индекса (`t1_idx` с `id` на `val`) — изменение, которое проваливает
`compareUnalterable` и обязано пройти через блок из вопроса. Сгенерированный
скрипт печатается между маркерами `PROBE SCRIPT >>>` / `<<< PROBE SCRIPT`.

Зонд работает через файловые фикстуры, а не через строки SQL:
`IntegrationTestUtils.getScript`
(`src/test/java/org/pgcodekeeper/core/it/IntegrationTestUtils.java:129-139`)
принимает `fileNameTemplate` и сам достраивает
`<template>_original.sql` / `<template>_new.sql` через `loadTestDump` и
`FILES_POSTFIX`. Временные фикстуры:

`src/test/resources/org/pgcodekeeper/core/it/diff/pg/probe_concurrently_original.sql`:
```sql
CREATE TABLE public.t1 (
    id bigint,
    val text
);

CREATE INDEX t1_idx ON public.t1 USING btree (id);
```

`src/test/resources/org/pgcodekeeper/core/it/diff/pg/probe_concurrently_new.sql`:
```sql
CREATE TABLE public.t1 (
    id bigint,
    val text
);

CREATE INDEX t1_idx ON public.t1 USING btree (val);
```

Сам зонд, добавленный в конец `PgDiffTest`:
```java
@Test
void PROBE_concurrentlyRebuildShape() throws IOException, InterruptedException {
    var settings = new CoreSettings();
    settings.setConcurrentlyMode(true);
    String script = getScript(databaseProvider, "probe_concurrently", settings, PgDiffTest.class);
    System.out.println("PROBE SCRIPT >>>\n" + script + "\n<<< PROBE SCRIPT");
}
```

Команда:
```
cd /Users/fenoman/GitProjects/pg_code_keeper/pgcodekeeper-core && mvn -o test -Dtest=PgDiffTest#PROBE_concurrentlyRebuildShape -DfailIfNoTests=false
```

## Результат

Дословный вывод между маркерами (тест прошёл: `Tests run: 1, Failures: 0, Errors: 0`):

```
PROBE SCRIPT >>>
SET search_path = pg_catalog;

DROP INDEX public.t1_idx;

CREATE INDEX CONCURRENTLY t1_idx ON public.t1 USING btree (val);
<<< PROBE SCRIPT
```

Полный лог прогона (включая `BUILD SUCCESS`) — в приложении ниже
(раздел «Лог сборки»).

## Вывод

**БЛОК МЁРТВ** — issue #138 требует сначала оживить путь генерации, а уже
потом вставлять проверку валидности.

Обоснование по самому выводу зонда: в скрипте нет ни временного имени
(`tmp<random>_t1_idx`), ни `BEGIN TRANSACTION`/`COMMIT TRANSACTION`, ни
`ALTER INDEX ... RENAME TO`. Есть только `DROP INDEX public.t1_idx;` и
`CREATE INDEX CONCURRENTLY t1_idx ON public.t1 USING btree (val);` — ровно
пара DROP+CREATE, то есть результат ухода объекта в `ObjectState.RECREATE`.

Это согласуется со статическим чтением кода (без запуска, но подтверждено им):
`DepcyResolver.getObjectState` (`DepcyResolver.java:705-718`) кладёт
одноразовый `alterScript` в карту `alterScripts` только когда
`state.in(ObjectState.ALTER, ObjectState.ALTER_WITH_DEP)` (строка 714); при
`RECREATE` условие ложно, `alterScript` (со всем содержимым блока из вопроса)
никуда не сохраняется и исчезает вместе с локальной переменной.
`ActionsToScriptConverter.printAction` (`ActionsToScriptConverter.java:341-414`)
для `RECREATE`-объектов работает через ветки `CREATE`/`DROP`, а не `ALTER`:
`DROP` зовёт `addToDropScript` → обычный `getDropSQL` (реализация по
умолчанию, `AbstractStatement.java:184-192`, без `CONCURRENTLY`), `CREATE`
зовёт `addToAddScript` → `obj.getCreationSQL(script)` → приватный
`PgIndex.getCreationSQL(script, name)` (`PgIndex.java:122-149`) с
подлинным именем индекса, а не с временным.

**Третий исход, о котором стоит сказать отдельно.** Слово `CONCURRENTLY` в
итоговом `CREATE INDEX` — не осколок мёртвого блока, а след независимой
проверки в самом обычном `getCreationSQL`: строка `PgIndex.java:132`
(`if (settings.isConcurrentlyMode() && !settings.isAddTransaction())`)
добавляет `"CONCURRENTLY "` при простом создании индекса вообще без всякой
связи с блоком из вопроса. Из-за этого результат выглядит наполовину
"concurrency-aware", но таковым не является:
- `DROP INDEX` в выводе — обычный, блокирующий (нет `CONCURRENTLY`,
  `getDropSQL` его никогда не добавляет);
- нет атомарной транзакции и `RENAME`, которые в исходном блоке из вопроса
  как раз и обеспечивали, что окно "индекса нет" — минимальное и управляемое;
- то есть текущее поведение не «частично работает так, как задумано», а
  даёт иную, более грубую последовательность (блокирующий DROP, затем
  неблокирующий CREATE), которая совпала с ожидаемой лишь по одному
  ключевому слову.

Для планирования issue #138 это значит: вставить проверку валидности после
`CREATE INDEX CONCURRENTLY` в существующий поток недостаточно — самого потока
(временное имя → транзакция → drop → rename) в скрипте нет, его сначала нужно
воскресить (переиспользовать `alterScript` при `RECREATE` для индексов, либо
завести отдельное состояние/ветку в `ActionsToScriptConverter`), и только
затем в него добавлять шаг проверки.

## Зонд убран

Тест `PROBE_concurrentlyRebuildShape` удалён из `PgDiffTest.java`, временные
фикстуры `probe_concurrently_original.sql` / `probe_concurrently_new.sql`
удалены. В дереве остаётся только этот протокол.

## Приложение: лог сборки (служебные WARNING про версии Maven-плагинов вырезаны, тестовый вывод — дословно)

```
[INFO] Scanning for projects...
[INFO] 
[INFO] -----------------< org.pgcodekeeper:pgcodekeeper-core >-----------------
[INFO] Building pgcodekeeper-core 15.1.0-neo1
[INFO]   from pom.xml
[INFO] -------------------------------[ bundle ]-------------------------------
[INFO] 
[INFO] --- git-commit-id:9.0.2:revision (resolve-build-commit) @ pgcodekeeper-core ---
[INFO] 
[INFO] --- antlr4:4.13.2:antlr4 (antlr) @ pgcodekeeper-core ---
[INFO] No grammars to process
[INFO] ANTLR 4: Processing source directory /Users/fenoman/GitProjects/pg_code_keeper/pgcodekeeper-core/src/main/antlr4
[INFO] 
[INFO] --- resources:3.5.0:resources (default-resources) @ pgcodekeeper-core ---
[INFO] Copying 9 resources from src/main/resources to target/classes
[INFO] 
[INFO] --- compiler:3.15.0:compile (default-compile) @ pgcodekeeper-core ---
[INFO] Nothing to compile - all classes are up to date.
[INFO] 
[INFO] --- resources:3.5.0:testResources (default-testResources) @ pgcodekeeper-core ---
[INFO] Copying 2375 resources from src/test/resources to target/test-classes
[INFO] 
[INFO] --- compiler:3.15.0:testCompile (default-testCompile) @ pgcodekeeper-core ---
[INFO] Recompiling the module because of changed source code.
[INFO] Compiling 244 source files with javac [debug release 17] to target/test-classes
[INFO] 
[INFO] --- surefire:3.6.0-M1:test (default-test) @ pgcodekeeper-core ---
[INFO] Using auto detected provider org.apache.maven.surefire.junitplatform.JUnitPlatformProvider
[INFO] 
[INFO] -------------------------------------------------------
[INFO]  T E S T S
[INFO] -------------------------------------------------------
[INFO] Running org.pgcodekeeper.core.it.diff.pg.PgDiffTest
PROBE SCRIPT >>>
SET search_path = pg_catalog;

DROP INDEX public.t1_idx;

CREATE INDEX CONCURRENTLY t1_idx ON public.t1 USING btree (val);
<<< PROBE SCRIPT
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.311 s -- in org.pgcodekeeper.core.it.diff.pg.PgDiffTest
[INFO] 
[INFO] Results:
[INFO] 
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO] 
[INFO] BUILD SUCCESS
```
