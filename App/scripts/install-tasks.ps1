<#
.SYNOPSIS
  Ставит автозапуск кассового POSM-клиента «навсегда»: задача планировщика основного приложения
  (с перезапуском при сбое) + watchdog (ловит зависания). Самодостаточный: кладётся РЯДОМ с exe
  (в распакованную папку сборки) и запускается оттуда — сам находит exe и конфиг.

.DESCRIPTION
  Создаёт две задачи планировщика:

    1. "EpharmPOSM"          — запуск приложения при входе пользователя (ONLOGON),
                               RestartOnFailure (каждую минуту, до 999 раз), без лимита по
                               времени, не глушится на батарее. Покрывает ПАДЕНИЯ процесса.

    2. "EpharmPOSM-Watchdog" — раз в минуту watchdog.ps1: поднимает клиента, если он упал ИЛИ
                               завис (устаревший heartbeat-файл). Покрывает ЗАВИСАНИЯ
                               (которые RestartOnFailure не ловит — процесс жив, но мёртв).

  Что делает дополнительно (чтобы автозапуск работал на ЛЮБОЙ сборке zip):
    - сам находит exe: CustomerDisplay.exe ИЛИ Epharm-POSM.exe в папке установки;
    - копирует posm.json в C:\Epharm\posm.json — путь, который приложение читает ПО УМОЛЧАНИЮ
      (планировщик запускает exe без env, поэтому конфиг должен лежать на дефолтном пути).

  Идемпотентно: повторный запуск пересоздаёт задачи. Запускать ОТ ИМЕНИ АДМИНИСТРАТОРА.

.PARAMETER InstallDir    Папка с exe. По умолчанию — папка этого скрипта (положи рядом с exe).
.PARAMETER ScriptsDir    Папка с watchdog.ps1. По умолчанию — рядом с этим файлом.
.PARAMETER ConfigPath    posm.json. По умолчанию <InstallDir>\posm.json.
.PARAMETER HeartbeatPath Путь heartbeat (должен совпадать с posm.json). По умолчанию C:\Epharm\heartbeat.txt.
.PARAMETER MaxAgeSec     Порог «зависания» для watchdog (сек). По умолчанию 90.

.EXAMPLE
  # из распакованной папки сборки, от администратора:
  powershell -ExecutionPolicy Bypass -File .\install-tasks.ps1
#>
param(
    [string]$InstallDir = $PSScriptRoot,
    [string]$ScriptsDir = $PSScriptRoot,
    [string]$ConfigPath = "",
    [string]$HeartbeatPath = "C:\Epharm\heartbeat.txt",
    # Пусто = взять screenMode из posm.json (единый источник правды). Явный -ScreenMode перекрывает.
    [string]$ScreenMode = "",
    [string]$AppLogPath = "C:\Epharm\customerdisplay.log",
    [int]$MaxAgeSec = 90
)

$ErrorActionPreference = "Stop"

# ── 0. Права администратора (Register-ScheduledTask с RunLevel Highest требует elevation) ────────
$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
).IsInRole([Security.Principal.WindowsBuiltinRole]::Administrator)
if (-not $isAdmin) {
    throw "Запусти от имени АДМИНИСТРАТОРА: ПКМ по «Windows PowerShell» → «Запуск от имени администратора», затем снова выполни этот скрипт."
}

# ── 1. Находим exe (имя зависит от сборки: dev = Epharm-POSM.exe, прод = CustomerDisplay.exe) ────
$exePath = $null
foreach ($n in @("CustomerDisplay.exe", "Epharm-POSM.exe")) {
    $p = Join-Path $InstallDir $n
    if (Test-Path $p) { $exePath = $p; break }
}
if (-not $exePath) {
    throw "Не найден exe (CustomerDisplay.exe или Epharm-POSM.exe) в '$InstallDir'. " +
          "Положи install-tasks.ps1 РЯДОМ с exe (в распакованную папку) или укажи -InstallDir."
}
$exeName = [System.IO.Path]::GetFileName($exePath)
Write-Host "[i] Приложение: $exePath" -ForegroundColor Cyan
foreach ($p in @("CustomerDisplay", "Epharm-POSM")) {
    Get-Process -Name $p -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
}

$watchdog = Join-Path $ScriptsDir "watchdog.ps1"
$hasWatchdog = Test-Path $watchdog
if (-not $hasWatchdog) {
    Write-Warning "watchdog.ps1 не найден в '$ScriptsDir' — задача-watchdog (защита от ЗАВИСАНИЙ) создана НЕ будет. Положи watchdog.ps1 рядом."
}

