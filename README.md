# Podspelar

Enkel podkastspelar for Android.

- **Finn poddar:** søk i Apple sin opne podkastkatalog (krev ingen konto eller nøkkel), eller lim inn ei RSS-adresse
- **Favorittar:** stjernemerk ein pod, så viser startsida dei nyaste episodane frå alle favorittane
- **Speleliste:** legg episodar i kø, flytt dei opp og ned, og la dei spela etter kvarandre. Ein episode forsvinn frå lista når han er høyrd ferdig
- **Nedlasting:** last ned enkeltepisodar for å høyra utan nett, elles blir lyden strøymd
- **Avspeling i bakgrunnen:** kontrollar i varsellinja, på låseskjermen og på headset/bil
- Hugsar kor langt du kom i kvar episode, hopp 15 s tilbake / 30 s fram, og val av fart

Appen har ingen konto, ingen reklame og ingen sporing. Favorittar, framdrift og nedlastingar ligg
berre på telefonen. Nettet blir brukt til å søkja (itunes.apple.com), henta RSS-feedane til poddane
og henta lyd og omslagsbilete frå dei som gir ut poddane.

## Slik er koden bygd opp

| Fil | Innhald |
|---|---|
| `Feeds.kt` | Søk, henting og tolking av RSS. Rein Kotlin, så han kan testast på PC-en |
| `Store.kt` | Det appen hugsar: favorittar, episodelister, framdrift, nedlastingar (JSON-filer) |
| `PlaybackService.kt` | Avspelingstenesta (Media3/ExoPlayer) og appen si kopling til henne |
| `DownloadService.kt` | Nedlasting som forgrunnsteneste, éin episode om gongen |
| `MainActivity.kt`, `Ui.kt` | Skjermane (Jetpack Compose) |

## Byggja

Krev JDK 17 eller nyare og Android SDK (platform 35).

```bash
./gradlew assembleDebug       # testversjon: app/build/outputs/apk/debug/Podspelar-x.y-debug.apk
./gradlew assembleRelease     # signert versjon: app/build/outputs/apk/release/Podspelar-x.y-release.apk
./gradlew test                # testar RSS-tolkinga
```

Signert release-bygg krev fila `keystore.properties` i rotmappa (ligg **ikkje** i repoet):

```properties
storeFile=/sti/til/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Ved ny versjon: auk `versionCode` (og `versionName`) i `app/build.gradle.kts`, byggj med
`assembleRelease` og legg APK-fila ut som ein release.

Ein telefon som har ein debug-versjon installert, må avinstallera han før den signerte
versjonen kan installerast, fordi dei to er signerte med ulike nøklar.

## Lisens

Copyright © 2026 Frode

Podspelar er fri programvare under GNU General Public License versjon 3 (GPL-3.0). Sjå `LICENSE`.
