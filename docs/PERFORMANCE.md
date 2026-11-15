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

### Ruckeln beim Umschauen im Monkeytalk-Pack (14.11. abends)

Kopie des 128-Mod-Packs, Eclipse Unstable HIGH, 2560x1440 (DXGI, randlos), Flug 0,3 Blöcke/Tick mit 6°/Tick Drehung
(`-Dvsdh.flight.turn`, neu im Testclient). Zwei Läufe: 93 / 96 FPS, Median 10,1 / 9,9 ms, 15 % der Frames > 16,7 ms,
76 > 25 ms. Spike-Zeilen ≥ 18 ms: 201 von 276 Present-Wartezeit, 72 sonstige, je 1–2 GC/gpuWait. JFR: in langen Frames
25 von 60 Render-Thread-Samples in `D3d12Presenter.present` (Win32Com.invoke). GPU je Frame konstant ≈ 9,6 ms
(größte Pässe: dhWater 1,95, translucent 1,5–1,8, composite3 1,64, composite1 0,82, composite 0,65 ms); GPU_SPIKE
außerhalb des Ladens klein. Deutung: GPU-begrenzt bei etwa 100 FPS, CPU nahe daran; Schwankungen landen als Present-
Wartezeit in einzelnen Frames. Empfehlung für Spieler: FPS-Grenze unter dem Mittelwert (z. B. 80) oder Eclipse eine
Stufe niedriger. Offene VSDH-Kandidaten: dhWater und translucent gegen Iris im selben Pack vergleichen.

### Eclipse: VSDH gegen Iris je Pass (14.11. abends, Testclients, 1440p, DH 3.3.3, Standardprofil, Flug)

VSDH 119,3 FPS, Median 8,23 ms, p99 19,6 ms; Iris 114,9 FPS, Median 8,47 ms, p99 18,0 ms. GPU je Frame: VSDH 7,65–7,99 ms,
Iris 8,00–8,14 ms. Wasser/Transparentes: VSDH dhWater 1,73–1,79 + translucent 0,48–0,57 = 2,2–2,4 ms, Iris translucent
(enthält DH-Wasser) 1,70–1,76 ms, also VSDH ≈ 0,5 ms langsamer. composite3 1,58–1,62 gegen 1,48–1,51 ms (+0,1),
composite1 0,67–0,69 gegen 0,71 ms. Iris finalize 0,45 ms gegen VSDH final+copyBack 0,14 ms. Offen: Ursache der
DH-Wasser-Differenz (Tiefentest gegen Minecraft-Tiefe/Überzeichnung prüfen; Bildvergleich nötig).
Ursache der DH-Wasser-Differenz (Fable, 14.11. abends; je ein aufgeteilter Lauf pro Client): (1) Feedback-Kopien –
Eclipse liest Ziele, die es im selben Pass schreibt (`dh_water`: colortex11; `gbuffers_water`: colortex11, colortex7);
VSDH kopiert vorher einen Schnappschuss (`NativeColorTargets.feedbackImage`), etwa 3 × 0,1 ms = 0,23–0,25 ms, Iris bindet
die Textur direkt. (2) `dhDepthTex` in DH-Programmen ist bei VSDH der opake Schnappschuss, bei Iris die laufende DH-Tiefe
mit bereits gezeichnetem Wasser; der 30-Schritt-SSR-Marsch läuft dadurch länger (Obergrenze ≈ 0,14 ms). Reihenfolge,
Tiefentest, Culling, Blending, Draw-Liste und `far` wie bei Iris/DH. Behebung bräuchte `VK_EXT_attachment_feedback_loop_layout`
(150–250 Zeilen, Mehr-Pack-Bildvergleich); nicht umgesetzt. Messschalter `-Dvsdh.gpuProfileFeedback=true`.

### Feedback-Schleifen statt Schnappschuss-Kopien (14.11. 19:54–20:02, Fable)

Umgesetzt: Pack-Programme, die ein Farbziel lesen, in das sie im selben Pass zeichnen, binden den Anhang selbst
(`VK_EXT_attachment_feedback_loop_layout`: Bildnutzung, Feedback-Layout in Render-Pass und Deskriptor, Pipeline-Flag).
Entscheidung je Programm und Bild aus dem Plan (`FeedbackLoops`, `NativeShaderChain.loops`); DH-Programme, die
`dhDepthTex`/`dhDepthTex0` lesen, bekommen die laufende DH-Tiefe als Tiefen-Feedback (wie Iris), `dhDepthTex1` bleibt
die getrennte opake Kopie. Ohne die Erweiterung oder mit `-Dvsdh.feedbackLoops=false` bleibt die Kopie
(`NativeColorTargets.feedbackImage`). Testrechner (NVIDIA): Erweiterung vorhanden und aktiv
(Logzeile `Attachment feedback loops: VK_EXT_attachment_feedback_loop_layout`).
Eclipse laut Log: `dh_water=[11], gbuffers_hand_water=[11], gbuffers_water=[7, 11]`, DH-Tiefe live in `dh_water`.
Nachtrag 14.11. nachts: Die Live-DH-Tiefe ist wieder aus (Standard: opake Kopie, `-Dvsdh.dhDepthFeedbackLoop=true`
schaltet sie ein). Der Nutzer meldete Flackern in der Ferne; `dh_water` liest `dhDepthTex` an fremden Pixeln, während
derselbe Pass sie schreibt (Datenrennen). Die Zahlen unten enthalten die Live-Tiefe (Anteil höchstens ≈ 0,14 ms).

Messung Eclipse Unstable, Testclient, 2560x1440 Fenster, DH 3.3.3, feste Szene (`run-vsdh-026-profile.ps1 -NoJfr
-Extra -Ptest1440p`, `-Dvsdh.gpuProfileFeedback=true`), je 2 Läufe abwechselnd, Mittel der Profilfenster 7–9 (je 5 s,
gleicher Szenenabschnitt), GPU-ms je Frame:

| | Kopien 1 | Kopien 2 | Schleifen 1 | Schleifen 2 |
|---|---|---|---|---|
| gesamt | 11,02 | 11,52 | 8,63 | 9,15 |
| Wasser (beforeFeedback + feedbackCopy + dhWater + translucent) | 3,38 | 3,40 | 2,76 | 2,55 |
| davon feedbackCopy | 0,22 | 0,22 | – | – |
| composite | 1,66 | 2,12 | 1,32 | 1,39 |
| composite1 | 1,20 | 1,16 | 0,92 | 1,05 |
| composite3 | 1,17 | 1,65 | 0,95 | 1,24 |
| composite4 | 1,22 | 0,83 | 0,38 | 0,51 |
| BENCH_FPS | 104,0 | 98,1 | 117,3 | 121,8 |

Wasser/Transparentes: −0,6 bis −0,85 ms. Die Composite-Pässe sind ebenfalls schneller, obwohl sie nicht angefasst
wurden; Ursache nicht geklärt (im Bild kein Unterschied erkennbar), deshalb den Gesamtgewinn von etwa 2,3 ms nicht als
gesichert lesen, bis er wiederholt ist. Kein Iris-Lauf in derselben Sitzung.

Bilder (1280x720, Eclipse, je ein Lauf, aufgenommen während der Nutzer ein anderes Spiel offen hatte – nur als
Sichtprüfung brauchbar): Schleifen gegen Iris mittlere Abweichung 6,5, Kopien gegen Iris 6,7, Schleifen gegen Kopien
7,0, Iris gegen Iris vom 10.11. 7,6 (Wellenphase, DH-Fortschritt). Kein Artefakt an Wasser, Nah/Fern-Übergang oder
Spiegelung zu sehen. Complementary, Photon, BSL: noch NICHT verglichen (Läufe abgebrochen, weil der Nutzer spielte).

### Schattenpass: Liste der zeichenbaren Sektionen (14.11.)

`WorldRenderer.prepareShadowDraws` lief je Frame über alle Sektionen des Gitters (im 128-Mod-Pack über 10 000;
5,9 % der Render-Thread-Samples in `mt-turn1.jfr`, fast alles im Box-Test je Sektion). Jetzt `ShadowSectionList`:
nicht leere Sektionen mit Bereich, nach Bereich gruppiert, Koordinaten daneben; neu aufgebaut nur, wenn eine
Sektion leer/nicht leer wird oder umzieht (`ShadowSectionList.changed()`). Ein vom Licht ganz erfasster Bereich
(`ShadowCuller.contains`) übernimmt seine Sektionen ohne Einzeltest. Gleiche Sektionen je Bereich in gleicher
Reihenfolge. `-Dvsdh.shadowSectionList=false` = alter Durchlauf, `=verify` = beide mit Vergleich.
Prüfung: `verify` in einem Eclipse-Lauf, 2000 Frames ohne Abweichung; kleine Testszene (1944 Sektionen, 93 zeichenbar):
53 µs → 11 µs je Frame (Zeit während eines fremden Spiels gemessen, nur Größenordnung). Im großen Pack nicht gemessen.

Weitere Render-Thread-Allokationen aus demselben JFR entfernt: `PackTexturePlan.key` (String je Sampler und Draw,
16 MB/30 s; `NativeWorldTextures.resolve` merkt sich die Antwort) und `Integer`-Boxing im DH-Puffercache
(27 MB/30 s; `Int2ObjectOpenHashMap`). Kein FPS-Anspruch.

Frame-Begrenzung: kein eigener Schalter. Der vorhandene Regler „Max. Bildrate“ der Video-Seite bekommt den Hinweis
im Tooltip (`vsdh.options.framerateLimit.tooltip`, deutsch und englisch). Beobachtung im JFR des Nutzer-Packs:
15 DH-Weltgenerator-Threads liefen im Flug durchgehend; DHs CPU-Last-Einstellung ist Sache des Spielers.

### Tick-Frames im 128-Mod-Pack: Biom-Abstand, Sprite-Upload, Uniform-Wege (14.11. nachts, Fable; NUR Code + vorhandene JFR)

Kein Spieltest (vom Nutzer untersagt). Grundlage: `mt-turn1.jfr`/`.csv` (Eclipse HIGH, 1440p, Drehflug) und
`mt-profile1.jfr`. Vorsicht bei Anteilen: der Render-Thread hat in 30 s nur 188 (turn1) bzw. 313 (profile1) Samples,
davon 95 bzw. 215 Java-Samples (15 DH-Weltgenerator-Threads belegen den Sampler). Anteile sind grobe Werte.

Befund zur Glätte: Frames > 16,7 ms (436 von 2777) folgen einander meist im Abstand 40–59 ms (203 von 435 Abständen),
also im Takt des Client-Ticks; danach folgt oft ein 2–4-ms-Frame. Der Tick liegt bei 34 von 188 Samples (18 %):
`ClientLevel.animateTick` 15 (davon 12 in `BiomeManager.getBiome` aus `doAnimateTick:421`), Raffman's Detailed Map 7–8
(davon 5 über `calculateBlockTint` → `getBiome`), `TextureManager.tick` 7 (4 `memcpy` im Staging-Puffer,
3 Interpolation), `tickEntities` 3. Present-Wartezeit 65 Samples (34,6 %), `vkWaitForFences` in `preInitFrame` 16 (8,5 %):
Treiber/GPU, nicht änderbar.

Umgesetzt (alle ergebnisgleich, je ein A/B-Schalter):