# ── 2. Конфиг на дефолтный путь C:\Epharm\posm.json (планировщик запускает exe без env) ─────────
New-Item -ItemType Directory -Force -Path "C:\Epharm" | Out-Null
if (-not $ConfigPath) { $ConfigPath = Join-Path $InstallDir "posm.json" }
$defaultCfg = "C:\Epharm\posm.json"
$srcFull = if (Test-Path $ConfigPath) { [System.IO.Path]::GetFullPath($ConfigPath) } else { $ConfigPath }
$dstFull = [System.IO.Path]::GetFullPath($defaultCfg)
if (Test-Path $ConfigPath) {
    if ($srcFull -ieq $dstFull) {
        Write-Host "[i] Конфиг уже на дефолтном пути ($defaultCfg)." -ForegroundColor DarkGray
    } else {
        Copy-Item $ConfigPath $defaultCfg -Force
        Write-Host "[ok] Конфиг скопирован → $defaultCfg (приложение читает его по умолчанию)." -ForegroundColor Green
        try {
            $cfg = Get-Content $defaultCfg -Raw | ConvertFrom-Json
            Write-Host ("    Аптека: {0} | Фармацевт: {1} | Backend: {2}" -f `
                $cfg.pharmacyId, $cfg.pharmacistId, $cfg.backendBaseUrl) -ForegroundColor DarkGray
        } catch {}
    }
} elseif (-not (Test-Path $defaultCfg)) {
    Write-Warning "Конфиг не найден ('$ConfigPath') и '$defaultCfg' отсутствует — приложение стартует ВЫКЛЮЧЕННЫМ. Положи posm.json рядом с exe и перезапусти скрипт."
}

$appTask = "EpharmPOSM"
$wdTask = "EpharmPOSM-Watchdog"
$me = "$env:USERDOMAIN\$env:USERNAME"

# Режим экрана: приоритет — явный -ScreenMode; иначе screenMode из posm.json (единый источник
# правды); если и там нет — dev. Раньше был хардкод "dev", из-за чего setup-autostart.bat всегда
# ставил dev-режим, игнорируя "screenMode":"prod" в конфиге (окно слева вместо киоска на 2-м экране).
if ([string]::IsNullOrWhiteSpace($ScreenMode)) {
    $cfgForMode = if (Test-Path $defaultCfg) { $defaultCfg } elseif (Test-Path $ConfigPath) { $ConfigPath } else { $null }
    if ($cfgForMode) {
        try {
            $modeFromCfg = (Get-Content $cfgForMode -Raw | ConvertFrom-Json).screenMode
            if (-not [string]::IsNullOrWhiteSpace($modeFromCfg)) {
                $ScreenMode = $modeFromCfg
                Write-Host "[i] screenMode из posm.json: $ScreenMode" -ForegroundColor Cyan
            }
        } catch { }
    }
}
if ([string]::IsNullOrWhiteSpace($ScreenMode)) { $ScreenMode = "dev" }
$ScreenMode = $ScreenMode.Trim().ToLowerInvariant()
if ($ScreenMode -ne "prod") { $ScreenMode = "dev" }

# ── 2.5. Единый launcher для автозапуска и watchdog ────────────────────────────────────────────
# Нельзя запускать exe напрямую: тогда задача планировщика не передаёт dev/prod env-параметры,
# и окно может открыться не в левом DEV-режиме. Launcher выставляет окружение перед стартом.
$appLauncher = Join-Path $InstallDir "start-posm.ps1"
$appLauncherBody = @'
param(
    [Parameter(Mandatory=$true)][string]$ExePath,
    [Parameter(Mandatory=$true)][string]$ConfigPath,
    [string]$ScreenMode = "dev",
    [string]$AppLogPath = "C:\Epharm\customerdisplay.log"
)

$ErrorActionPreference = "Stop"

$env:EPHARM_POSM_CONFIG = $ConfigPath
$env:EPHARM_SCREEN_MODE = $ScreenMode
$env:EPHARM_APP_LOG = $AppLogPath
Remove-Item Env:\EPHARM_LOG_PATH -ErrorAction SilentlyContinue

if ($ScreenMode -eq "dev") {
    $env:EPHARM_DEBUG = "1"
} else {
    Remove-Item Env:\EPHARM_DEBUG -ErrorAction SilentlyContinue
}

Start-Process -FilePath $ExePath -WorkingDirectory (Split-Path -Parent $ExePath)
'@
[System.IO.File]::WriteAllText($appLauncher, $appLauncherBody, [System.Text.UTF8Encoding]::new($true))

$appHiddenLauncher = Join-Path $InstallDir "start-posm-hidden.vbs"
$appPsArgs = "-NoLogo -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$appLauncher`" " +
             "-ExePath `"$exePath`" -ConfigPath `"$defaultCfg`" -ScreenMode `"$ScreenMode`" -AppLogPath `"$AppLogPath`""
$appCommand = "powershell.exe $appPsArgs"
$appCommandForVbs = $appCommand.Replace('"', '""')
$appVbs = "Set sh = CreateObject(""WScript.Shell"")`r`n" +
          "sh.Run ""$appCommandForVbs"", 0, False`r`n"
[System.IO.File]::WriteAllText($appHiddenLauncher, $appVbs, [System.Text.UTF8Encoding]::new($false))
Write-Host "[ok] Launcher создан: screenMode=$ScreenMode, log=$AppLogPath" -ForegroundColor Green

# ── 3. Основное приложение: ONLOGON + RestartOnFailure ──────────────────────────────────────────
$appAction = New-ScheduledTaskAction -Execute "wscript.exe" -Argument "`"$appHiddenLauncher`"" -WorkingDirectory $InstallDir
$appTrigger = New-ScheduledTaskTrigger -AtLogOn
$appPrincipal = New-ScheduledTaskPrincipal -UserId $me -LogonType Interactive -RunLevel Highest
$appSettings = New-ScheduledTaskSettingsSet `
    -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -RestartInterval (New-TimeSpan -Minutes 1) -RestartCount 999 `
    -ExecutionTimeLimit (New-TimeSpan -Seconds 0) `
    -MultipleInstances IgnoreNew `
    -StartWhenAvailable

Register-ScheduledTask -TaskName $appTask -Action $appAction -Trigger $appTrigger `
    -Principal $appPrincipal -Settings $appSettings -Force | Out-Null
Write-Host "[ok] Задача '$appTask' создана (ONLOGON, launcher -> exe=$exeName, screenMode=$ScreenMode)." -ForegroundColor Green

# ── 4. Watchdog: ONLOGON + повтор каждую минуту (если watchdog.ps1 рядом) ────────────────────────
if ($hasWatchdog) {
    # Запуск watchdog через wscript/VBS нужен, чтобы раз в минуту НЕ мигало окно PowerShell.
    # -WindowStyle Hidden у powershell.exe в интерактивной задаче не всегда предотвращает flash.
    $hiddenLauncher = Join-Path $InstallDir "watchdog-hidden.vbs"
    $wdPsArgs = "-NoLogo -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$watchdog`" " +
                "-ExePath `"$exePath`" -ConfigPath `"$defaultCfg`" -ScreenMode `"$ScreenMode`" " +
                "-AppLogPath `"$AppLogPath`" -HeartbeatPath `"$HeartbeatPath`" -MaxAgeSec $MaxAgeSec"
    $wdCommand = "powershell.exe $wdPsArgs"
    $wdCommandForVbs = $wdCommand.Replace('"', '""')
    $vbs = "Set sh = CreateObject(""WScript.Shell"")`r`n" +
           "sh.Run ""$wdCommandForVbs"", 0, False`r`n"
    [System.IO.File]::WriteAllText($hiddenLauncher, $vbs, [System.Text.UTF8Encoding]::new($false))
    $wdAction = New-ScheduledTaskAction -Execute "wscript.exe" -Argument "`"$hiddenLauncher`""

    $wdTrigger = New-ScheduledTaskTrigger -AtLogOn
    $rep = (New-ScheduledTaskTrigger -Once -At (Get-Date) `
            -RepetitionInterval (New-TimeSpan -Minutes 1) `
            -RepetitionDuration (New-TimeSpan -Days 3650)).Repetition
    $wdTrigger.Repetition = $rep

    $wdPrincipal = New-ScheduledTaskPrincipal -UserId $me -LogonType Interactive -RunLevel Highest
    $wdSettings = New-ScheduledTaskSettingsSet `
        -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
        -ExecutionTimeLimit (New-TimeSpan -Minutes 5) `
        -MultipleInstances IgnoreNew `
        -StartWhenAvailable

    Register-ScheduledTask -TaskName $wdTask -Action $wdAction -Trigger $wdTrigger `
        -Principal $wdPrincipal -Settings $wdSettings -Force | Out-Null
    Write-Host "[ok] Задача '$wdTask' создана (проверка каждую минуту: падение/зависание)." -ForegroundColor Green
}

# ── 5. Запустить приложение сейчас (single-instance mutex не даст дубля) ─────────────────────────
try { Start-ScheduledTask -TaskName $appTask; Write-Host "[ok] '$appTask' запущена сейчас." -ForegroundColor Green } catch {}

# ── 6. Проверка результата ───────────────────────────────────────────────────────────────────────
Start-Sleep -Seconds 2
Write-Host ""
Write-Host "── Проверка ──────────────────────────────────────────────" -ForegroundColor DarkGray
Get-ScheduledTask EpharmPOSM* | Select-Object TaskName, State | Format-Table -AutoSize
$proc = Get-Process ([System.IO.Path]::GetFileNameWithoutExtension($exeName)) -ErrorAction SilentlyContinue
if ($proc) { Write-Host ("[ok] Процесс запущен: {0} (PID={1})." -f $exeName, $proc.Id) -ForegroundColor Green }
else { Write-Warning "Процесс ещё не виден — проверь лог приложения (по умолчанию Рабочий стол\customerdisplay.log)." }

Write-Host ""
Write-Host "Готово. Приложение поднимается при входе пользователя и переживает падение, kill," -ForegroundColor Green
Write-Host "зависание, ребут и Windows Update." -ForegroundColor Green
Write-Host ""
Write-Host "ВАЖНО для моноблока-киоска: триггер ONLOGON срабатывает при ВХОДЕ пользователя." -ForegroundColor Yellow
Write-Host "Чтобы касса стартовала сама при ВКЛЮЧЕНИИ (без ручного входа) — включи автологин Windows" -ForegroundColor Yellow
Write-Host "(надёжнее всего Sysinternals Autologon: пароль хранится зашифрованным LSA-секретом)." -ForegroundColor Yellow
Write-Host "Снять автозапуск: .\uninstall-tasks.ps1" -ForegroundColor DarkGray
