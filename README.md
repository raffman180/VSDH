# VSDH — Entwicklungsstand 0.26.0-dev

VSDH enthält einen **VulkanMod-Reforged-Fork**, eine native Anbindung für
Distant Horizons und einen Vulkan-Renderer für ausgewählte Iris-Shaderpacks.
Die Spieltests verwenden Complementary Reimagined r5.9.3, BSL 10.1 und
Bliss 2.1.2, seit dem 11.11.2026 zusätzlich Photon 1.4, Eclipse Unstable,
Solas 3.9 und Rethinking Voxels r0.1-beta9 (siehe unten). Verwende für
dieses Profil die VSDH-JAR als Renderer.

| Bestandteil | Gebundene Version |
|---|---|
| Minecraft Java | 1.21.1 |
| NeoForge | 21.1.264 |
| Java | 21 |
| Vulkan-Renderer | in dieser VSDH-JAR enthalten; Reforged-Basis 0618965 |
| Distant Horizons | **optional.** Ohne DH laufen Vulkan-Renderer und Shaderpacks allein. Mit DH: 3.2.0-b sowie 3.3.0 bis 3.3.3 für 1.21.1, alle mit derselben JAR (für 3.3.0 passt VSDH sich beim Laden an; diese Einzel-JAR ist im Spiel noch nicht geprüft). |
| Erstes Shader-Ziel | Complementary Reimagined r5.9.3, Profil MEDIUM |

## Testinstallation

1. Ein separates Profil für Minecraft 1.21.1 / NeoForge 21.1.264 anlegen.
2. Eine passende VSDH-0.26.0-dev-JAR in dessen `mods`-Ordner legen, für ferne
   Landschaften zusätzlich genau eine der oben genannten DH-Versionen (optional).
3. Aus diesem Profil die originale Reforged-JAR und frühere VSDH-JARs entfernen.
   Die VSDH-JAR enthält den Renderer und die Anbindung für Distant Horizons.
4. Iris, Sodium und andere Renderer-Ersatzmods aus diesem Profil herauslassen.
5. Eine neue Testwelt ohne Shader starten und die LOD-Erstellung abwarten.

## Shader aktivieren

1. Die originale ZIP-Datei des Shaderpacks unverändert in den Ordner `shaderpacks`
   des Profils legen (Optionen → Videoeinstellungen → **Shader** → Shaderpack-Ordner
   „Öffnen“ öffnet ihn).
2. In derselben Seite unter **Shaderpack** das Pack wählen, optional ein
   **Pack-Profil** (z. B. LOW, MEDIUM, HIGH; „Pack-Standard“ nutzt die Werte des Packs),
   dann **Übernehmen**.
3. Im Hauptmenü lädt VSDH das Pack sofort (Meldung „Shaderpack wird vorbereitet“, dann
   „Vulkan-Shaderpack bereit“). Wird die Wahl in einer Welt getroffen, gilt sie ab dem
   nächsten Verlassen der Welt. Ein Neustart ist nicht nötig.
4. **Shaderpack: Aus** schaltet zurück auf die Standarddarstellung.
5. Unter **Pack-Optionen** lassen sich die Einstellungen des Packs (Schatten, Wolken, Nebel …)
   einzeln ändern, mit den Namen aus dem Pack. „Übernehmen“ lädt das Pack neu, ohne Neustart.

Kann ein Pack nicht geladen werden, bleibt die bisherige Darstellung und die Ursache
steht in `logs/latest.log`. Scheitert ein Pack erst in der Welt, schaltet VSDH mit
Chat-Hinweis auf die Standarddarstellung, statt das Spiel zu beenden. Die Wahl steht in
`config/vsdh.json`; Profil und Pack-Optionen werden je Pack gemerkt (`otherPacks`), so
dass ein Wechsel hin und zurück nichts verliert. Einzelne Pack-Optionen stehen dort unter `shaderOptions`, zum Beispiel:

```json
{
  "shaderPack": "ComplementaryReimagined_r5.9.3.zip",
  "shaderProfile": "MEDIUM",
  "shaderOptions": { "ENTITY_SHADOW": "2" },
  "shaderDistantHorizons": true
}
```

Iris, Oculus, Sodium und Embeddium nicht zusammen mit VSDH installieren; NeoForge meldet
sie beim Start als unverträglich. Getestet sind die unten genannten Packs auf einer
NVIDIA RTX 4070 unter Windows 11; andere Packs und GPUs können abweichen.