| Änderung | Dateien | Schalter | Anteil im Profil | Schätzung |
|---|---|---|---|---|
| Fiddle-Werte je Biomzelle merken | `BiomeFiddleCache`, `BiomeManagerCornerMixin` | `-Dvsdh.biomeFiddleCache=false` | 17 von 95 Java-Samples (turn1) im Abstandsteil von `getBiome` | Mikrobenchmark: zufällige Partikel-Positionen 172 → 79 ns je Abfrage (−54 %), Farbton-Flächen 57 → 41 ns (−28 %) |
| Nur den gelesenen Ausschnitt eines Sprite-Blatts stagen | `SubImageRange`, `VTextureSelector.uploadSubTexture` | `-Dvsdh.tightSpriteUpload=false` | 4–5 Samples `StagingBuffer.copyBuffer` (2,1 % / 1,6 %) | kopierte Bytes je animiertem Sprite um die Frame-Anzahl kleiner (32 Frames: 1/32); nicht gemessen |
| Uniform-Lieferant je Member einmal bestimmen | `UniformRoutes`, `WorldUniforms.write`, `Std140Layout` (Typ beim Member) | `-Dvsdh.uniformRoutes=false` | `WorldUniforms.write` 10 von 188 (5,3 %) bzw. 5 von 313 (1,6 %) | etwa die Hälfte davon (4–6 String-Hash-Abfragen je Member weniger, kein `ConcurrentHashMap.computeIfAbsent`); nicht gemessen |
| Framebuffer-Suche ohne Lambda je Render-Pass-Beginn | `SwapChain.getFramebufferId`, `Framebuffer.getFramebufferId` | – (trivial) | 17 MB / 30 s Allokation | nur Allokation |

Der Mikrobenchmark (Scratchpad `perf/bench/Bench.java`, eine Variante je JVM, Rechner nicht im Leerlauf) zeigt auch:
bei zufälligen Positionen war der Positionsspeicher allein (`BiomeCornerCache`) langsamer als Vanilla (172 gegen 152 ns
netto), erst mit dem Zellspeicher lohnt er sich. Absolut ergibt der Benchmark nur etwa 0,2 ms je Tick für die 1334
Vanilla-Abfragen, das Profil deutet auf mehr; welcher Wert im Spiel gilt, ist offen. Kein FPS- oder Glätte-Anspruch
vor einer Flugmessung (mehrere Paare, Mediane).

Render-Thread-Allokation ist im Pack kein Hebel: 785 MB in 30 s gegen 5,8 GB des Server-Threads und 1–2,4 GB je
DH-Weltgenerator-Thread (zusammen über 25 GB). Größter Einzelposten auf dem Render-Thread:
`CubicSampler.gaussianSampleVec3` (Himmel-/Nebelfarbe, 5 Läufe je Frame: Vanilla 2, DH `getFogColor` 2, `renderSky` 1;
etwa 220 MB / 30 s `Vec3`). Nicht umgesetzt: ein Merker je Frame bräuchte Eingriffe in `ClientLevel.getSkyColor` und
`FogRenderer.setupColor`, die viele Mods anfassen; geschätzter Gewinn unter 0,5 % der Framezeit.

Nicht angefasst, Kandidaten: animierte Sprites nur hochladen, wenn sichtbar (wie Sodium; Risiko eingefrorener
Animationen bei Mods mit eigenen Zeichenwegen); `NativeWorldShadows`-Sichtbarkeitstest (`ShadowCuller.outsideCube`,
6 Samples); `SectionGraph.update` (1,6–2,6 %).

Mod-Posten für den Spieler: Raffman's Detailed Map (`DioramaSession.tick`, `ClientMapUpdater.tick`: 7–8 von 95
Java-Samples in turn1, 42 von 215 in profile1), Subtle Effects (`BiomeEffectsManager.tick`, `FireflyManager.tick`:
60 von 215 in profile1 vor dem Positionsspeicher, in turn1 nicht mehr sichtbar), Distant Horizons (15 Weltgenerator-
Threads dauerhaft aktiv, `RenderBufferHandler.buildRenderList` 3 Samples).

Prüfung: `gradlew --offline build verifyDhLinks` grün, 138 Tests (neu: `BiomeFiddleCacheTest`, `SubImageRangeTest`,
`UniformRoutesTest`). Im Spiel NICHT geprüft.

### Biom-Abfragen des Client-Ticks: einheitliche Sektionen ohne Rechnung (15.11., Fable; NUR Code + vorhandene JFR)

Kein Spieltest. Grundlage wieder `mt-turn1.jfr` (188 Render-Thread-Samples in 30 s) und `mt-profile1.jfr` (313); Anteile
sind grobe Werte. Rangfolge in turn1 nach äußerstem Abschnitt: Present-Wartezeit 65 (34,6 %), `vkWaitForFences` in
`preInitFrame` 16 (8,5 %), `ClientLevel.animateTick` 15 (8,0 %), DH `LodRenderer.render` 14 (7,4 %, etwa zur Hälfte DHs eigene
Renderliste und Nebelfarbe), NeoForge-Ereignisse fremder Mods 12 (6,4 %), `prepareShadowDraws` 11 (5,9 %, seit `ShadowSectionList`
ersetzt), `WorldUniforms.write` 10 (5,3 %, seit `UniformRoutes` verkürzt), `TextureManager.tick` 7 (3,7 %).
43 % der Samples sind Warten auf GPU/Present: die Szene ist GPU-begrenzt, CPU-Arbeit hebt die FPS dort nicht, sie
verkürzt nur die Tick-Frames (Tick 34 von 188 Samples, grob 9 ms je Tick).

