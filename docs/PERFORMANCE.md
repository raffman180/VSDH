# Leistung und Messung (Stand 0.26)

## Messwerkzeuge

Alle Werkzeuge sind standardmäßig aus und kosten dann nur eine Abfrage.

- `-Dvsdh.gpuProfile=true`: GPU-Zeitstempel pro Abschnitt. `GpuTimestamps`
  setzt pro Frame-Slot einen Query-Pool zurück und liest ihn erst nach dem
  Fence des Slots, ohne zu warten. Alle 5 s schreibt das Log eine
  `GPU_PROFILE`-Zeile mit Millisekunden pro Frame, einzeln pro Pack-Programm
  (`deferred1`, `composite4/mips`, …), sowie `idleBetweenFramesMs`.
- `-Dvsdh.syncTrace=true`: zählt, wer `Synchronization` eine Fence übergibt.
  Jede solche Fence lässt den nächsten Frame vor `vkQueueSubmit` auf dem
  Prozessor warten. Ausgabe als `SYNC_TRACE`.
- `-Dvsdh.legacyUploadSync=true`: Notschalter für den alten Upload-Weg mit
  einer Fence pro Upload. Nur zur Fehlersuche.
- Java Flight Recorder für den Render-Thread. Bei
  `jdk.NativeMethodSample` zeigt die Hauptlast, worauf der Thread wartet.

Das private FPS-Profil unter `build/reference/release-client-025-fps`
nimmt `-PtestGpuProfile`, `-PtestSyncTrace` und `-PtestJfr=<Datei>` an.

## Testszene

2560×1440, Complementary Reimagined r5.9.3 MEDIUM, DH 3.3.3 (LOD-Radius 128),
Sichtweite und Simulation 8, Fancy, VSync aus, FPS-Grenze 260, feste Kamera
(0.5, 100, 0.5) in einer isolierten Küstenwelt, Mittag, klar. 35 s Aufwärmen,
drei Proben à 10 s. RTX 4070, Ryzen 9 7950X3D, 64 GB, Windows 11, Fenstermodus.

## Befunde und Änderungen in 0.26

| Stand | Ø FPS | 1%-Low |
|---|---|---|
| 0.25 (A/B in derselben Sitzung) | 89,4 | ≈ 83 |
| Tiefenkopien nur noch mit Tiefenanteil | 107,8 | ≈ 98 |
| Gebündelte Textur-Uploads pro Frame | 112,5 | ≈ 101 |

1. **Tiefenkopien.** Die Welt-Tiefe ist D32_SFLOAT_S8_UINT. Die Kopien für
   `depthtex2`/`depthtex1` kopierten auch den Stencil-Anteil und kosteten je
   1,29 ms GPU. Packs lesen die Kopien nur über Tiefen-Ansichten. Jetzt
   wird nur noch der Tiefenanteil kopiert (0,055 ms). `ChainGpuCheck` prüft
   die exakten Tiefenwerte für D32, D24S8 und D32S8.
2. **Textur-Uploads.** DH lädt die Lightmap in jedem Frame hoch. Jeder Upload
   bekam einen eigenen Befehlspuffer mit Fence, und `submitFrame` wartete
   darauf. Das wartete faktisch auf das Ende des vorherigen Frames.
   Uploads und Layoutwechsel des Render-Threads landen nun in einem
   gemeinsamen Puffer. Er wird direkt vor dem Frame auf derselben Queue
   abgeschickt. Die Reihenfolge auf der GPU bleibt gleich, die
   Barrieren der Uploads bleiben wirksam. Jede andere Übergabe an die
   Grafik-Queue und `flushCmds` schicken offene Uploads vorher ab.
3. **Schatten-Culling.** `ShadowCuller` folgt den Pack-Vorgaben wie Iris.
   In der Testszene verwirft es nur 1–2 von ≈ 630 Sektionen. Der Nutzen
   zeigt sich erst bei mehr Gelände außerhalb des Bildes. Der Schattenpass
   zeichnet nun gebündelt (indirekt), wenn `indirectDraw` aktiv ist.
4. **Kleinigkeiten auf dem Prozessor.** Regex-Muster werden vorab übersetzt.
   Die Bereichsprüfung des Schatten-Cullings nutzt ein Array.

## Verbleibende Kosten (GPU ≈ 7,9 ms pro Frame)

DH opak ≈ 1,7 ms, Terrain opak ≈ 1,1 ms, Transparentes ≈ 1,1 ms,
`deferred1` ≈ 0,9 ms, DH-Wasser ≈ 0,85 ms, `composite1` ≈ 0,6 ms,
Schatten ≈ 0,45 ms, übrige Composite-Programme ≈ 0,7 ms, Mipmaps ≈ 0,45 ms,
Kopien ≈ 0,2 ms. Das meiste ist die Rechenarbeit des Packs selbst.

Zwischen zwei Frames bleibt die GPU ≈ 0,9 ms unbeschäftigt. Mit drei statt
zwei Frames in der Warteschlange ändert sich daran nichts (111,7 FPS). Der
Render-Thread wartet in `vkQueuePresentKHR`. Das spricht für die
Bildkomposition von Windows im Fenstermodus. Vollbild wurde nicht gemessen.

Ein Vergleich mit Iris/OpenGL in derselben Szene steht noch aus. Ohne diesen
Vergleich wird kein Vorsprung gegenüber OpenGL behauptet. Andere GPUs,
Packs, Sichtweiten und Szenen sind nicht gemessen.

## Vergleich mit Iris (11.11.2026)

Messung: `build/reference/run-vsdh-026-bench.ps1`, gleiche Szene, 1280×720,
ungebremste FPS, Mittel über Ticks 500–780, VSDH-Client gegen Iris 1.8.12.

