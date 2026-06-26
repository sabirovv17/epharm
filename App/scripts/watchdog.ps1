<#
.SYNOPSIS
  Watchdog кассового POSM-клиента Epharm. Гарантирует, что прослушка логов Стандарт-Н
  работает «всегда»: перезапускает CustomerDisplay.exe, если процесс УПАЛ или ЗАВИС.

.DESCRIPTION
  Запускается задачей планировщика "EpharmPOSM-Watchdog" раз в минуту. Проверяет:
    1. Запущен ли процесс. Нет → запускает exe.
    2. Свежий ли heartbeat-файл (его пишет UI-поток приложения каждые ~15с).
       Если процесс жив, но heartbeat старше MaxAgeSec → приложение зависло (deadlock UI):
       убивает процесс и запускает заново.

  Падения и так подхватывает RestartOnFailure самой задачи EpharmPOSM; этот watchdog
  закрывает второй сценарий — ЗАВИСАНИЕ, которое RestartOnFailure не ловит (процесс жив).

  Всё fail-safe: ошибки только логируются, watchdog не падает.

.PARAMETER ExePath        Полный путь к CustomerDisplay.exe.
.PARAMETER HeartbeatPath  Путь к heartbeat-файлу (как в posm.json HeartbeatPath).
.PARAMETER MaxAgeSec      Порог устаревания heartbeat в секундах (по умолчанию 90).
.PARAMETER LogPath        Лог watchdog.
#>
param(
    [string]$ExePath = "C:\epharm\app\CustomerDisplay.exe",
    [string]$HeartbeatPath = "C:\Epharm\heartbeat.txt",
    [int]$MaxAgeSec = 90,
    [string]$LogPath = "C:\Epharm\watchdog.log"
)

function Write-Log([string]$msg) {
    try {
        $dir = Split-Path -Parent $LogPath
        if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
        Add-Content -Path $LogPath -Value ("{0} {1}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $msg)
    } catch { }
}

function Start-Posm() {
    try {
        Start-Process -FilePath $ExePath -WorkingDirectory (Split-Path -Parent $ExePath)
        Write-Log "started: $ExePath"
    } catch {
        Write-Log "start FAILED: $($_.Exception.Message)"
    }
}

try {
    $procName = [System.IO.Path]::GetFileNameWithoutExtension($ExePath)
    $proc = Get-Process -Name $procName -ErrorAction SilentlyContinue

    if (-not $proc) {
        Write-Log "process '$procName' not running -> starting"
        Start-Posm
        return
    }

    # Процесс жив — проверяем, не завис ли (по свежести heartbeat).
    if (Test-Path $HeartbeatPath) {
        $ageSec = ((Get-Date) - (Get-Item $HeartbeatPath).LastWriteTime).TotalSeconds
        if ($ageSec -gt $MaxAgeSec) {
            Write-Log ("HUNG: heartbeat stale {0:N0}s (> {1}s) -> kill + restart" -f $ageSec, $MaxAgeSec)
            try { Stop-Process -Name $procName -Force -ErrorAction SilentlyContinue } catch { }
            Start-Sleep -Seconds 2
            Start-Posm
        }
        # иначе всё хорошо — молча выходим (без спама в лог)
    } else {
        # Heartbeat ещё не создан (приложение только стартовало) — не трогаем, дадим время.
        Write-Log "process alive, heartbeat file not yet present (grace) — skip"
    }
} catch {
    Write-Log "watchdog error (fail-safe): $($_.Exception.Message)"
}
