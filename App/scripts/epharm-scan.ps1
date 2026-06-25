# Имитация скана товара на кассе — демо рекомендаций Epharm (запускать в VM, Терминал 2).
#
#   .\epharm-scan.ps1           — «скан» Now Витамин С → попап с ДВУМЯ табами: ЗАМЕНА + КРОСС-СЕЛЛ
#   .\epharm-scan.ps1 delete    — удалить товар из чека (проверка scan → delete → scan)
#   .\epharm-scan.ps1 clear     — закрыть/очистить чек (между прогонами)
#
# Один скан этого товара даёт сразу и замену (→ Аскорбинка), и кросс-селл (→ Цинкит).
# Пишет строку в тот же лог, что слушает касса (EPHARM_LOG_PATH или C:\Epharm\zkassa.log), в 1251.
# Если PowerShell блокирует запуск:  powershell -ExecutionPolicy Bypass -File .\epharm-scan.ps1

param([ValidateSet('scan', 'delete', 'clear')][string]$do = 'scan')

$log = if ($env:EPHARM_LOG_PATH) { $env:EPHARM_LOG_PATH } else { 'C:\Epharm\zkassa.log' }
New-Item -ItemType Directory -Force -Path (Split-Path $log) | Out-Null
$enc = [System.Text.Encoding]::GetEncoding(1251)
function Write-Line($s) { [System.IO.File]::AppendAllText($log, "$s`r`n", $enc) }

if ($do -eq 'clear') {
  Write-Line 'RunScriptByIndex После печати очереди чеков'
  Write-Host '-> Чек закрыт/очищен. Можно сканировать заново.' -ForegroundColor Yellow
}
elseif ($do -eq 'delete') {
  Write-Line 'Add2Cheque iPartID=80001(733739006905);sname=Now Витамин С 1000мг;price=6900;quant=0(delete)'
  Write-Host '-> Товар удалён из чека. Повторный scan должен снова показать рекомендацию.' -ForegroundColor Yellow
}
else {
  # EAN в скобках iPartID=<внутр.код>(<штрихкод>) — так касса увидит штрих-код 733739006905.
  Write-Line 'Add2Cheque iPartID=80001(733739006905);sname=Now Витамин С 1000мг;price=6900;quant=1'
  Write-Host '-> Отсканирован "Now Витамин С". На кассе всплывёт окно с ДВУМЯ табами:' -ForegroundColor Green
  Write-Host '   [Замена] Аскорбинка  и  [Кросс-селл] Цинкит. Принять текущий таб: F9, переключить: Tab.' -ForegroundColor Green
}