**Ungültige frühere Werte.** Alle VSDH-Läufe zwischen 06:43 und 09:40 liefen
ohne DH-LODs: Ein inzwischen entfernter Abschalter über die DH-API hatte
`rendererMode = "DISABLED"` dauerhaft in die DH-Konfiguration des Testclients
geschrieben. Die damaligen FPS-Zahlen (z. B. Complementary 459,8 gegen 406,8)
sind deshalb nicht vergleichbar. Die Skripte setzen den Wert jetzt vor jedem
Lauf zurück, und die Zusammenfassung nennt die DH-Zeichenaufrufe.

**CPU-Seite.** Laut JFR kostete `WorldUniforms.write` rund ein Viertel des
Render-Threads. Ein Programm schreibt seine pro Frame festen Werte jetzt nur
einmal je Erfassung und Zeichenraum; spätere Draws erneuern nur die Werte aus
`PER_DRAW`. `skyColor` (Biom-Mischung über `CubicSampler`) wird nur berechnet,
wenn ein Pack ihn liest.

**Gültige Werte mit DH (11.11.2026, RTX 4070, Fenster).** Mittelwerte eines
Laufs je Client; Lauf-zu-Lauf-Streuung bis etwa ±5 %.

| Pack | 1280×720 VSDH | Iris | 2560×1440 VSDH | Iris |
|---|---|---|---|---|
| Complementary Reimagined r5.9.3 | 383,5 | 414,5 | 136,1 | 146,0 |
| Photon 1.4 | 361,4 | 406,9 | 136,6 | 147,6 |
| Bliss 2.1.2 | 341,0 | 425,4 | 108,4 | 135,1 |
| Eclipse Unstable | 301,4 | 340,2 | – | – |

Mit Shaderpack und DH ist VSDH in dieser Szene 7–20 % langsamer als Iris. Der
Abstand bleibt in Prozent bei beiden Auflösungen gleich, liegt also in der
GPU-Arbeit pro Pixel und nicht in festen Kosten pro Frame. GPU-Zeiten
(Bliss, 720p): 2,62 ms pro Frame, davon Kopien ≈ 0,09 ms und Mipmaps
≈ 0,06 ms; der Rest sind die Programme des Packs. Die SPIR-V-Optimierung
(`size` statt `performance`) ändert nichts (2,636 gegen 2,649 ms).
JFR-Nativproben: Der Render-Thread wartet zu 67 % in `vkQueuePresentKHR` und
zu 10 % auf Fences, er ist also nicht der Engpass.

**Ohne Shaderpack, mit DH** (`run-vsdh-026-bench-noshader.ps1`, gleicher
Szenenablauf wie die Iris-Referenzszene): VSDH-Standardrenderer (Vulkan)
gegen Iris 1.8.12 + Sodium mit ausgeschalteten Shadern.

| Auflösung | VSDH | Iris + Sodium |
|---|---|---|
| 1280×720 | 1041 / 1072 | 693 / 793 |
| 2560×1440 | 438 | 427 |

In 720p, wo die CPU-Seite zählt, ist der Vulkan-Renderer 35–50 % schneller;
in 1440p, wo die GPU begrenzt, liegen beide gleichauf (+3 %). Ein Vorsprung
mit Shaderpack wurde nicht gemessen (siehe oben). Andere GPUs, Treiber,
Szenen und Sichtweiten sind nicht gemessen.

## GPU-Vergleich Pass für Pass mit Iris (11.11.2026 nachmittags)

Der Iris-Referenzclient misst jetzt mit GL-Zeitstempeln je Stufe und je
Composite-Pass (`build/reference/run-iris-gpu-profile.ps1`, Testmod-Mixins
`GpuStageMixin`/`GpuCompositeMixin`), im selben Format wie VSDHs
`GPU_PROFILE`. Bliss, 720p, vor der Korrektur (ms): opak 0,36 / 0,37, deferred
0,20 / 0,15, Transluzent+DH-Wasser 0,58 / 0,50, **Composites 1,29 / 0,93**
(VSDH / Iris); `composite3` allein 0,401 / 0,182.

Ursache: VSDH zeichnete Vollbildpässe als ein übergroßes Dreieck, Iris als
Viereck (0..1). Packs verkleinern `gl_Position`, um nur einen Teil des Bildes
zu rechnen (Bliss: Nebel in halber Auflösung). Das verkleinerte Dreieck
bedeckte die doppelte Fläche und überschrieb Bildbereiche, die unter Iris
unberührt bleiben. Jetzt zeichnet `NativeFullscreenPass` ein Viereck aus zwei
Dreiecken. Bliss: `composite3` 0,404 → 0,216 ms, `composite4` 0,147 → 0,054 ms,
GPU pro Frame 2,66 → 2,40 ms, 338 → 379 FPS.

Zugleich korrigiert: Die erzeugte `noisetex` filtert linear wie unter Iris (war
nächster Nachbar), und Packs sehen den echten GPU-Hersteller und -Namen
(`MC_GL_VENDOR_*`, `MC_GL_RENDERER_*`) statt „other“.

## Nach dem Iris-Abgleich (11.11.2026 abends)

GL-Ausrichtung mit einem zusätzlichen gespiegelten Blit am Ende, `vignette`-Regel und
neue Iris-Merkmale kosten messbar nichts. 1280×720, DH 3.3.3, RTX 4070, Fenster, GUI
ausgeblendet (wie im Iris-Referenzclient):

| Pack | vorher (13:14) | jetzt (18:19) | Iris 1.8.12 |
|---|---|---|---|
| Complementary Reimagined | 377,5 | 381,7 | 414,5 |
| Photon 1.4 | 363,0 | 365,4 | 406,9 |
| BSL 10.1 | 450,4 | 452,0 | 443 |
| Bliss 2.1.2 | 379,8 | 385,0 | 425,4 |
| Eclipse Unstable | 310,9 | 312,6 | 340,2 |