Größter verbleibender VSDH-eigener Posten: 12 der 15 `animateTick`-Samples liegen in `getBiome` aus
`doAnimateTick:421`, 9 davon mit oberstem Frame im eigenen `BiomeManager`-Redirect (`vsdh$rememberedCorner`,
`BiomeCornerCache.begin`): der Positionsspeicher trifft bei Zufallspositionen nicht. Dazu 5 Samples Farbton-Flächen
(Raffman's Detailed Map über `calculateBlockTint`); in profile1 (vor dem Positionsspeicher) 74 von 313 Samples in
`getBiome`, davon Subtle Effects 46, Vanilla 10.

Umgesetzt: `ClientLevel.getBiome(BlockPos)` (per Mixin als Methode ergänzt, kein Callback-Objekt je Aufruf) fragt auf
dem Render-Thread zuerst `UniformBiomeProbe`: liegen alle acht Kandidatenzellen der Position in geladenen
Chunk-Sektionen, deren Biom-Palette nur ein und dasselbe Biom enthält (`PalettedContainerRO.maybeHas`), ist das die
Antwort; die acht Abstände und der Zellzugriff entfallen. Sonst (Sektion mit mehreren Biomen, Chunk fehlt, fremde
Chunk-Klasse, anderer Thread) der unveränderte Weg über `BiomeManager` mit `BiomeCornerCache`/`BiomeFiddleCache`.
Ergebnis ist derselbe `Holder`; Höhen-Klemmung und Zellwahl wie `ChunkAccess.getNoiseBiome`/`BiomeManager.getBiome`.
Dateien: `UniformBiomeProbe`, `ClientBiomeSections`, `ClientLevelUniformBiomeMixin`, `UniformBiomeProbeTest`
(Vergleich mit nachgebautem Vanilla-`getBiome` über 1 Mio. Positionen in fünf Welten, alle Sektions- und Chunkgrenzen).
Schalter: `-Dvsdh.uniformBiomeSkip=false` (alter Weg), `=verify` (beide Wege, Vergleich, Logzeile
`UNIFORM_BIOME verify probes=… hits=… mismatches=…` alle 65 536 Abfragen).
Schätzung: je Treffer 1–2 Chunk-Abfragen und ein Palettenvergleich statt 8 Abständen plus Zellzugriff; Trefferquote
hängt von der Welt ab (3D-Biome senken sie) und ist im Pack NICHT gemessen. Im Spiel noch nicht gemessen; kein FPS-
oder Glätte-Anspruch.

Geprüft und nicht umgesetzt: `CubicSampler.gaussianSampleVec3` ohne Zwischen-`Vec3` (rund 160 MB / 30 s weniger
Allokation, CPU 2 von 188 Samples; Nachbau einer Vanilla-Farbrechnung ohne spürbaren Gewinn), DHs Nebelfarbe je Pass
(`FogRenderer.setupColor` zweimal je Frame, 4 Samples; Eingriff in DH-Klassen mehrerer Versionen), Wrapper-Objekte in
`Pipeline.updateDescriptorSet` (44 MB / 30 s) und `GlFramebuffer.captureBindings` (28 MB / 30 s): reine Allokation,
neben über 25 GB der Server- und DH-Threads ohne Wirkung. GPU-Seite (dhWater, composite3) ohne Messung nicht angefasst.

## Borderless presentation (15.11.2026, test client, Eclipse, 2560x1440, 30 s flight, RD 12, two runs each)

| Variant | avg FPS | frames > 2 refresh/s | frames > 3 refresh/s | p99.9 ms | 0.1% low |
|---|---|---|---|---|---|
| Borderless, Vulkan swapchain | 120.6 / 114.3 | 3.17 / 3.20 | 1.53 / 1.07 | 31.8 / 23.1 | - / 41.8 |
| Borderless, DXGI flip (D3D12) | 108.5 / 138.2 | 6.04 / 4.63 | 3.10 / 0.70 | 31.8 / 24.2 | 28.5 / 34.9 |
| Exclusive fullscreen, Vulkan | 116.7 / 141.3 | 2.60 / 2.73 | 1.17 / 0.97 | 31.5 / 24.8 | 24.5 / 32.5 |

Average FPS varies more between runs than between variants (terrain generation along the route); the pacing columns
are what separates them. The frame times are CPU-side frame intervals; how the compositor shows the frames (independent
flip or composition) is not measured here. Default for borderless: the Vulkan swapchain.

## VSDH gegen Iris 1.8.12 + Sodium 0.6.13 (15.11.2026, Testclient, exklusives Vollbild, 2560x1440, 30-s-Flug, RD 12, DH 3.3.3 in beiden, je 2 Läufe)

| Szene | VSDH Ø FPS | Iris+Sodium Ø FPS | VSDH Median ms | Iris Median ms | VSDH 0,1 % low | Iris 0,1 % low | VSDH p99,9 ms | Iris p99,9 ms |
|---|---|---|---|---|---|---|---|---|
| Eclipse | 126,3 / 133,2 | 120,5 / 121,4 | 7,83 / 7,41 | 8,20 / 8,12 | 34,9 / 26,7 | 34,5 / 37,3 | 26,3 / 32,2 | 24,0 / 23,3 |
| Complementary r5.9.3 | 212,4 / 209,2 | 183,3 / 186,4 | 4,60 / 4,60 | 5,36 / 5,24 | 42,8 / 34,7 | 41,8 / 42,2 | 21,2 / 23,0 | 21,0 / 21,5 |
| ohne Shader | 677,3 / 689,3 | 534,1 / 605,9 | 1,35 / 1,32 | 1,59 / 1,44 | 46,5 / 39,7 | 48,2 / 61,1 | 17,1 / 21,2 | 15,4 / 13,4 |

VSDH liegt im Mittel vorn (Eclipse +5–10 %, Complementary +13–16 %, ohne Shader +14–27 %) und hat die kürzeren
Median-Bilder. Im seltenen Schwanz (0,1 % low, p99,9) ist Iris gleich gut oder besser: VSDH hat einzelne längere
Bilder mehr. Das ist der nächste Ansatzpunkt. Frames über 2 Bildwechsel/s: Eclipse VSDH 3,07/2,97 gegen Iris
3,37/3,94; Complementary 1,80/3,17 gegen 2,83/2,73; ohne Shader 1,77/1,93 gegen 1,53/1,03.

## GPU-side VSDH-only work with Eclipse (15.11.2026, Fable; CODE + existing logs only, nothing measured in game)

Sources: `bench-026/Eclipse-Shader-Unstable-eclgpu-vsdh.log` / `-eclgpu-iris.log` (14.11. 19:23, 1440p flight, same session),
`Eclipse-Shader-Unstable-fbB1/fbB2.log` (snapshot copies split out with `-Dvsdh.gpuProfileFeedback=true`, UTF-16),
`ComplementaryReimagined_r5.9.3-gpuprof-vsdh/-iris.log` (13.11.). ms per frame, VSDH / Iris:

| Eclipse 1440p | VSDH | Iris | note |
|---|---|---|---|
| total | 7.65-7.99 | 8.00-8.14 | |
| DH water + translucent | 2.21-2.37 | 1.70-1.76 | includes 5 snapshot copies, about 0.22 ms (fbB: 0.21-0.24) |
| composite .. composite12 | 4.09-4.27 | 4.07-4.10 | composite3 +0.1, composite +0.07-0.12, composite4 -0.05-0.09 |
| mip chains (composite1, composite11) | 0.16-0.19 | 0.18-0.20 (setup1, setup10) | same work |
| depth snapshots (depthtex2, depthtex1, DH opaque) | 0.057 + 0.057 + 0.038 | inside before#2/translucent | Iris copies them too |
| target clears etc. before the world | 0.060 | before#0 0.12 | Iris clears both halves too |
| final + mirrored blit + copy-back | 0.040 + 0.10 | finalize 0.45 | blit about 0.03, copy-back of colortex7/8 about 0.07 |

VSDH-only: the snapshot copies (0.22 ms) and the mirrored blit (0.03 ms); everything else Iris pays as well. About
0.45 ms of 8 ms is copy work at all, so removing every copy would give at most 5 % in this pack.

Changes (not measured yet):

- **Empty terrain layers are skipped in pack frames** (`TerrainLayerDraws`, `WorldRenderer.renderSectionLayer`;
  `-Dvsdh.skipEmptyTerrainLayers=false` restores the old path). Vanilla asks for five layers, the renderer holds geometry
  in two or three; SOLID, TRIPWIRE (and CUTOUT with one opaque layer) never draw. The pack program was prepared anyway:
  uniforms, an empty render pass, and for TRIPWIRE (gbuffers_water) the snapshot copies of colortex7 and colortex11.
  Eclipse: 2 of the 5 copies per frame, 44 of 118 MB, estimated 0.08 ms at 1440p; a TRANSLUCENT layer with no water
  in any queued area saves the same again. No picture change expected (nothing was drawn).
- **Wider swap rule, opt-in** (`PersistentSwap`, `-Dvsdh.swapAfterGeometry=true`): a persistent target also swaps
  halves when geometry draws into it, provided the first thing a frame does with the alternate half is an unblended
  fullscreen pass. Adds Eclipse colortex7/8 (estimated 0.07 ms), Complementary colortex4 (about 0.02 ms), Bliss
  colortex7, MakeUp 0/1, Mellow 1. Opt-in because a pass that shrinks gl_Position or discards (Bliss) would leave this
  frame's gbuffer contents where Iris leaves last frame's final ones; needs a picture comparison per pack. Geometry
  pipelines are built once more on the second frame (two render pass objects per program).

Colour feedback loops (`-Dvsdh.feedbackLoops=true`) would remove the remaining 3 copies (about 0.13 ms after the first
change, 0.22 ms before). Every read of colortex7/11 in Eclipse's water programs is `texelFetch(…, ivec2(gl_FragCoord.xy), 0)`,
the fragment's own texel. The 2.3 ms of the 14.11. A/B included the live DH depth (now off) and unexplained composite
differences; repeat it with colour loops alone before quoting it.

## Nachtmessungen 15.11.2026 (Testclient, 1440p)

- **Leere Gelände-Schichten überspringen** (`vsdh.skipEmptyTerrainLayers`, Eclipse, GPU-Profil, je 2 Läufe):
  feedbackCopy 0,189 / 0,223 ms (aus) gegen 0,119 / 0,119 ms (an). Die Gesamtzeit schwankt zwischen Läufen um bis zu
  1,5 ms (Szene), die Einsparung zeigt sich am Pass.
- **Farb-Feedback-Schleifen** (`vsdh.feedbackLoops=true`, je 2 Läufe): feedbackCopy 0,12 -> 0 ms, translucent 0,74 -> 0,58 ms.
  Standard bleibt aus, bis Bildvergleiche gegen Iris gemacht sind.
- **Biom-Kurzweg** (`vsdh.uniformBiomeSkip=verify`, Eclipse-Flug): 2.716 Treffer in 1,18 Mio. Abfragen (0,2 %), 0 Abweichungen.
  Richtig, aber fast nie anwendbar: Standard jetzt aus.
- **Chunk-Aufbau** (Flug ohne Shader, 3 Paare): Die erste Fassung von ChunkCatchUp (5 ms Upload, 3 ms Planung pro Bild) und
  prebuildHidden (4 pro Bild, ganze Sichtweite) kostete 1 %-Low (101-121 gegen 125-132 FPS) und mehr Ruckler (74-99 gegen
  68-73). Nach dem Umbau (normale Zeitbudgets, nur Anzahl erhöht; Vorab-Bauen 1 pro Bild im Umkreis von 96 Blöcken):
  1 %-Low 107/120/132 gegen 109/119/121, Ruckler 92/68/77 gegen 86/82/77, also kein messbarer Nachteil mehr.
- Die übrigen langen Bilder im Flug ohne Shader sind laut ChunkDiag G1-Pausen um 23-25 ms und einzelne GPU-Wartezeiten
  (bis 88 ms), nicht Chunk-Arbeit.
- **Farb-Feedback-Schleifen wieder an** (Bildvergleich Wasserszene, mittlere Abweichung je Kanal): Eclipse an/aus 2,13,
  gegen Iris 5,85 / 5,29; Bliss an/aus 2,83, gegen Iris 5,68 / 5,36; Complementary ohne Schleifen zwei Läufe 2,89 (Rauschen).
  Sichtbar kein Unterschied. Standard jetzt an.

## Speicherbereinigung mit 10 GiB Heap wie im Spieler-Pack (15.11.2026, Eclipse-Flug, exklusives Vollbild, je 2 Läufe)

| JVM | Ø FPS | 1 % low | 0,1 % low | p99,9 ms | Bilder > 33 ms | Ruckler |
|---|---|---|---|---|---|---|
| G1 (Standard) | 121,7 / 128,0 | 38,0 / 40,8 | 23,1 / 23,0 | 40,4 / 39,0 | 9 / 10 | 26 / 20 |
| `-XX:+UseZGC` (Spieler, Java 21 ohne Generationen) | 130,4 / 139,8 | 53,6 / 62,5 | 31,3 / 35,6 | 29,3 / 25,8 | 1 / 0 | 17 / 11 |
| `-XX:+UseZGC -XX:+ZGenerational` | 129,4 / 143,4 | 53,7 / 69,0 | 31,6 / 44,2 | 25,8 / 19,4 | 1 / 0 | 18 / 7 |

Mit großem Heap ist ZGC klar ruhiger als G1 (die frühere Notiz „ZGC nicht empfohlen“ galt dem 4-GiB-Testclient).
Empfehlung an Spieler mit großem Heap: `-XX:+UseZGC -XX:+ZGenerational`. Die Mod setzt keine JVM-Flags.

## Periodische Hänger mit DH: Upload-Menge ausgeschlossen (15.11.2026, Testclient, ohne Shader, exklusives Vollbild, 1440p, ZGC gen. 10 GiB)

Without DH the 15-27 ms frames about every 0.8 s almost vanish (0.03/s frames >2 refresh intervals vs 0.60/s). A per-frame
DH upload budget was tried and removed again: DH uploads at most 0.45-1.4 MB per frame in the flight (40-90 MB per 10 s),
and capping it at 64 KiB (about 700 uploads moved to later frames per 10 s, no errors) left the spikes unchanged.

| Run | Budget | FPS | >2 refresh/s | 0.1 % low | spikes |
|---|---|---|---|---|---|
| upbA1-3 | off | 710 / 748 / 723 | 0.33 / 0.20 / 0.30 | 73 / 116 / 74 | 11 / 6 / 14 |
| upbB1-3 | 4 MiB (never reached) | 713 / 701 / 721 | 0.23 / 0.27 / 0.27 | 93 / 78 / 84 | 7 / 11 / 8 |
| upsmall A/B, updiag B | 64 KiB | 755 / 694 / 743 | 0.27 / 0.53 / 0.43 | 79 / 49 / 60 | 10 / 21 / 14 |
| updiag A | off | 737 | 0.33 | 72 | 12 |

The cause was narrowed down later the same morning (next section).

## Periodische Hänger mit DH: Ursache eingegrenzt (15.11.2026 09:20-10:30, Testclient, exklusives Vollbild, 1440p, ZGC gen. 10 GiB)

Measured, in this order (all flights 30 s, RD 12; frame CSV plus `-Dvsdh.spikeMs=12` breakdown lines):

1. GPU work per frame never spikes: `-Dvsdh.gpuSpikes=true` found no long GPU frame in the no-shader flight (0.8 ms each).
2. Per-frame GPU idle gaps (new `GPU_IDLE` line under `vsdh.gpuSpikes`): in every stall frame the GPU sat idle 16-30 ms
   **before** the frame's first command buffer (the upload batch) began, while the CPU waited the same time in
   `vkWaitForFences` (`gpuWait` 14-26 ms). The work was submitted; the GPU did not start it.
3. Not driver memory: `-Dvsdh.vmaTrace=true` (new, logs every vkAllocateMemory/vkFreeMemory) shows one allocation in the
   whole flight, not at the stalls.
4. Not the fence wait itself: polling the fence for up to 2 ms before blocking (`fenceSpinUs`, removed again) left the
   stalls unchanged (>2 refresh 0.53/0.47 vs 0.57/0.40 per s).
5. Not the swapchain: 4 instead of 3 images (`-Dvsdh.swapchainImages=4`, kept as a switch) unchanged (0.33/0.47 vs
   0.23/0.57); Mailbox halves FPS (356) and is not better.
6. DH CPU load: the test client's DH config (16 worker threads, priority 5, the same as the player's pack) changed for
   test runs only and restored byte for byte afterwards:

