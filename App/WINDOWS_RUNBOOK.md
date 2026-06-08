# POSM клиентский экран (C#/WPF) — запуск на Windows для демо

Это десктоп-приложение киоска (клиентский экран кассы), а не Windows-сервис. Запускается
на Windows (WPF). Цель этой инструкции — поднять и сделать скриншоты для показа.

## Что увидим

- **Киоск на весь экран**: слева промо-видео, справа живой чек (Название/Цена/Кол-во/Скидка/Сумма + ИТОГО).
- **Popup рекомендации** замены/допродажи с бонусом фармацевту (клавиша `D` — демо).

---

## 0. Требования (один раз)

- **Windows 10/11** (или Windows-VM).
- **.NET 10 SDK** — проверить: `dotnet --version` → должно быть `10.x`.
  Нет → поставить SDK с https://dotnet.microsoft.com/download (именно SDK, не только Runtime).
- VLC отдельно ставить **не нужно** — нативные библиотеки идут NuGet-пакетом `VideoLAN.LibVLC.Windows`.

## 1. Скопировать код в Windows

Нужны **обе** папки рядом: `App/` и `Models/` (csproj подключает `..\Models\**\*.cs`).

```powershell
git clone <repo> C:\epharm
cd C:\epharm\App
```

(или скопировать папки `App` и `Models` вручную в одну родительскую папку.)

## 2. Положить промо-видео на рабочий стол

Приложение играет `Desktop\promo.mp4`. Без него левая панель будет чёрной.

```powershell
# любой mp4 переименовать в promo.mp4 и положить на рабочий стол:
copy "C:\путь\к\ролику.mp4" "$env:USERPROFILE\Desktop\promo.mp4"
```

## 3. Собрать и запустить

```powershell
cd C:\epharm\App
dotnet run
# если ругается на RID:  dotnet run -r win-x64
```

Откроется на втором мониторе (если есть) или на основном — frameless, на весь экран.

## 4. Что скриншотить

1. **Киоск целиком** — промо слева + панель чека справа. (Скриншот в VM: `Win + Shift + S`.)
2. **Popup рекомендации** — нажми клавишу **`D`** → всплывёт карточка
   «Bioderma → SelfieLab Zen, +650 ₸» с кнопками **Заменить (F9)** / **Пропустить (Esc)**.
   👉 Это главный кадр для показа.

## 5. (Опц.) Наполнить чек реальными позициями для скриншота

Приложение читает лог кассы `C:\Standart-N\Kassir\zkassa.log` как `tail -f`. Пока оно **запущено**,
допиши строки в кодировке 1251 — позиции появятся на экране:

```powershell
$log = "C:\Standart-N\Kassir\zkassa.log"
New-Item -ItemType Directory -Force -Path (Split-Path $log) | Out-Null
$enc = [System.Text.Encoding]::GetEncoding(1251)
$lines = @(
  "Add2Cheque iPartID=80309(80309);sname=Аквалор Норм спрей 50мл;price=1620;quant=1",
  "Add2Cheque iPartID=80312(80312);sname=Аквамарис Норм спрей 30мл;price=1480;quant=1",
  "Add2Cheque iPartID=70150(70150);sname=Пиносол капли;price=890;quant=2"
)
foreach ($l in $lines) { [System.IO.File]::AppendAllText($log, "$l`r`n", $enc) }
```

Скидка на весь чек:

```powershell
[System.IO.File]::AppendAllText($log, "ChequeList.OnChange 4880,00 / 4880,00 (-488,00)`r`n", $enc)
```

Закрыть чек (очистить экран): строка с `RunScriptByIndex` + `После печати очереди чеков`.

## 6. (Опц.) Реальные рекомендации от backend — «как на самом деле»

Чтобы popup приходил не по `D`, а по добавлению товара (через наш Rules Engine):

1. Подними backend (на Mac/сервере): `./gradlew bootRun` в `admin-panel/backend`.
2. Создай `C:\Epharm\posm.json` (шаблон — `App/posm.sample.json`):
   ```json
   {
     "enabled": true,
     "backendBaseUrl": "http://<IP-хоста>:8080",
     "deviceKey": "dev-posm-key",
     "pharmacistId": "u_41",
     "pharmacyId": "farmis_1"
   }
   ```
   (`backendBaseUrl` — адрес, по которому VM видит backend; `pharmacistId/pharmacyId` — реальные из сида.)
3. Запусти приложение и допиши в лог строку `Add2Cheque ... iPartID=p_aql_norm_s ...` (см. п.5) —
   приложение отправит корзину в `/api/posm/recommend` и покажет popup с настоящей рекомендацией.

## 7. Управление

| Клавиша | Действие                         |
| ------- | -------------------------------- |
| **D**   | показать демо-popup рекомендации |
| **F9**  | принять рекомендацию (в popup)   |
| **Esc** | пропустить рекомендацию          |
| **Q**   | выйти из приложения              |

## 8. Если что-то не так

- **«The framework 'Microsoft.NETCore.App', version '10…' was not found»** → не установлен .NET 10 SDK.
- **Левая панель чёрная** → нет `Desktop\promo.mp4`.
- **Чек пустой** → это норма: позиции появляются из лога кассы (см. п.5).
- **Кракозябры в названиях** → лог дописан не в кодировке 1251 (используй `[System.Text.Encoding]::GetEncoding(1251)` как в п.5).
- **Окно не там / нужно закрыть** → нажми **Q**.
- **VM с одним монитором** → киоск займёт единственный экран (это нормально), выход — **Q**.