Die Abstände zu Iris bleiben wie oben beschrieben (Fenster-Present bei hoher FPS).

## 2560×1440 gegen Iris (11.11.2026 abends)

Ziel: mehr FPS als Iris bei 2560×1440. Messung GPU pro Pass (`-PtestGpuProfile`,
`run-iris-gpu-profile.ps1 -HighResolution`), Photon:

- GPU-Arbeit pro Frame war fast gleich (VSDH 6,90 ms, Iris 6,75 ms), aber VSDH hatte
  **~1 ms GPU-Leerlauf zwischen den Frames**; im Fenster blockiert `vkQueuePresentKHR`
  (~6 ms) und der Treiber kopiert das Bild für den Desktop (Vulkan im Fenstermodus,
  NVIDIA „native“ Present). OpenGL (Iris) zahlt das nicht.
- Geprüft und verworfen (kein Gewinn, Code wieder entfernt): ein Frame verzögertes
  Present, eigene Present-Queue, eigener Present-Thread mit vorab geholten Bildern,
  späteres Warten auf das Swapchain-Bild, Vsync/FIFO (81 FPS). Die eigene Present-Queue
  bleibt als Schalter `-Dvsdh.separatePresentQueue=true` (Standard aus).
- Im **Vollbild** (`-PtestFullscreen`, beide Clients) entfällt die Kopie weitgehend.

Umgesetzt (gelten in Fenster und Vollbild):
- **Persistente Ziele tauschen statt kopieren** (`NativeColorTargets.copyBack`): Iris
  kopiert am Frameende die Alt- in die Haupthälfte. VSDH tauscht die Rollen der beiden
  Bilder, wenn nur ungeblendete Vollbildpässe das Ziel schreiben (sonst bleibt die
  Kopie: Geometrie mit Blending oder Bild-Stores könnten die ältere Alt-Hälfte sehen).
  Photon: `copyBack` 0,37 → 0,03 ms. Notschalter `-Dvsdh.copyBack=true`.
- **Tiefen-Schnappschüsse nur bei Bedarf**: `depthtex1`, `depthtex2` und `dhDepthTex1`
  werden nur kopiert, wenn ein Programm sie liest (`PackUsage.activeSamplers`).
  Tests/Proben erzwingen sie mit `-Dvsdh.allDepthSnapshots=true`.

Ergebnis Vollbild 2560×1440, DH 3.3.3, RTX 4070, beide Clients in derselben Szene:

| Pack | VSDH | Iris 1.8.12 | Vorsprung |
|---|---|---|---|
| Complementary Reimagined | 166,9 | 149,7 | +11,5 % |
| Photon 1.4 | 168,0 | 155,1 | +8,3 % |
| BSL 10.1 | 198,6 | 171,2 | +16,0 % |
| Bliss 2.1.2 | 151,5 | 139,7 | +8,4 % |
| Eclipse Unstable | 120,0 | 109,3 | +9,8 % |

(Endstand 20:48 mit Tausch-Sicherheitsregel; ein Lauf vor der Regel ergab +7,5 bis
+16,4 %. Lauf-zu-Lauf-Streuung etwa ±3 %.)

Im Fenster (2560×1440) liegt VSDH weiterhin 6–10 % hinter Iris (Complementary 141,4
gegen 150,6; Photon 143,6 gegen 157,9). Abhilfe auf Nutzerseite: Vollbild (F11) oder in
der NVIDIA-Systemsteuerung „Vulkan/OpenGL-Präsentationsmethode → Bevorzugt geschichtet
auf DXGI-Swapchain“ (nicht gemessen, da Systemeinstellung).

## Weitere GPU-Einsparung ohne Bildverlust (11.11.2026 nachts, in Arbeit)

- **Hardware-Sinus wie Iris**: Der Übersetzer ersetzte jedes `sin`/`cos` durch einen
  Software-Sinus (Cody-Waite-Reduktion + Polynom, ~15 Operationen) für Präzision bei
  großen Argumenten. Iris nutzt den Treiber-Sinus; auf derselben GPU rechnet VSDH damit
  jetzt wie Iris. Complementary 1440p Vollbild: GPU 6,44 → 5,83 ms pro Frame
  (`deferred1` 1,28 → 0,98, Transluzente 1,50 → 1,05). Standard jetzt Treiber-Sinus,
  alter Weg mit `-Dvsdh.reduceTrig=true` (`ShaderPackStartup`, `GlslTranslator.reduceTrig`).
  Bildvergleich mit allen 14 Packs steht noch aus (zu prüfen: Wellen, Rauschen).
- GPU-Zeit pro Frame danach (Vollbild 1440p, VSDH gegen Iris): Photon 5,45 / 6,63 ms,
  BSL 4,55 / 6,87, Bliss 5,72 / 7,57, Eclipse 10,02 / 10,49 (`run-pass-compare.ps1`).
- Verbleibend ~0,6 ms GPU-Pause zwischen Frames (auch im Vollbild, auch mit MAILBOX):
  Präsentationsarbeit des Treibers. Ein früher Teil-Submit (`Renderer.submitPartial`,
  `-Dvsdh.splitSubmit=true`) brachte nichts und wird standardmäßig nicht genutzt.

### Stand 22:40 (Hardware-Sinus, Tausch statt Rückkopie, Tiefenkopien nach Bedarf)

2560×1440, DH 3.3.3, RTX 4070, gleiche Szene in beiden Clients:

| Pack | Vollbild VSDH / Iris | Fenster VSDH / Iris |
|---|---|---|
| Complementary Reimagined | 166,1 / 151,1 (+9,9 %) | 144,3 / 143,7 (+0,4 %) |
| Photon 1.4 | 168,3 / 152,3 (+10,5 %) | 148,6 / 148,7 (±0) |
| BSL 10.1 | 202,1 / 172,9 (+16,9 %) | 172,6 / 173,8 (−0,7 %) |
| Bliss 2.1.2 | 156,3 / 141,8 (+10,3 %) | 136,4 / 134,4 (+1,5 %) |
| Eclipse Unstable | 122,0 / 108,5 (+12,5 %) | 109,6 / 103,7 (+5,7 %) |

