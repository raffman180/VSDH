# VSDH — Portierungsstand, 08.11.2026

## Ziel und verbindliche Entscheidungen

- Minecraft 1.21.1, NeoForge 21.1.264, Java 21.
- Nutzer hat VulkanMod Reforged als Zielrenderer gewählt.
- Vorgehen: zuerst Vulkan + DH, anschließend Shaderpacks.
- Erstes Shader-Ziel: Complementary Reimagined.
- Langfristiger Wunsch: breite Shader-/DH-Kompatibilität für viele Geräte.
  Keine Freigabe ungetesteter Kombinationen.
- Referenzgerät: RTX 4070, Ryzen 9 7950X3D, 64 GB DDR5, 2560×1440.
  Automatischer erster Test: 1280×720, begrenzte Bildrate, kein FPS-Benchmark.

## Quellen und Lizenz

- Einzige Portierungsreferenz: Braffolk/distant-horizons-vulkanmod,
  Commit `a72ba645188c5fab5f9d48254b6703b659543f0c`.
  [Quelle und Lizenztexte](https://github.com/Braffolk/distant-horizons-vulkanmod/tree/a72ba645188c5fab5f9d48254b6703b659543f0c).
  LGPL-3.0; LGPL- und zugehöriger GPL-Text übernommen, Hinweise beibehalten.
- Zielrenderer: TrulyRin/VulkanMod-Reforged,
  Commit `0618965fbedb71ca2ee2e5d4528fcedc4fb27551`, Release
  `mc-1.21.1-0618965`, 0.3.5-ALPHA+1.21.1.
- DH: offizielles Modrinth-Release `9w34y8ai`, Version 3.3.3 für 1.21.1.
  Dateihashes in Buildskript und Downloadskript festgelegt.
- Unveränderte Vergleichsquellen liegen außerhalb dieses Projekts unter
  `C:/Users/Stranger/Documents/ChatGPT/Vulkan Shader&DistantHorizons/.reference`;
  sie sind nicht Teil der Mod oder des Quellpakets.

## Anpassungsmatrix

| System | Vorlage | Umsetzung | Stand |
|---|---|---|---|
| Loader/Einstieg | Fabric ClientModInitializer | `de.stranger.vsdh.Vsdh`, physischer Client | angepasst |
| Rendererbindung | Fabric DependencySetup-Mixin | `DependencySetup_neoforge`, einmalige Bindung | angepasst, Start protokolliert |
| DH-Render-API | DH 3.0 / 2.4 Mischpfad | DH 3.3.3, direkte typisierte VBO-Felder | angepasst |
| Terrain/Pipelines | Vulkan-DH-Kern + GLSL 450 | Reforged-Puffer, UBOs, UINT32-Indizes | erste Welt sichtbar |
| CPU-Staging | Callback an veränderlichem Feld | synchronisierte Eigentümerschaft, atomare VBO-IDs | vier Speichertests bestanden |
| GPU-Freigabe | Worker liest HashMap | Worker stellt nur IDs an, Renderthread gibt frei | angepasst |
| Tiefenbild | falscher 5-Argumente-ImageView-Aufruf | Basis-Mipmap 0 für Reforged | angepasst |
| Fenstergröße | mehrere dauerhafte Callbacks je Welt | zentrale Registrierung / Freigabe | 1280×720 → 960×540 im Spiel geprüft |
| Welt verlassen | kein NeoForge-Lifecycle | LoggingOut → Renderthread-Cleanup | erstes Beenden protokolliert |
| Konfiguration | Fabric-Pfad, behauptetes Hot-Reload | NeoForge-Pfad, einmaliges Laden vor Rendering | angepasst |
| Eigene Wolken | VulkanMod 0.6-API | Vanilla-/Reforged-Wolken erhalten | eigene Wolken nicht unterstützt |
| Textur-LODs / AA / API-Geometrie | teils fehlend | ausdrücklich abgeschaltet und dokumentiert | nicht implementiert |
| Shaderpacks | nicht in DH-Vorlage | noch kein Shader-Backend | offen |

## Tatsächlich ausgeführte Prüfungen

- `gradlew.bat build`: erfolgreich, einschließlich fünf JUnit-Tests, keine
  fehlgeschlagenen oder übersprungenen Tests.
- Fremd-JARs: Hashprüfung erfolgreich.
- Echter Client: Minecraft 1.21.1 / NeoForge 21.1.264 mit Reforged und DH gestartet.
- Log: Vulkan-Gerät RTX 4070, Vulkan-Instanz, Renderer und VSDH-DH-Bindung erstellt.
- Eigene Testwelt: Terrain-, SSAO-, Nebel- und Tiefen-Pipelines erfolgreich erstellt.
- Screenshot `run/screenshots/vsdh-smoke.png` tatsächlich aufgenommen und angesehen:
  Vanilla-Nahbereich und entfernte farbige LODs sichtbar.
- Beenden dieser Welt: Cleanup für 323 zwischengespeicherte Vulkan-Puffer protokolliert.
- Ein Regressionstest prüft zusätzlich DHs Wolkenregistrierung beim Weltaufbau.
  Die Registrierung bleibt trotz ausgeschalteter Geometrie-Darstellung funktionsfähig.
- Erweiterter Test mit Größenwechsel, Regen/Nacht und zweiter Welt:
  `run-smoke-graceful.log`, `runSmokeClient` erfolgreich in 2 min 37 s.
  Zwei Welten neu erstellt, beide mit sichtbaren LODs, Ressourcen zwischen den
  Welten freigegeben und neu angelegt. Screenshots tatsächlich angesehen.
  Der abschließende Lauf enthält keine ERROR- oder FATAL-Zeilen.
- Tages- und Nacht/Regenbilder aus dem Spiel liegen unter `docs/validation/`.
- JAR-Inhalt kontrolliert: kein Smoke-Testcode, keine eingebetteten Fremd-JARs,
  zehn eigene Vulkan-Shader-Ressourcen vorhanden.

## Offener Shutdown-Befund

Der vorherige Lauf `run-smoke-verified.log` beendete den Client direkt aus der
laufenden zweiten Welt. Dabei protokollierte DHs Regenerierungsqueue eine
`java.sql.SQLException: stmt pointer is closed`. Der Stacktrace liegt in
`FullDataSourceV2Repo.getChildPositionsToRegen` / `FullDataUpdatePropagatorV2`,
während DH seine Datenbanken schließt. Die genaue Ursache und eine mögliche
Beteiligung dieser Mod-Kombination sind nicht durch einen Vergleich ohne VSDH
abgeklärt. Keine Fehlerunterdrückung und keine Änderungen an der Datenbanklogik.

Der letzte Test folgt in beiden Welten dem regulären Vanilla-Ablauf
`level.disconnect()` → `Minecraft.disconnect(...)` → Hauptmenü / Beenden;
dieser Lauf war ohne ERROR/FATAL. Das ist keine Behebung oder Entwarnung für
den direkten Beenden-Pfad. Deshalb bleibt die Mod eine Entwicklungsfassung.

## Nicht nachgewiesen / Abweichungen

Kein Complementary, kein Iris, kein Sodium. Kein Test weiterer DH-Versionen,
anderer GPUs/Betriebssysteme, dedizierter Server oder großer Modpacks. Kein
Langzeittest, kein kontrollierter Leistungsvergleich. Die sichtbare erste Welt
belegt keine fehlerfreie Darstellung aller Fälle.

Die DH-2.4-Mixin-Variante wurde nicht übernommen: andere Schnittstellen dürfen
nicht versehentlich als DH-3.3-kompatibel behandelt werden. Das ist eine bewusste
Eingrenzung des ersten Meilensteins. Benutzereinstellungen werden nicht dauerhaft
umgeschrieben. Die neuen Tests verwenden ausschließlich selbst erzeugte Welten.

## Nächste Aufgaben

1. Shutdown-Befund in einem Vergleichsprofil isolieren; Langzeittest ergänzen.
2. Tiefen-/Wasser-/Wolkenübergänge, Dimensionswechsel und F3+T gezielt prüfen.
3. Architekturentscheidung mit dem Nutzer treffen: separates Addon oder eigener
   Reforged-Fork. Die geprüfte `Framebuffer.Builder`-Implementierung von Reforged
   lehnt mehr als einen Farbanhang ausdrücklich ab (`colorAttachments <= 1`).
   Eigenen Shader-Backend-Entwurf für Complementary festlegen: Terrain-/Material-
   und Schattenausgabe, mehrere Farbpuffer, Shader-Übersetzung und Pass-Reihenfolge.
   Ein reiner Bildschirmfilter reicht dafür nicht.
4. Erst nach einem funktionierenden festgelegten Shader weitere Shaderpacks
   und DH-Versionen über getrennte Adapter aufnehmen.
