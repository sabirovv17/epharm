<#
.SYNOPSIS
  Устанавливает автозапуск кассового POSM-клиента «навсегда»: две задачи планировщика —
  основное приложение (с перезапуском при сбое) и watchdog (ловит зависания).

.DESCRIPTION
  Заменяет ручные `schtasks` из POSM_DEPLOY.md. Создаёт:

    1. "EpharmPOSM"          — запуск CustomerDisplay.exe при входе пользователя,
                               RestartOnFailure (каждую минуту, до 999 раз),
                               без ограничения по времени, не глушится на батарее.
                               Это покрывает ПАДЕНИЯ процесса.

    2. "EpharmPOSM-Watchdog" — раз в минуту запускает watchdog.ps1, который поднимает
                               клиента, если он упал ИЛИ завис (устаревший heartbeat).
                               Это покрывает ЗАВИСАНИЯ (которые RestartOnFailure не ловит).

  Идемпотентно: повторный запуск пересоздаёт задачи. Запускать ОТ ИМЕНИ АДМИНИСТРАТОРА.

.PARAMETER InstallDir   Папка установки (где CustomerDisplay.exe). По умолчанию C:\epharm\app.
.PARAMETER ScriptsDir   Папка со скриптами (watchdog.ps1). По умолчанию рядом с этим файлом.
.PARAMETER HeartbeatPath Путь heartbeat (должен совпадать с posm.json). По умолчанию C:\Epharm\heartbeat.txt.
.PARAMETER MaxAgeSec    Порог «зависания» для watchdog (сек). По умолчанию 90.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File install-tasks.ps1
#>
param(
    [string]$InstallDir = "C:\epharm\app",
    [string]$ScriptsDir = $PSScriptRoot,
    [string]$HeartbeatPath = "C:\Epharm\heartbeat.txt",
    [int]$MaxAgeSec = 90
)

$ErrorActionPreference = "Stop"
$exePath = Join-Path $InstallDir "CustomerDisplay.exe"
$watchdog = Join-Path $ScriptsDir "watchdog.ps1"
$appTask = "EpharmPOSM"
$wdTask = "EpharmPOSM-Watchdog"
$me = "$env:USERDOMAIN\$env:USERNAME"

if (-not (Test-Path $exePath)) {
    Write-Warning "Не найден $exePath — убедись, что приложение опубликовано (см. POSM_DEPLOY.md §1)."
}

# ── 1. Основное приложение: ONLOGON + RestartOnFailure ──────────────────────────
$appAction = New-ScheduledTaskAction -Execute $exePath -WorkingDirectory $InstallDir
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
Write-Host "[ok] Задача '$appTask' создана (ONLOGON + restart-on-failure)."

# ── 2. Watchdog: ONLOGON + повтор каждую минуту, бесконечно ──────────────────────
$wdArgs = "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$watchdog`" " +
          "-ExePath `"$exePath`" -HeartbeatPath `"$HeartbeatPath`" -MaxAgeSec $MaxAgeSec"
$wdAction = New-ScheduledTaskAction -Execute "powershell.exe" -Argument $wdArgs

# AtLogOn + repetition каждую минуту (приём: берём Repetition у Once-триггера).
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
Write-Host "[ok] Задача '$wdTask' создана (проверка каждую минуту: падение/зависание)."

# Запустить приложение сейчас (если ещё не запущено — mutex не даст дубля).
try { Start-ScheduledTask -TaskName $appTask; Write-Host "[ok] '$appTask' запущена." } catch { }

Write-Host ""
Write-Host "Готово. Прослушка логов Стандарт-Н теперь поднимается при входе и переживает"
Write-Host "падение, kill, зависание, ребут и Windows Update. Проверка: Get-ScheduledTask EpharmPOSM*"