Geprüft danach: Unit-Tests 93, Smoke 3/3, Release-Matrix 6/6, 14-Pack-Bildvergleich ohne
Fehlerzeilen und mit Iris-gleichen Mittelwerten.

## GPU-Pause zwischen Frames genau vermessen (12.11.2026 nachts)

`-Dvsdh.gpuProfile` schreibt jetzt zusätzlich `GPU_GAP` (Zeitstempel im Upload-Batch des
Frames) und in `CPU_FRAME` die Acquire-Zeit und die Zahl der Neben-Submits. Photon,
1280×720, Fenster:

- `vkAcquireNextImageKHR` wartet 0,001 ms, die GPU wartet nie auf das Swapchain-Bild
  (`imageWait` 0,000, TOP_OF_PIPE-Zeitstempel am Frameanfang).
- Die Pause liegt **vor** dem nächsten Upload-Batch, also beim Present (0,79 ms), der Batch
  selbst kostet 0,002 ms.
- 1 ms künstliche CPU-Last pro Frame (`-Dvsdh.debugCpuDelayUs=1000`) lässt die Pause
  unverändert und kostet nur 3 % FPS: die GPU wartet nicht auf die CPU. Mit 3 Frames im
  Flug verschiebt sich das Warten nur vom Fence in den Present-Aufruf.
- Ohne Wirkung (wieder entfernt): 5 Swapchain-Bilder, BGRA-Format, Swapchain ohne
  SAMPLED-Nutzung, eigene Present-Queue (schlechter: 320 statt 331 FPS).

Ergebnis: Die verbleibende Pause ist Präsentationsarbeit des Treibers (Fenster ~0,8 ms,
Vollbild ~0,6 ms) und von VSDH aus nicht zu beseitigen.

Umgesetzt, ohne Bildänderung:

- **Ein Submit statt zwei**: Der Upload-Batch des Frames geht als erster Command-Buffer in
  denselben `vkQueueSubmit` wie der Frame (`Renderer.submitFrame`,
  `GraphicsQueue.detachBatch`). Lücke zwischen beiden 0,12 → 0,001 ms; Photon 720p
  331 → 342 FPS.
- **Mipmaps gebündelt**: Fordert ein Pass mehrere Mip-Ketten an (Photon `deferred4`:
  colortex5 und colortex11), erzeugt `NativeImageOps.mipmaps(List, …)` sie Stufe für Stufe mit
  einer Barriere pro Stufe für alle Bilder; die Blits sind dieselben.
- **Echte 3D-Texturen** für Volumen, die ein Pack mitliefert (Photon, Mellow), statt des
  2D-Atlas mit Hilfsfunktion (`docs/NATIVE_PACK_FEATURES.md`, Notschalter
  `-Dvsdh.flatVolumes=true`). Photon 720p GPU 2,19 → 2,11 ms, 345 → 352 FPS.

- `shadowtex1` wird nur noch kopiert, wenn ein Programm es liest (`NativeWorldShadows.opaqueSnapshot`,
  wie die Welt-Tiefenkopien; alle 14 Test-Packs lesen es in mindestens einer Option).

Messhinweis: Die `GPU_PROFILE`-Fenster (VSDH 5 s, Iris 300 Frames) decken je Lauf andere
Abschnitte der bewegten Testszene ab. Pass-Zeiten echter Packs zwischen Läufen und Clients sind
daher nur Näherungen; belastbar sind FPS-Mittel und Mess-Packs mit festem Bildinhalt.
`<pass>/setup` misst jetzt die Layout-Übergänge vor jedem Vollbild-Pass (Photon: zusammen 0,001 ms);
die Stufenenden heißen `prepareTail`, `deferredTail`, `compositeTail`, `finalTail` (vorher
mit gleichnamigen Pack-Pässen zusammengezählt).

Untersucht, nicht geändert:

- **Gleicher Shader, anderer Treiberweg.** Ein Mess-Pack (nur lokal, nicht ausgeliefert) mit
  identischem GLSL in beiden Clients, 2560×1440, gleicher GPU-Takt (2835 MHz laut `nvidia-smi`):
  Schleife aus Multiplikationen 2,43 / 2,20 ms (VSDH / Iris), `exp` 4,98 / 4,36, `inversesqrt`
  5,13 / 4,35, `pow` 7,62 / 6,50, 64 Texturzugriffe 0,96 / 0,78, 64 Tiefen-`texelFetch` 0,98 /
  0,82. Der übersetzte Shader ist zeichengleich mit dem Original; der NVIDIA-Treiber erzeugt aus
  SPIR-V (Vulkan) 10–20 % langsameren Code als aus GLSL (OpenGL). Ohne Wirkung: Debug-Infos aus
  dem SPIR-V entfernen, Denormals-auf-Null (der Treiber bietet für FP32 keine Wahl). VSDH gleicht
  das bei echten Packs über weniger Arbeit außerhalb der Shader aus (Geometrie, Kopien).
- BGRA-Swapchain im Vollbild: Pause 0,59 / 0,63 ms, ohne Wirkung. 5 statt 3 Swapchain-Bilder im
  Vollbild (Photon): 170,7 / 169,0 FPS, mit MAILBOX 168,8; Pause unverändert ~0,6 ms.

### Eigene DXGI-Ausgabe (12.11.2026 morgens: D3D11-Experiment; mittags: D3D12, im Fenster Standard)

