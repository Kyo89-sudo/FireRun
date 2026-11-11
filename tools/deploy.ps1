<#
  FireRun – installazione e aggiornamento sul Fire Stick dal PC, via ADB in rete.

  Uso:
    .\deploy.ps1 -FireStick 192.168.1.50
    .\deploy.ps1 -FireStick 192.168.1.50 -AttivaTastoHome
    .\deploy.ps1 -FireStick 192.168.1.50 -Force            # reinstalla anche se già aggiornato
    .\deploy.ps1 -FireStick 192.168.1.50 -DisattivaHomeAmazon
    .\deploy.ps1 -FireStick 192.168.1.50 -RiattivaHomeAmazon
    .\deploy.ps1 -FireStick 192.168.1.50 -Sfondo "C:\Immagini\montagne.jpg"   # immagine di sfondo
    .\deploy.ps1 -FireStick 192.168.1.50 -Pulizia -Anteprima      # mostra cosa farebbe, senza cambiare nulla
    .\deploy.ps1 -FireStick 192.168.1.50 -Pulizia                 # disattiva 34 pacchetti Amazon (pubblicità, ACR, telemetria)
    .\deploy.ps1 -FireStick 192.168.1.50 -PuliziaProfonda         # + toglie il sottofondo ai 5 pacchetti protetti
    .\deploy.ps1 -FireStick 192.168.1.50 -Ripristina              # rimette tutto com'era prima della pulizia

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
    [string]$Sfondo,
    [switch]$Pulizia,
    [switch]$PuliziaProfonda,
    [switch]$Anteprima,
    [switch]$Ripristina
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

