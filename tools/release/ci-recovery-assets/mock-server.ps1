$ErrorActionPreference='Stop'
$root='C:\epharm-pr160-pilot'
$listener=New-Object System.Net.HttpListener
$listener.Prefixes.Add('http://127.0.0.1:18761/')
$listener.Start()
[IO.File]::WriteAllText("$root\evidence\mock-server-process.json",([ordered]@{ProcessId=$PID;ProcessStartTicks=[Diagnostics.Process]::GetCurrentProcess().StartTime.ToUniversalTime().Ticks;StartedUtc=[DateTime]::UtcNow.ToString('o')}|ConvertTo-Json))
[IO.File]::WriteAllText("$root\evidence\mock-server-ready.txt",[DateTime]::UtcNow.ToString('o'))
try {
 while($listener.IsListening) {
  $context=$listener.GetContext()
  $response=$context.Response
  try {
   $response.KeepAlive=$false
   $path=$context.Request.Url.AbsolutePath
   [IO.File]::AppendAllText("$root\evidence\mock-http.log",([DateTime]::UtcNow.ToString('o')+' '+$context.Request.HttpMethod+' '+$path+"`r`n"))
   if($path -eq '/api/posm/app/version') {
    $response.ContentType='application/json; charset=utf-8'
    $bytes=[IO.File]::ReadAllBytes("$root\mock\manifest.current.json")
   } elseif($path -eq '/candidate.zip') {
    $response.ContentType='application/zip'
    $bytes=[IO.File]::ReadAllBytes("$root\mock\candidate.zip")
   } else {
    $response.StatusCode=503
    $bytes=[Text.Encoding]::UTF8.GetBytes('{"syntheticOffline":true}')
   }
   $response.ContentLength64=$bytes.Length
   $response.OutputStream.Write($bytes,0,$bytes.Length)
  } catch {
   [IO.File]::AppendAllText("$root\evidence\mock-errors.log",($_.Exception.GetType().Name+"`r`n"))
  } finally { $response.Close() }
 }
} finally { $listener.Close() }