VSDH wird ausschließlich auf dem Client installiert. Bestehende DH-Speicherung
und Multiplayer-Protokolle bleiben bei DH. Keine eigenen Änderungen an Inventaren
oder Weltdaten; dedizierte Server und große Modpacks sind noch ungetestet.

## Stand 0.26 — Iris-Abgleich (11.11.2026 abends)

Mit einem Prüf-Pack, das Uniform-Werte als Farbbalken ausgibt, wurden VSDH und
Iris 1.8.12 Wert für Wert verglichen. Behoben:
- Die Pack-Kette arbeitet jetzt in OpenGL-Ausrichtung (`gbufferProjection[1][1]`
  positiv, Bildzeilen von unten nach oben); vorher kehrten Packformeln wie
  `gbufferProjection[1][1] / 1.37` (Umgebungsverdeckung, Lichtbrechung,
  Tiefenschärfe) ihr Vorzeichen um.
- `vignette=false` und `underwaterOverlay=false` der Packs werden beachtet (wie
  Iris); vorher dunkelte die Vanilla-Vignette die Bildränder um 5–10 % ab.
- Iris-Merkmale `HIGHER_SHADOWCOLOR`, `BLOCK_EMISSION_ATTRIBUTE`,
  `ENTITY_TRANSLUCENT` werden angekündigt und sind umgesetzt (u. a. interaktives
  Wasser in Rethinking Voxels, `shadowcolorimgN`).
- Terrain nutzt wie Iris `gbuffers_terrain_solid`/`_cutout` (Photon, Mellow,
  Eclipse) und die Ersatzkette bis `gbuffers_textured`.
Einzelheiten: `docs/NATIVE_PACK_FEATURES.md`, Abschnitt „Iris parity fixes“.

## Stand 0.26 — Shaderpack-Kompatibilität (11.11.2026)

Neu unterstützt: Compute-Programme (inkl. `setup` und `shadowcomp`),
Speicherpuffer (`bufferObject`), eigene Bilder (`image.*`), Datentexturen
(`.dat`, auch 3D), Geometry- und Tessellation-Stufen sowie die Pack-Wolken
(`gbuffers_clouds`). Damit lassen sich alle 14 Testpacks starten. Mit
derselben Szene wie im Iris-Referenzclient sehen Photon, Eclipse Unstable,
Bliss, Complementary Reimagined/Unbound und Miniature sichtbar gleich aus;
Solas und Rethinking Voxels weitgehend. Nach dem Iris-Abgleich am Abend
stimmen alle 14 Testpacks sichtbar überein (BSL-Wolken kommen über DHs
Generic-Objekte). Einzelheiten in `docs/NATIVE_PACK_FEATURES.md` und
`docs/MIGRATION_STATUS.md`. Kann ein Pack in einer Welt nicht aufgebaut
werden, schaltet VSDH mit Chat-Hinweis auf die Standarddarstellung, statt
abzustürzen. Andere Packs, Einstellungen und GPUs sind nicht getestet.

## Stand 0.26 — Leistung

In der festen Testszene (2560×1440, Complementary MEDIUM, DH 3.3.3,
Sichtweite 8, RTX 4070) steigen die FPS von 89,4 (0.25) auf 112,4.
Die Tiefenkopien für `depthtex1`/`depthtex2` kopieren keinen ungenutzten
Stencil-Anteil mehr. Textur-Uploads lassen den nächsten Frame nicht mehr
auf dem Prozessor warten. Der Schattenpass zeichnet nur noch, was nach
den Pack-Vorgaben Schatten ins Bild werfen kann. Messwerte, Werkzeuge und
Grenzen stehen in `docs/PERFORMANCE.md`.

