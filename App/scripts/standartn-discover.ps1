# standartn-discover.ps1 — узнать, КУДА и В КАКОМ ФОРМАТЕ Стандарт-Н пишет события при скане товара.
# Запускать в VM, где установлена Стандарт-Н ДЕМО. PowerShell.
#
#   .\standartn-discover.ps1 find            — найти установку + файлы-кандидаты (log/csv/json/txt/dbf/fdb...)
#   .\standartn-discover.ps1 watch           — поймать, КАКОЙ файл меняется при добавлении товара в чек
#   .\standartn-discover.ps1 dump '<путь>'   — показать хвост файла в cp1251 и utf-8 (увидеть реальный формат)
#
# Если PowerShell блокирует:  powershell -ExecutionPolicy Bypass -File .\standartn-discover.ps1 find

param([ValidateSet('find','watch','dump')][string]$mode='find', [string]$path='')
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
  if (-not $roots) { Write-Host '  не нашёл папок Standart/Apteka/Kassir в C:\ — укажи путь установки вручную' -ForegroundColor Yellow }
  $roots | ForEach-Object { Write-Host "  $_" }

  Write-Host "`n=== 2. Исполняемые (касса) ===" -ForegroundColor Cyan
  foreach ($r in $roots) {
    Get-ChildItem $r -Recurse -Include *.exe -ErrorAction SilentlyContinue |
      Where-Object { $_.Name -match 'Kassir|Kassa|Standart|POS|Apteka' } |
      Select-Object -First 10 | ForEach-Object { Write-Host "  $($_.FullName)" }
  }

  Write-Host "`n=== 3. Файлы-кандидаты на журнал событий (свежие сверху) ===" -ForegroundColor Cyan
  foreach ($r in $roots) {
    Get-ChildItem $r -Recurse -Include *.log,*.csv,*.json,*.txt,*.dat,*.dbf,*.fdb,*.gdb -ErrorAction SilentlyContinue |
      Sort-Object LastWriteTime -Descending | Select-Object -First 40 |
      ForEach-Object { Write-Host ("  {0,9} б  {1}  {2}" -f $_.Length, $_.LastWriteTime.ToString('yyyy-MM-dd HH:mm'), $_.FullName) }
  }
  Write-Host "`nДальше:  .\standartn-discover.ps1 watch   — поймать файл, который пишется при скане." -ForegroundColor Green
}
elseif ($mode -eq 'watch') {
  $roots = Find-Roots
  if (-not $roots) { Write-Host 'Не нашёл установку. Сначала find.' -ForegroundColor Red; return }
  Write-Host "Слежу за папками: $($roots -join '; ')" -ForegroundColor Cyan
  $before = @{}
  foreach ($r in $roots) {
    Get-ChildItem $r -Recurse -File -ErrorAction SilentlyContinue |
      ForEach-Object { $before[$_.FullName] = "$($_.LastWriteTimeUtc.Ticks)|$($_.Length)" }
  }
  Write-Host ("Снято файлов: {0}" -f $before.Count)
  Write-Host "`n>>> ТЕПЕРЬ в Стандарт-Н добавь/отсканируй товар в чек, потом нажми Enter здесь <<<" -ForegroundColor Yellow
  [void](Read-Host)
  $changed = New-Object System.Collections.Generic.List[string]
  foreach ($r in $roots) {
    Get-ChildItem $r -Recurse -File -ErrorAction SilentlyContinue | ForEach-Object {
      $sig = "$($_.LastWriteTimeUtc.Ticks)|$($_.Length)"
      if (-not $before.ContainsKey($_.FullName) -or $before[$_.FullName] -ne $sig) { $changed.Add($_.FullName) }
    }
  }
  if ($changed.Count -eq 0) {
    Write-Host "Ничего не изменилось. Значит Стандарт-Н НЕ пишет файл при скане (события в БД?), либо скан не зафиксировался, либо лог в другом месте." -ForegroundColor Yellow
  } else {
    Write-Host "ИЗМЕНИЛИСЬ при скане (вот сюда пишет Стандарт-Н):" -ForegroundColor Green
    $changed | ForEach-Object { Write-Host "  $_" }
    Write-Host "`nПокажи формат:  .\standartn-discover.ps1 dump '<путь из списка выше>'" -ForegroundColor Green
  }
}
elseif ($mode -eq 'dump') {
  if (-not $path -or -not (Test-Path $path)) { Write-Host "Укажи путь: .\standartn-discover.ps1 dump 'C:\...\file.log'" -ForegroundColor Red; return }
  $bytes = [System.IO.File]::ReadAllBytes($path)
  Write-Host "=== Последние строки в CP1251 (ожидаемая кодировка кассы) ===" -ForegroundColor Cyan
  try {
    $t = [System.Text.Encoding]::GetEncoding(1251).GetString($bytes)
    ($t -split "`r?`n") | Select-Object -Last 25 | ForEach-Object { Write-Host $_ }
  } catch { Write-Host "(не читается как cp1251: $_)" -ForegroundColor Yellow }
  Write-Host "`n=== Те же строки в UTF-8 (вдруг файл в utf-8) ===" -ForegroundColor Cyan
  try {
    $t2 = [System.Text.Encoding]::UTF8.GetString($bytes)
    ($t2 -split "`r?`n") | Select-Object -Last 25 | ForEach-Object { Write-Host $_ }
  } catch {}
  Write-Host "`nСкопируй сюда 10-20 строк (после скана/удаления/закрытия чека) — подгоню парсер под реальный формат." -ForegroundColor Green
}
