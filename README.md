# FireRun

Launcher per Fire TV Stick 4K Max (e qualsiasi Android TV) in stile **Apple TV**.
Nessuna pubblicità, nessun contenuto sponsorizzato.

- **Top Shelf**: la metà alta mostra l'app selezionata, con nome grande e lo sfondo
  che prende il colore del suo banner
- **Griglia a 5 colonne** di banner 5:3: il riquadro selezionato si solleva con ombra e
  riflesso e mostra il nome sotto
- Prima i **preferiti** (con il riquadro **Aggiungi** per sceglierli), poi **Tutte le app**
- In alto a destra: **Cerca**, **Impostazioni** del Fire Stick, **Opzioni FireRun** (la fiamma), orologio
- Tieni premuto OK (o tasto ≡) su un'app: apri, aggiungi/togli dai preferiti, sposta, nascondi, info, disinstalla
- Sfondo: colore dell'app (come Apple TV), Sequoia, Notte, Prugna, Grafite oppure **una tua immagine dal PC**:
  `deploy.ps1 -FireStick <IP> -Sfondo "C:\percorso\immagine.jpg"`
- Servizio **Tasto Home**: quando Fire OS apre la home Amazon, torna subito su FireRun
- **Si aggiorna da solo** dalle release di GitHub

## Come funziona il giro completo

```
push su GitHub ──► GitHub Actions compila ──► Release "v1.0.N" con FireRun.apk + version.json
                                                     │
              ┌──────────────────────────────────────┴───────────────────────────┐
              ▼                                                                  ▼
  FireRun sul Fire Stick controlla ogni 2 ore,                 tools\deploy.ps1 sul PC installa
  mostra «Aggiornamento» e si installa (1 conferma sul TV)     via ADB in rete, senza conferme
```

## 1. Primo caricamento su GitHub

1. Crea il repository **FireRun** su GitHub (pubblico: i link di download delle release devono
   essere raggiungibili dal Fire Stick senza login).
2. Aggiungi la chiave di firma come **Secrets** (Settings → Secrets and variables → Actions →
   New repository secret):
   - `KEYSTORE_BASE64`: il contenuto del file `KEYSTORE_BASE64.txt`
   - `KEYSTORE_PASSWORD`: il contenuto del file `KEYSTORE_PASSWORD.txt`

   La chiave **non va mai nel repository**: chi la possiede può firmare aggiornamenti
   che il Fire Stick accetterebbe come FireRun. Conserva una copia di `firerun-release.jks`
   e della password: se la perdi, per installare le build nuove dovrai disinstallare FireRun.
3. Dalla scheda **Actions** avvia **Build FireRun** (o fai un push). In circa 4 minuti
   pubblica la release `v1.0.N`.

Se il repo ha un altro nome, l'app lo prende in automatico dalla build; nello script usa `-Repo utente/nome`.

## 2. Preparare il Fire Stick (una volta sola)

1. Impostazioni → La mia Fire TV → Informazioni → premi 7 volte sul nome del dispositivo
   per far comparire **Opzioni sviluppatore**.
2. Opzioni sviluppatore → attiva **Debug ADB**.
3. Annota l'indirizzo IP: Impostazioni → La mia Fire TV → Informazioni → Rete.

## 3. Installare da remoto dal PC

Nella cartella `tools`:

- doppio clic su **deploy.cmd** e scrivi l'IP, oppure
- da PowerShell: `.\deploy.ps1 -FireStick 192.168.1.50 -AttivaTastoHome`

La prima volta sul TV compare «Consenti debug USB?»: scegli **Consenti sempre da questo computer**.
Lo script scarica da solo `adb` (platform-tools ufficiali Google) se non l'hai, installa l'ultima
versione e attiva il servizio Tasto Home.

### Aggiornamento automatico dal PC

Per far installare ogni nuova build senza far nulla, programma lo script ogni ora:

```
schtasks /create /tn "FireRun deploy" /sc hourly /tr "powershell -NoProfile -ExecutionPolicy Bypass -File C:\percorso\FireRun\tools\deploy.ps1 -FireStick 192.168.1.50"
```

Lo script installa solo se su GitHub c'è una versione più nuova di quella sul Fire Stick.
Conviene dare al Fire Stick un IP fisso dal router.

## 4. Aggiornamento dal Fire Stick stesso

FireRun controlla le release ogni 2 ore. Se c'è una versione nuova la scarica e apre subito la richiesta di installazione (disattivabile in Opzioni → Aggiornamenti automatici); compare anche il pulsante arancione
**Aggiornamento** in alto (oppure Opzioni → Aggiorna FireRun). La prima volta Fire OS chiede di
consentire a FireRun di installare app; poi a ogni aggiornamento basta confermare **Installa**.

## Tasto Home e home Amazon

- Il servizio di accessibilità vede quando compare la home Amazon (`HomeActivity_vNext`) e la
  sostituisce con FireRun, coprendola con un velo così non si vede. Le Impostazioni e le altre
  schermate Amazon restano aperte. Attivalo con `deploy.ps1 -AttivaTastoHome`: imposta anche
  `min_futurity=0`, che rende istantaneo il ritorno a FireRun dopo il tasto Home.
- **Opzioni → Diagnostica finestre Amazon** mostra quali schermate Amazon sono state viste e sostituite.
- **Opzioni → Apri la home Amazon** se ti serve qualcosa di Amazon: resta aperta finché non apri un'altra app.
- Facoltativo: `deploy.ps1 -DisattivaHomeAmazon` prova a disattivare la home Amazon
  (su molte versioni di Fire OS non è permesso). Se riesce, FireRun diventa l'unica home.
  Si annulla con `-RiattivaHomeAmazon`; nel caso peggiore c'è il ripristino di fabbrica
  (Indietro + destra sul telecomando per 10 secondi).

## Note

- Fire OS non sfoca gli sfondi come macOS: i pannelli in vetro sono semi-trasparenti.
- Non funziona sui modelli con Vega OS (es. Fire TV Stick 4K Select), che non installano APK.
- La chiave di firma sta solo nei Secrets di GitHub, così ogni build si installa sopra la precedente.
- Per compilare in locale serve Android Studio (apri la cartella come progetto).

## Crediti

Il sistema del Tasto Home (riconoscimento esatto della home Amazon, velo, avvio istantaneo
dopo Home) è ripreso da [dumbtv](https://github.com/humblemuzzu/dumbtv), licenza MIT.
Dettagli in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