Vergleich mit Iris 1.8.12 in derselben Szene (RTX 4070, DH 3.3.3,
Sichtweite 4, Fenster, 11.11.2026): Ohne Shaderpack ist der Vulkan-Renderer
in 1280×720 35–50 % schneller als Iris mit Sodium, in 2560×1440 gleichauf.
Mit Shaderpack und DH bei 2560×1440 (RTX 4070, Stand 12.11.2026): im **Vollbild** ist VSDH
in allen fünf gemessenen Packs 9–18 % schneller als Iris (Complementary 162 gegen 147 FPS,
Photon 169/150, BSL 202/171, Bliss 154/138, Eclipse 118/108). Im **Fenster** ist VSDH
seit der eigenen DirectX-12-Bildausgabe (12.11.2026 mittags) 5–14 % schneller als Iris
(Complementary 160 gegen 146, Photon 166/148, BSL 193/170, Bliss 152/137, Eclipse 110/104).
Die DirectX-12-Ausgabe ist auf NVIDIA unter Windows im Fenster automatisch aktiv; im Vollbild
nutzt VSDH weiter die Vulkan-Ausgabe, weil sie dort schneller ist. Abschalten:
JVM-Schalter `-Dvsdh.dxgiPresent=false`. Andere GPUs, Treiber und Szenen sind nicht gemessen;
auf AMD/Intel bleibt die Vulkan-Ausgabe.
3D-Texturen eines Packs (Photon, Mellow) sind echte 3D-Bilder; der frühere 2D-Atlas
bleibt als JVM-Schalter `-Dvsdh.flatVolumes=true`.

Bei Darstellungsfehlern nach Textur-Updates hilft zur Eingrenzung der
JVM-Schalter `-Dvsdh.legacyUploadSync=true` (alter Upload-Weg).

## Stand 0.25

DH 3.2.0-b verwendet die ältere Methode zum Übertragen der fertigen
Textur und ältere Datenbankabfragen. VSDH wählt die passenden Hooks
anhand der installierten DH-JAR. Der Vulkan-Shaderpfad verbindet
Vertex- und Fragmentwerte über ihre GLSL-Namen und sichert bei Bedarf
eine Farbtextur, bevor Geometrie denselben Puffer liest und beschreibt.
Bliss 2.1.2 ist mit den Standardoptionen in der Oberwelt bei 1440p geprüft.
Setze dafür `shaderPack` auf den Namen des originalen Bliss-ZIPs und
`shaderProfile` auf eine leere Zeichenfolge.

## Stand 0.24

Für BSL 10.1 liefert der gemeinsame Renderer zusätzliche Werte für
Welthöhe, Sonnenrichtung, dominante Hand und Blockentitätskontext.
Der ältere DH-3.3-Tiefenadapter wird zur Laufzeit ausgewählt. Beide
Adapter verwenden denselben Vulkan-Renderer. DH 3.3.0 benennt seine NeoForge-Klassen
separat; ein mitgelieferter Coremod passt VSDH beim Laden daran an (nur bei DH 3.3.0).

Für BSL `BSL_v10.1.zip` als `shaderPack` und `MEDIUM` als Profil eintragen.
`ENTITY_SHADOW` ist in der unten gezeigten Form eine Complementary-Option.

## Stand 0.23

Die Hand verwendet getrennte Pack-Durchläufe für opake und transparente
Gegenstände. Verzauberungsglanz verwendet die animierte Textur und die
bereits gezeichnete Tiefe. Normale transparente Blockentitäten und
leuchtende Mob-Schichten haben eigene Programme. Modellschatten folgen
den Einstellungen des Shaderpacks.

Für Mob- und Blockentitätsschatten mit Complementary MEDIUM kannst du in
`config/vsdh.json` zusätzlich `"shaderOptions": { "ENTITY_SHADOW": "2" }`
eintragen. Die Option überschreibt das Profil beim nächsten Start.

## Stand 0.20

Transparente Mob-Schichten wie die Außenhaut von Schleimen können
`gbuffers_entities_translucent` verwenden. VSDH zeichnet sie vor Deferred
oder bei `separateEntityDraws=true` unmittelbar danach. Transparente
Blockentitäten, Sonderprogramme, Hand und Entitätsschatten stehen aus.

## Stand 0.19

Opake Blockentitäten wie Truhen können den eigenen Blockentitätspass von
Complementary verwenden. Die Material-ID des Blocks bleibt getrennt von
der Material-ID eines Mobs. Die Hand und transparente Schichten stehen aus.

## Stand 0.18

Opake und ausgeschnittene Mob-Meshes können den eigenen Entitätspass von
Complementary verwenden. Die Material-ID bleibt erhalten, wenn Minecraft
mehrere Meshes gemeinsam zeichnet. In diesem Stand standen Hand, Blockentitäten, transparente
Entitätsschichten und Entitätsschatten noch aus.

## Stand 0.17

Shaderprogramme können vorzeichenbehaftete und vorzeichenlose ganzzahlige
Farbpuffer lesen. Bei Vollbild-Durchläufen wird ein unpassender Puffertyp
beim Vorbereiten des Packs mit Shadername und Puffer gemeldet.

