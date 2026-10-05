# Vibe – hudební přehrávač pro Android

> **Soukromý projekt, není dokončený.** Aplikace vznikla pro vlastní potřebu a pár kamarádů a je dost možné,
> že zůstane jen u verze 1.0.0. Používání je na vlastní riziko.

**[⬇ Stáhnout APK (poslední verze)](https://github.com/ItsCr4zyyy/Vibe/releases/latest/download/vibe.apk)** •
[Všechny verze](https://github.com/ItsCr4zyyy/Vibe/releases)

Kotlin + Jetpack Compose + Media3. Streamování i stahování z YouTube přes **yt-dlp**, který se aktualizuje sám.
Žádný účet, žádné reklamy. Android 8+.

## Sestavení
1. Otevři složku v Android Studiu (2025.3+) a počkej na Gradle sync.
2. Vyber zařízení/emulátor → **Run**. Studio samo nasadí správnou ABI (arm64 / x86_64).
3. APK k vydání: `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-arm64-v8a-release.apk`
   (do GitHub Release nahrát přejmenované na `vibe.apk`).

Web ke stažení (SvelteKit) je ve složce [`web/`](web/README.md).

## Funkce
- **Hledání** přes YouTube Music (InnerTube API) – rychlé, s obaly a metadaty.
- **Režim přehrávání** (Nastavení): *Jen streamovat* nebo *Streamovat a ukládat* (přehrané skladby se uloží offline). Kdykoli lze stáhnout ručně skladbu nebo celý playlist.
- **Import** playlistů/alb/skladeb z YouTube, YouTube Music a Spotify (vložením odkazu nebo přes *Sdílet → Vibe*). Spotify skladby se dohledají na YouTube Music podle názvu, interpreta a délky.
- **Synchronizace** importovaných playlistů (ručně i automaticky každých 12 h).
- **Automatické aktualizace yt-dlp** – při startu a periodicky (kanál Stable/Nightly).
- **Stránky interpretů a alb** (populární skladby, alba, singly, podobní interpreti; album lze uložit do knihovny).
- **Rádio a mixy**: rádio ze skladby, rádio z playlistu/alba (vlastní skladby proložené podobnými, ve smyčce), „Tvůj mix“ z celé knihovny, automatické pokračování po dohrání fronty. Shuffle u playlistu hraje náhodně dokola.
- Ovládání v panelu notifikací / na zamykací obrazovce včetně tlačítek **Líbí se, Náhodně, Opakovat**.
- Oblíbené, historie, fronta, synchronizované texty písní (LRCLIB).
- **Android Auto** – procházení knihovny (Oblíbené, Naposledy, Playlisty, Mixy), hledání a hlasové „pusť … na Vibe“.
  Aplikace mimo Google Play se v autě zobrazí až po zapnutí *Android Auto → Nastavení vývojáře → Neznámé zdroje*
  (vývojářský režim se zapne 10× ťuknutím na verzi v nastavení Android Auto).
- Časovač vypnutí (s plynulým ztlumením), vyrovnání hlasitosti, plynulé přechody, ekvalizér.
- Cache streamů (512 MB) – opakovaně přehrávané skladby startují hned a bez dat.
- Úpravy playlistů (tažením), hledání a řazení v playlistu, historie a našeptávač hledání.
- Záloha / obnova celé knihovny a sdílení playlistů s kamarády (soubor `.vibe.json`, otevře se ve Vibe).
- Statistiky poslechu (minuty, top interpreti a skladby, graf za 30 dní).
- Dynamické barvy podle obalu alba, volitelně Material You.

## Odolnost vůči změnám YouTube (bez aktualizace aplikace)
- **yt-dlp** se aktualizuje sám (při startu a každých 12 h).
- **YouTube Music API**: aktuální verze klienta se zjišťuje z music.youtube.com (denně a při odmítnutí).
- **Záložní cesty**: hledání, rádio, alba i interpreti umí fungovat i čistě přes yt-dlp.
- **Diagnostika** v Nastavení ukáže stav všech částí a tlačítkem „Otestovat vše“ prověří celý řetězec.

## Struktura
```
app/src/main/java/com/vibe/music/
├── VibeApp.kt            # DI kontejner, start (init yt-dlp, auto-update)
├── MaintenanceWorker.kt  # periodická aktualizace yt-dlp + sync playlistů
├── data/                 # Room DB, nastavení, import, knihovna
├── remote/               # yt-dlp, YouTube Music API, Spotify, texty
├── download/             # fronta stahování (WorkManager)
├── playback/             # Media3 služba, resolver vibe://track/<id> → soubor / stream
└── ui/                   # Compose obrazovky (home, search, library, playlist, player, settings)
```

## Poznámky
- Verze knihoven jsou zvolené pro **compileSdk 36 + AGP 8.13**. Novější (Compose 1.12, Navigation 2.10…) vyžadují SDK 37 a AGP 9.1.
- Spotify bez API klíče načte max. ~100 skladeb z playlistu; Client ID/Secret (zdarma z developer.spotify.com) lze zadat v Nastavení.
- Stažená hudba je v `Android/data/com.vibe.music/files/music` (soukromá složka aplikace).
- Stahování z YouTube porušuje jeho podmínky užívání – aplikace je určená jen pro osobní použití.