| Scene | DH threads | FPS | >2 refresh/s | >3 refresh/s | lateMs/s |
|---|---|---|---|---|---|
| no shader | 16 (base, 2 runs this series) | 703 / 716 | 0.53 / 0.50 | 0.37 / 0.37 | 7.9 / 7.6 |
| no shader | 4 (4 runs) | 830 / 847 / 834 / 845 | 0.20 / 0.07 / 0.23 / 0.30 | 0.03 / 0.03 / 0.10 / 0.13 | 1.5-4.5 |
| no shader | 8 | 706 / 712 | 0.33 / 0.47 | 0.23 / 0.33 | 5.2 / 8.0 |
| no shader | 16 at priority 1 (4 runs) | 704 / 702 / 701 / 686 | 0.03 / 0.43 / 0.60 / 0.27 | 0.03 / 0.27 / 0.47 / 0.20 | 0.9-10.9 |
| Eclipse | 16 (4 runs) | 131.5 / 127.3 / 136.4 / 127.2 | 0.57 / 0.83 / 0.57 / 0.47 | 0.23 / 0.43 / 0.30 / 0.20 | 188-232 |
| Eclipse | 4 (4 runs) | 167.4 / 160.8 / 159.7 / 157.5 | 0.23 / 0.33 / 0.23 / 0.37 | 0.17 / 0.20 / 0.13 / 0.23 | 47-76 |

