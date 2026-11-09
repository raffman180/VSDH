# VSDH — Entwicklungsstand 0.9.0-dev

VSDH enthält einen eigenen **VulkanMod-Reforged-Fork** und eine native Anbindung
für Distant Horizons. Die Durchlaufkette für Shader-Farbpuffer, HDR/Mipmaps, gespeicherte Werte zwischen
Frames und getrennte Tiefenkopien ist hinzugekommen. **Der vollständige Complementary-Renderer
ist noch nicht fertig.** Iris und Sodium können nicht zusätzlich geladen werden.

| Bestandteil | Gebundene Version |
|---|---|
| Minecraft Java | 1.21.1 |
| NeoForge | 21.1.264 |
| Java | 21 |
| Vulkan-Renderer | in dieser VSDH-JAR enthalten; Reforged-Basis 0618965 |
| Distant Horizons | 3.3.3 für 1.21.1, Fabric/NeoForge-JAR |
| Erstes Shader-Ziel | Complementary Reimagined r5.9.3, Profil MEDIUM |

## Testinstallation

1. Ein separates Profil für Minecraft 1.21.1 / NeoForge 21.1.264 anlegen.
2. Die VSDH-0.9.0-dev-JAR und DH 3.3.3 in dessen `mods`-Ordner legen.
3. Die originale Reforged-JAR sowie alte VSDH-0.1-JARs entfernen: Ab 0.2 ersetzt
   diese eine JAR den Renderer und das bisherige Addon.
4. Iris, Sodium und andere Renderer-Ersatzmods aus diesem Profil herauslassen.
5. Eine neue Testwelt ohne Shader starten und die LOD-Erstellung abwarten.

Es gibt noch keine funktionierende Auswahl für vollständige Shaderpacks.
Ein Shader-ZIP im Ordner allein aktiviert deshalb keinen Shader. Die unten
beschriebenen Compiler-/GPU-Prüfungen sind Entwicklungstests.

VSDH wird ausschließlich auf dem Client installiert. Bestehende DH-Speicherung
und Multiplayer-Protokolle bleiben bei DH. Keine eigenen Änderungen an Inventaren
oder Weltdaten; dedizierte Server und große Modpacks sind noch ungetestet.

## Aktuell 0.9

Die echte Complementary-Renderkette läuft jetzt auf Welt-Chunks mit nativen
Schatten, Packtexturen und Weltformeln. 56 automatisierte Tests bestehen. Der
Diagnose- und der echte Pack-Weltlauf prüfen Größenänderung, 100%/50% Scale und
Weltwechsel. Ein gemessener Fehler der Projektion wurde behoben: Wolken werden
wieder im Himmel statt über dem Wasser berechnet.

Die Integration ist weiterhin unvollständig. Sichtbarer heller Wasserübergang,
DH-Packprogramme und andere Geometriefamilien bleiben offen. Der Standardmodus
aktiviert Shader weiterhin nicht automatisch. Entwicklungsprüfung des echten
Packs: runSmokeClient mit -PsmokeLivePack=true und -PsmokeShaderPack=<Pack-ZIP>.
Details: docs/WORLD_SHADOWS.md und docs/NEXT_AI_PROMPT.md.

## Stand 0.6

Die native Welt-Renderkette wird mit einem eigenen Diagnosepack auf tatsächlichen
Chunks samt DH geprüft, einschließlich reduziertem Rendermaßstab. Eine vollständige
Complementary-Auswahl ist weiterhin nicht verfügbar. Neu sind echte Kamera-,
Augenlicht-, Effekt-, Atlas- und Nebelwerte sowie die Erkennung tatsächlich
gelesener Uniforms. 36 CPU-Tests bestehen; GPU-Messungen prüfen die Werte in Luft,
Wasser an negativen Koordinaten und mit Statuseffekten. Technischer Stand:
`docs/WORLD_SHADER_HOST.md`, `docs/WORLD_UNIFORMS.md`; Fortsetzungs-Prompt:
`docs/NEXT_AI_PROMPT.md`.

## Enthalten

- Eigenes Shader-Terrainformat mit echten Material-IDs, Normalen/Tangenten,
  Quad-/Blockmittelpunkten und getrennten Licht-/AO-/Farbdaten. In einer
  Testwelt durch echte Chunk-Bauer geprüft; noch keine vollständige Pack-Weltgrafik.

- Farbige DH-LODs mit Lichtkarte, Tiefenkomposition, Nebel und SSAO.
- Renderthread-Eigentümerschaft der Vulkan-Ressourcen; Worker stellen kopierte
  Upload-Daten bereit. Ressourcenfreigabe bei Weltwechsel und Größenänderung.
- Bis zu acht Farbausgaben pro Renderdurchlauf, abhängig von der Geräteobergrenze.
- Explizite Abbildung von Shader-Zielformaten auf Vulkan, einschließlich HDR.
- Include-/Options-/Profilparser und GLSL-Übersetzung aus dem offenen Vitrail-Code;
  an Java 21 und die Zielversion angepasst. Kein Minecraft-26.x-Runtime-Import.
