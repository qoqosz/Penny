# Penny

Aplikacja na Androida do przeglądania i dodawania transakcji w **Money** (Jumsoft) z synchronizacją przez iCloud.

## Jak to działa

Money trzyma dane w prywatnej bazie **CloudKit** (`iCloud.com.jumsoft.money`), a nie jako plik na iCloud Drive.
Apple nie udostępnia tej bazy aplikacjom spoza ekosystemu, więc Android nie może jej czytać bezpośrednio.
Penny korzysta z Maca jako mostu:

```
Android (Penny) ⇄ Wi-Fi ⇄ penny-bridge (Mac) ⇄ lokalna baza Money ⇄ Money.app ⇄ iCloud ⇄ iPhone/iPad
```

- **Odczyt:** `penny-bridge` czyta lokalną bazę Money (Core Data/SQLite) przez model danych wczytany wprost z Money.app.
- **Zapis:** most zamyka Money, robi kopię zapasową, dopisuje transakcję do bazy Money i rejestruje ją w dzienniku
  zmian SyncKit (stan „new”), tak jak robi to sam Money. Potem uruchamia Money w tle. Money wysyła transakcję
  do iCloud przy najbliższej synchronizacji, a stamtąd trafia ona na pozostałe urządzenia.
- Nowe transakcje kopiują pola techniczne (`transactionType`, typ splitu, flagi) z ostatniej podobnej transakcji
  zapisanej przez Money, więc wyglądają tak samo jak wprowadzone ręcznie.
- Telefon trzyma kolejkę offline: transakcje dodane poza domem czekają i wysyłają się po powrocie do sieci Wi-Fi.

## Instalacja na Macu

Wymagania: macOS 14+, Money 9 z włączoną synchronizacją iCloud, Xcode Command Line Tools.

```bash
cd bridge
swift build -c release
.build/release/penny-bridge selftest     # test na sztucznej bazie, nie dotyka danych Money
.build/release/penny-bridge install      # instaluje usługę uruchamianą przy logowaniu
```

Następnie:

1. Ustawienia systemowe → Prywatność i ochrona → **Pełny dostęp do dysku** → „+” → `Cmd+Shift+G` →
   `~/Library/Application Support/PennyBridge/bin/penny-bridge`. Dostęp trzeba nadać ponownie po każdej
   reinstalacji, bo macOS rozpoznaje program po podpisie.
2. Zrestartuj usługę: `launchctl kickstart -k gui/$(id -u)/app.penny.bridge`
3. Sprawdź: `~/Library/Application\ Support/PennyBridge/bin/penny-bridge doctor`
4. Jeśli macOS zapyta o połączenia przychodzące lub sieć lokalną, zezwól.

Log usługi: `~/Library/Logs/PennyBridge.log`. Kopie zapasowe bazy przed każdym zapisem:
`~/Library/Application Support/PennyBridge/backups/` (ostatnie 30).

## Instalacja na Androidzie

```bash
cd android
./gradlew assembleRelease
adb install app/build/outputs/apk/release/app-release.apk
```

Przy pierwszym uruchomieniu Penny szuka Maca w sieci (Bonjour). Po wybraniu Maca uruchom na nim
`penny-bridge pair` i wpisz w telefonie pokazany 6-cyfrowy kod.

## Polecenia mostu

| Polecenie | Opis |
|---|---|
| `penny-bridge pair` | kod parowania telefonu (ważny 10 min) |
| `penny-bridge doctor [--verbose]` | diagnostyka: lokalizacja baz, rozpoznane konwencje, salda |
| `penny-bridge devices` / `revoke NAZWA` | sparowane urządzenia |
| `penny-bridge install` / `uninstall` | usługa LaunchAgent |
| `penny-bridge selftest` | test zapisu na sztucznej bazie zbudowanej z modelu Money |

Konfiguracja: `~/Library/Application Support/PennyBridge/config.json`, m.in. `port`, `writesEnabled`
(wyłącza zapis) i `launchMoneyAfterWrite`.

## Ograniczenia

- Mac musi być włączony i w tej samej sieci, żeby telefon pobrał świeże dane lub wysłał transakcje.
- Ruch w sieci lokalnej jest nieszyfrowany (HTTP + token). Używaj w zaufanej sieci domowej.
- Zapis zamyka Money na kilka sekund. Gdy Money jest na pierwszym planie (właśnie go używasz) albo ma otwarte
  okno edycji, most odkłada zapis, a telefon ponawia wysyłkę później. Można to wyłączyć opcją
  `deferWhileMoneyActive: false`.
- Obsługiwane są wydatki i przychody z jedną kategorią. Przelewy między kontami, transakcje dzielone
  i edycja istniejących transakcji jeszcze nie działają.
- Saldo kont inwestycyjnych to tylko saldo gotówkowe, bez wycenianych papierów.
- Format danych Money nie jest udokumentowany. Most sprawdza zgodność modelu z zainstalowaną wersją Money i odmawia
  zapisu, jeśli nie rozpozna formatu. Po aktualizacji Money warto uruchomić `penny-bridge doctor`.

## Struktura

- `bridge/`: Swift, bez zależności (Core Data, SQLite, Network.framework).
- `android/`: Kotlin, Jetpack Compose, Material 3, OkHttp, kotlinx.serialization, WorkManager.
