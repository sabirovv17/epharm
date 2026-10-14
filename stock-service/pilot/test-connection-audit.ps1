#requires -version 5.1
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $root 'cashier-connection-audit.ps1') -LibraryOnly
function Assert-Audit([bool]$Condition, [string]$Message) { if (-not $Condition) { throw ('FAILED: ' + $Message) } }
$fixture = [IO.Path]::GetTempFileName()
try {
    $ini = "[Other]`r`npassword=fixture-other-secret`r`n[Connect]`r`nserver=KASSA1`r`nbase=C:\Standart-N\base\ztrade.fdb`r`nlogin=fixture-login-secret`r`npassword=fixture-password=a=b`r`npwd=fixture-pwd-secret`r`ntoken=fixture-token-secret`r`n"
    [IO.File]::WriteAllText($fixture, $ini, [Text.Encoding]::GetEncoding(1251))
    $summary = Read-AuditIni $fixture
    Assert-Audit ($summary.Values['server'] -eq 'KASSA1') 'server field retained'
    Assert-Audit ($summary.AuthenticationKeyNames -contains 'password' -and $summary.AuthenticationKeyNames -contains 'login') 'credential key names retained without values'
    $serialized = ConvertTo-Json $summary -Depth 5
    Assert-Audit ($serialized -notmatch 'fixture-') 'no credential or token values appear in output data'
    Assert-Audit (-not $summary.Values.ContainsKey('password') -and -not $summary.Values.ContainsKey('login')) 'credential values are not extracted'
    [IO.File]::WriteAllText($fixture, "[Connect]`nbase=C:\db\ztrade.fdb?pass=fixture-nested-secret`nserver=KASSA1?user=fixture-nested-user`n", [Text.Encoding]::ASCII)
    $summary = Read-AuditIni $fixture
    Assert-Audit ((ConvertTo-Json $summary -Depth 5) -notmatch 'fixture-') 'nested auth parameters in endpoint fields cannot leak'
    [IO.File]::WriteAllText($fixture, "# Authentication=mixed`nRemoteServicePort=3051`nRemoteServiceName=gds_db`nAuthentication=native # comment`nSecurityDatabase=fixture-server-secret`nWireCrypt=fixture-crypt-secret`n", [Text.Encoding]::ASCII)
    $config = Read-AuditFirebirdConfig $fixture
    Assert-Audit ($config.Count -eq 3 -and $config['authentication'] -eq 'native' -and $config['remoteserviceport'] -eq '3051') 'only whitelisted server fields retained'
    Assert-Audit ((ConvertTo-Json $config) -notmatch 'fixture-') 'unrelated server fields cannot expose secrets'
    [IO.File]::WriteAllText($fixture, "RemoteServicePort=3051`nRemoteServicePort=3050`nAuthentication=fixture-secret`n", [Text.Encoding]::ASCII)
    $config = Read-AuditFirebirdConfig $fixture
    Assert-Audit ($config['remoteserviceport'] -eq '[AMBIGUOUS]' -and $config['authentication'] -eq '[INVALID_OR_UNSUPPORTED]') 'conflicting and invalid config values are marked, not guessed'
    $rows = @(
        [pscustomobject]@{OwningProcess=11;RemoteAddress='10.200.67.101';RemotePort=3051},
        [pscustomobject]@{OwningProcess=12;RemoteAddress='10.200.67.101';RemotePort=3050},
        [pscustomobject]@{OwningProcess=11;RemoteAddress='10.10.1.94';RemotePort=3050},
        [pscustomobject]@{OwningProcess=11;RemoteAddress='::ffff:10.200.67.101';RemotePort=3052}
    )
    $peers = @(Select-AuditPeers $rows 11 @('10.200.67.101'))
    Assert-Audit ($peers.Count -eq 2 -and $peers[0].RemotePort -eq 3051 -and $peers[1].RemotePort -eq 3052) 'peer filtering is scoped to cashier PID and all normalized target addresses'
    $source = [IO.File]::ReadAllText((Join-Path $root 'cashier-connection-audit.ps1'))
    $tokens = $null; $errors = $null
    $ast = [Management.Automation.Language.Parser]::ParseInput($source, [ref]$tokens, [ref]$errors)
    Assert-Audit ($errors.Count -eq 0) 'audit PowerShell parses'
    $commands = @($ast.FindAll({param($node) $node -is [Management.Automation.Language.CommandAst]}, $true) | ForEach-Object { $_.GetCommandName() })
    Assert-Audit (-not @($commands | Where-Object { $_ -in @('Start-Process','Invoke-Metadata','Invoke-Expression','Add-Type','Invoke-WebRequest','Invoke-RestMethod') }).Count) 'no auth process, dynamic code, native library load or HTTP call in audit'
    Assert-Audit ($source -notmatch 'isc_attach_database|ISC_PASSWORD|Sockets\.TcpClient|LoadLibrary|ReadProcessMemory|CREATE USER|ALTER USER') 'audit contains no database attach, auth environment, raw memory access or user mutation'
    $bat = [IO.File]::ReadAllText((Join-Path $root 'cashier-connection-audit.bat'))
    $marker = [regex]::Match($bat, '(?m)^::EPHARM_PS_BASE64=([A-Za-z0-9+/=]+)\r?$')
    Assert-Audit $marker.Success 'audit BAT has embedded source'
    Assert-Audit ([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($marker.Groups[1].Value)) -eq $source) 'audit BAT exactly matches source'
    $launcher = @($bat -split '\r?\n' | Where-Object { $_.StartsWith('powershell.exe ') })[0]
    $command = $launcher.Substring($launcher.IndexOf('-Command "') + 10).TrimEnd('"').Replace('& ([scriptblock]::Create($s))', '& ([scriptblock]::Create($s)) -LibraryOnly')
    $env:EPHARM_STOCK_DIAG_SELF = Join-Path $root 'cashier-connection-audit.bat'
    $null = & ([scriptblock]::Create($command))
    Write-Host 'PASS: secret-free INI/config data, ambiguous config handling, cashier peer scoping, no database calls and standalone BAT launcher.'
} finally { Remove-Item -LiteralPath $fixture -Force -ErrorAction SilentlyContinue }