- Native Vollbilddurchläufe mit eigenen Deskriptoren, geprüfter std140-Anordnung
  und Erkennung versehentlichen Lesens und Schreibens desselben Bildes.
- Native Durchlaufreihenfolge begin/prepare/deferred/composite/final; getrennte
  Lese-/Schreibbilder, profilspezifische Puffergrößen, Speicher-Vorprüfung und
  Rückkopieren gespeicherter Bilddaten am Frame-Ende.
- Getrennte Tiefenbilder vor der Hand und vor transparenten Blöcken, einschließlich
  korrekter Stencil-Kopien. D32, D24S8 und D32S8 im Entwicklungstest geprüft.
- Korrigierte Behandlung von Nummernlücken bei optimierten Textur-Bindings.
- Gezielte DH-3.3.3-Abschaltergänzung: laufende lesende Regenerationsabfragen werden
  abgebrochen, Worker beendet und erst danach ihre Datenbank geschlossen.

## Grenzen

Die Integration der echten Weltgeometrie mit Materialdaten, Schatten, Himmel,
Entitäten, Transparenz und shaderseitigen DH-Ausgaben ist noch zu vervollständigen.
Die neue Kette ist mit einem eigenen Diagnosepack auf echten Chunks geprüft;
alle neun Vollbild-Pipelines des echten Complementary-Packs wurden auf der GPU
angelegt. Das aktiviert noch keinen vollständigen Shader-Modus. Erfolgreich übersetzte
Programme allein bedeuten keine vollständige Pack-Unterstützung. Es gibt keine
Freigabe für beliebige Shaderpacks, DH-Versionen oder Grafikkarten.

Texturierte DH-LODs, DH-Antialiasing, DH-Drahtgitter und benutzerdefinierte DH-API-
Geometrie sind noch nicht implementiert. Diese DH-Optionen werden nur für die
Sitzung abgeschaltet; gespeicherte Benutzerwerte werden nicht überschrieben.
Vanilla-/Reforged-Wolken bleiben aktiv. Wasser-/Wolken-/LOD-Übergänge benötigen
weitere Sichtprüfungen. Bisheriges Referenzgerät: RTX 4070, Windows. Keine FPS-Zusage.

## Bauen und prüfen

JDK 21 installieren; im Projektordner unter PowerShell:

```powershell
.\tools\fetch-dependencies.ps1
.\gradlew.bat build verifyBundledLwjgl
.\gradlew.bat runSmokeClient
```

Die installierbare JAR liegt unter `build/libs/`; die `-sources.jar` ist kein Mod.
Vulkan/shaderc/VMA werden als Bibliotheken eingebettet. DH bleibt extern;
Flywheel wird nur zum Kompilieren vorhandener Renderer-Kompatibilitätsklassen
benötigt. Die festgelegten Dateihashes stehen in `libs/README.md`.

Der Smoke-Test erzeugt ausschließlich neue `VSDH-Smoke-…`-Welten unter `run/saves`.
Er prüft GPU-Pixelwerte, Weltwechsel, Fensteränderung, Nacht/Regen und Abschalten.
Vorhandene Welten werden nicht ausgewählt oder gelöscht. Der gesamte Code unter
`src/smoke` und dessen Diagnose-Shaderpack sind aus der installierbaren JAR ausgeschlossen.

Eine externe Originalkopie von Complementary für die Compilerprüfung angeben:

```powershell
.\gradlew.bat auditShaderPack auditRenderGraph '-PshaderPack=C:/Pfad/ComplementaryReimagined_r5.9.3.zip'
.\gradlew.bat runSmokeClient '-PsmokeShaderPack=C:/Pfad/ComplementaryReimagined_r5.9.3.zip'
```

Der Spieltest prüft die native Kette mit einem eigenen Diagnosepack, Tiefen- und
Stencil-Kopien und Schachbrett-Mipmaps. Beim Originalpack führt er nur den
abschließenden Durchlauf mit kontrollierten Eingabebildern aus und legt zusätzlich
die vollständige Vollbild-Pipeline-Kette an. Das sichtbare Weltbild bleibt ohne Shader.
Das Shaderpack wird nicht mitgeliefert. Übersetzte Pack-Quellen bleiben in `build/`
und gehören nicht in ein öffentliches Quellpaket. Auch ohne GPU lassen sich
Java-Build, Logiktests und die SPIR-V-Compilerprüfung ausführen.

## Prüfstand und Herkunft

Tatsächliche Ergebnisse und offene Arbeiten: `docs/MIGRATION_STATUS.md`.
Technische Befunde: `MODLOG.md`; Weiterarbeit: `docs/HANDOFF.md`.
Ein kurzer erfolgreicher Test ist keine allgemeine Stabilitätsfreigabe.

Inoffizielle Anpassung von [Braffolks Vulkan-DH-Erweiterung](https://github.com/Braffolk/distant-horizons-vulkanmod),
[VulkanMod Reforged](https://github.com/TrulyRin/VulkanMod-Reforged) und portablen
Teilen von [Vitrail](https://github.com/avpbynf/Vitrail-Shaders). Exakte Stände,
Autoren und Lizenztexte: `NOTICE`, `LICENSE`, `COPYING`, `docs/upstream/`.
