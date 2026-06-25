# publish-exe.ps1 — собирает self-contained .exe POSM-клиента (win-x64) и пакует
# в ZIP для отправки другому разработчику.
#
# ГДЕ ЗАПУСКАТЬ: на Windows с .NET 10 SDK, из КОРНЯ репозитория (где папка App).
# По умолчанию берёт боевой конфиг из C:\Epharm\posm.json (с ключом и прод-URL).
#
#   Set-ExecutionPolicy -Scope Process Bypass -Force
#   .\App\scripts\publish-exe.ps1
#
# Результат: dist\Epharm-POSM-v<версия>-win-x64.zip — его и отправляй.
# Получатель распаковывает архив и запускает run.bat или run-kassa.ps1:
# клиентский экран откроется слева в dev-режиме, POSM-логи пойдут в этот же терминал.

param(
  [string]$ConfigPath = "C:\Epharm\posm.json",
  [string]$Version = "1.0.13"
)
$ErrorActionPreference = "Stop"

$root = (Get-Location).Path
$proj = Join-Path $root "App\CustomerDisplay.csproj"
if (!(Test-Path $proj)) {
  throw "Не найден $proj. Запускай из КОРНЯ репозитория (где лежит папка App)."
}

$out = Join-Path $root "dist\Epharm-POSM"
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Force -Path $out | Out-Null

