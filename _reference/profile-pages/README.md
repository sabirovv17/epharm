# Профиль · экраны кнопок

Эта папка содержит **все полноэкранные страницы**, которые открываются из кнопок раздела «Профиль» (списки «Помощь» и «О приложении»).

Главный экран профиля живёт отдельно — `src/screens/profile.jsx`. Здесь — только дочерние экраны, на которые он навигирует.

---

## Текущий статус

| # | Кнопка (label в profile.jsx) | Файл | Источник контента | Статус |
|---|---|---|---|---|
| 1 | Служба поддержки в WhatsApp | — | внешняя ссылка (`wa.me/…`) | внешний переход, экран не нужен |
| 2 | Вопросы и ответы | `faq.jsx` | референс из чата | ✅ готово |
| 3 | Подробная инструкция | `instruction.jsx` | `uploads/инструкция.pdf` | ✅ готово |
| 4 | Сотрудничество | `cooperation.jsx` | оригинал (без референса) | ✅ готово |
| 5 | Пользовательское соглашение | `terms.jsx` | `uploads/Пользовательское соглашение.pdf` | ✅ готово |
| 6 | Политика конфиденциальности | `privacy.jsx` | `uploads/Политика конфиденциальности.pdf` | ✅ готово |

Общий вспомогательный модуль:
- `long-doc.jsx` — `<LongDocScreen>` для terms / privacy (плоский рендер юр-документа с h1/h2/p/li/def-блоками, без аккордеона)
- `terms-content.js` / `privacy-content.js` — данные блоков, авто-сгенерированные из PDF (НЕ редактировать вручную, пересобирать из источника)

Аккордеон с `+/×` используется **только в FAQ** — это сознательный выбор: для коротких списков вопросов он работает, для длинных юр-документов — нет (мешает читать целиком, ломает поиск Ctrl+F).

---

## Как сделать новый экран кнопки — пошагово

Все страницы строятся по одному шаблону. Делай как `faq.jsx`.

### Шаг 1. Создать файл в этой папке

`src/screens/profile-pages/<name>.jsx`

Структура файла:

```jsx
// ────────────────────────────────────────────────────────────────────────────
// <name>.jsx — <Заголовок экрана>
// Открывается из Профиль → «<секция>» → «<label кнопки>».
// ────────────────────────────────────────────────────────────────────────────

function <Name>Screen({ onBack }) {
  return (
    <div className="screen absolute inset-0 flex flex-col" style={{ background: '#EFF3FB' }}>
      <StatusBar time="6:12" />

      {/* Header: back arrow */}
      <div className="px-5">
        <button
          onClick={onBack}
          className="w-10 h-10 -ml-2 grid place-items-center text-ink-900 active:opacity-60"
          aria-label="Назад"
        >
          <I.Back size={26}/>
        </button>
      </div>

      {/* Centered bold title */}
      <div className="px-5 pt-3 pb-5">
        <h1 className="text-center text-[26px] font-extrabold text-ink-900 leading-tight">
          <Заголовок>
        </h1>
      </div>

      {/* Body (scrolls) */}
      <div className="flex-1 overflow-y-auto no-scrollbar pb-10 px-4">
        {/* ...контент... */}
      </div>
    </div>
  );
}

window.<Name>Screen = <Name>Screen;
```

> Заголовок всегда центрирован, 26/800 `ink/900`. Фон страницы — `#EFF3FB` (бледно-лавандовый, как у FAQ). Подробности — `design-tokens.md` §6.9.

### Шаг 2. Подключить скрипт в HTML

В **`PharmaPay.html` и `PharmaPay (Android).html`** добавить строку рядом с `faq.jsx`:

```html
<script type="text/babel" src="src/screens/profile-pages/<name>.jsx"></script>
```

Порядок строк важен: все экраны грузятся до `src/app.jsx`.

### Шаг 3. Добавить роут в `src/app.jsx`

1. Добавить ключ в `SCREENS`:
   ```js
   const SCREENS = {
     …,
     FAQ: 'faq',
     INSTRUCTION: 'instruction',   // новое
   };
   ```
2. Передать в `ProfileScreen` обработчик:
   ```jsx
   <ProfileScreen
     …
     onInstruction={()=>setScreen(SCREENS.INSTRUCTION)}
   />
   ```
3. Отрендерить экран:
   ```jsx
   {screen === SCREENS.INSTRUCTION && (
     <InstructionScreen onBack={()=>setScreen(SCREENS.PROFILE)}/>
   )}
   ```

### Шаг 4. Привязать обработчик к строке списка в `src/screens/profile.jsx`

`ProfileScreen` уже принимает обработчики и сравнивает с label-ом строки. Добавь свой:

```jsx
function ProfileScreen({ …, onInstruction }) {
  …
  <Row
    key={i}
    icon={iconMap[r.icon]}
    label={r.label}
    onClick={
      r.label === 'Вопросы и ответы' ? onFaq :
      r.label === 'Подробная инструкция' ? onInstruction :
      undefined
    }
  />
}
```

---

## Визуальные правила (общие для всех страниц)

- **Канвас** — `#EFF3FB` (paleblue/lavender). Не белый, не `paper/DEFAULT` — этот оттенок специально мягче, чтобы белые карточки внутри читались.
- **Header** — back-стрелка `I.Back size=26` слева вверху, заголовок центром, отступ от стрелки 12 px.
- **Список ответов / карточки** — белые карточки на лавандовом фоне, см. `design-tokens.md` §6.9 «Question card».
- **Inline-акценты в тексте** — `brand/green/400` (#3DCDA2), вес 600, без подчёркивания. Используй маркеры в строке контента — формат смотри в `faq.jsx → FaqText`:
  - `[link]…[/link]` → акцентная teal-ссылка
  - `**…**` → жирный фрагмент
- **Bullet** — 5×5 px черный круг, gap 12 px до текста.
- **Скролл** — `overflow-y-auto no-scrollbar`, нижний отступ `pb-10`, чтобы контент не упирался в нижний край устройства.

---

## Кросс-платформа (iOS / Android)

Все эти экраны платформенно-нейтральные — они работают одинаково и на `PharmaPay.html`, и на `PharmaPay (Android).html`, потому что:
- `StatusBar` уже платформо-знающий (см. `src/ui.jsx`)
- BottomNav не показывается на этих экранах (они не в `showNav` в `app.jsx`)
- back-стрелка `I.Back` одна на обеих платформах

Если конкретный экран должен **выглядеть по-другому** на Android — проверяй `window.PLATFORM === 'android'` внутри своего компонента (как это делает `StatusBar` / `BottomNav`).

---

## Файлы в этой папке

- `README.md` — этот документ
- `faq.jsx` — экран «Вопросы и ответы» (готов, единственный с `+/×` аккордеоном)
- `instruction.jsx` — «Подробная инструкция» (готов, 5-шаговая карусель)
- `cooperation.jsx` — «Сотрудничество» (готов)
- `long-doc.jsx` — общий компонент `<LongDocScreen>` для плоских юр-документов
- `terms.jsx` — «Пользовательское соглашение» (использует `long-doc.jsx`)
- `terms-content.js` — данные блоков для terms (авто-сгенерировано из PDF)
- `privacy.jsx` — «Политика конфиденциальности» (использует `long-doc.jsx`)
- `privacy-content.js` — данные блоков для privacy (авто-сгенерировано из PDF)
- *новые файлы добавлять сюда, обновляя таблицу выше*
