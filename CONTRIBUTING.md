# Contributing — Git workflow для Epharm

> Один файл, в котором описано **как мы работаем с кодом**: ветки, коммиты, PR, мерж. Если что-то неясно — спроси в issue или открой PR с правкой этого файла.

## TL;DR

```bash
# 1. Свежий main
git checkout main && git pull

# 2. Новая ветка под задачу
git checkout -b feat/admin-rules-engine

# 3. Работаешь, коммитишь мелкими шагами
git commit -m "feat(admin): add Rules Engine page skeleton"

# 4. Пуш + PR
git push -u origin feat/admin-rules-engine
gh pr create --fill                    # gh подхватит шаблон PR из .github/pull_request_template.md

# 5. Дожидаешься зелёного CI → жмёшь "Squash and merge" в GitHub UI
# Ветка автоудалится после merge.
```

## Модель ветвления — Trunk-Based

- **`main`** — единственная долгоживущая ветка. Прямой push на `main` запрещён (на честном слове, т.к. private GitHub Free не enforced; **не пушь, даже если можешь**).
- **Feature-ветки** — короткие (1-3 дня жизни), всегда от `main`, мержатся обратно в `main` через PR.
- Никаких `develop`, `release`, `hotfix` веток. Релизы — через теги (`v0.1.0`, `v0.2.0`).

### Нейминг веток

| Префикс           | Когда                         | Пример                                               |
| ----------------- | ----------------------------- | ---------------------------------------------------- |
| `feat/<slug>`     | Новая фича                    | `feat/admin-rules-engine`, `feat/posm-recommend-api` |
| `fix/<slug>`      | Багфикс                       | `fix/posm-recommend-latency`, `fix/auth-otp-expiry`  |
| `chore/<slug>`    | Техдолг, зависимости, конфиги | `chore/bump-spring-boot`, `chore/repo-cleanup`       |
| `refactor/<slug>` | Без изменения поведения       | `refactor/extract-receipt-service`                   |
| `docs/<slug>`     | Только документы              | `docs/api-openapi-spec`                              |
| `test/<slug>`     | Только тесты                  | `test/rules-engine-coverage`                         |
| `ci/<slug>`       | Только `.github/workflows/*`  | `ci/add-jacoco-report`                               |

Slug — `kebab-case`, без пробелов, до 50 символов.

## Конвенция коммитов — Conventional Commits

```
<type>(<scope>): <subject>

<body — опционально, почему это сделано>

<footer — опционально, BREAKING CHANGE / refs>
```

### Допустимые `type`

`feat` ∙ `fix` ∙ `chore` ∙ `docs` ∙ `refactor` ∙ `test` ∙ `ci` ∙ `perf` ∙ `build` ∙ `style` ∙ `revert`

### Допустимые `scope`

`admin` ∙ `backend` ∙ `mobile` ∙ `posm` ∙ `infra` ∙ `repo` ∙ `deps`

### Правила