Write-Host "== dotnet publish (self-contained win-x64, может занять пару минут) ==" -ForegroundColor Cyan
dotnet publish $proj -c Release -r win-x64 --self-contained true `
  -p:EnableWindowsTargeting=true -p:Version=$Version -p:DebugType=None -p:DebugSymbols=false -o $out
if ($LASTEXITCODE -ne 0) { throw "dotnet publish завершился с ошибкой." }

# Имя exe НЕ меняем: install-tasks.ps1, watchdog.ps1, авто-апдейтер и документация
# ожидают CustomerDisplay.exe. Старое переименование в Epharm-POSM.exe давало эффект
# «запускается раз через раз»: run.bat мог стартовать одно имя, а scheduled task/watchdog
# искали другое.

# Боевой конфиг рядом с .exe (адрес бэкенда, ключ устройства, id аптеки).
if (Test-Path $ConfigPath) {
  Copy-Item $ConfigPath (Join-Path $out "posm.json") -Force
  try {
    $cfg = Get-Content $ConfigPath -Raw | ConvertFrom-Json
    Write-Host ("Конфиг скопирован. BackendBaseUrl = {0} | PharmacyId = {1}" -f $cfg.BackendBaseUrl, $cfg.PharmacyId) -ForegroundColor Green
    if ($cfg.BackendBaseUrl -match "localhost|127\.0\.0\.1") {
      Write-Warning "Конфиг смотрит на localhost — для боевого режима поправь BackendBaseUrl в posm.json внутри dist\Epharm-POSM перед упаковкой!"
    }
  } catch { }
} else {
  Write-Warning "Конфиг $ConfigPath не найден — впиши posm.json в dist\Epharm-POSM вручную перед отправкой!"
}

# README и диагностические скрипты для получателя.
$readme = Join-Path $root "App\scripts\README-distrib.md"
if (Test-Path $readme) { Copy-Item $readme (Join-Path $out "README.md") -Force }
$discover = Join-Path $root "App\scripts\standartn-discover.ps1"
if (Test-Path $discover) { Copy-Item $discover (Join-Path $out "standartn-discover.ps1") -Force }

# Лаунчер для тестовой передачи разработчику: подставляет путь к конфигу,
# включает dev-режим (окно слева) и оставляет POSM-логи в этом же терминале.
# EPHARM_LOG_PATH здесь НЕ задаём: в реальном режиме клиент сам слушает стандартные
# пути Стандарт-Н (C:\Standart-N\Kassir\zkassa.log и demo-путь). Override нужен
# только для ручной диагностики конкретной кассы.
$ps1 = @'
$ErrorActionPreference = "Stop"

try {
  chcp 65001 > $null
  [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
  [Console]::InputEncoding = [System.Text.UTF8Encoding]::new($false)
} catch { }

function Write-NewLogText {
  param(
    [string]$Path
  )

  if (!(Test-Path $Path)) { return }

  try {
    $fs = [System.IO.File]::Open($Path, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
    try {
      if ($fs.Length -lt $script:LogPosition) { $script:LogPosition = 0L }
      if ($fs.Length -eq $script:LogPosition) { return }

      [void]$fs.Seek($script:LogPosition, [System.IO.SeekOrigin]::Begin)
      $count = [int]($fs.Length - $script:LogPosition)
      $buffer = New-Object byte[] $count
      $read = $fs.Read($buffer, 0, $count)
      $script:LogPosition = $fs.Position

      if ($read -gt 0) {
        $text = [System.Text.Encoding]::UTF8.GetString($buffer, 0, $read)
        $text = $text.TrimStart([char]0xFEFF)
        if ($text.Length -gt 0) { Write-Host -NoNewline $text }
      }
    } finally {
      $fs.Dispose()
    }
  } catch {
    # На кассе лог может на мгновение быть занят. Следующая итерация дочитает хвост.
  }
}

$root = $PSScriptRoot
$exe = Join-Path $root "CustomerDisplay.exe"
$config = Join-Path $root "posm.json"

if (!(Test-Path $exe)) { throw "Не найден CustomerDisplay.exe: $exe" }
if (!(Test-Path $config)) { throw "Не найден posm.json: $config" }

Write-Host "Останавливаю старые копии CustomerDisplay ..." -ForegroundColor Cyan
Get-Process CustomerDisplay -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue

New-Item -ItemType Directory -Force -Path "C:\Epharm" | Out-Null

$env:EPHARM_DEBUG = "1"
$env:EPHARM_SCREEN_MODE = "dev"
$env:EPHARM_POSM_CONFIG = $config
$env:EPHARM_APP_LOG = "C:\Epharm\customerdisplay.log"
Remove-Item Env:\EPHARM_LOG_PATH -ErrorAction SilentlyContinue

if (Test-Path $env:EPHARM_APP_LOG) {
  Remove-Item $env:EPHARM_APP_LOG -Force -ErrorAction SilentlyContinue
}

Write-Host "Конфиг: $env:EPHARM_POSM_CONFIG" -ForegroundColor Green
Write-Host "Лог приложения: $env:EPHARM_APP_LOG" -ForegroundColor Green
Write-Host "Лог Стандарт-Н НЕ переопределяю: клиент слушает стандартные zkassa.log пути." -ForegroundColor Green
Write-Host "Клиентский экран: dev-режим, окно слева." -ForegroundColor Green
Write-Host ""
Write-Host "Логи POSM ниже. Для остановки закрой окно приложения или нажми Ctrl+C." -ForegroundColor Yellow
Write-Host "======================================================================" -ForegroundColor DarkGray

$proc = Start-Process -FilePath $exe -WorkingDirectory $root -PassThru
Write-Host ("Приложение запущено, PID={0}. Жду появления лога..." -f $proc.Id) -ForegroundColor DarkGray

$script:LogPosition = 0L
$warnedNoLog = $false
$startedAt = Get-Date

try {
  while ($true) {
    $proc.Refresh()
    Write-NewLogText -Path $env:EPHARM_APP_LOG

    if ($proc.HasExited) { break }

    if (!$warnedNoLog -and !(Test-Path $env:EPHARM_APP_LOG) -and ((Get-Date) - $startedAt).TotalSeconds -ge 10) {
      Write-Host "Лог ещё не создан. Если окно POSM не появилось, проверь распаковку архива и Windows Defender/SmartScreen." -ForegroundColor Yellow
      $warnedNoLog = $true
    }

    Start-Sleep -Milliseconds 250
  }

  Write-NewLogText -Path $env:EPHARM_APP_LOG
} finally {
  if ($proc -and !$proc.HasExited) {
    Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
  }
}

if ($proc.ExitCode -ne 0) {
  throw "CustomerDisplay завершился с кодом $($proc.ExitCode). Смотри лог: $env:EPHARM_APP_LOG"
}

exit 0
'@
[System.IO.File]::WriteAllText(
  (Join-Path $out "run-kassa.ps1"),
  $ps1,
  [System.Text.UTF8Encoding]::new($true))

$bat = @'
@echo off
chcp 65001 >nul
cd /d "%~dp0"
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-kassa.ps1"
if errorlevel 1 pause
'@
Set-Content -Path (Join-Path $out "run.bat") -Value $bat -Encoding Default

# Упаковать в ZIP.
$zip = Join-Path $root ("dist\Epharm-POSM-v{0}-win-x64.zip" -f $Version)
if (Test-Path $zip) { Remove-Item $zip -Force }
Compress-Archive -Path (Join-Path $out "*") -DestinationPath $zip
$sizeMb = [math]::Round((Get-Item $zip).Length / 1MB, 1)

Write-Host ""
Write-Host ("ГОТОВО: {0}  ({1} МБ)" -f $zip, $sizeMb) -ForegroundColor Green
Write-Host "Внутри: CustomerDisplay.exe + рантайм + libvlc, posm.json, run-kassa.ps1, run.bat, README.md, standartn-discover.ps1" -ForegroundColor Green
Write-Host "Отправь этот ZIP. Получатель распаковывает целиком и запускает run.bat или run-kassa.ps1." -ForegroundColor Green