**Not a fair picture comparison:** with 4 threads DH loads far less distant terrain during the 30 s flight (Eclipse GPU
profile: dhWater 0.57-0.89 vs 1.45-1.67 ms, dhOpaque 0.06-0.09 vs 0.15-0.26 ms). Most of the FPS gain is less distant
terrain. What holds: the stalls belong to DH's loading work (CPU-heavy), not to steady rendering; priority 1 alone does
not reliably remove them. Fewer DH threads trade loading speed for smoothness while moving. Player choice only; VSDH
never writes DH config.

Decided at 10:38 with `-Dvsdh.separateUploadSubmit=true` (new diagnostic: the upload batch is submitted alone, without
the swapchain wait): in every stall frame the uploads ran at once (beforeBatchMs 0.02-1.4) and the frame itself waited
13-25 ms afterwards (afterBatchMs), and its only dependency is the swapchain acquire semaphore. The presentation engine
returns the image late; with 4 images it was not better, so no image comes back during these moments. It happens only
under DH's loading work; the JFR thread samples (10 s period, rough) put `DH-World Gen` and `DH-Render Loader` on top,
so DH generates new distant terrain in the flight. Likely the driver's flip thread in the game process is starved; not
reachable from Java without native thread enumeration (not done). Possible later mitigation: let pack frames run their
world and composite passes before the swapchain wait (acquire the image only for FINAL and GUI); it would hide at most
the frame's own GPU time (about 7 ms of a 20 ms wait in Eclipse).

