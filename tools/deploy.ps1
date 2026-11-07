<#
  FireRun – installazione e aggiornamento sul Fire Stick dal PC, via ADB in rete.

  Uso:
    .\deploy.ps1 -FireStick 192.168.1.50
    .\deploy.ps1 -FireStick 192.168.1.50 -AttivaTastoHome
    .\deploy.ps1 -FireStick 192.168.1.50 -Force            # reinstalla anche se già aggiornato
    .\deploy.ps1 -FireStick 192.168.1.50 -DisattivaHomeAmazon
    .\deploy.ps1 -FireStick 192.168.1.50 -RiattivaHomeAmazon
    .\deploy.ps1 -FireStick 192.168.1.50 -Sfondo "C:\Immagini\montagne.jpg"   # immagine di sfondo

  Cosa fa:
    1. Se manca adb, scarica gli Android platform-tools ufficiali di Google
       in %LOCALAPPDATA%\FireRun\platform-tools.
    2. Si collega al Fire Stick (la prima volta sul TV compare "Consenti debug": scegli
       "Consenti sempre da questo computer").
    3. Confronta la versione installata con l'ultima release su GitHub e, se serve,
       scarica FireRun.apk e la installa senza conferme sul TV.
#>
param(
    [Parameter(Mandatory = $true)][string]$FireStick,
    [string]$Repo = "Kyo89-sudo/FireRun",
    [switch]$Force,
    [switch]$AttivaTastoHome,
    [switch]$DisattivaHomeAmazon,
    [switch]$RiattivaHomeAmazon,
    [string]$Sfondo
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"   # Invoke-WebRequest è molto più veloce senza barra
# Windows PowerShell 5.1 può usare TLS vecchi che GitHub e Google rifiutano
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
$Pkg = "com.kyo89.firerun"
$AmazonHome = "com.amazon.tv.launcher"
$Base = Join-Path $env:LOCALAPPDATA "FireRun"
New-Item -ItemType Directory -Force -Path $Base | Out-Null

# --- adb ---------------------------------------------------------------------
$Adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $Adb) {
    $Adb = Join-Path $Base "platform-tools\adb.exe"
    if (-not (Test-Path $Adb)) {
        Write-Host "Scarico gli Android platform-tools ufficiali..."
        $zip = Join-Path $Base "platform-tools.zip"
        Invoke-WebRequest "https://dl.google.com/android/repository/platform-tools-latest-windows.zip" -OutFile $zip -UseBasicParsing
        Expand-Archive $zip -DestinationPath $Base -Force
        Remove-Item $zip
    }
}

$Target = if ($FireStick -match ":") { $FireStick } else { "${FireStick}:5555" }

# In Windows PowerShell 5.1, con ErrorActionPreference=Stop, qualsiasi riga che adb
# scrive su stderr diventa un errore fatale: qui dentro la si tratta come testo normale.
function Adb-Raw {
    $ErrorActionPreference = "Continue"
    $out = & $Adb @args 2>&1 | ForEach-Object { "$_" }
    return ($out -join "`n")
}
function Invoke-Adb {
    $out = Adb-Raw -s $Target @args
    if ($LASTEXITCODE -ne 0) { throw "adb $($args -join ' ') non riuscito:`n$out" }
    return $out
}

Write-Host "Collegamento a $Target..."
Adb-Raw start-server | Out-Null
Write-Host (Adb-Raw connect $Target)

# La prima volta il Fire Stick chiede di autorizzare questo PC: aspettiamo fino a 60 s.
$state = ""
for ($i = 0; $i -lt 30; $i++) {
    $state = (Adb-Raw -s $Target get-state).Trim()
    if ($state -eq "device") { break }
    if ($i -eq 0 -and $state -match "unauthorized") {
        Write-Host "Sul TV scegli 'Consenti sempre da questo computer' e premi OK..." -ForegroundColor Yellow
    }
    Start-Sleep -Seconds 2
    if ($state -notmatch "unauthorized") { Adb-Raw connect $Target | Out-Null }
}
if ($state -ne "device") {
    throw ("Fire Stick non raggiungibile o non autorizzato (stato: $state).`n" +
           "- Controlla l'indirizzo IP (Impostazioni > La mia Fire TV > Informazioni > Rete)`n" +
           "- Attiva Debug ADB in Opzioni sviluppatore`n" +
           "- Sul TV accetta 'Consenti debug' e riprova")
}

# --- versione ----------------------------------------------------------------
$Rel = "https://github.com/$Repo/releases/latest/download"
# GitHub serve version.json come file binario: lo salviamo e lo leggiamo come testo.
$verFile = Join-Path $Base "version.json"
Invoke-WebRequest "$Rel/version.json" -OutFile $verFile -UseBasicParsing
$latest = [int]((Get-Content $verFile -Raw | ConvertFrom-Json).versionCode)
$installed = 0
$dump = Adb-Raw -s $Target shell dumpsys package $Pkg
$m = [regex]::Match($dump, "versionCode=(\d+)")
if ($m.Success) { $installed = [int]$m.Groups[1].Value }
Write-Host "Versione installata: $installed   Ultima su GitHub: $latest"

if ($Force -or $latest -gt $installed) {
    $apk = Join-Path $Base "FireRun.apk"
    Write-Host "Scarico FireRun.apk..."
    Invoke-WebRequest "$Rel/FireRun.apk" -OutFile $apk -UseBasicParsing
    Write-Host "Installo sul Fire Stick..."
    Invoke-Adb install -r $apk | Write-Host
    Invoke-Adb shell am start -n "$Pkg/.MainActivity" | Out-Null
    Write-Host "FireRun $latest installato." -ForegroundColor Green
} else {
    Write-Host "FireRun è già aggiornato." -ForegroundColor Green
}