## Stand 0.16

Terrainprogramme erhalten über beide üblichen GLSL-Namen dieselben
Lichtkoordinaten. Vollbildprogramme von `begin` bis `composite` können
ihre Ausgabe mit dem vorhandenen Pufferinhalt mischen. Ganzzahlige
Farbpuffer behalten die unveränderten Ausgabewerte des Shaders.

## Stand 0.15

Bei aktivierter Rückseitenausblendung verwendet der Shaderpfad die
sichtbare Seite der Wasseroberfläche. Die Korrektur behebt in der
Testszene die zu dunkle Beleuchtung des Nahwassers. Der Übergang von
texturiertem Nahwasser zu gröberen Fern-LODs bleibt sichtbar.

## Stand 0.13

Der Entwicklungsclient kann für die drei Vanilla-Dimensionen getrennte
Pack-Pläne vorbereiten. Beim Dimensionswechsel tauscht er Renderziele
und Kamerahistorie. Die Shaderaktivierung erfolgt weiter über den Entwicklungslauf.
Der Spieltest schaltete nach Nether und Ende wieder auf die Oberwelt um.

## Stand 0.12

Die Partikel- und Wetterprogramme zeichnen die vorhandenen Minecraft-Meshes.
Der Diagnoselauf prüft Regenpixel und opake sowie transparente Partikel.
Der vollständige Shader-Modus bleibt in Entwicklung. Details stehen in
docs/WORLD_WEATHER.md und docs/NEXT_AI_PROMPT.md.

## Stand 0.11

Complementary kann die Himmelsgeometrie mit seinen eigenen Programmen
zeichnen. Der Entwicklungsclient prüft Himmelsfarbe und Welttiefe auf
der GPU. Composite und Final laufen vor dem Löschen der Tiefe für die
Vanilla-Hand. In 0.11 fehlen Packprogramme für Entitäten, Partikel, Wetter und Hand. Die Shaderaktivierung erfolgt weiterhin über den Entwicklungslauf.

## Stand 0.10

Im Entwicklungsclient zeichnet Complementary nun eigenes Nahterrain,
Fernterrain und Fernwasser. Auf der RTX 4070 wurden beide DH-Tiefenbilder
bei 1280×720 und 480×270 geprüft. Die Vulkan-Ziele für Wasser können
unterschiedliche Mischregeln anwenden. Der Übergang zwischen Nah- und
Fernwasser ist im Bild weiterhin sichtbar.

Der Entwicklungsaufruf benötigt runSmokeClient, -PsmokeLivePack=true,
-PsmokeDistantPack=true und -PsmokeShaderPack=<Pack-ZIP>. Die normale
Installation aktiviert Shader noch nicht. Details stehen in
docs/DISTANT_PACK.md und docs/NEXT_AI_PROMPT.md.

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

Terrain, Wasser, Himmel, Wetter, Partikel, Hand, Entitäten und DH-Land und
-Wasser haben native Shaderpfade. Der Umfang der geprüften Szenen steht im
Prüfbericht. Andere Shaderpacks können weitere Shaderstufen, Texturformate
oder Spezialgeometrie benötigen. Weitere DH-Versionen und GPUs benötigen
eigene Spieltests.

Texturierte DH-LODs, DH-Antialiasing und DH-Drahtgitter sind noch nicht
implementiert. DH-Generic-Objekte (DHs Wolken, Beacon-Strahlen, API-Boxen)
werden als LOD-Geometrie gezeichnet; mit Shaderpack über `dh_generic` bzw.
`dh_terrain` wie unter Iris. Diese DH-Optionen werden nur für die
Sitzung abgeschaltet; gespeicherte Benutzerwerte werden nicht überschrieben.
Der Renderer verwendet die jeweils unterstützten Pack-Programme für Wolken.
Wasser-, Wolken- und LOD-Übergänge in weiteren Welten benötigen Sichtprüfungen.
Die bisherigen Spieltests liefen auf einer RTX 4070 unter Windows. Ein
reproduzierbarer FPS-Vergleich steht aus.

## Bauen und prüfen

Eine JAR für alle DH-Versionen. `verifyDhLinks` prüft ohne Spiel, ob alle DH-Verweise in jeder
unterstützten Version passen (Referenz-JARs unter `build/reference`).

```powershell
.\gradlew.bat --offline build verifyDhLinks
```

Für den regulären Build JDK 21 installieren und im Projektordner PowerShell öffnen.

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
