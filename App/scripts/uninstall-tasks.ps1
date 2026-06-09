<#
.SYNOPSIS
  Удаляет задачи автозапуска и watchdog кассового POSM-клиента Epharm.
.DESCRIPTION
  Останавливает и удаляет "EpharmPOSM" и "EpharmPOSM-Watchdog". Запускать от администратора.
  Само приложение (CustomerDisplay.exe) при этом не удаляется — снимается только автозапуск.
#>
$ErrorActionPreference = "SilentlyContinue"
foreach ($t in @("EpharmPOSM", "EpharmPOSM-Watchdog")) {
    if (Get-ScheduledTask -TaskName $t -ErrorAction SilentlyContinue) {
        Stop-ScheduledTask -TaskName $t
        Unregister-ScheduledTask -TaskName $t -Confirm:$false
        Write-Host "[ok] Задача '$t' удалена."
    } else {
        Write-Host "[skip] Задача '$t' не найдена."
    }
}
Write-Host "Готово. Автозапуск снят. Процесс CustomerDisplay (если запущен) можно закрыть вручную."
