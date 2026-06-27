# standartn-discover.ps1 — узнать, КАК интегрироваться с реальной Стандарт-Н (а не с выдуманным логом).
# Запускать в VM со Стандарт-Н ДЕМО.
#
#   .\standartn-discover.ps1 find    — найти установку + файлы-кандидаты (log/csv/dbf/fdb...)
#   .\standartn-discover.ps1 tms     — найти ТМС-скрипты кассы (Object Pascal: ZKassa/ChequeList/P_Name) <-- ГЛАВНОЕ
#   .\standartn-discover.ps1 sql     — найти MS SQL Server (СУБД Стандарт-Н) и его экземпляры/базы
#   .\standartn-discover.ps1 watch   — поймать, какой ФАЙЛ меняется при добавлении товара в чек
#   .\standartn-discover.ps1 dump '<путь>'  — показать хвост файла в cp1251/utf8
#   .\standartn-discover.ps1 schema  — ВЫГРУЗИТЬ СХЕМУ MS SQL: базы → таблицы → поля (фокус чек/продажа/товар) <-- ПОЛЯ БД
#   .\standartn-discover.ps1 cols '<server>' '<db>' '<table>'  — все поля одной таблицы (тип/длина/NULL)
#   .\standartn-discover.ps1 peek '<server>' '<db>' '<table>'  — первые 5 строк данных таблицы
#
# По умолчанию подключение Windows-аутентификацией. Если нужен SQL-логин:  ... schema -user sa -pass <пароль>
# Если PowerShell блокирует:  powershell -ExecutionPolicy Bypass -File .\standartn-discover.ps1 tms

param([ValidateSet('find','tms','sql','watch','dump','schema','cols','peek')][string]$mode='find',
      [string]$path='', [string]$db='', [string]$table='', [string]$user='', [string]$pass='')
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch {}