Ziel: die Fenster-Kosten der Vulkan-Ausgabe ohne Treibereinstellung vermeiden. Umsetzung
`net.vulkanmod.vulkan.present`: D3D11 auf derselben GPU (Adapter über die Vulkan-LUID) besitzt drei
gemeinsame Texturen (NT-Handles), die Vulkan importiert und statt der Swapchain-Bilder bemalt, und zwei
gemeinsame Fences (Vulkan-Timeline-Semaphoren). D3D11 wartet auf der GPU auf den Frame, kopiert ihn in
den Back-Buffer einer DXGI-Flip-Swapchain (FLIP_DISCARD, Tearing) und präsentiert. Funktionsnummern,
Strukturen und Konstanten gegen die Windows-SDK-10.0.28000-Header geprüft.

Ergebnis Photon 2560×1440 (RTX 4070), FPS / GPU-Pause zwischen Frames:

| | Vulkan-Swapchain | DXGI-Weg |
|---|---|---|
| Fenster | 150,2 / 1,18 ms | 139,9 / 1,64 ms (Warteschlange 8: 148,3 / 1,46 ms) |
| Vollbild | 168,8 / 0,60 ms | 141,5 / 1,97 ms |

Die Übergabe zwischen Vulkan und D3D11 (Fence-Warten über das Betriebssystem, Kontextwechsel,
Kopie) kostet mehr, als der Flip-Weg spart. Der D3D11-Weg bleibt nur zum Vergleich:
`-Dvsdh.dxgiPresent=d3d11` (die Vulkan-Erweiterungen `VK_KHR_external_memory_win32`/`_semaphore_win32`
und Timeline-Semaphoren werden nur mit aktivem DXGI-Weg eingeschaltet; bei jedem Fehler automatischer
Rückfall auf die Vulkan-Swapchain, im Test bestätigt). Weitere Schalter: `-Dvsdh.dxgiLatency`
(nur D3D11, Standard 8), `-Dvsdh.dxgiBuffers` (Standard 3; 2 ergab mit D3D11 114 FPS).

**D3D12 (mittags, `D3d12Presenter`):** gleicher Vertrag, aber Texturen (`CreateCommittedResource`
mit `HEAP_FLAG_SHARED`, Import als `VK_EXTERNAL_MEMORY_HANDLE_TYPE_D3D12_RESOURCE_BIT`) und Fences sind
native D3D12-Objekte; die neun Kopien (3 Texturen × 3 Back-Buffer) sind einmal aufgezeichnet und
werden pro Frame nur ausgeführt, auf der Direct-Queue der Swapchain. Vorsicht beim Nachzählen von
Funktionsnummern: `d3d12.h` deklariert struct-zurückgebende Methoden in den C-Tabellen doppelt
(`#if`/`#else`); falsch gezählte Nummern ließen `CreateFence` abstürzen.

| Photon 2560×1440 | Vulkan-Swapchain | D3D12 |
|---|---|---|
| Fenster (3 Paare) | 148,5 / 152,8 / 151,6 FPS, Pause 1,12–1,18 ms | 161,4 / 162,6 / 158,5 FPS, Pause 0,70–0,72 ms |
| Vollbild (2 Paare) | 171,2 / 166,5 FPS, Pause 0,64 ms | 144,9 / 150,2 FPS, Pause 0,48–0,59 ms |

Im Fenster gewinnt D3D12 rund 7 %, im Vollbild verliert es 12–15 % (die CPU wartet länger im
Present, obwohl die GPU-Pause kleiner ist; vermutlich bekommt die Vulkan-Swapchain dort den direkten
Weg zum Monitor). Deshalb: **D3D12 im Fenster, Vulkan-Swapchain im Vollbild**; `SwapChain` wechselt
bei jeder Neuerstellung nach `glfwGetWindowMonitor` (F11 hin und zurück sowie Größenänderungen im
Spiel getestet, `-Dvsdh.releaseQA.windowSteps=150:toggle;250:toggle;350:1600x900`; ein echtes
Desktop-Bildschirmfoto des Fensters stimmt mit dem Spiel-Screenshot überein). Standard
`-Dvsdh.dxgiPresent=auto` = D3D12 nur auf NVIDIA (nur dort gemessen); `d3d12`/`true` erzwingt es,
`d3d11` nimmt den langsameren D3D11-Weg, `false` schaltet ab.

Fünf Packs im Fenster mit D3D12 (12.11.2026 12:07, 2560×1440, DH 3.3.3, gleiche Szene, Iris im
selben Durchlauf): Complementary 160,3 / 146,4 (+9,5 %), Photon 165,9 / 148,2 (+12,0 %), BSL
193,3 / 170,1 (+13,6 %), Bliss 152,0 / 136,6 (+11,3 %), Eclipse 109,6 / 104,4 (+4,9 %). Vorher im
Fenster −0,7 bis +6,2 % (Tabelle „Stand 01:47“). Alle fünf VSDH-Läufe mit aktiver D3D12-Ausgabe und
ohne Fehlerzeilen.

Verworfen: eigene Kopier-Queue (die Kopie in den Back-Buffer von der Copy-Queue entfernte nach
~3 s das Gerät, `DXGI_ERROR_DEVICE_REMOVED`, Grund `0x887A002B` Access denied; VSDH fiel wie
vorgesehen auf Vulkan zurück) und ein einmaliges „Vorwärmen“ des Fensters mit einer kurzlebigen
DXGI-Swapchain vor der Vulkan-Swapchain (152,0 / 152,1 FPS, Pause unverändert 1,12 ms). Der schnellere
Vulkan-Lauf nach dem Kopier-Queue-Rückfall (158,5 FPS, Pause 0,80 ms) ließ sich so nicht nachstellen.

### Stand 01:47 (alle Änderungen dieser Nacht)

2560×1440, DH 3.3.3, RTX 4070, gleiche Szene in beiden Clients:

