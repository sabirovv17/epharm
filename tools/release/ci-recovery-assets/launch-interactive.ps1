$ErrorActionPreference='Stop'
$root='C:\epharm-pr160-pilot'
# Remove known inherited Epharm variables without reading or printing their values.
foreach($key in @(
'EPHARM_APP_LOG',
'EPHARM_BACKEND_FALLBACK_URLS',
'EPHARM_BACKEND_URL',
'EPHARM_DEBUG',
'EPHARM_DEVICE_ID',
'EPHARM_DISABLE_CONSOLE_LOG',
'EPHARM_FISCAL_RECEIPT_INBOX_DIR',
'EPHARM_FISCAL_RECEIPT_MAX_ARTIFACT_MB',
'EPHARM_FISCAL_RECEIPT_MAX_CLOCK_SKEW_SEC',
'EPHARM_FISCAL_RECEIPT_POLL_SEC',
'EPHARM_FISCAL_RECEIPT_RETENTION_HOURS',
'EPHARM_FISCAL_RECEIPT_TRUSTED_SOURCES',
'EPHARM_FULFILLMENT_CACHE_PATH',
'EPHARM_FULFILLMENT_CREDENTIAL_PATH',
'EPHARM_FULFILLMENT_ENABLED',
'EPHARM_FULFILLMENT_FALLBACK_URLS',
'EPHARM_FULFILLMENT_POLL_SEC',
'EPHARM_FULFILLMENT_URL',
'EPHARM_HEARTBEAT_PATH',
'EPHARM_HEARTBEAT_SEC',
'EPHARM_LOG_PATH',
'EPHARM_MEDIA_CACHE_DIR',
'EPHARM_NO_VIDEO',
'EPHARM_PHARMACIST_ID',
'EPHARM_PHARMACIST_PREVIEW',
'EPHARM_PHARMACY_ID',
'EPHARM_PLAYLIST_POLL_SEC',
'EPHARM_POSM_CONFIG',
'EPHARM_POSM_ENABLED',
'EPHARM_POSM_KEY',
'EPHARM_RECEIPT_ACTIVE_RETENTION_DAYS',
'EPHARM_RECEIPT_CAPTURE_DIR',
'EPHARM_RECEIPT_CAPTURE_ENABLED',
'EPHARM_RECOMMEND_DEBOUNCE_MS',
'EPHARM_RECOMMEND_REFRESH_SEC',
'EPHARM_SCREEN_MODE',
'EPHARM_STAFF_AUTH_ENABLED',
'EPHARM_STAFF_SESSION_MAX_AGE_SEC',
'EPHARM_STANDARDN_DB_ENABLED',
'EPHARM_STANDARDN_DB_HOST',
'EPHARM_STANDARDN_DB_PASSWORD',
'EPHARM_STANDARDN_DB_PATH',
'EPHARM_STANDARDN_DB_PORT',
'EPHARM_STANDARDN_DB_TIMEOUT_MS',
'EPHARM_STANDARDN_DB_USER',
'EPHARM_STANDARDN_LOG_PATHS',
'EPHARM_STANDARDN_RECEIPT_POLL_MS',
'EPHARM_UPDATE_ENABLED',
'EPHARM_UPDATE_POLL_SEC',
'EPHARM_UPDATE_PUBLIC_KEY_SPKI',
'EPHARM_VLC_ARGS'
)) { [Environment]::SetEnvironmentVariable($key,$null,'Process') }
$env:TEMP="$root\temp"
$env:TMP="$root\temp"
$env:EPHARM_POSM_CONFIG="$root\data\posm.json"
$env:EPHARM_APP_LOG="$root\evidence\customerdisplay.log"
$env:EPHARM_STANDARDN_DB_ENABLED='false'
$env:EPHARM_STAFF_AUTH_ENABLED='false'
$env:EPHARM_NO_VIDEO='true'
$env:EPHARM_DISABLE_CONSOLE_LOG='true'
$env:EPHARM_DEBUG='0'
$start=New-Object Diagnostics.ProcessStartInfo
$start.FileName="$root\install\CustomerDisplay.exe"
$start.WorkingDirectory="$root\install"
$start.UseShellExecute=$false
$start.EnvironmentVariables.Clear()
$allow=@{
 SystemRoot=$env:SystemRoot;WINDIR=$env:SystemRoot;SystemDrive=$env:SystemDrive;ComSpec=(Join-Path $env:SystemRoot 'System32\cmd.exe');PATH=((Join-Path $env:SystemRoot 'System32')+';'+$env:SystemRoot)
 TEMP="$root\temp";TMP="$root\temp";USERPROFILE=$env:USERPROFILE;APPDATA=$env:APPDATA;LOCALAPPDATA=$env:LOCALAPPDATA
 EPHARM_POSM_CONFIG="$root\data\posm.json";EPHARM_APP_LOG="$root\evidence\customerdisplay.log";EPHARM_STANDARDN_DB_ENABLED='false';EPHARM_STAFF_AUTH_ENABLED='false';EPHARM_NO_VIDEO='true';EPHARM_DISABLE_CONSOLE_LOG='true';EPHARM_DEBUG='0'
}
foreach($key in $allow.Keys){$start.EnvironmentVariables[$key]=[string]$allow[$key]}
if(@($start.EnvironmentVariables.Keys|Where-Object {$_ -match 'TOKEN|SECRET|PASSWORD|GITHUB|ACTIONS'}).Count){throw 'Unexpected inherited credentials'}

$child=[Diagnostics.Process]::Start($start)
Start-Sleep -Milliseconds 300
$record=[ordered]@{ ProcessId=$child.Id; ProcessStartUtc=$child.StartTime.ToUniversalTime().ToString('o'); SessionId=$child.SessionId; AssemblyVersion=[Reflection.AssemblyName]::GetAssemblyName("$root\install\CustomerDisplay.dll").Version.ToString(); LaunchUtc=[DateTime]::UtcNow.ToString('o') }
[IO.File]::WriteAllText("$root\evidence\interactive-launch.json",($record | ConvertTo-Json))
