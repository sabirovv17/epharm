# publish-exe.ps1 — собирает self-contained .exe POSM-клиента (win-x64) и пакует
# в ZIP для отправки другому разработчику (боевой режим).
#
# ГДЕ ЗАПУСКАТЬ: на Windows с .NET 10 SDK, из КОРНЯ репозитория (где папка App).
# По умолчанию берёт боевой конфиг из C:\Epharm\posm.json (с ключом и прод-URL).
#
#   Set-ExecutionPolicy -Scope Process Bypass -Force
#   .\App\scripts\publish-exe.ps1
#
# Результат: dist\Epharm-POSM-v<версия>-win-x64.zip — его и отправляй.

param(
  [string]$ConfigPath = "C:\Epharm\posm.json",
  [string]$Version = "1.0.0"
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
  -p:Version=$Version -p:DebugType=None -p:DebugSymbols=false -o $out
if ($LASTEXITCODE -ne 0) { throw "dotnet publish завершился с ошибкой." }

# Переименовать лаунчер для брендинга (apphost-обёртку переименовывать безопасно —
# имя главной dll в неё вшито при сборке). Запускать всё равно через run.bat.
$exe = Join-Path $out "CustomerDisplay.exe"
if (Test-Path $exe) { Rename-Item $exe "Epharm-POSM.exe" -Force }

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

# README для получателя (dev-гайд).
$readme = Join-Path $root "App\scripts\README-distrib.md"
if (Test-Path $readme) { Copy-Item $readme (Join-Path $out "README.md") -Force }

# Лаунчер: подставляет путь к конфигу и запускает (с фоллбэком на старое имя exe).
$bat = @'
@echo off
chcp 65001 >nul
cd /d "%~dp0"
set "EPHARM_POSM_CONFIG=%~dp0posm.json"
if exist "%~dp0Epharm-POSM.exe" ( start "" "Epharm-POSM.exe" ) else ( start "" "CustomerDisplay.exe" )
'@
Set-Content -Path (Join-Path $out "run.bat") -Value $bat -Encoding Default

# Упаковать в ZIP.
$zip = Join-Path $root ("dist\Epharm-POSM-v{0}-win-x64.zip" -f $Version)
if (Test-Path $zip) { Remove-Item $zip -Force }
Compress-Archive -Path (Join-Path $out "*") -DestinationPath $zip
$sizeMb = [math]::Round((Get-Item $zip).Length / 1MB, 1)

Write-Host ""
Write-Host ("ГОТОВО: {0}  ({1} МБ)" -f $zip, $sizeMb) -ForegroundColor Green
Write-Host "Внутри: Epharm-POSM.exe + рантайм + libvlc, posm.json, run.bat, README.md" -ForegroundColor Green
Write-Host "Отправь этот ZIP. Получатель распаковывает целиком и жмёт run.bat." -ForegroundColor Green
