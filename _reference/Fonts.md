# PharmaPay — Шрифты

Полный реестр всех шрифтов, используемых в проекте. Источник — `PharmaPay.html`, `PharmaPay (Android).html` и компоненты в `src/`.

---

## 1. Manrope — основной шрифт всего интерфейса

**Источник:** Google Fonts → [fonts.googleapis.com/css2?family=Manrope](https://fonts.googleapis.com/css2?family=Manrope)

**Подключение** (в обоих HTML-файлах):
```html
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Manrope:wght@400;500;600;700;800&display=swap" rel="stylesheet">
```

**Используемые начертания:** 400 (Regular), 500 (Medium), 600 (SemiBold), 700 (Bold), 800 (ExtraBold).

**Где применяется:**
- Всё тело интерфейса — заголовки, основной текст, кнопки, чипы, ярлыки, поля ввода.
- Подключён через Tailwind: `theme.extend.fontFamily.sans = ['Manrope', …]` → `font-sans` по умолчанию.
- Применяется глобально в `body { font-family: 'Manrope', …; }`.

**Почему Manrope:**
- Геометрический гротеск с отличной поддержкой кириллицы.
- Полный диапазон веса 400–800 — нужен и для тонкого подписного текста, и для крупных «дисплейных» заголовков.

**CSS-fallback:**
- iOS: `'Manrope', -apple-system, 'SF Pro Display', system-ui, sans-serif`
- Android: `'Manrope', 'Roboto', system-ui, sans-serif`

---

## 2. Roboto — системный шрифт Android-чрома

**Источник:** Google Fonts → [fonts.googleapis.com/css2?family=Roboto](https://fonts.googleapis.com/css2?family=Roboto)

**Подключение** (только в `PharmaPay (Android).html`):
```html
<link href="https://fonts.googleapis.com/css2?family=Manrope:wght@400;500;600;700;800&family=Roboto:wght@400;500;700&display=swap" rel="stylesheet">
```

**Используемые начертания:** 400 (Regular), 500 (Medium), 700 (Bold).

**Где применяется** (только Android-сборка):
- `AndroidStatusBar` — время «6:12», 14 px / 500 / `letter-spacing: 0.1px`. Источник: `src/ui.jsx` ~ строка 95.
- `AndroidBottomNav` — лейблы табов «Главная / Обучение / Чек / Профиль». Источник: `src/ui.jsx` ~ строка 347.
- `android-frame.jsx` (стартовая Material-3 рамка) — top-app-bar title, list-item labels, M3-кнопки.

**Почему Roboto:**
- Стандартный системный шрифт Android — без него Material-3-чром выглядит как iOS-приложение в Android-обёртке.
- Manrope остаётся для контентной типографики; Roboto «работает» только в системных полосках.

---

## 3. SF Pro Display / SF Pro Text — системный шрифт iOS-чрома

**Источник:** Apple system font, поставляется с macOS / iOS. Не загружается через сеть — резолвится через `-apple-system`.

**CSS-stack:**
```css
font-family: -apple-system, "SF Pro Display", "SF Pro Text", system-ui, sans-serif;
```

**Где применяется** (только iOS-сборка):
- `IOSStatusBar` — время «9:41», 17 px / 600 / `letter-spacing: -0.32px` / `tabular-nums`. Источник: `src/ui.jsx` ~ строка 61.
- В остальном iOS пользуется Manrope (через body `font-family`).

**Почему SF Pro:**
- Чтобы статус-бар выглядел как настоящий iOS-чром, а не как часть приложения.
- `-apple-system` гарантирует подмену на текущую версию SF независимо от устройства.

---

## 4. Системные fallback-стеки

| Платформа | Стек | Где |
|---|---|---|
| iOS общий | `'Manrope', -apple-system, 'SF Pro Display', system-ui, sans-serif` | `PharmaPay.html` → tailwind config + body |
| Android общий | `'Manrope', 'Roboto', system-ui, sans-serif` | `PharmaPay (Android).html` → tailwind config + body |
| iOS статус-бар | `-apple-system, "SF Pro Display", "SF Pro Text", system-ui, sans-serif` | `src/ui.jsx` IOSStatusBar |
| Android статус-бар / nav | `Roboto, "Google Sans", system-ui, sans-serif` | `src/ui.jsx` AndroidStatusBar / AndroidBottomNav |

`system-ui` и `sans-serif` — финальные fallback'ы на случай, если Google Fonts не загрузился (offline-сценарий или corporate-firewall).

---

## 5. Изолированные исключения (не для основного UI)

| Где | Шрифт | Зачем |
|---|---|---|
| `src/icons.jsx` → глиф ₸ в `Coin` | `Manrope, sans-serif` (явно прописан на `<text>` в SVG) | SVG-текст не наследует `font-family` от родителя, поэтому Manrope указан inline |
| `logo/design-canvas.jsx` → post-it sticky | `"Comic Sans MS", "Marker Felt", "Segoe Print", cursive` | Стилизация бумажных стикеров в дизайн-канвасе (служебный UI handoff'а, **не входит в продакшен-приложение**) |

---

## 6. Типографические токены

См. также `design-tokens.md` → §2 «Typography» — там указаны конкретные размеры, веса и применения по ролям (Display / H1 / H2 / Body / Caption / Status-bar / Micro).

Сводка ролей и веса:

| Роль | Размер / Line | Вес | Шрифт |
|---|---|---|---|
| Display | 32–34 / 38 | 800 | Manrope |
| H1 | 26 / 32 | 800 | Manrope |
| H2 | 22–24 / 28–30 | 800 | Manrope |
| H3 / Title | 17–20 / 24 | 700–800 | Manrope |
| List-row title | 17 / 22 | **800** | Manrope (особый case — заголовок строки в Профиле; чуть плотнее body-strong) |
| Body-strong | 16 / 22 | 700 | Manrope |
| Body | 14–16 / 20–22 | 500–600 | Manrope |
| Caption | 12–13 / 18 | 500–600 | Manrope |
| Status-bar iOS | 17 / 22 | 600 | SF Pro Display |
| Status-bar Android | 14 / 20 | 500 | Roboto |
| Tab labels Android | 12 / 16 | 500–700 | Roboto |
| Micro | 11–12 / 16 | 600–700 | Manrope |

**Уточнение по «брайтности»:** заголовки секций в Профиле («Помощь» / «О приложении») и в дочерних экранах = H1 26/800, **не** H2. Это даёт нужный визуальный контраст с list-row title 17/800 и body-strong 16/700.

---

## 7. Что важно при правках

- **Не добавлять новые шрифты** без обновления этого файла + `design-tokens.md`.
- При добавлении нового начертания (например, Manrope 300 Light) — обновить URL в `<link>` обоих HTML-файлов, иначе он будет недогружен и браузер подменит на ближайший доступный (обычно 400).
- Системные шрифты (`SF Pro`, `Roboto`) **не должны попадать на iOS / Android противоположной платформы** — иначе сломается ощущение «нативного чрома».
- Шрифты для SVG-текста (`<text>`-элементы) указывать **inline через `fontFamily`** — наследование от родителя не работает в SVG-namespace.

---

## 8. Источники в коде

| Файл | Что задаёт |
|---|---|
| `PharmaPay.html` | Подключение Manrope, body-стек iOS, tailwind config `sans` |
| `PharmaPay (Android).html` | Подключение Manrope + Roboto, body-стек Android, tailwind config `sans` |
| `src/ui.jsx` | StatusBar и BottomNav — явные `fontFamily` для системного чрома |
| `src/icons.jsx` | Inline `fontFamily="Manrope, sans-serif"` для SVG-глифа ₸ |
| `android-frame.jsx` | Inline Roboto-стеки для M3-компонентов (top-bar, list-item, button) |
| `design-tokens.md` | Роли, размеры, веса (источник для §6 этого файла) |
