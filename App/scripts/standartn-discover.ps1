# standartn-discover.ps1 — узнать, КАК интегрироваться с реальной Стандарт-Н (а не с выдуманным логом).
# Запускать в VM со Стандарт-Н ДЕМО.
#
#   .\standartn-discover.ps1 find    — найти установку + файлы-кандидаты (log/csv/dbf/fdb...)
#   .\standartn-discover.ps1 tms     — найти ТМС-скрипты кассы (Object Pascal: ZKassa/ChequeList/P_Name) <-- ГЛАВНОЕ
#   .\standartn-discover.ps1 sql     — найти MS SQL Server (СУБД Стандарт-Н) и его экземпляры/базы
#   .\standartn-discover.ps1 watch   — поймать, какой ФАЙЛ меняется при добавлении товара в чек
#   .\standartn-discover.ps1 dump '<путь>'  — показать хвост файла в cp1251/utf8
#
# Если PowerShell блокирует:  powershell -ExecutionPolicy Bypass -File .\standartn-discover.ps1 tms

param([ValidateSet('find','tms','sql','watch','dump')][string]$mode='find', [string]$path='')
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch {}

function Find-Roots {
  $cands = @('C:\Standart-N','C:\Standart-N_DEMO','C:\StandartN','C:\Standart_N',
             'C:\Program Files\Standart-N','C:\Program Files (x86)\Standart-N',
             'C:\Program Files (x86)\Standart_N')
  $found = @($cands | Where-Object { Test-Path $_ })
  $found += Get-ChildItem 'C:\' -Directory -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -match 'Standart|Apteka|Kassir' } | ForEach-Object { $_.FullName }
  $found | Select-Object -Unique
}

