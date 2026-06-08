<!--
Заголовок PR должен следовать Conventional Commits:
  feat(scope): что добавлено
  fix(scope): что починено
  chore(scope): техдолг / зависимости
  refactor(scope): без изменения поведения
  docs(scope): только документы
  test(scope): только тесты
  ci(scope): CI-конфиг
  perf(scope): производительность

scope = admin | backend | mobile | posm | infra | repo
-->

## Что изменилось

<!-- 1-3 предложения: что именно сделано. Без воды. -->

## Зачем

<!-- Какую проблему/задачу из ТЗ или PLAN.md этот PR закрывает. Ссылка на этап если есть. -->

## Как проверить

<!-- Шаги для ревьюера или автотестов:
  1. `docker compose up -d`
  2. `cd admin-panel/backend && ./gradlew bootRun`
  3. `curl localhost:8080/api/...` → ожидаем X
-->

## Checklist

- [ ] Тесты добавлены / обновлены (unit / integration / e2e)
- [ ] `npm run lint && npx tsc --noEmit` зелёное (если затронут frontend)
- [ ] `./gradlew build test` зелёное (если затронут backend)
- [ ] `flutter analyze && flutter test` зелёное (если затронут lib/)
- [ ] `claude-admin-notes.md` или `claude-notes.md` обновлён, если решение нетривиальное
- [ ] Нет секретов (`.env`, keystore, API-ключей) в diff'е
- [ ] PR — атомарный (одна фича / один баг), не сборная солянка

## Скриншоты / логи (если UI или баг)

<!-- Перетащи сюда картинки или вставь output. -->

## Breaking changes

<!-- Если есть — опиши миграционный путь. Если нет — напиши "нет". -->