| Pack | Vollbild VSDH / Iris | Fenster VSDH / Iris |
|---|---|---|
| Complementary Reimagined | 161,9 / 147,0 (+10,1 %) | 142,1 / 143,1 (−0,7 %) |
| Photon 1.4 | 169,0 / 149,8 (+12,8 %) | 150,3 / 145,4 (+3,4 %) |
| BSL 10.1 | 201,6 / 170,7 (+18,1 %) | 169,8 / 169,1 (+0,4 %) |
| Bliss 2.1.2 | 154,4 / 137,5 (+12,3 %) | 135,6 / 131,4 (+3,2 %) |
| Eclipse Unstable | 118,0 / 108,1 (+9,2 %) | 107,8 / 101,5 (+6,2 %) |

Iris lag in diesem Lauf 1–3 % unter den Werten von 22:40 (Lauf-zu-Lauf-Streuung etwa ±3 %).
Complementary im Fenster bleibt im Rahmen der Streuung gleichauf; ein A/B-Lauf derselben Szene
ergab 146,7 FPS. Geprüft: Unit-Tests 95, Smoke 3/3 (52/51/54 PASS; im Dimensionslauf eine
DH-eigene SQLite-Fehlermeldung „database connection closed“ beim Speichern während des
Weltwechsels, kein Renderfehler), Release-Matrix 6/6, 14-Pack-Bildvergleich ohne Fehlerzeilen,
Helligkeitsmittel je Pack höchstens 2,2 Stufen (von 255) von Iris entfernt.

## Flug-Messung: Glätte statt Durchschnitt (13.11.2026 nachts)

Neues Werkzeug `build/reference/run-vsdh-026-flight.ps1` (Testmods `FlightBench` in beiden Clients,
identischer Code): gleiche Welt (Seed 825765032), Zuschauermodus, ab Tick 300 30 s Flug nach Osten mit
1 Block/Tick durch neues Gelände (Chunk-Laden, Bauen, DH-Erzeugung), Sichtweite 12, VSync aus, jede
Frame-Zeit wird erfasst (`run/flight-frames.csv`, Zeitstempel je Frame für JFR-Abgleich).
`flight-analyse.py` zählt Frames über einem bzw. zwei Monitortakten (Testmonitor 165 Hz = 6,06 ms).
Optionen: `-Packs none` (ohne Shader), `-Jar` (A/B), `-Extra`/`-IrisExtra` (`-PtestJvmArgs=…`,
`-PtestGpuProfile`, `-PtestJfr=…` mit `-PtestJfrDelay=68s -PtestJfrDuration=34s`).

Ergebnis 2560×1440, Complementary, DH 3.3.3, G1, RTX 4070 (mehrere Läufe, Median):

| | Läufe | Ø FPS | Frames > 1 Takt pro s | > 2 Takte pro s |
|---|---|---|---|---|
| Iris 1.8.12 | 4 | ≈ 188 (184–212) | ≈ 33 (23–36) | 2,1–3,0 |
| VSDH (Stand 13.11.) | 6 | ≈ 208 (168–227) | ≈ 18 (13–63) | 2,3–4,1 |

Ohne Shader (je 1 Lauf): VSDH 714 FPS / 2,9 pro s, Iris+Sodium 573 FPS / 3,5 pro s. Photon (je 1 Lauf
mit älterem Stand): 179 / 178 FPS. Einzelläufe schwanken stark (VSDH 168–227 FPS bei gleicher JAR, ohne
Unterschied im Log); nur Mediane mehrerer Läufe vergleichen.

Befunde:
- Im Flug ist VSDH GPU-begrenzt: GPU 4,2–4,5 ms pro Frame, Prozessorarbeit 1,2–1,5 ms, der Rest ist
  Warten in Present. Iris braucht im selben Flug 4,8–5,1 ms GPU (`composite1` 0,44 gegen 0,59 ms bei
  VSDH – bekannte Vulkan-Codeerzeugung –, DH-Wasser/Transluzent etwa gleich). Wechselnde DH-Ladestände
  verändern `dhWater` (0,61–0,81 ms) und `deferred1` (0,75–0,96 ms) und damit die Läufe.
- Ruckler stammen vor allem aus G1-Pausen (≈ 3 pro s, 5–12 ms; JFR) und aus dem Present-Takt. Speicher
  legen fast nur Minecraft-Server (28 %, `EventHooks.canCreateFluidSource`) und DH (über 40 %, u. a.
  Exceptions in `PriorityTaskPicker$Executor.execute`) an; VSDHs Render-Thread lag bei 5,7 %
  (7,4 von 130 GB in 34 s) und wurde um den Großteil verringert: keine Identitätsmenge je Vollbildpass
  (`NativeGraphicsProgram.storedImage`), Typinfo je GLSL-Typ statt Regex je Uniform (`Std140Layout`),
  gemerkte Pass-Schritte (`TargetSchedule.step`) und Ansichten (`NativeShaderChain.view`).
- Geräte-lokale Puffer-Uploads (DH-LODs, VBOs) liefen je Upload als eigene Transfer-Queue-Übermittlung
  mit Fence, auf die der nächste Frame-Submit wartete (ab 50 pro Frame sogar mitten im Frame). Jetzt
  laufen sie im Upload-Batch des Frames (`GraphicsQueue.recordBufferUpload`, eine Barriere am Batch-Ende).
  A/B im Flug: kein messbarer Unterschied in guten Läufen (Schwankung größer als der Effekt); `fence`
  in den Spike-Zeilen bleibt 0.
- ZGC (`-XX:+UseZGC -XX:+ZGenerational`, je 1 Lauf): VSDH schlechter (171 FPS, 57 pro s), Iris leicht
  besser bei langen Frames. Keine ZGC-Empfehlung.
- Diagnose: `-Dvsdh.spikeMs=9 -Dvsdh.spikeLogMs=0` (Spike-Zeilen ab 9 ms, neu `bufUp`),
  `-Dvsdh.gpuSpikes=true` mit `-PtestGpuProfile` (teuerste Pässe einzelner langer GPU-Frames; im Flug
  gab es keine: GPU-Zeit pro Frame ist konstant).