$soloLettura = $Anteprima -or $Ripristina   # anteprima e ripristino non installano nulla
if ($soloLettura) {
    Write-Host "Anteprima/ripristino: nessuna installazione di FireRun."
} elseif ($Force -or $latest -gt $installed) {
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
    # (Windows PowerShell 5.1 perde le virgolette vuote: senza altri servizi si cancella la voce)
    if ($others.Count -gt 0) {
        Invoke-Adb shell settings put secure enabled_accessibility_services ($others -join ":") | Out-Null
    } else {
        Adb-Raw -s $Target shell settings delete secure enabled_accessibility_services | Out-Null
    }
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
    # Permesso per l'interruttore Privacy nelle Opzioni di FireRun (impostazioni di sistema)
    Adb-Raw -s $Target shell pm grant $Pkg android.permission.WRITE_SECURE_SETTINGS | Out-Null

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

# --- pulizia Amazon ----------------------------------------------------------
# Liste riprese da dumbtv (https://github.com/humblemuzzu/dumbtv, MIT), provate su Fire TV
# Stick 4K Max di 2a generazione con Fire OS 8.1.8.5. Esclusi apposta gli aggiornamenti di
# Fire OS (com.amazon.tv.forcedotaupdater.v2, com.amazon.tv.easyupgrade, software.ota*).
# I pacchetti vengono DISATTIVATI, mai disinstallati: -Ripristina li riattiva.
$PuliziaPacchetti = @(
    # pubblicità, promozioni e shopping
    "com.amazon.hybridadidservice", "com.amazon.privacypassservice", "com.amazon.shoptv.client",
    "com.amazon.shoptv.firetv.client", "com.amazon.sneakpeek", "com.amazon.media.recommendations",
    "com.amazon.systemnotices", "com.amazon.tv.website_launcher",
    # cosa guardi (ACR "legge" lo schermo, anche l'HDMI)
    "com.amazon.tv.acr", "com.amazon.hedwig",
    # telemetria, log, rapporti
    "com.amazon.tv.fw.metrics", "com.amazon.wirelessmetrics.service", "com.amazon.perfc",
    "com.amazon.perfcollection", "com.amazon.dp.logger", "com.amazon.logan",
    "com.amazon.neo.minerva.logcat", "com.amazon.ceviche", "com.amazon.connectivitydiag",
    "com.amazon.tigris", "com.amazon.tv.developer.dataservice",
    # esperimenti e configurazione remota
    "com.amazon.ale", "com.amazon.katoch", "com.amazon.device_remote_config_sync_service",
    # demo, tutorial e app vuote
    "com.amazon.tv.democontent.provider", "com.amazon.tmm.tutorial", "com.amazon.tv.releasenotes",
    "com.amazon.dummy.alarmclock", "com.amazon.dummy.calendar", "com.amazon.dummy.contacts",
    "com.amazon.dummy.gallery", "com.amazon.dummy.music", "com.amazon.dummy.settings",
    "com.android.traceur"
)
# Amazon non li lascia disattivare: solo niente sottofondo (e niente dati sulle reti a consumo).
# Esclusi gli aggiornamenti (com.amazon.device.software.ota e .override).
$PuliziaProtetti = @(
    "com.amazon.device.metrics", "com.amazon.device.crashmanager", "com.amazon.device.rdmapplication",
    "com.amazon.ftvads.deeplinking", "com.amazon.tv.csapp"
)
# Da non toccare MAI (lista dumbtv): schermata, Impostazioni, ingressi, telecomando, Appstore…
$MaiToccare = @(
    "com.amazon.tv.launcher", "com.amazon.firehomestarter", "com.amazon.tv.settings.v2",
    "com.amazon.tv.settings.core", "com.amazon.tv.inputpreference.service", "com.amazon.tv.routing",
    "com.amazon.tv.devicecontrol", "com.amazon.tv.devicecontrolsettings", "com.amazon.tv.keypolicymanager",
    "com.amazon.tv.livetv", "com.amazon.franktvinput", "com.amazon.tifobserver",
    "com.amazon.fireinputdevices", "com.amazon.autopairservice", "com.amazon.device.bluetoothpa",
    "com.amazon.device.blepa", "com.amazon.whisperjoin.middleware.np", "com.amazon.whisperplay.service.install",
    "com.amazon.storm.lightning.tutorial", "com.amazon.tv.oobe", "com.amazon.tv.ooberesource",
    "com.amazon.webview.chromium", "com.amazon.venezia", "com.amazon.firebat", "com.amazon.device.messaging",
    "com.amazon.vizzini", "com.android.systemui", "com.android.packageinstaller",
    "com.android.permissioncontroller", "com.amazon.tv.forcedotaupdater.v2", "com.amazon.tv.easyupgrade",
    "com.amazon.device.software.ota", "com.amazon.device.software.ota.override", $Pkg
)
# Impostazioni di privacy (le gestisce l'interruttore Privacy di FireRun; qui solo per la fotografia)
$PrivacyChiavi = @(
    "secure amz_limit_ad_tracking", "secure limit_ad_tracking", "secure non_amz_limit_ad_tracking",
    "secure advertisingIdApp:interestBasedAds:value", "secure USAGE_METRICS_UPLOAD_ENABLED",
    "secure usage_metrics_marketing_enabled", "secure settingsApp:appUsageData:value",
    "secure settingsApp:deviceUsageData:value", "global heads_up_notifications_enabled",
    "global com.amazon.tv.settings.featured_item_video_enabled",
    "global com.amazon.tv.settings.featured_item_video_volume"
)

function Get-PkgStates {
    $states = @{}
    foreach ($l in ((Adb-Raw -s $Target shell pm list packages -e) -split "`n")) {
        $n = $l.Trim() -replace "^package:", ""; if ($n) { $states[$n] = "enabled" }
    }
    foreach ($l in ((Adb-Raw -s $Target shell pm list packages -d) -split "`n")) {
        $n = $l.Trim() -replace "^package:", ""; if ($n) { $states[$n] = "disabled" }
    }
    return $states
}
function Get-Uid([string]$p) {
    $m = [regex]::Match((Adb-Raw -s $Target shell pm list packages -U $p), "package:$([regex]::Escape($p)) uid:(\d+)")
    if ($m.Success) { return [int]$m.Groups[1].Value } else { return 0 }
}
function Get-OpMode([string]$p, [string]$op) {
    $m = [regex]::Match((Adb-Raw -s $Target shell cmd appops get $p $op), "${op}:\s*(\w+)")
    if ($m.Success) { return $m.Groups[1].Value } else { return "default" }
}
function Get-Blacklist {
    $out = Adb-Raw -s $Target shell cmd netpolicy list restrict-background-blacklist
    return @([regex]::Matches($out, "\b\d{5,}\b") | ForEach-Object { [int]$_.Value })
}

if ($Pulizia -or $PuliziaProfonda -or $Ripristina) {
    $serial = (Adb-Raw -s $Target shell getprop ro.serialno).Trim()
    if (-not $serial) { $serial = ($FireStick -replace "[^\w]", "_") }
    $statoFile = Join-Path $Base "stato-prima-della-pulizia-$serial.json"
    $states = Get-PkgStates
    $fos = (Adb-Raw -s $Target shell getprop ro.build.version.name).Trim()
    Write-Host "Fire OS: $fos"
}

# --- anteprima / pulizia ---
if ($Pulizia -or $PuliziaProfonda) {
    $doPkgs = $Pulizia -or $PuliziaProfonda   # la profonda include la normale
    # permesso per l'interruttore Privacy di FireRun (serve la versione con il permesso dichiarato)
    if (-not $Anteprima) { Adb-Raw -s $Target shell pm grant $Pkg android.permission.WRITE_SECURE_SETTINGS | Out-Null }
    $doProt = $PuliziaProfonda

    # Fotografia dello stato: la prima resta (è quella "di fabbrica" da ripristinare);
    # le chiamate successive aggiungono solo ciò che non c'era.
    $snap = $null
    if (Test-Path $statoFile) { $snap = Get-Content $statoFile -Raw | ConvertFrom-Json }
    $snapPk = @{}; $snapOps = @{}; $snapSet = @{}; $snapBl = @()
    if ($snap) {
        $snap.packages.PSObject.Properties | ForEach-Object { $snapPk[$_.Name] = $_.Value }
        $snap.appops.PSObject.Properties | ForEach-Object { $snapOps[$_.Name] = $_.Value }
        $snap.settings.PSObject.Properties | ForEach-Object { $snapSet[$_.Name] = $_.Value }
        $snapBl = @($snap.blacklist)
    } else {
        $snapBl = Get-Blacklist
    }
    foreach ($p in ($PuliziaPacchetti + $PuliziaProtetti)) {
        if (-not $snapPk.ContainsKey($p)) { $snapPk[$p] = if ($states.ContainsKey($p)) { $states[$p] } else { "absent" } }
    }
    foreach ($p in $PuliziaProtetti) {
        if (-not $snapOps.ContainsKey($p) -and $states.ContainsKey($p)) {
            $snapOps[$p] = [pscustomobject]@{ RUN_IN_BACKGROUND = (Get-OpMode $p "RUN_IN_BACKGROUND"); RUN_ANY_IN_BACKGROUND = (Get-OpMode $p "RUN_ANY_IN_BACKGROUND") }
        }
    }
    foreach ($k in $PrivacyChiavi) {
        if (-not $snapSet.ContainsKey($k)) {
            $ns, $key = $k -split " ", 2
            $snapSet[$k] = (Adb-Raw -s $Target shell settings get $ns $key).Trim()
        }
    }

    Write-Host ""
    if ($Anteprima) { Write-Host "ANTEPRIMA: non viene cambiato nulla." -ForegroundColor Cyan }
    $daFare = @()
    if ($doPkgs) {
        Write-Host "Pacchetti da disattivare:"
        foreach ($p in $PuliziaPacchetti) {
            if ($MaiToccare -contains $p) { Write-Host ("  {0,-48} saltato (da non toccare)" -f $p); continue }
            if (-not $states.ContainsKey($p)) { Write-Host ("  {0,-48} non presente" -f $p); continue }
            if ($states[$p] -eq "disabled") { Write-Host ("  {0,-48} già disattivato" -f $p); continue }
            Write-Host ("  {0,-48} DA DISATTIVARE" -f $p) -ForegroundColor Yellow
            $daFare += $p
        }
    }
    $protDaFare = @()
    if ($doProt) {
        Write-Host "Pacchetti protetti (niente sottofondo):"
        foreach ($p in $PuliziaProtetti) {
            if (-not $states.ContainsKey($p)) { Write-Host ("  {0,-48} non presente" -f $p); continue }
            $uid = Get-Uid $p
            if ($uid -lt 10000) { Write-Host ("  {0,-48} saltato (uid di sistema condiviso)" -f $p); continue }
            $mode = Get-OpMode $p "RUN_ANY_IN_BACKGROUND"
            if ($mode -eq "ignore") { Write-Host ("  {0,-48} già bloccato" -f $p); continue }
            Write-Host ("  {0,-48} DA BLOCCARE (uid $uid)" -f $p) -ForegroundColor Yellow
            $protDaFare += [pscustomobject]@{ Name = $p; Uid = $uid }
        }
    }

    if ($Anteprima) {
        Write-Host ""
        Write-Host ("Totale: {0} da disattivare, {1} da bloccare. Per applicare, rilancia senza -Anteprima." -f $daFare.Count, $protDaFare.Count) -ForegroundColor Cyan
    } elseif ($daFare.Count -eq 0 -and $protDaFare.Count -eq 0) {
        Write-Host "Niente da fare: è già tutto pulito." -ForegroundColor Green
    } else {
        # salva la fotografia PRIMA di cambiare qualsiasi cosa
        [pscustomobject]@{
            device = $serial; fireos = $fos; created = (Get-Date).ToString("s")
            packages = [pscustomobject]$snapPk; appops = [pscustomobject]$snapOps
            settings = [pscustomobject]$snapSet; blacklist = $snapBl
        } | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 $statoFile
        Write-Host "Stato salvato in $statoFile"

        $ime = ((Adb-Raw -s $Target shell settings get secure default_input_method).Trim() -split "/")[0]
        $essenziali = @($MaiToccare + $ime) | Where-Object { $_ -and $states.ContainsKey($_) -and $states[$_] -eq "enabled" }

        $fatti = @()
        foreach ($p in $daFare) {
            $out = Adb-Raw -s $Target shell pm disable-user --user 0 $p
            if ($out -match "new state: disabled") { Write-Host ("  {0,-48} disattivato" -f $p) -ForegroundColor Green; $fatti += $p }
            elseif ($out -match "protected") { Write-Host ("  {0,-48} protetto da Amazon: lasciato com'è" -f $p) }
            else { Write-Host ("  {0,-48} non riuscito: {1}" -f $p, $out.Trim()) -ForegroundColor Yellow }
        }
        foreach ($x in $protDaFare) {
            Adb-Raw -s $Target shell cmd appops set $x.Name RUN_IN_BACKGROUND ignore | Out-Null
            Adb-Raw -s $Target shell cmd appops set $x.Name RUN_ANY_IN_BACKGROUND ignore | Out-Null
            Adb-Raw -s $Target shell am force-stop $x.Name | Out-Null
            Adb-Raw -s $Target shell cmd netpolicy add restrict-background-blacklist $x.Uid | Out-Null
            Write-Host ("  {0,-48} niente sottofondo" -f $x.Name) -ForegroundColor Green
        }

        # Controllo finale: Fire Stick raggiungibile ed essenziali ancora attivi, altrimenti si annulla.
        Start-Sleep -Seconds 2
        $after = Get-PkgStates
        $rotti = @($essenziali | Where-Object { -not $after.ContainsKey($_) -or $after[$_] -ne "enabled" })
        if ((Adb-Raw -s $Target get-state).Trim() -ne "device" -or $rotti.Count -gt 0) {
            Write-Host "Controllo finale NON superato ($($rotti -join ', ')): riattivo quanto appena disattivato." -ForegroundColor Red
            foreach ($p in $fatti) { Adb-Raw -s $Target shell pm enable $p | Out-Null }
            throw "Pulizia annullata per sicurezza."
        }
        Write-Host ("Pulizia completata: {0} disattivati, {1} bloccati. Controllo finale superato." -f $fatti.Count, $protDaFare.Count) -ForegroundColor Green
        Write-Host "Interruttore Privacy disponibile in FireRun → Opzioni. Per annullare: -Ripristina"
    }
}

# --- ripristino ---
if ($Ripristina) {
    if (-not (Test-Path $statoFile)) {
        Write-Host "Nessuna fotografia trovata ($statoFile): riattivo comunque tutti i pacchetti della pulizia." -ForegroundColor Yellow
        foreach ($p in $PuliziaPacchetti) { if ($states[$p] -eq "disabled") { Write-Host (Adb-Raw -s $Target shell pm enable $p) } }
        foreach ($p in $PuliziaProtetti) {
            if (-not $states.ContainsKey($p)) { continue }
            Adb-Raw -s $Target shell cmd appops set $p RUN_IN_BACKGROUND default | Out-Null
            Adb-Raw -s $Target shell cmd appops set $p RUN_ANY_IN_BACKGROUND default | Out-Null
            $uid = Get-Uid $p
            if ($uid -ge 10000) { Adb-Raw -s $Target shell cmd netpolicy remove restrict-background-blacklist $uid | Out-Null }
        }
    } else {
        $snap = Get-Content $statoFile -Raw | ConvertFrom-Json
        Write-Host "Ripristino lo stato del $($snap.created)..."
        foreach ($prop in $snap.packages.PSObject.Properties) {
            $p = $prop.Name
            if ($prop.Value -eq "enabled" -and $states[$p] -eq "disabled") {
                Write-Host ("  {0,-48} {1}" -f $p, (Adb-Raw -s $Target shell pm enable $p).Trim())
            }
        }
        foreach ($prop in $snap.appops.PSObject.Properties) {
            $p = $prop.Name
            if (-not $states.ContainsKey($p)) { continue }
            Adb-Raw -s $Target shell cmd appops set $p RUN_IN_BACKGROUND $prop.Value.RUN_IN_BACKGROUND | Out-Null
            Adb-Raw -s $Target shell cmd appops set $p RUN_ANY_IN_BACKGROUND $prop.Value.RUN_ANY_IN_BACKGROUND | Out-Null
            $uid = Get-Uid $p
            if ($uid -ge 10000 -and @($snap.blacklist) -notcontains $uid) {
                Adb-Raw -s $Target shell cmd netpolicy remove restrict-background-blacklist $uid | Out-Null
            }
            Write-Host ("  {0,-48} sottofondo ripristinato" -f $p)
        }
        foreach ($prop in $snap.settings.PSObject.Properties) {
            $ns, $key = $prop.Name -split " ", 2
            if ($prop.Value -eq "null" -or [string]::IsNullOrEmpty($prop.Value)) {
                Adb-Raw -s $Target shell settings delete $ns $key | Out-Null
            } else {
                Adb-Raw -s $Target shell settings put $ns $key $prop.Value | Out-Null
            }
        }
        Write-Host "  impostazioni di privacy ripristinate"
        Move-Item -Force $statoFile ($statoFile -replace "\.json$", ".ripristinato.json")
    }
    Write-Host "Ripristino completato." -ForegroundColor Green
}