- **Subject** — императив, не длиннее **72 символов**, без точки в конце, в нижнем регистре (имена технологий типа «React», «Spring» можно с большой)
- **Body** — почему так сделано (не «что» — это видно в diff'е)
- **Footer** — `BREAKING CHANGE: <миграция>`, `Refs: #42`, `Closes: #42`

### Примеры

✅ Хорошо:

- `feat(admin): add Rules Engine substitution drawer`
- `fix(backend): handle null pharmacy_id in /posm/recommend`
- `chore(deps): bump kotlin to 2.0.21`
- `refactor(mobile): extract ReceiptDraft notifier from controller`

❌ Плохо:

- `update stuff` — нет type/scope
- `feat: added rules` — нет scope, прошедшее время
- `feat(admin): added Rules Engine drawer with sparkline and tabs and ...` — слишком длинный
- `WIP` — не PR-ready

### Локальная проверка

`commit-msg` hook автоматически проверяет каждый твой коммит через `commitlint`. Кривой формат → коммит отменится с пояснением что не так. CI прогоняет ту же проверку на **заголовке PR**.

## Pre-commit hooks

При `git commit` локально запускаются:

1. **`lint-staged`** — `prettier --write` на JSON/MD/YAML/CSS/HTML и на TS/TSX в `admin-panel/web/`. Авто-форматирование.
2. **`commitlint`** — валидация сообщения коммита.

Если что-то сломалось — коммит не пройдёт. Поправь и попробуй снова.

**Отключить хуки нельзя** (`--no-verify` запрещён по политике). Если хук неправильно срабатывает — открой issue/PR с фиксом конфига.

## Pull requests

### Когда открывать PR

После первого осмысленного коммита на feature-ветке (даже если фича не закончена) — открой **draft PR**. Это даёт ранний CI-фидбек.

```bash
gh pr create --draft --fill
```

Закончил → `gh pr ready` или кнопка «Ready for review» в UI.

### Что в PR

Шаблон в `.github/pull_request_template.md`. Минимум:

- **Что** — 1-3 предложения по существу
- **Зачем** — задача из `PLAN.md` или баг-репорт
- **Как проверить** — шаги для ревьюера или ссылка на тесты
- **Checklist** — тесты, lint, notes-файл обновлён

### Required CI checks

Все эти **должны быть зелёными** перед merge (на честном слове — на GitHub Free private не enforced, **но не мержь с красным CI**):

1. `admin / lint`
2. `admin / typecheck`
3. `admin / build`
4. `backend / build`
5. `backend / test`
6. `commitlint` (валидация заголовка PR)
7. `dependency-review` (CVE-сканирование новых зависимостей)

Дополнительно, если затронут `lib/`:

- `mobile / analyze`
- `mobile / test`

### Размер PR

Цель — **под 500 строк изменений**. Большие PR разбивай на цепочку:

1. `chore(admin): scaffold Rules feature folder`
2. `feat(admin): add Rules Engine table component`
3. `feat(admin): add Rules Engine drawer with substitution form`
4. `feat(admin): wire Rules Engine to MSW handlers`

Каждый — отдельный PR, мержится последовательно.

## Merge strategy — Squash only

В репо разрешён **только Squash and merge**. Other стратегии отключены на уровне репо.

### Что это значит

- В твоей ветке может быть 50 коммитов «WIP», «попробуй так», «нет, обратно».
- При merge **GitHub сожмёт всё в один коммит** для `main`.
- Сообщение этого коммита — **заголовок твоего PR** + тело — список squash'ленных коммитов (можно отредактировать перед merge).
- Ветка автоматически удалится после merge.

### Хорошее squash-сообщение

```
feat(admin): add Rules Engine substitution drawer (#12)

— Drawer открывается из таблицы правил, валидирует поля
— Mock-MSW handler /api/admin/rules возвращает фикстуру из references/data.jsx
— Toast вылетает на successful save
```

`(#12)` GitHub добавит автоматически — это ссылка на PR.

### `main` после squash

```
abc7  feat(admin): add Rules Engine substitution drawer (#12)
abc6  fix(backend): handle null pharmacy_id in /posm/recommend (#11)
abc5  chore(repo): bootstrap Этап 0 — mobile, admin, backend, infra
```

Один коммит = одна фича = ссылка на PR-обсуждение. `git log --oneline main` читается как changelog.

## Что НЕ делаем

- ❌ Прямой push в `main`
- ❌ Force-push в общие ветки (`git push -f origin main` или `feat/*` если кто-то ещё там работает)
- ❌ Коммит секретов (`.env`, ключей, паролей, keystore)
- ❌ Коммит build-артефактов (`build/`, `dist/`, `node_modules/`, `.gradle/`) — `.gitignore` это ловит, но не злоупотребляй
- ❌ Merge-коммиты / rebase-merge (отключены на уровне репо)
- ❌ `git commit --amend` после `git push` (это переписывает историю — для других пользователей ломает локальные клоны)
- ❌ Merge с красным CI, даже если уверен «там просто flaky тест»

## Если что-то пошло не так

| Проблема                                | Решение                                                                                                                                                         |
| --------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Коммит не прошёл commit-msg хук         | Прочитай сообщение от `commitlint` — там написан конкретный rule. Поправь `git commit --amend -m "<new>"` (ДО push'а)                                           |
| Pre-commit prettier модифицировал файлы | Они уже в stage — просто пиши коммит ещё раз, всё ок                                                                                                            |
| CI красный                              | Открой Actions tab в PR, найди failed job, кликни → читай логи. Обычно понятно. Поправь → push в ту же ветку → CI перезапустится                                |
| PR с conflict с main                    | Локально: `git fetch origin && git rebase origin/main`. Разреши конфликты. `git push --force-with-lease` (безопаснее `--force` — не перезатрёт чужих изменений) |
| Закоммитил секрет / случайный файл      | Если ещё не push'нул — `git rm --cached <file> && git commit --amend`. Если push'нул — спроси в issue, нужно ротировать ключ + переписать историю               |

## Когда подключим GitHub Pro / Student Pack

Branch protection включится одной командой (см. `admin-panel/claude-admin-notes.md` → «Git workflow» → «Включение branch protection»):

```bash
gh api --method POST /repos/sabirovv17/epharm/rulesets --input <path-to-ruleset.json>
```

`ruleset.json` лежит зафиксированный в `claude-admin-notes.md` — готов к активации.
