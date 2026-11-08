# Weiterarbeit an VSDH

Projektwurzel: `C:/Users/Stranger/Documents/Meine Mods/VSDH`.
Entscheidungen und Referenzstände: `AGENTS.md`, `docs/MIGRATION_STATUS.md`.

```powershell
.\tools\fetch-dependencies.ps1
.\gradlew.bat build
.\gradlew.bat runSmokeClient
```

Ein echter erster Weltlauf war erfolgreich; Screenshot und Logs liegen unter
`run/screenshots/` und `run-smoke.log`. Fünf Tests bestehen: vier für Speicher,
einer für DHs Registrierung beim Weltaufbau.
Der abschließende Lauf `run-smoke-graceful.log` war erfolgreich: zwei Welten,
Größenwechsel, Nacht/Regen, Neuinitialisierung; keine ERROR/FATAL-Zeilen.
Vorher trat beim direkten Beenden aus einer Welt eine DH-SQL-Meldung auf.
Dieser Befund ist ausdrücklich noch offen; Details im Portierungsstand.

Unbedingt ehrlich bleiben: Dies ist erst die native DH-Anbindung. Es gibt noch
keine Iris-/Sodium- oder Shaderpack-Ausführung. Complementary Reimagined ist das
vom Nutzer gewählte erste Shader-Ziel; kein Funktionsnachweis dafür vorhanden.
Vor dem größeren Shader-Ausbau ist die Wahl Addon / eigener Reforged-Fork mit
dem Nutzer abzustimmen. Ein Fork wäre für die nötigen Rendereränderungen
einfacher zu pflegen; er ersetzt aber die bisher installierte Reforged-JAR.

Der Entwicklungs-Testcode liegt in `src/smoke`, außerhalb der Release-JAR.
Fremd-JARs werden nicht in die Release-JAR eingebettet. Die passenden JARs müssen
im Spiel neben VSDH installiert sein. Kein Remote-Repository / keine Veröffentlichung
wurde beauftragt oder eingerichtet.
