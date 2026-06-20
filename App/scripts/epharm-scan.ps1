# Имитация скана товара на кассе — для демо рекомендаций Epharm (запускать в VM, Терминал 2).
#
#   .\epharm-scan.ps1 zamena   — «скан» Now Витамин С  → попап ЗАМЕНА (Аскорбинка)
#   .\epharm-scan.ps1 cross    — «скан» Аскорбинка     → попап КРОСС-СЕЛЛ (Цинкит)
#   .\epharm-scan.ps1 clear    — закрыть/очистить чек (между сценариями)
#
# Пишет строку в тот же лог, что слушает касса (EPHARM_LOG_PATH или C:\Epharm\zkassa.log),
# в кодировке 1251. Касса уже запущена в Терминале 1 — попап всплывёт сам через ~0.5 с.
# Если PowerShell блокирует запуск:  powershell -ExecutionPolicy Bypass -File .\epharm-scan.ps1 zamena

param([Parameter(Mandatory = $true)][ValidateSet('zamena', 'cross', 'clear')][string]$do)

$log = if ($env:EPHARM_LOG_PATH) { $env:EPHARM_LOG_PATH } else { 'C:\Epharm\zkassa.log' }
New-Item -ItemType Directory -Force -Path (Split-Path $log) | Out-Null
$enc = [System.Text.Encoding]::GetEncoding(1251)
function Write-Line($s) { [System.IO.File]::AppendAllText($log, "$s`r`n", $enc) }

switch ($do) {
  'zamena' {
    Write-Line 'Add2Cheque iPartID=80001(733739006905);sname=Now Витамин С 1000мг;price=6900;quant=1'
    Write-Host '-> Отсканирован "Now Витамин С". Жди попап ЗАМЕНЫ на экране кассы. Принять: F9.' -ForegroundColor Green
  }
  'cross' {
    Write-Line 'Add2Cheque iPartID=80002(4870004560307);sname=Аскорбиновая кислота вива фарм;price=1200;quant=1'
    Write-Host '-> Отсканирована "Аскорбинка". Жди попап КРОСС-СЕЛЛА (Цинкит). Принять: F9.' -ForegroundColor Green
  }
  'clear' {
    Write-Line 'RunScriptByIndex После печати очереди чеков'
    Write-Host '-> Чек закрыт/очищен. Можно сканировать следующий сценарий.' -ForegroundColor Yellow
  }
}