Also measured: the DH quad index buffer now lives in device memory like the renderer's own AutoIndexBuffer
(`-Dvsdh.dhIndexHostMemory=true` = old host memory). DH passes cost 0.15 ms in Eclipse either way; no measurable gain,
kept for parity.

DH thread priority 1 vs 5 with Eclipse (16 threads, 4 runs each alternating, GPU profile to check the LOD amount):

| Priority | FPS | >2 refresh/s | >3 refresh/s | dhOpaque / dhWater ms (windows 4-7) |
|---|---|---|---|---|
| 5 (pack default) | 127.4 / 127.6 / 124.8 / 140.3 | 0.73 / 0.70 / 0.67 / 0.80 | 0.47 / 0.27 / 0.33 / 0.27 | 0.18-0.20 / 1.52-1.57 |
| 1 | 130.5 / 135.5 / 142.8 / 129.6 | 0.73 / 0.60 / 0.63 / 0.67 | 0.33 / 0.17 / 0.37 / 0.20 | 0.13-0.23 / 1.12-1.57 |

Same distant terrain (one priority-1 run a little less), slightly fewer stalls and +3 % FPS on average; within the run
spread, so at most a weak player tip, not a measured fix.

## Late swapchain wait (15.11.2026, measured worse, removed again; code in commit 8fe3174)

`-Dvsdh.lateAcquire=true` (default off). The mitigation named above: a pack frame submits its upload batch and all
world and pack passes up to COMPOSITE in a first `vkQueueSubmit` without the swapchain wait and without a fence
(`Renderer.submitBeforeSwapchain`), and FINAL, hand and GUI in a second command buffer whose submission waits for the
image, signals render-finished and the frame fence. Acquire stays in `beginFrame`.

Why it is valid (Vulkan specification, checked 15.11.2026 in KhronosGroup/Vulkan-Docs):
- Synchronization, "Fences", fence signaling: "Fence signal operations that are defined by vkQueueSubmit additionally
  include in the first synchronization scope all commands that occur earlier in submission order." The frame fence
  on the second submission therefore also covers the first; no second fence or timeline semaphore is needed.
- Command Buffers, "Queue Submission", vkQueueSubmit: the first scope of each semaphore signal operation "includes
  every command in the same batch ... and all commands that occur earlier in submission order" (render-finished and
  the DXGI render timeline cover the early part); the second scope of each semaphore wait operation "includes every
  command in the same batch ... and all commands that occur later in submission order", so the early batch is not
  held by the image wait.

What keeps the swapchain image out of the early part: the default main pass is not begun in such frames
(`MainPass.beginWithoutTarget`); the pack host clears `mainDeferred` on every path that binds the delegate's target;
and the split is refused unless the image still has its frame-start layout (any use has to leave the present layout
first). Frames without a pack host, without a level, with a failed graph, with a pending recreate or after a readback
flush keep the single submission.

Diagnostics: under `-Dvsdh.gpuProfile=true` or `-Dvsdh.syncTrace=true` the log prints
`LATE_ACQUIRE deferredFrames=.. earlySubmits=.. refused=..` every 5 s; `GPU_PROFILE` gains `lateSubmitGap` (GPU
finished the early part before the rest was submitted) and `lateImageWait` (what is left of the image wait), and
`imageWait` at the frame start drops to about zero.

Known limits, to check in game: the split costs one more `vkQueueSubmit` (the separate upload submit cost about
0.1 ms GPU gap); texture uploads recorded after the split run after the world passes instead of before them; with
`frameQueueSize=1` the GPU now runs the world while the CPU records FINAL and GUI, which no code path was seen to
depend on. A/B plan: docs/NEXT_AI_PROMPT.md, entry 15.11.2026.

### Result in game (15.11.2026, test client, Eclipse, 2560x1440 window, DH 3.3.3, 30 s flights, interleaved)

| Run | lateAcquire | avg FPS | >2 refresh/s | >3 refresh/s |
|---|---|---|---|---|
| 1 | on | 116.8 | 31.7 | 3.2 |
| 1 | off | 125.0 | 4.2 | 1.0 |
| 2 | on | 115.6 | 31.1 | 4.3 |
| 2 | off | 124.5 | 3.7 | 0.8 |
| 3 | on | 133.3 | 27.0 | 1.9 |
| 3 | off | 122.1 | 4.1 | 1.5 |