# --- immagine di sfondo ------------------------------------------------------
# Fire OS non lascia scrivere ad ADB nella cartella privata delle app: l'immagine va
# in Download, FireRun riceve il permesso di leggere le immagini e la importa da sé.
if ($Sfondo) {
    if (-not (Test-Path -LiteralPath $Sfondo -PathType Leaf)) { throw "Immagine non trovata: $Sfondo" }
    $ext = [IO.Path]::GetExtension($Sfondo).ToLowerInvariant()
    if ($ext -notin @(".jpg", ".jpeg", ".png", ".webp")) { throw "Usa un'immagine JPG, PNG o WEBP (trovato: $ext)" }
    $remote = "/sdcard/Download/FireRun-sfondo$ext"
    Adb-Raw -s $Target shell "rm -f /sdcard/Download/FireRun-sfondo.*" | Out-Null
    Write-Host "Copio lo sfondo sul Fire Stick..."
    Invoke-Adb push (Resolve-Path -LiteralPath $Sfondo).Path $remote | Out-Null
    # Permesso di leggere le immagini (su Fire OS 8 = Android 11 è READ_EXTERNAL_STORAGE)
    Adb-Raw -s $Target shell pm grant $Pkg android.permission.READ_EXTERNAL_STORAGE | Out-Null
    Adb-Raw -s $Target shell pm grant $Pkg android.permission.READ_MEDIA_IMAGES | Out-Null
    # Riporta FireRun in primo piano: importa l'immagine e la applica subito
    Invoke-Adb shell am start -n "$Pkg/.MainActivity" | Out-Null
    Start-Sleep -Seconds 4
    Adb-Raw -s $Target shell "rm -f /sdcard/Download/FireRun-sfondo.*" | Out-Null
    Write-Host "Sfondo inviato e applicato." -ForegroundColor Green
}

# --- tasto Home --------------------------------------------------------------
# Sistema ripreso da dumbtv (https://github.com/humblemuzzu/dumbtv, MIT).
if ($AttivaTastoHome) {
    $svc = "$Pkg/.HomeRedirectService"
    $cur = (Adb-Raw -s $Target shell settings get secure enabled_accessibility_services).Trim()
    if ($cur -eq "null") { $cur = "" }
    $others = @($cur -split ":" | Where-Object { $_ -and $_ -ne $svc -and $_ -ne "$Pkg/$Pkg.HomeRedirectService" })
    # Prima senza FireRun, poi con: così Fire OS ricollega il servizio anche dopo un aggiornamento.
    Invoke-Adb shell settings put secure enabled_accessibility_services "`"$($others -join ':')`"" | Out-Null
    Start-Sleep -Milliseconds 500
    Invoke-Adb shell settings put secure enabled_accessibility_services (($others + $svc) -join ":") | Out-Null
    Invoke-Adb shell settings put secure accessibility_enabled 1 | Out-Null

    # Tasto Home istantaneo: dopo Home Android ritarda di 5 s l'apertura delle app; FireRun
    # la fa partire da un allarme di sistema, che scatta subito solo con min_futurity=0.
    $sdk = [int]((Adb-Raw -s $Target shell getprop ro.build.version.sdk).Trim())
    if ($sdk -ge 31) {
        Adb-Raw -s $Target shell device_config put alarm_manager min_futurity 0 | Out-Null
    } else {
        $consts = (Adb-Raw -s $Target shell settings get global alarm_manager_constants).Trim()
        if ($consts -eq "null") { $consts = "" }
        $kv = @($consts -split "," | Where-Object { $_ -and $_ -notlike "min_futurity=*" }) + "min_futurity=0"
        Invoke-Adb shell settings put global alarm_manager_constants ($kv -join ",") | Out-Null
    }

    # "Mostra sopra le altre app": consente a FireRun di aprirsi da solo all'accensione
    Adb-Raw -s $Target shell appops set $Pkg SYSTEM_ALERT_WINDOW allow | Out-Null

    # Verifica che Fire OS abbia davvero collegato il servizio
    $bound = $false
    for ($i = 0; $i -lt 10; $i++) {
        # dumpsys mostra l'etichetta del servizio collegato ("FireRun – Tasto Home"), non il suo nome
        if ((Adb-Raw -s $Target shell dumpsys accessibility) -match "Service\[label=FireRun|firerun\.HomeRedirectService") { $bound = $true; break }
        Start-Sleep -Seconds 1
    }
    if ($bound) {
        Write-Host "Tasto Home attivato: istantaneo e con avvio automatico all'accensione." -ForegroundColor Green
    } else {
        Write-Host "Attenzione: Fire OS non risulta aver collegato il servizio Tasto Home. Riavvia il Fire Stick e rilancia con -AttivaTastoHome." -ForegroundColor Yellow
    }
}

# --- home Amazon (facoltativo, reversibile) ---------------------------------
if ($DisattivaHomeAmazon) {
    Write-Host "Attenzione: su molte versioni di Fire OS Amazon lo impedisce; se riesce, alcune funzioni Amazon possono smettere di funzionare." -ForegroundColor Yellow
    $ok = Read-Host "Scrivi SI per disattivare la home Amazon (si annulla con -RiattivaHomeAmazon)"
    if ($ok -eq "SI") {
        Write-Host (Adb-Raw -s $Target shell pm disable-user --user 0 $AmazonHome)
    } else {
        Write-Host "Annullato."
    }
}
if ($RiattivaHomeAmazon) {
    Write-Host (Adb-Raw -s $Target shell pm enable $AmazonHome)
}