- VSync an (`-Vsync`, 1280×720, je 1 Lauf): VSDH 180 FPS, 1-%-Low 58; Iris 177 FPS, 1-%-Low 56. Auf dem
  Testsystem begrenzt VSync in beiden Clients nicht auf die gemeldeten 165 Hz (Treiber-/Monitorsache,
  nicht geändert); die D3D12-Ausgabe übergibt Sync-Intervall 1.
- Korrektur (13.11. vormittags): Die `RejectedExecutionException`-Flut aus `ChunkUpdateQueueManager`
  (rund 8 Mio.) entsteht erst nach dem Verlassen der Welt, nicht im Flug. Ursache ist eine Leerlaufschleife
  in DH 3.3.x, siehe nächster Abschnitt.
- Wasser-Nahbereich Complementary gegen Iris (frische Bilder 13.11.): gleiche Helligkeit, Kontrast
  des Wassermusters VSDH 8,2–10,9 je Lauf, Iris 7,8 (mit Mipmaps) bzw. 9,0 (ohne); Schwankung der
  Wasseranimation, kein Fehler. AMD-iGPU des Testrechners kann das Fenster nicht darstellen (Monitor an
  der NVIDIA), daher weiter kein AMD-Test.
- Endstand (13.11. 03:00, `dist/0.26.0-dev-night`), je 2 Flüge 1440p: Photon VSDH 189/193 FPS, 34/28 Frames
  > 1 Takt pro s; Iris 174/174 FPS, 50/47 pro s. BSL VSDH 201/193 FPS, 22/29 pro s; Iris 210/178 FPS,
  26/42 pro s.

### Speicher-Churn und Java-Optionen (13.11. vormittags)

JFR-Allokationsanalyse im 26-s-Flugfenster (`jfr print --json --stack-depth 40`; Standardtiefe 5 reicht nicht):
- Weltgenerierung: `MaterialRuleList.calculate` und `SurfaceRules.SequenceRule.tryApply` legen je Block einen
  Iterator an (rund 17 % aller Bytes). Jetzt Indexschleife bei `RandomAccess`-Listen (gleiche Reihenfolge,
  gleiches Ergebnis): `MaterialRuleListMixin`, `SurfaceSequenceRuleMixin` (Zugriff per Access Transformer);
  `-Dvsdh.worldgenLoops=false` schaltet sie für A/B-Tests ab.
- DH 3.3.x `ChunkUpdateQueueManager.runQueueingLoop` schläft nur bei zwei leeren Warteschlangen; während der
  250-ms-Entprellung (und nach dem Verlassen der Welt bei abgeschalteten Thread-Pools) läuft er leer im Kreis:
  rund 250 MB/s im Flug, über 1 GB/s beim Verlassen. In 3.3.4 unverändert; 3.2.0-b hat die Methode nicht.
  `MixinChunkUpdateQueueBackoff` wartet 2 ms nach einem Durchlauf ohne Eingang und ohne Abgabe
  (`DhMixinPlugin` prüft die Methode im DH-JAR; `-Dvsdh.dhQueueBackoff=false` schaltet ab). Nachher: etwa
  1 MB/s nach dem Verlassen.
- VSDH-Render-Thread: Schatten-Zeichenlisten ohne Map-Einträge je Durchlauf (`WorldRenderer.shadowDrawList`);
  `WorldShaderSession.external` merkt sich, ob ein Programm `watershadow` liest, statt eines Streams je Frame.

A/B im Flug (1440p, Complementary, DH 3.3.3, je 7 abwechselnde Läufe, Median):

| | Ø FPS | Frames > 1 Takt/s | > 2 Takte/s | > 3 Takte/s | GC-Läufe im JFR-Fenster |
|---|---|---|---|---|---|
| ohne die Korrekturen | 204 | 18,7 | 2,60 | 0,40 | 135 |
| mit den Korrekturen | 202 | 21,3 | 2,40 | 0,57 | 107 |

Ergebnis: weniger Müllsammlung, aber kein messbarer Unterschied bei FPS und Rucklern (Schwankung größer als
der Effekt). G1-Pausen bleiben gleich lang (Median 8 ms); ihre Länge hängt am lebenden Speicher, nicht an der
Allokationsrate. Spürbare Ruckler im Flug (über 25 ms): 1–3 je 30 s, Ursachen G1-Pause (≈ 25 ms),
Present-Wartezeit im Fenster (26–53 ms, Treiber/Windows) und Einzelfälle.

Java-Optionen (nur Messung, nichts wird beim Spieler gesetzt):
- `-XX:MaxGCPauseMillis=15` (1 Lauf): Pausen eher länger (Median 9,0 ms), keine Verbesserung.
- `-Xms4G` bei `-Xmx4G` (3 Läufe): halb so viele GC-Läufe, weniger Frames > 2 Takte (1,3–1,8/s), aber etwa
  doppelt so viele > 3 Takte (0,93–1,00/s, Pausen 15–24 ms). Kein Gewinn für ruckelfreies Spielen.
- ZGC: siehe oben, schlechter. Empfehlung bleibt: Standard-G1, nur `-Xmx` passend setzen.

GPU je Pass im selben Flug (VSDH `-PtestGpuProfile`, Iris `IRIS_GPU_PROFILE`): VSDH 4,51 ms, Iris 4,86–4,92 ms.
Größere Abweichungen: `composite1` (Complementary-Lichtstrahlen/Nebel) 0,65 gegen 0,44 ms und `deferred1`
samt Mip-Erzeugung 1,00 gegen 0,85 ms (der Mip-Abschnitt enthält den Abschluss der vorigen Pässe und die
Speicherbarriere für die Floodfill-Bilder). Shaderc läuft bereits mit `performance`; frühere Messung mit
anderer Stufe ohne Unterschied. Offen.