The split ran in every frame (`LATE_ACQUIRE earlySubmits = deferredFrames, refused=0`); the Eclipse screenshot run
rendered normally. GPU profile pair: `imageWait` was 0 already without the split and `lateImageWait` 0 with it, i.e.
in this window run the GPU never waited for the image; instead it idled `lateSubmitGap` ~0.3 ms until the CPU had
recorded and submitted FINAL/GUI, and frame pacing became much worse (8x more frames over two refresh intervals).
Removed; the per-frame gap of ~0.7 ms is not an image wait here. Not retested in exclusive fullscreen, where the DH
stalls were measured.

## Exact cull updates (15.11.2026, measured)

Turn series (Complementary, `vsdh.turnSeries.shore`): with the old 2-degree/0.5-block rule the leading screen edge
showed DH LODs where real terrain belonged (frame 32, island at the right edge); with `GraphUpdatePolicy` the real
terrain is there. Edge-strip pixel statistics over the whole series stay within run noise (mostly water).
Eclipse 1440p flights, 3 interleaved pairs: avg FPS on 130.5/127.5/133.3, off 130.1/130.4/140.7 (medians equal);
frames >2 refresh intervals per s on 4.3/3.6/4.1, off 7.7/6.9/6.3. The flight is mostly straight, so this is a hint,
not proof of a smoothness gain.

## Input latency: waits after the input poll (15.11.2026, code review + unit tests, NOT measured in game)

Report: "the camera turns later than I move the mouse" (Jurgens + DH, exclusive fullscreen, VSync on, cap 180 on a
180 Hz monitor, frame queue 2, 3 swapchain images). Not a smear.

Frame order (Minecraft 1.21.1 `Minecraft.runTick`, NeoForge 21.1.264 sources; renderer hooks in `MinecraftMixin`):

| Step | Vanilla / OpenGL | This renderer before | Now |
|---|---|---|---|
| end of frame N-1 | `flipFrame`: poll, **swap (blocks)**, poll | submit, present; poll, poll | submit, present, **fence + acquire (block)**; poll, poll |
| limiter | `glfwWaitEventsTimeout` (polls) | same | same |
| `runTick` HEAD | - | `preInitFrame`: **fence wait (blocks)**, uploads | fence already signalled, uploads |
| tasks, ticks | same | same | same |
| `RenderSystem.clear` | - | `beginFrame`: **acquire (blocks)** | image already acquired |
| `handleAccumulatedMovement` | applies what the last poll saw | same | same |
| `GameRenderer.render` | camera of this frame | same | same |

The mouse movement made during the two waits was not polled, so the frame showed a camera older by that wait.
r = refresh interval (5.56 ms at 180 Hz), c = CPU time of a frame, g = GPU time of a frame; newest mouse sample to
the frame being on screen, without scan-out and display lag:

| Case | before | early waits | low latency mode |
|---|---|---|---|
| VSync (FIFO), GPU faster than refresh, 3 images, queue full | 3r - c (about 15 ms at c = 2 ms; just under 3 frames) | 2r (11.1 ms) | r (5.6 ms) |
| same with N images | N*r - c | (N-1)*r | r |
| VSync, GPU slower than refresh (g > r), frame queue 2 | 3g - c + 0..r | 2g + 0..r | c + g + 0..r, FPS falls to refresh/2 or lower |
| VSync off, cap 177, GPU faster than the cap | c + g (no wait after the poll: the limiter polls while it sleeps) | same | same |
| VSync off, GPU-bound, frame queue 2 | 3g - c | 2g | c + g, FPS = 1/(c+g) |

With cap 180 on 180 Hz the limiter and the display beat against each other: the FIFO queue is full when the limiter
is the faster of the two and empty when it is the slower, so the latency drifted between about 1 and 3 intervals.
A cap a few FPS below the refresh rate (170-175) keeps the queue empty: about c + g + 0..r.

Changes: `Renderer.waitBeforeInput` (end of `endFrame`): frame-slot fence, then `vkAcquireNextImageKHR` for the
next frame; `beginFrame` uses `earlyImageIndex`. Low latency mode: `VkPresentIdKHR` in the `VkPresentInfoKHR` chain,
`vkWaitForPresentKHR(previous id, 100 ms)` first in `waitBeforeInput`. Specification checked (docs.vulkan.org
refpages, 15.11.2026): the wait returns when the swapchain's presentId >= the given id or after the timeout
(nanoseconds, `VK_TIMEOUT`); the presentWait feature must be enabled; the swapchain must not be retired; non-zero
ids must grow per swapchain. LWJGL 3.3.3 has `KHRPresentId`, `KHRPresentWait`, `VkPresentIdKHR` and both feature
structs (javap on the pinned JAR).

Rejected as cause: a stale pack camera. `WorldShaderLevelMixin` hands `LevelRenderer.renderLevel`'s own view and
projection to `WorldShaderSession.beginWorld` -> `WorldUniforms.capture` -> `WorldMatrices.begin` in the same frame;
`CameraBobbing.intoView` builds from that frame's matrices; `DistantProjection.followCamera` copies that frame's
projection. No rotation is read per tick.

To measure (after a go): `-Dvsdh.inputTrace=true` logs `INPUT_TRACE pollToPresentAvgMs/MaxMs` (poll at the end of
`endFrame` to the return of the present call); compare `-Dvsdh.earlyFrameWait=false` with the default in exclusive
fullscreen with VSync, uncapped and with the pack. PresentMon, if installed: the until-displayed / click-to-photon columns
(names differ between 1.x and 2.x, not checked here) for the test client; low latency mode on/off. Also check FPS and frames >2 refresh
intervals in the flight A/B (no loss expected for the early waits; a loss is expected for low latency mode whenever
FPS < refresh rate), and resize, minimise/restore, fullscreen switches and a pack switch with the early acquire.