if ($mode -eq 'find') {
  Write-Host '=== 1. Папки Стандарт-Н ===' -ForegroundColor Cyan
  $roots = Find-Roots
  if (-not $roots) { Write-Host '  не нашёл папок Standart/Apteka/Kassir в C:\ — укажи путь вручную' -ForegroundColor Yellow }
  $roots | ForEach-Object { Write-Host "  $_" }
  Write-Host "`n=== 2. Исполняемые (касса) ===" -ForegroundColor Cyan
  foreach ($r in $roots) {
    Get-ChildItem $r -Recurse -Include *.exe -ErrorAction SilentlyContinue |
      Where-Object { $_.Name -match 'Kassir|Kassa|Standart|POS|Apteka' } |
      Select-Object -First 10 | ForEach-Object { Write-Host "  $($_.FullName)" }
  }
  Write-Host "`n=== 3. Файлы-кандидаты на журнал/обмен (свежие сверху) ===" -ForegroundColor Cyan
  foreach ($r in $roots) {
    Get-ChildItem $r -Recurse -Include *.log,*.csv,*.json,*.txt,*.dat,*.dbf,*.fdb,*.gdb,*.mdb -ErrorAction SilentlyContinue |
      Sort-Object LastWriteTime -Descending | Select-Object -First 40 |
      ForEach-Object { Write-Host ("  {0,9} б  {1}  {2}" -f $_.Length, $_.LastWriteTime.ToString('yyyy-MM-dd HH:mm'), $_.FullName) }
  }
  Write-Host "`nДальше:  tms (скрипты),  sql (база),  watch (какой файл пишется при скане)." -ForegroundColor Green
}
elseif ($mode -eq 'tms') {
  Write-Host '=== ТМС-скрипты кассы (Object Pascal) — главный канал интеграции ===' -ForegroundColor Cyan
  $roots = Find-Roots
  $hit = 0
  foreach ($r in $roots) {
    Get-ChildItem $r -Recurse -Include *.pas,*.tms,*.script,*.inc -ErrorAction SilentlyContinue |
      Where-Object { $_.Length -lt 2000000 } | ForEach-Object {
        $c = ''
        try { $c = [System.IO.File]::ReadAllText($_.FullName) } catch {}
        if ($c -match 'ZKassa|ChequeList|P_Name|RunScript|ActiveIID|P_Price|P_Quant') {
          $hit++
          Write-Host "  НАЙДЕН ТМС-скрипт: $($_.FullName)" -ForegroundColor Green
        }
      }
  }
  if ($hit -eq 0) { Write-Host '  Файлов-скриптов на диске не нашёл (могут лежать в БД/закрыты).' -ForegroundColor Yellow }
  Write-Host "`nВ самой АРМ Кассир поищи редактор: Настройки/Сервис → 'Скрипты' / 'ТМС' / 'Настройки кассира'." -ForegroundColor Yellow
  Write-Host "Если редактор есть — это ОН: можно повесить скрипт на событие 'после добавления позиции'." -ForegroundColor Yellow
}
elseif ($mode -eq 'sql') {
  Write-Host '=== MS SQL Server (СУБД Стандарт-Н) ===' -ForegroundColor Cyan
  $svc = Get-Service -ErrorAction SilentlyContinue | Where-Object { $_.Name -match 'MSSQL|SQLEXPRESS|SQL Server' }
  if ($svc) { $svc | ForEach-Object { Write-Host "  Служба: $($_.Name)  [$($_.Status)]" -ForegroundColor Green } }
  else { Write-Host '  Служб MS SQL не вижу — возможно, демо без SQL или другой экземпляр.' -ForegroundColor Yellow }
  Write-Host "`nЭкземпляры (sqlcmd -L, если установлен):" -ForegroundColor Cyan
  try { & sqlcmd -L 2>$null } catch { Write-Host '  sqlcmd не найден (это ок).' -ForegroundColor Yellow }
  Write-Host "`nПапки SQL:" -ForegroundColor Cyan
  Get-ChildItem 'C:\Program Files\Microsoft SQL Server','C:\Program Files (x86)\Microsoft SQL Server' -Directory -ErrorAction SilentlyContinue |
    ForEach-Object { Write-Host "  $($_.FullName)" }
}
elseif ($mode -eq 'watch') {
  $roots = Find-Roots
  if (-not $roots) { Write-Host 'Не нашёл установку. Сначала find.' -ForegroundColor Red; return }
  Write-Host "Слежу за: $($roots -join '; ')" -ForegroundColor Cyan
  $before = @{}
  foreach ($r in $roots) { Get-ChildItem $r -Recurse -File -ErrorAction SilentlyContinue | ForEach-Object { $before[$_.FullName] = "$($_.LastWriteTimeUtc.Ticks)|$($_.Length)" } }
  Write-Host ("Снято файлов: {0}" -f $before.Count)
  Write-Host "`n>>> ТЕПЕРЬ в Стандарт-Н добавь/отсканируй товар в чек, потом нажми Enter <<<" -ForegroundColor Yellow
  [void](Read-Host)
  $changed = New-Object System.Collections.Generic.List[string]
  foreach ($r in $roots) {
    Get-ChildItem $r -Recurse -File -ErrorAction SilentlyContinue | ForEach-Object {
      $sig = "$($_.LastWriteTimeUtc.Ticks)|$($_.Length)"
      if (-not $before.ContainsKey($_.FullName) -or $before[$_.FullName] -ne $sig) { $changed.Add($_.FullName) }
    }
  }
  if ($changed.Count -eq 0) { Write-Host "Ничего не изменилось → Стандарт-Н пишет НЕ в файл (события в MS SQL?). Тогда канал = ТМС-скрипт или база." -ForegroundColor Yellow }
  else { Write-Host "ИЗМЕНИЛИСЬ при скане:" -ForegroundColor Green; $changed | ForEach-Object { Write-Host "  $_" }; Write-Host "`ndump '<путь>' — покажет формат." -ForegroundColor Green }
}
elseif ($mode -eq 'dump') {
  if (-not $path -or -not (Test-Path $path)) { Write-Host "Укажи путь: .\standartn-discover.ps1 dump 'C:\...\file'" -ForegroundColor Red; return }
  $bytes = [System.IO.File]::ReadAllBytes($path)
  Write-Host "=== хвост в CP1251 ===" -ForegroundColor Cyan
  try { ([System.Text.Encoding]::GetEncoding(1251).GetString($bytes) -split "`r?`n") | Select-Object -Last 25 | ForEach-Object { Write-Host $_ } } catch { Write-Host "(не cp1251: $_)" -ForegroundColor Yellow }
  Write-Host "`n=== хвост в UTF-8 ===" -ForegroundColor Cyan
  try { ([System.Text.Encoding]::UTF8.GetString($bytes) -split "`r?`n") | Select-Object -Last 25 | ForEach-Object { Write-Host $_ } } catch {}
}