# ── MS SQL: подключение через .NET SqlClient (без зависимости от sqlcmd/модулей) ──────────────
function New-ConnStr($server, $database) {
  if ($user) { "Server=$server;Database=$database;User Id=$user;Password=$pass;Connect Timeout=5;TrustServerCertificate=True" }
  else       { "Server=$server;Database=$database;Integrated Security=SSPI;Connect Timeout=5;TrustServerCertificate=True" }
}
function Invoke-Sql($server, $database, $query) {
  $cn = New-Object System.Data.SqlClient.SqlConnection (New-ConnStr $server $database)
  $cn.Open()
  try {
    $cmd = $cn.CreateCommand(); $cmd.CommandText = $query
    $da = New-Object System.Data.SqlClient.SqlDataAdapter $cmd
    $dt = New-Object System.Data.DataTable
    [void]$da.Fill($dt)
    return ,$dt
  } finally { $cn.Close() }
}
function Find-SqlServers {
  $servers = @()
  try {
    $r = Get-ItemProperty 'HKLM:\SOFTWARE\Microsoft\Microsoft SQL Server\Instance Names\SQL' -ErrorAction Stop
    foreach ($pn in $r.PSObject.Properties) {
      if ($pn.Name -match '^PS') { continue }
      if ($pn.Name -eq 'MSSQLSERVER') { $servers += 'localhost' } else { $servers += "localhost\$($pn.Name)" }
    }
  } catch {}
  $servers += @('localhost','.\SQLEXPRESS','localhost\SQLEXPRESS','.\STANDARTN','localhost\STANDARTN','(localdb)\MSSQLLocalDB')
  $servers | Select-Object -Unique
}
function Get-Reachable($servers) {
  $ok = @()
  foreach ($s in $servers) {
    try { [void](Invoke-Sql $s 'master' 'SELECT 1'); Write-Host "  доступен: $s" -ForegroundColor Green; $ok += $s }
    catch { Write-Host "  нет: $s" -ForegroundColor DarkGray }
  }
  $ok
}

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
elseif ($mode -eq 'schema') {
  Write-Host '=== MS SQL: инстансы → базы → поля (фокус: чек/продажа/товар) ===' -ForegroundColor Cyan
  $servers = if ($path) { @($path) } else { Find-SqlServers }
  Write-Host "Проверяю серверы:" -ForegroundColor Cyan
  $reachable = Get-Reachable $servers
  if (-not $reachable) {
    Write-Host "`nНи один SQL не ответил (Windows-auth). Варианты:" -ForegroundColor Yellow
    Write-Host "  • SQL-логин:  .\standartn-discover.ps1 schema '<server>' -user sa -pass <пароль>" -ForegroundColor Yellow
    Write-Host "  • Узнать сервер:  .\standartn-discover.ps1 sql   (службы MSSQL$...)" -ForegroundColor Yellow
    return
  }
  # ключевые таблицы кассы: чек/продажа/товар/позиция/документ/касса
  $kw = 'che.?k|chek|чек|sale|prod|прод|tovar|товар|goods|posit|позиц|stro|строк|item|doc|документ|cash|kass|касс|receipt|realiz|реализ'
  foreach ($s in $reachable) {
    $dbs = if ($db) { @($db) } else {
      (Invoke-Sql $s 'master' "SELECT name FROM sys.databases WHERE database_id>4 AND state=0 ORDER BY name").Rows | ForEach-Object { "$($_.name)" }
    }
    if (-not $dbs) { Write-Host "  на $s нет пользовательских баз (только системные)" -ForegroundColor DarkGray; continue }
    foreach ($d in $dbs) {
      Write-Host "`n################  БД [$d]  на  $s  ################" -ForegroundColor Cyan
      $q = "SELECT t.TABLE_NAME AS n, ISNULL(p.rows,0) AS rows " +
           "FROM INFORMATION_SCHEMA.TABLES t " +
           "LEFT JOIN sys.tables st ON st.name = t.TABLE_NAME " +
           "LEFT JOIN sys.partitions p ON p.object_id = st.object_id AND p.index_id IN (0,1) " +
           "WHERE t.TABLE_TYPE = 'BASE TABLE' ORDER BY ISNULL(p.rows,0) DESC, t.TABLE_NAME"
      $tbls = $null
      try { $tbls = Invoke-Sql $s $d $q } catch { Write-Host "  ! нет доступа к [$d]: $($_.Exception.Message)" -ForegroundColor Red; continue }
      Write-Host ("  всего таблиц: {0}" -f $tbls.Rows.Count)
      $hot = @($tbls.Rows | Where-Object { "$($_.n)" -match $kw })
      if (-not $hot) {
        Write-Host "  ключевых (чек/продажа) по имени не нашёл. Топ-20 таблиц по строкам:" -ForegroundColor Yellow
        $tbls.Rows | Select-Object -First 20 | ForEach-Object { Write-Host ("    {0,-34} ~{1} строк" -f $_.n,$_.rows) }
        Write-Host "  Поля любой:  .\standartn-discover.ps1 cols '$s' '$d' '<табл>'" -ForegroundColor DarkGray
        continue
      }
      Write-Host "  --- ключевые таблицы ---" -ForegroundColor Yellow
      $hot | ForEach-Object { Write-Host ("    {0,-34} ~{1} строк" -f $_.n,$_.rows) -ForegroundColor Green }
      foreach ($h in $hot) {
        $cols = Invoke-Sql $s $d "SELECT COLUMN_NAME c, DATA_TYPE dt, CHARACTER_MAXIMUM_LENGTH len, IS_NULLABLE nu FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='$($h.n)' ORDER BY ORDINAL_POSITION"
        Write-Host ("`n  [{0}]  (~{1} строк)" -f $h.n,$h.rows) -ForegroundColor Green
        $cols.Rows | ForEach-Object {
          $len = if ($_.len -and "$($_.len)" -ne '' -and [int]"$($_.len)" -gt 0) { "($($_.len))" } else { '' }
          $nu  = if ($_.nu -eq 'YES') { 'null' } else { 'NOT NULL' }
          Write-Host ("       {0,-30} {1}{2}  {3}" -f $_.c,$_.dt,$len,$nu)
        }
      }
      Write-Host "`n  Данные:  .\standartn-discover.ps1 peek '$s' '$d' '<табл>'" -ForegroundColor DarkGray
    }
  }
}
elseif ($mode -eq 'cols') {
  if (-not $path -or -not $db -or -not $table) { Write-Host "Использование: cols '<server>' '<db>' '<table>'" -ForegroundColor Red; return }
  $cols = Invoke-Sql $path $db "SELECT ORDINAL_POSITION p, COLUMN_NAME c, DATA_TYPE dt, CHARACTER_MAXIMUM_LENGTH len, IS_NULLABLE nu FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='$table' ORDER BY ORDINAL_POSITION"
  if ($cols.Rows.Count -eq 0) { Write-Host "Таблица [$table] не найдена в [$db]." -ForegroundColor Yellow; return }
  Write-Host "=== поля [$db].[$table] ($($cols.Rows.Count)) ===" -ForegroundColor Cyan
  $cols.Rows | ForEach-Object {
    $len = if ($_.len -and "$($_.len)" -ne '' -and [int]"$($_.len)" -gt 0) { "($($_.len))" } else { '' }
    $nu  = if ($_.nu -eq 'YES') { 'null' } else { 'NOT NULL' }
    Write-Host ("  {0,3}. {1,-32} {2}{3}  {4}" -f $_.p,$_.c,$_.dt,$len,$nu)
  }
}
elseif ($mode -eq 'peek') {
  if (-not $path -or -not $db -or -not $table) { Write-Host "Использование: peek '<server>' '<db>' '<table>'" -ForegroundColor Red; return }
  $dt = Invoke-Sql $path $db "SELECT TOP 5 * FROM [$table]"
  Write-Host "=== первые 5 строк [$db].[$table] ===" -ForegroundColor Cyan
  ($dt | Format-Table -AutoSize | Out-String -Width 5000) | Write-Host
}