### Vollbild gegen Fenster, VSDH gegen Iris (13.11. 10:34–10:49)

Gleiche Sitzung, abwechselnd, 1440p, Complementary, DH 3.3.3. VSDH im Vollbild über die Vulkan-Swapchain
(Wechsel vor Flugbeginn im Log bestätigt), im Fenster über DXGI/D3D12.

| | Läufe | Ø FPS | Frames > 1 Takt/s | > 3 Takte/s | Frames > 25 ms je Flug | p99,9 |
|---|---|---|---|---|---|---|
| VSDH Vollbild | 3 | 209 / 209 / 216 | 14,6 / 11,8 / 10,1 | 0,67–0,77 | 3 / 6 / 2 | 21,8–25,1 ms |
| Iris Vollbild | 3 | 208 / 188 / 191 | 18,8 / 30,5 / 25,1 | 0,37–0,67 | 1 / 0 / 2 | 20,0–22,0 ms |
| VSDH Fenster | 2 | 210 / 206 | 14,9 / 18,0 | 0,50–0,57 | 4 / 7 | 22,5–27,0 ms |
| Iris Fenster | 2 | 189 / 193 | 27,7 / 27,6 | 0,50–0,70 | 1 / 2 | 20,8 ms |

Ergebnis: Vollbild ändert bei beiden wenig. VSDH hat mehr FPS und nur halb so viele kleine Hänger,
aber mehr lange (> 25 ms) als Iris. Ursachen der VSDH-Spitzen im Flug: Present/Acquire 25–30 ms auch im
Vollbild, gpuWait 27 ms, eine synchrone Chunk-Erzeugung 21 ms auf dem Render-Thread, G1-Pause, sonst
nicht zugeordnet. Nächster Schritt: diese langen Spitzen mit JFR je Zeitstempel zuordnen.

### Lange Hänger im Vollbild zugeordnet (13.11. 14:07–14:25)

JFR im Vollbildflug, jeder Frame ≥ 18 ms gegen JFR-Ereignisse (`flight-frames.csv` hat Epoch-Zeitstempel):
- G1-Young-Pausen ≈ 15 ms → Frames 18–20 ms.
- G1-Remark etwa jede Sekunde (17 Zyklen in 75 s), davon „Class Unloading“ 10–32 ms, obwohl im ganzen Flug nur
  1 von 36 000 Klassen entladen wird → Frames bis 39 ms.
- Present/Acquire 20–25 ms (auch Vollbild, Swapchain IMMEDIATE), ohne längere GPU-Arbeit: Treiber.
- Ein 55-ms-Frame war `RedefineClasses` beim JFR-Start (Messartefakt, nur mit `-PtestJfr`).

`-XX:-ClassUnloadingWithConcurrentMark` (Klassen werden dann nur bei einer vollen GC entladen): Remark max
32,7 → 1,3 ms, Mittel 9,2 → 0,9 ms (JFR). 3 Vollbildflüge: Frames > 25 ms 2 / 1 / 4 (vorher 3 / 6 / 2 / 3),
> 33 ms 0 / 0 / 1 (vorher 2 / 0 / 1 / 2), 216–242 FPS (vorher 209–216; Schwankung). Empfehlung als
Java-Argument für Spieler (nie aus Code setzen). Rest: Present-Wartezeiten und einzelne nicht zugeordnete
Render-Thread-Spitzen (z. B. 42,8 ms ohne GC/Present).

### Treiber-Present-Hänger und FPS-Grenze (13.11. nachmittags)

1-ms-JFR (`build/reference/bench-026/spikes.jfc`, `-PtestJfrSettings=`) erfasst den Render-Thread in langen Frames
kaum (Windows-Sampler); die Zuordnung kommt aus den Spike-Zeilen (`-Dvsdh.spikeMs=18 -Dvsdh.spikeLogMs=0`). Mit
`-XX:-ClassUnloadingWithConcurrentMark`, 3 Vollbildflüge, Frames ≥ 20 ms: 10 × Present (vkQueuePresentKHR
20–25 ms, Swapchain IMMEDIATE, 2 Frames in Flight = Minimum), 2 × G1, 1 × gpuWait, 1 sonstig.

FPS-Grenze 160 (Testmonitor 165 Hz; `-PtestFlightFps=160` in beiden Testclients), Vollbild, beide mit derselben
Java-Option:

| | FPS | Frames > 20 ms | > 25 ms | > 33 ms | max |
|---|---|---|---|---|---|
| VSDH, Grenze 160 (2 Läufe) | 156 / 155 | 11 / 7 | 0 / 0 | 0 / 0 | 22,3 / 23,6 ms |
| Iris, Grenze 160 (2 Läufe) | 155 / 155 | 4 / 10 | 2 / 3 | 2 / 0 | 38,1 / 32,4 ms |
| VSDH ohne Grenze | 207 | 9 | 3 | 0 | 31,4 ms |
| Iris ohne Grenze | 192 | 9 | 2 | 1 | 37,0 ms |

Mit Grenze verschwinden die Present-Hänger (keine Spike-Zeile ≥ 20 ms mehr). Empfehlung für Spieler mit
ruckelfreiem Ziel: FPS-Grenze knapp unter der Bildwiederholrate + `-XX:-ClassUnloadingWithConcurrentMark`.
Keine automatische Grenze im Code (sichtbare Abwägung, entscheidet der Nutzer).

GPU-Abstand `composite1`/`deferred1` zu Iris: ausgeschlossen sind Zielformate (wie Iris), `IS_IRIS`-Pfad (gesetzt),
Speicher-Nutzungsflags (nur wo nötig), Lichtstrahl-Probenzahl (fest). Ohne GPU-Profiler (Nsight/RenderDoc)
keine weitere Spur.
