# Fortsetzungs-Prompt — VSDH 0.26

Arbeite in dem Projektordner (Repository-Wurzel) auf
`codex/renderer-shaders`. Der Nutzer will autonome Weiterarbeit bis zum
Gesamtziel und gemessene FPS. Er kommuniziert auf Deutsch und möchte
Entscheidungen zu Aussehen, Kompatibilität und größeren Leistungsabwägungen
selbst treffen. Siehe AGENTS.md, README, `docs/PERFORMANCE.md` und den
neuesten Bericht unter `dist/`. Java 21 und NeoForge 21.1.264 bleiben fest.
Nur isolierte Spielwelten verwenden.

## Stand 0.26 abends (Iris-Abgleich, 11.11.2026)

Die Pack-Kette arbeitet in GL-Ausrichtung (siehe AGENTS.md, NATIVE_PACK_FEATURES.md
„Iris parity fixes“): keine Y-gespiegelten Matrizen mehr, Bilder zeilenweise von
unten, gespiegeltes Blit nur am Ende, `Renderer.setMainTargetRowsBottomUp` für
Vanilla-Zeichnungen in die Pack-Szene, DH-Tiefenleser spiegelt. Neu: `vignette`/
`underwaterOverlay` wie Iris, `HIGHER_SHADOWCOLOR`/`BLOCK_EMISSION_ATTRIBUTE`/
`ENTITY_TRANSLUCENT`, `shadowcolorimgN`, `indirect.<pass>`, Terrainprogramme je
Schicht. Prüf-Packs (nur Test, nie ausliefern): `build/reference/packs-debug/
zzUniformProbe.zip` (Uniforms als Farbbalken; Vergleich mit Iris über
`run-vsdh-026-compare.ps1 -Only zzUniformProbe` nach Kopie nach `packs/`) und
`zzIndirectProbe.zip` (grün = indirekter Dispatch korrekt). Testszenen blenden die
GUI aus (Iris-Referenz ebenso); `-PtestShowGui` zeigt sie. `run-vsdh-026-single.ps1`
kennt `-Options '{"NAME":"wert"}'`, `-Profile`, `-Jar <Datei>` (A/B mit alter JAR).
Vorsicht beim Bildvergleich: Wassertextur-Animation, DH-Erzeugungsfortschritt
(Inseln, dunkle LOD-Rechtecke im Nahbereich ohne Pack) und Sonnenglanz-Wellen
ändern sich je Lauf; mehrere Ticks/Läufe vergleichen, bevor ein Fehler vermutet wird.
Offen: typisierte Custom-Image-Views, reine Nachbearbeitungs-Packs ohne
Terrainprogramm, `frustum.culling`, `beacon.beam.depth`. Fenster-Present: seit 12.11. eigene
D3D12-Ausgabe auf NVIDIA (keine Treibereinstellung nötig; Treibereinstellungen nie selbst ändern).

## Laufende Arbeit (11.11.2026 nachts, Nutzer: „Refine the mod until 5:15“)

Ziel: maximale Leistung ohne Bildverlust, dann offene Punkte. Erledigt (22:55):
Hardware-Sinus Standard (`-Dvsdh.reduceTrig=true` für alt), 14-Pack-Bildvergleich,
Smoke 3/3, Matrix 6/6, Bench (Vollbild +10–17 %, Fenster gleichauf, Tabelle
`docs/PERFORMANCE.md`), dist-JARs 72abd8c3…/0d82df0b…. Verworfen: Teil-Submits,
Present-Thread, MAILBOX. Weitere Ideen: GPU-Pausen ~0,6 ms (Treiber-Präsentation),
Mipmaps per Compute, Pass-Vergleich `run-pass-compare.ps1`. Nutzer will bis 5:15
weiter verfeinern lassen (dann Bericht auf Deutsch, getrennt nach geprüft/kompiliert/
im Spiel getestet).

Nachts (12.11.2026, bis 5:15): GPU-Pause zwischen Frames vermessen (`GPU_GAP`, Acquire,
`-Dvsdh.debugCpuDelayUs`): reine Treiber-Präsentation, nicht behebbar. Umgesetzt: Upload-Batch
im selben Submit wie der Frame, gebündelte Mip-Ketten, echte 3D-Texturen für Pack-Volumen
(`-Dvsdh.flatVolumes=true` = alter Atlas), Test `NativeVolumeTest`. Diagnose-Hilfen:
`-PtestSysProps=a=b,c=d` im Testclient (`release-client-026-packs/build.gradle`).
Befund: derselbe Shader läuft unter Vulkan 10–20 % langsamer als unter OpenGL (NVIDIA-Treiber,
Mess-Pack, gleicher Takt); Debug-Infos/Denormals sind es nicht. Pass-Zeiten echter Packs zwischen
Läufen nur als Näherung lesen (Profilfenster decken andere Szenenabschnitte ab). Ergebnis
1440p: Vollbild +9 bis +18 % vor Iris, Fenster −0,7 bis +6 % (`docs/PERFORMANCE.md`).
`shadowtex1` wird nur kopiert, wenn gelesen. Paket: `dist/0.26.0-dev-perf` (SHA-256
1bb69c41… Haupt-JAR, 6aa78531… dh330), identisch mit den in Smoke 3/3 und Matrix 6/6 (02:44)
geprüften JARs; `dist/0.26.0-dev-compat` bleibt der Abendstand zum Vergleich.

Morgens (12.11.): Nutzerwunsch „jeder Spieler ohne Umstellen“ → eigene DXGI-Ausgabe gebaut
(`net.vulkanmod.vulkan.present`). D3D11-Interop war langsamer (nur noch `-Dvsdh.dxgiPresent=d3d11`).
Mittags auf Nutzerwunsch D3D12 (`D3d12Presenter`): im Fenster schneller (Photon 159–163 statt
149–153 FPS), im Vollbild langsamer (145–150 statt 166–171) → Standard „auto“: D3D12 nur im Fenster
und nur auf NVIDIA, Vollbild Vulkan, Wechsel bei F11. Fünf Packs im Fenster jetzt +5 bis +14 % vor
Iris. Kopier-Queue verworfen (Gerät entfernt), Vorwärmen wirkungslos. Geprüft: 95 Unit-Tests, Smoke 3/3,
Matrix 6/6, F11/Größenwechsel, Desktop-Bildschirmfoto. Paket `dist/0.26.0-dev-d3d12`. Nicht getestet:
AMD/Intel (dort bleibt Vulkan), VSync an, Mehrmonitor, HDR. Windows-SDK-Header lagen nur im Scratchpad.
Nichts committet.

Abends (12.11.): Nutzer „Refine it to the best you can do for anyone to use“ → Seite **Shader** in den
Videoeinstellungen (Pack, Profil, DH-Schalter, Ordner öffnen), Wechsel ohne Neustart (Hauptmenü sofort,
in der Welt beim Verlassen), Einstellungen je Pack gemerkt, Unverträglichkeit mit Iris/Oculus/Sodium/
Embeddium deklariert, übersetzte Meldungen. D3D12-Rückfall und Frame-Guard bestanden schon. Geprüft:
95 Unit-Tests, Menü- und Weltwechseltest, Smoke 3/3, Matrix 6/6. Paket `dist/0.26.0-dev-menu`.
Nutzerentscheidung 12.11. abends (korrigiert): DH ist optional; VSDH muss auch ohne DH starten (Vulkan-Renderer + Shader).
Offen: NeoForge-Version ist exakt 21.1.264 gepinnt. Pack-Optionen einzeln gibt es nur in `vsdh.json` (keine Optionsseite je Pack).
Nichts committet.

Nachts (13.11., Nutzer schläft, „arbeite selber weiter bis 5:15“; Ziel: DH, Shader und Vulkan
„bestmöglich und smooth“ kombinieren; DH ausdrücklich nicht Pflicht): DH optional gemacht und ohne DH
getestet; Flug-Messung für Glätte gebaut (VSDH vs Iris, Median 208/188 FPS, 18/33 verpasste Takte pro s);
Ursachen der Ruckler vermessen (G1-Pausen durch Server/DH-Allokation, Present-Takt; GPU konstant);
DH-/VBO-Uploads in den Frame-Batch, Render-Thread-Allokationen gesenkt. ZGC hilft VSDH nicht.
Offen: Lauf-Schwankung (168–227 FPS) ungeklärt, `composite1` 0,15 ms langsamer als Iris (Treiber).
Außerdem: Seite „Pack-Optionen“ (alle Optionen der Pack-Seiten, Beschriftungen aus dessen Sprachdatei,
Neuladen ohne Neustart), Optionsindex liest auch per #include eingebundene Dateien (Miniature: 13 → 82
Optionen). Weiter offen: Ersatz-Terrainprogramm für reine Nachbearbeitungs-Packs, AMD/Intel-Test
(Testrechner: AMD-iGPU kann das Fenster nicht darstellen), NeoForge exakt 21.1.264 gepinnt (Nutzerfrage).
Paket der Nacht: `dist/0.26.0-dev-night` (siehe MIGRATION_STATUS „Stand 13.11.2026 nachts“). Nichts committet.

## Stand 0.26 abends (1440p gegen Iris)

Nutzerziel: mehr FPS als Iris bei 2560×1440. Erreicht im Vollbild (alle fünf
Mess-Packs vorn, `docs/PERFORMANCE.md`). Im Fenster fehlt ~1 ms pro Frame durch die
Vulkan-Present-Kopie des NVIDIA-Treibers; Nutzer-Abhilfe: Vollbild oder
NVIDIA-Einstellung „Bevorzugt geschichtet auf DXGI-Swapchain“ (vom Nutzer zu setzen,
danach neu messen). Nächste Hebel, falls weiter optimiert wird: GPU-Zeiten pro Pass
mit Iris vergleichen (beide Clients haben Profiler), Mipmaps per Compute, gebündelte
Barrieren.

## Stand 0.26 (Leistung)

Gemessene Szene und Werkzeuge: `docs/PERFORMANCE.md`. 0.25: 89,4 FPS,
0.26: 112,4 FPS (1440p, Complementary MEDIUM, DH 3.3.3, RTX 4070).
- `ShadowCuller` + `WorldRenderer.prepareShadowDraws`: Iris-gleiche
  Auswahl der Schattenwerfer, Bereichs- und Sektionsprüfung, indirekte
  Schatten-Draws. Unit-Tests inklusive Strahl-Abtastung und Mutationsprobe.
- `NativeWorldDepth`: depthtex1/2 kopieren nur den Tiefenanteil
  (`NativeImageOps.copyDepthOnly`). `ChainGpuCheck` prüft exakte Tiefen.
- `GraphicsQueue`/`Renderer`: Uploads des Render-Threads pro Frame in einem
  Puffer ohne CPU-Fence; Notschalter `-Dvsdh.legacyUploadSync=true`.
- `GpuTimestamps` (`-Dvsdh.gpuProfile=true`), `Synchronization`-Trace
  (`-Dvsdh.syncTrace=true`). Das FPS-Profil nimmt `-PtestGpuProfile`,
  `-PtestSyncTrace`, `-PtestJfr=<Datei>` an.
Verbleibend: ≈ 0,9 ms GPU-Leerlauf pro Frame im Fenstermodus, der Thread
wartet in `vkQueuePresentKHR`. Vollbild ist nicht gemessen. Iris/OpenGL in
derselben Szene messen (Referenzclient unter build/reference/iris-client),
bevor ein Vorsprung behauptet wird. Mipmaps (≈ 0,45 ms) ließen sich per
Compute-Downsampler beschleunigen. Den Rest kostet die Pack-Arbeit selbst.
DH 3.1.2: build/compat-312-probe hat optionale Texture-Einstellungen; ein
Spieltest mit Testszene steht aus.

Kompatibilität (0.26): Der Nutzer will, dass möglichst jedes Shaderpack
fehlerfrei läuft. Vorgehen pro Pack: Spiellauf in VSDH und im Iris-Client
(`build/reference/iris-client`, gleicher Seed 825765032, Kamera 0/100/0,
Blick 45/35, Sichtweite 4, gleiche DH-Konfiguration), Screenshots
vergleichen und Abweichungen per Gegentest eingrenzen. Behoben: Bliss-Kacheln
durch DH-Pack-Culling und Bliss ohne DH-Programme. Offen: Photon
(`deferred4_a` als Compute-Pass), weitere Packs nur nach Freigabe des Nutzers
herunterladen. Testschalter im Bliss-Profil: `-PtestRemoveKelp`,
`-PtestSkipDistantWater` (nur QA-Mod, nicht im Produkt).

## Stand 0.25

0.25 ergänzt den Legacy-Adapter für DH 3.2.0-b. Das offizielle Artefakt
hat SHA-1 df25e8cfe06917963778723a1d9a60610dd21bec. Weiter mit DH 3.1.2.
Dort fehlt Config.Client.Advanced.Graphics.Texture. Vergleiche die
Original-JAR und vorhandene Reforged-Ports in den Referenzordnern.
2.x hat keinen 3.x-Renderer-Injector. Der gepinnte Braffolk-Commit
enthält dafür ein eigenes 2.4-Source-Set.

Bliss 2.1.2 nutzt Material-Standardtexel, neue Weltwerte und
Farbsnapshots für Geometrie-Feedback. Die CPU-Vorbereitung ist in
Overworld, Nether und End durchgelaufen. Die Stage-IO-Zuordnung in
VulkanProgramCompiler löst die voneinander abweichenden
Deklarationsreihenfolgen. Overworld mit DH 3.3.3 ist bei 1440p anhand
von Bild, Final-Pixeln und DH-Zählern geprüft. Bliss-DH-Schatten,
andere Dimensionen im Spiel und PBR-Resourcepack-Atlanten stehen aus.
Photon 1.4 braucht den Compute-Pass deferred4_a. Prüfe Storage-Image-
Layout, Synchronisierung mit derselben colortex4-Textur und den
persistenten Verlauf pro Frame. Shaderpacks, DH-JARs und dekompilierte
Referenzen bleiben privat unter build/reference.

Der 1440p-Benchmark nutzt Reimagined r5.9.3 MEDIUM, DH 3.3.3,
VSync aus, normale und simulierte Distanz 8, DH 128, eine feste Kamera
und fertige LODs in einer isolierten Küstenwelt. Der Lauf liegt unter
build/reference/release-client-025-fps. Nutze bei einer Wiederholung
35 Sekunden Aufwärmen und drei 10-Sekunden-Proben. Keine parallelen
Spielinstanzen während der Messung.

## Stand nach dem 0.25-Paketabschluss (10.11.2026)

`dist/0.25.0-dev` enthält jetzt verification.json, SHA256SUMS.txt und
verification-logs.zip (Skript: build/reference/package-vsdh-025.ps1,
läuft unter Windows PowerShell 5.1). Der Neuaufbau aus dem Quellarchiv
unterscheidet sich nur durch CRLF/LF in 230 Textressourcen und durch
MethodParameters-Namen in NativeGraphicsProgram$1.class. Die Befehle
sind laut javap identisch. Der Bericht führt beides einzeln auf.

DH 3.1.2: build/compat-312-probe macht Texture-Einstellungen optional
und erweitert versionRange. Der Probeclient erreichte das Hauptmenü.
Dem Profil release-client-026-dh312-probe fehlt src/ der Testszene,
deshalb startete keine Welt. Das ist noch kein Spieltest.

Leistung: In 0.26 umgesetzt, siehe oben und `docs/PERFORMANCE.md`.

## Historischer Stand 0.24

## Breite Kompatibilität

Der Nutzer möchte alle für Minecraft 1.21.1 / NeoForge 21.1.264 geeigneten
DH-Ausgaben und möglichst alle Iris-/OptiFine-Shaderpacks verwenden.
Siehe `dist/0.24.0-dev/verification.json` für echte Spieltests. Die vier
stabilen DH-Ausgaben reichen von 3.3.0 bis 3.3.3. Seit dem 13.11. deckt eine JAR
alle ab (DH 3.3.0 über den Coremod in `src/dh330`; die frühere `-dh330.jar` entfällt). Der ursprüngliche 3.3.0-Lauf hatte einen leeren
NeoForge-Renderer-Injector. Die Zuordnung zur `_neoforge`-Namensvariante
steht in `Dh330Mapping`.
Auf Modrinth sind 22 weitere
Alpha- und Beta-Ausgaben für dieselbe Zielplattform gelistet. Die lokale
Liste und geprüfte Binärvergleiche liegen unter `build/reference/dh-matrix`.
2.x braucht einen anderen Renderer-Einstieg. Bei 3.0–3.2 sind unter anderem
Render-API, Buffer-Verträge und Datenbank-Mixins versionsabhängig. Der
nächste DH-Kandidat ist 3.2.0-b. Prüfe jede Familie im eigenen Spielprofil.

Die 0.24-Adapter liegen in `src/dh33Early` und `src/main`. Der Build prüft
beide DH-Referenz-JARs. In der Laufzeit nur eine DH-JAR laden. Bei der alten
Variante auch `ConfigEntry.setApiValue(T)` berücksichtigen. Der aktuelle
Artefaktstand unterstützt zusätzliche gemeinsame Werte für BSL 10.1.

Nächste Pack-Hürden aus den CPU-Audits sind Bliss 2.1.2 (`FlatNormals` und
`viewToWorld` im übersetzten Terrain-Vertexshader) und Photon 1.4 (Compute
`deferred4_a`). Zuerst den gemeinsamen Shaderübersetzer bzw. den nativen
Compute-Pfad erweitern. Manche Packs liefern keine DH-Programme. Für diese
ist ein separater LOD-Pfad mit eigener Sichtprüfung nötig. Keine fremden
Packs oder DH-JARs mit dem Ergebnis ausliefern.

## Aktuelle Arbeit

0.21–0.23 ergänzt die Hand, Blocktransparenz, volle Beleuchtung für
leuchtende Mob-Schichten, Glanz und dynamische Schatten. Die frühe
Minecraft-Hand wird mit einer frischen ModelView-Basis aufgerufen,
weil der Welt-Stack während Deferred bereits die Kameramatrix trägt.
Der Eingriff liegt vor RenderType.setupRenderState des Wassers.
depthtex2 wird vor der opaken Hand kopiert, depthtex1 danach.
Die späte Hand zeichnet vor Composite. Die Vanilla-Bildschirmeffekte
laufen einmal wie gewohnt. Separate Glanzpuffer sind wegen Minecrafts
parallelem VertexMultiConsumer erforderlich. Der Glanz behält den
normalen EQUAL-Tiefenvergleich und die animierte Texturmatrix.

NativeShadowCasters verwendet die Modelle der geladenen Welt und den
Lichtfrustum. Mob- und Blockgeometrie liegen vor der opaken
Schattenkopie. Das MEDIUM-Profil deaktiviert diese Familien.
config/vsdh.json kann mit "shaderOptions": { "ENTITY_SHADOW": "2" }
das Profil überschreiben. Die Shader-ZIP bleibt unverändert.
ShadowCasterWorldProbe prüft echte zusätzliche Schatten-Tiefenpixel.
GlintWorldProbe wartet die Ausrüstungsanimation ab und vergleicht
Farbdaten vor und nach dem tatsächlichen Glanzaufruf.
TransparentBlockWorldProbe nutzt die Truhe mit transparentem RenderType
in der isolierten Spielszene. Alle Proben bleiben im Smoke-Source-Set.
Schattenprogramme teilen sich die Pack-Texturzuordnung der Gbuffers.
Unbound r5.9.3 liest dort gaux4 aus lib/textures/cloud-water.png.
verify-0.23-unbound-client.log dokumentiert die ursprüngliche Ablehnung.
Der korrigierte Lauf heißt verify-0.23-unbound-client-2.log.

Prüfe dist/0.23.0-dev und den Prüfbericht auf die getesteten Pack- und
DH-Kombinationen. 3.3.2 und 3.3.3 haben gleiche Signaturen für die
geprüften 45 DH-Klassen. Die Paketprüfungen laufen mit jeder Version
in ihrem eigenen NeoForge-Profil. Weitere GPUs, Performance und andere
Packs jeweils messen.
Die Entwicklungsstände 0.21 und 0.22 waren Zwischenprüfungen.

0.20 bindet transparente Mob-Schichten an gbuffers_entities_translucent.
Die beiden normalen Minecraft-RenderTypes verwenden das schon gepufferte
NEW_ENTITY-Mesh einschließlich sortierter Indizes. Der Shader erhält
die DH-Sichtweite über WorldUniforms. Bei separateEntityDraws=true hält
WorldShaderEntityLayerMixin nur diese Meshes bis nach Deferred im selben
Frame. ByteBufferBuilder.Result verwaltet Offsets und hält die Daten bis
close gültig. Die Warteschlange wird beim Graph-Wechsel geschlossen.
Keine Resultate in spätere Frames verschieben. Der normale Pfad zeichnet
vor Deferred. Der Partikelstandard wird bei getrennten Entitäten MIXED,
wenn das Pack keine ausdrückliche andere Reihenfolge vorgibt.

verify-0.20-original-translucent-2.log prüft das unveränderte Originalpack.
verify-0.20-late-translucent.log prüft eine private Kopie mit ausdrücklich
getrennter Entitätsphase. Beide verwenden einen Schleim in isolierten
Welten. Die private Kopie bleibt unter build/reference und gehört in
keine JAR. Prüfe die abschließenden Testlogs und den Paketbericht, bevor
du 0.20 als getestete Version ausgibst. Der geprüfte 0.19-Stand bleibt
unter dist/0.19.0-dev verfügbar.

In 0.20 waren Hand, transparente Blockentitäten, leuchtende Schichten
und Entitätsschatten noch offen. Die exakte Reihenfolge
für die Hand steht weiter unten. Beginne mit einem isolierten Test
für opake Handgeometrie und getrennte Tiefekopien.

0.19 zeichnet opake Blockentitäten über gbuffers_block. Der
BlockEntityRenderDispatcher liefert den Kontext, und ShaderBlockMaterials
liefert dieselbe ID wie das Terrain. Bei Truhen in Complementary ist es
5008. NativeEntityMesh.Batch trägt eine Kontextart pro Batch.
NativeEntityMesh schreibt Mob-ID in Spur 0 und Block-ID in Spur 1,
während die jeweils andere Spur 0 bleibt. Gemischte Batches bleiben im
Standardpfad. Der erste Live-Test ist die Truhe in der isolierten
Oberwelt. verify-0.19-block-entities-client.log und der Prüfbericht unter
dist/0.19.0-dev enthalten den Stand der GPU- und Paketprüfungen.
Der frühere Paketstand mit Mob-Tests in allen drei Dimensionen bleibt
unter dist/0.18.0-dev verfügbar.

0.18 bindet opake und Cutout-Mobs an das tatsächliche
gbuffers_entities-Programm. Der native VULKAN_ENTITY-Übersetzer nutzt die
vorhandenen 1.21.1-Drawmatrizen aus WorldUniforms ohne DynamicTransforms.
Die Vertexorte folgen dem erweiterten 56-Byte-Format und bleiben bei
inaktiven Attributen stabil. LevelRenderer.renderEntity setzt den
Materialkontext, BufferBuilderM speichert die IDs pro Vertex und
ShaderEntityMeshMixin reicht sie mit dem fertigen Mesh weiter.
NativeEntityMesh ergänzt den Polygonmittelpunkt und die Tangente.
Standardpuffer, gemischte Batches und nicht unterstützte Shader bleiben
auf dem vorhandenen Weg. EntityPackPlan liest entity.properties und
prüft die für diesen Einstieg implementierten Shaderressourcen.
Overlay ist der Minecraft-Slot 1, Textur Slot 0, Licht Slot 2. Der
Originalpfad für Schatten bleibt getrennt. Nether nutzt zusätzlich die
vorhandene Rauschtextur aus PackTexturePlan.

EntityContractGpuCheck prüft zwei IDs in einem echten Minecraft-Batch
und beide UV-Windungen bis zur GPU. verify-0.18-entity-dimensions.log
prüft das Originalpack mit DH und opaken Entitäten in den drei Dimensionen.
Prüfbericht und installierbares Paket unter dist/0.18.0-dev prüfen.
Der private Probeaufbau unter build/entity-contract-1.21.1 enthält den
Zwischenstand vor der Main-Branch-Integration und gehört zu keiner JAR.
Die früheren Entwicklungsstände unter dist/0.17.0-dev und dist/0.16.0-dev
sind verifiziert und bleiben verfügbar.

Für die Hand und gehaltene Items liefert die Iris-Referenz die Reihenfolge.
IrisRenderingPipeline.beginHand kopiert depthtex2 vor der Hand.
HandRenderer.renderSolid zeichnet vor Deferred mit Z-Skalierung 0.125.
beginTranslucents kopiert danach depthtex1 und startet Deferred.
renderTranslucent läuft nach der Welttransparenz vor Composite/Final.
GameRenderer.renderItemInHand enthält zusätzlich ScreenEffectRenderer.
Für eine native Implementierung nur die doppelte Handzeichnung
unterdrücken und die Bildschirmeffekte weiter ausführen. Die aus
Vanilla geprüften Sichtbedingungen (Egoansicht, GUI, Schlafen, Zuschauer,
Panorama) und NeoForge-Einstiegspunkte beibehalten. Die Vulkan-Projektion
zuerst in die Pack-Koordinaten umrechnen und die Z-Skalierung genau dort
anbringen. Welt- und Hand-Projektion getrennt prüfen. Keine Gleichheit der
beiden Tiefekopien als Erfolg akzeptieren. Entitätsschatten, weitere Packs,
Geräte und DH-Versionen einzeln testen. Keine ungetestete Kompatibilität behaupten.

0.17 liest native Ganzzahl-Farbpuffer über `isampler2D` und `usampler2D`.
ShaderChainPlan prüft bekannte Farbpufferformate schon beim Laden des Packs.
ChainGpuCheck liest in Deferred echte negative und vorzeichenlose Werte,
die Prepare gemeinsam mit einem Float-Ziel geschrieben hat. Prüfläufe,
Quellarchiv und geprüfte JAR stehen in dist/0.17.0-dev. Der letzte
Dimensionslauf steht in verify-0.16-complementary-client.log.

0.16 liefert `gl_MultiTexCoord1` und `gl_MultiTexCoord2` als dieselbe
Lichtmap-Eingabe im Vulkan-Terrainformat. NativeShaderChain unterstützt
programmweite Blend-Direktiven in den Vollbildphasen vor Final. Der
Diagnoselauf verify-0.16-compat-diagnostic-4.log prüft beide Lichtwerte
in vier Terrainvarianten, additive Mischung mit dem vorhandenen Puffer
und eine ungemischte RGBA8UI-Ausgabe im selben Pass wie ein Float-Ziel.
Den Spieltest der gebauten JAR und den Archiv-Neuaufbau im Bericht unter
dist/0.16.0-dev prüfen. Der frühere Stand liegt weiter unter dist/0.15.0-dev.

0.15 korrigiert die sichtbar falsche Nahwasser-Normale. Die
Originalpack-Vergleiche in packaged-water-pbuffer.log und
iris-water-pbuffer.log zeigten die Abweichung in colortex0 vor Composite.
Die anschließende lokale Diagnosekopie las Materialfarbe, Beleuchtung,
Reflexion und Normale separat. Bei VSDH war die Normale nach unten
gerichtet, bei Iris nach oben. Der A/B-Test unter
build/reference/release-client/packaged-water-trace-ccw.log belegt, dass
die Rückseitenausblendung die sichtbare Normale korrigiert und die
Beleuchtung dicht an die Iris-Referenz bringt.

WorldRenderer belässt jetzt bei aktivem Shaderpfad die normale
Minecraft-Cull-Einstellung für das transparente Terrain. Das gilt
bei eingeschalteter Rückseitenausblendung in den Vulkan-Einstellungen.
WaterFaceProbe in verify-0.15-water-diagnostic.log liest tatsächliche
Flächennormalen der Testszene. Weitere Spielbelege im Prüfbericht.
Testpack-Kopie und Diagnose-Mixins unter build/reference bleiben lokal.


0.14.0-dev war der erste Entwicklungsstand mit normalem Shaderstart.
Quell-Commit 284960f. dist/0.14.0-dev enthält die exakt im isolierten Profil getestete
JAR, Quellarchiv, Prüfsummen, Prüfbericht und Testlogs. 60 CPU-Tests,
build, verifyBundledLwjgl und Neuaufbau aus entpacktem Quellarchiv
bestehen. Die gebaute JAR wurde im getrennten NeoForge-Profil bei
2560×1440 mit aktiven Terrain-, Fernterrain- und Fernwasser-Draws geprüft.

Die experimentelle Shader-Auswahl erfolgt über config/vsdh.json.
ShaderPackStartup bereitet Pack und WorldPackSet asynchron vor und
installiert im Menü auf dem Renderthread. Ein leerer Name wählt den
Standardmodus. Die ZIP-Datei bleibt extern. verify-0.14-rejected-pack-2.log
prüft die Pfadablehnung. verify-0.14-configured-client.log bestätigt
Aktivierung, Oberwelt → Nether → Ende → Oberwelt, vorhandene Terrainpuffer,
zwei Rendergrößen, Partikel und Wetter sowie eine anschließende Welt ohne
Shader. Ein ungültiges Pack oder fehlende Daten immer aus dem Log klären.

Offen bleiben vollständige Entitäts-/Hand-Pässe, weitere Shaderpacks und
DH-Versionen. Die Nahwasser-Farbabweichung aus 0.14 ist oben eingegrenzt. Der Vergleich benutzt
Iris 1.8.12, Sodium 0.6.13, DH 3.3.3 und in beiden Fällen MEDIUM. Die
isolierten Projekte build/reference/iris-client und release-client
enthalten Wasserproben vor Composite, die in beiden Renderern denselben
Bildbereich aus colortex0 und colortex3 lesen. Prüfe die aktuellen Logs
packaged-water-pbuffer.log und iris-water-pbuffer.log auf die Befunde.

Für Entitäten liefert verify-entity-contract-2.log den SPIR-V-Audit von
Complementary MEDIUM. Aktive Eingaben enthalten echte EntityIds und
MidTexCoord. Tangenten sind im geprüften MEDIUM-Programm optimiert, können
bei anderen Einstellungen aktiv sein. Das übernommene ENTITY-Profil bindet
DynamicTransforms und of_CameraBob aus der 26.x-Vitrail-Umgebung.
Für 1.21.1 die tatsächlichen Draw-Transformationen und Overlaytextur
binden, feste Attributorte behalten und die IDs beim Meshaufbau erfassen,
damit verzögerte Minecraft-Batches den Entitätskontext behalten. Kein
Entitäts- oder Handprogramm als unterstützt ausgeben, bevor echte
Geometrie, IDs und Materialwerte im Spiel geprüft sind.


0.13 bereitet alle drei Vanilla-Dimensionen in WorldPackSet vor und tauscht
den aktiven Plan bei LevelRenderer.setLevel. Die Materialtabellen müssen
gleich sein, damit der Terrainstride und die Chunk-Builder beim Wechsel
gültig bleiben. Jede Welt erhält neue Vulkan-Ziele und frische Historie.
60 CPU-Tests, Build und Audit stehen in verify-0.13-build.log. Den
Spielstatus in verify-0.13-dimensions-client.log nachsehen. Der Lauf verwendet
-PsmokeDimensions=true zusätzlich zum echten Pack und DH.

0.12 ist mit Diagnose- und Originalpack, Weltwechsel, 59 Tests und einem
Neuaufbau des Quellarchivs geprüft. Commit d5a7670. Artefakte liegen unter
dist/0.12.0-dev. Regen und beide Partikelphasen bestehen im Originallauf.

Beim direkten MEDIUM-Vergleich unter Iris ist der Detailübergang auch
vorhanden. Das Nahwasser ist in VSDH messbar dunkler. Das Fernwasser ist
in den Stichproben nahezu gleich hell. Details und Prüfarchiv in
docs/DISTANT_PACK.md. Vergleiche die Near-GBuffer-Ausgaben und Reflexionen,
bevor die Pack-Mischung oder die DH-Tiefe weiter geändert wird.

Nächster Schritt nach einem erfolgreichen Dimensionslauf ist eine explizit
experimentelle Auswahl beim normalen Start. Packaufbereitung auf einem
Worker, Installation auf dem Renderthread bei ungeladener Welt und ein
Rückfall auf den Standardmodus bei fehlerhaften Packdateien sind Pflicht.
Entitäten und Pack-Hand bleiben eigene Geometriearbeiten.


0.11.0-dev ist ein vollständig geprüfter Zwischenstand. Commit
98c6537. Unter dist/0.11.0-dev liegen JAR, Quellarchiv, Prüfsummen und
verification.json. verify-0.11-complementary-client.log belegt Himmelskreis,
Sonne, Mond, Sterne, MC-Tiefe und beide DH-Tiefen bei 1280×720 und 480×270.
Der Diagnose-Test las den korrekten Himmelsfarbwert auf der GPU. 59 Tests
und der Offline-Neubau aus dem Quellarchiv bestanden.

0.12 ergänzt ParticlePackPlan und die gemeinsame
NativeGeometryPass für vorhandene Minecraft-Meshes. WorldShaderParticleMixin
kapselt die NeoForge-1.21.1-Aufrufe für Partikel vor und nach Deferred sowie
Regen und Schnee. auditWorldHost meldet je drei übersetzte Partikel- und
Wettervarianten und keine fehlenden aktiven Uniforms für Complementary.
Der Diagnoseclient in verify-0.12-diagnostic-3.log las 26.513 tatsächliche
Regenpixel aus RGBA16F und zählte 845 frühe und 279 späte Partikel-Draws.
Der Lauf mit dem Originalpack wird in verify-0.12-complementary-client.log
festgehalten. Prüfe Abschluss und PASS-Marker vor weiteren Änderungen.

Iris 1.8.12 und Sodium 0.6.13 wurden für die Wasserdiagnose in einem
isolierten Vergleichsclient unter build/reference/iris-client mit DH 3.3.3
eingerichtet. iris-reference-medium.log bestätigt MEDIUM ohne zusätzliche
Optionen. Der Prüfbericht enthält beide Ausschnitte und deren RGB-Mittelwerte. Quellordner build/reference,
fremde JARs und Shader-ZIP bleiben aus den VSDH-Artefakten heraus.

## Stand 0.11 am Vulkan-Renderer

0.11 ergänzt gbuffers_skybasic und gbuffers_skytextured über die
Minecraft-1.21.1-Aufrufe renderSky und renderEndSky. SkyPackPlan trennt
CPU-Übersetzung von Minecraft-Klassen. NativeSkyPass verwendet das
bereits hochgeladene VBO, feste Vulkan-Attributorte, die Pack-Mischregeln
und WorldUniforms.writeSky mit tatsächlichen Draw-Matrizen und Draw-Farbe.
Die Kamerahistorie bleibt getrennt. Testlauf verify-sky-world-1.log
zeichnet den Himmelskreis, Sonne, Mond und Sterne mit Complementary.
WORLD_SKY.md beschreibt die Umsetzung. Neuere Build- und Spielresultate
im letzten Prüfbericht und in den verify-0.11-Logs lesen.

GameRenderer.renderLevel löscht unmittelbar vor der Handzeichnung die
Welttiefe. WorldShaderGameMixin schließt Composite/Final jetzt vor diesem
Clear ab. RETURN bleibt für Frames ohne Hand-Aufruf. Die Vanilla-Hand
wird als Overlay nach Final gerendert. NearWorldDepthProbe prüft die
tatsächlich gefüllte Tiefe auf der GPU. Erst ein echter Pack-Handpass
macht depthtex1 und depthtex2 zeitlich verschieden.

Als Nächstes den sichtbaren Wasserübergang mit der korrigierten Tiefe
erneut messen. Für Wetter und Partikel nutzen LevelRenderer.renderSnowAndRain
und ParticleEngine.render bei NeoForge 1.21.1 denselben PARTICLE-Vertexaufbau,
aber unterschiedliche Texturen und Phasen vor bzw. nach Deferred.
Danach Entitäten und Hand, dimensionenspezifische Laufzeit und Auswahl.
Keine öffentliche Zusage für ungeprüfte Kombinationen.

## Stand 0.10 an DH

0.10.0-dev zeichnet mit -PsmokeLivePack=true -PsmokeDistantPack=true die
originalen Programme dh_terrain und dh_water aus Complementary Reimagined
r5.9.3 MEDIUM. verify-0.10-dh-client.log belegt GPU-Rücklesen beider
DH-Tiefenbilder bei 1280×720 und 480×270 sowie die Mischregeln pro Vulkan-Ziel.
Der sichtbare Übergang von Nah- zu Fernwasser bleibt offen. Details zu
Mischregeln und Tiefenvertrag stehen in docs/DISTANT_PACK.md.

NativeDistantTerrain sammelt die bestehenden DH-3.3.3-Vulkan-Puffer ohne
neuen Upload. Opak zeichnet vor dem Minecraft-Terrain. Frühe Wasserbefehle
werden erst nach Deferred ausgeführt. dhDepthTex1 enthält die opake Kopie.
DH-Wasser muss diese Kopie auch lesen, wenn es sie dhDepthTex nennt, um
Feedback auf der Vulkan-Tiefenanlage zu vermeiden. Außerhalb der DH-Pässe
bleibt dhDepthTex die vollständige DH-Tiefe. verify-dh-world-1.log belegt
den verhinderten Feedback-Fehler. Die Kamera-Tiefe von 0.9 und DHs eigene
GL-Projektion sind verschiedene Verträge. DHs Projektion nicht doppelt
nach GL konvertieren. DISTANT_HORIZONS ist pro Pack-Lesung gesetzt und
muss bei Expansion, finalem GLSL und Cache-Schlüssel identisch sein.

Arbeite direkt unter im Projektordner weiter, auf Deutsch.
Der Nutzer verlangt die Vervollständigung von Vulkan + Shadern + Distant Horizons.
Das Gesamtziel ist noch NICHT erreicht. Keine erneute Grundsatzfrage Addon/Fork,
keine Zusage perfekter Unterstützung sämtlicher Packs oder DH-Versionen.

Lies AGENTS.md, docs/HANDOFF.md, docs/WORLD_SHADOWS.md und den neuesten Bericht
unter docs/verification/. Prüfe git status, git log und die aktuellen Logs, bevor
Änderungen erfolgen. Branch: codex/renderer-shaders. Ausgangs-Commit vor der
Schatten-/Texturarbeit: 4bb9292. Neueren Commit selbst ermitteln; nicht zurücksetzen.

Zielversionen: Minecraft 1.21.1 / NeoForge 21.1.264 / Java 21 / DH 3.3.3.
Erstes festgelegtes Pack: Complementary Reimagined r5.9.3 MEDIUM, Original-SHA1
838139b54cddb56b2e83cd260d8efd960ac536d6. RTX 4070 unter Windows wurde geprüft.
Die eine VSDH-JAR enthält vsdh und vulkanmod. Original-Reforged, Iris und Sodium
nicht zusätzlich installieren. Die Shaderfunktionen werden nativ implementiert.

Was jetzt tatsächlich vorhanden ist:
- Echter 44-Byte-Chunk-Vertexpfad samt Block-/Fluidmaterial, Normalen/Tangenten,
  AO/Licht und Indexed Draws; Standardmodus nutzt weiter 20 Byte.
- NativeShaderChain mit begin/prepare/deferred/composite/final, MRTs, HDR/Mips,
  Frame-Historie und Tiefenkopien; WorldShaderSession als expliziter MainPass-Host.
- Aktive Uniform-Member werden aus optimiertem SPIR-V über std140-Byteoffsets
  erkannt. Unklare Pointerpfade bleiben konservativ. Keine Null-Pflichtdaten.
- Echte Welt-/Kamera-/Augen-/Effektwerte, Hand-IDs aus item.properties inklusive
  Block-State-Licht, Biome, Wetness und LightTexture-Darkness-Erfassung.
- PackExpressions nutzt portablen Vitrail/StarEval-Code mit kleinen VSDH-Adaptern.
  Formeln und Abhängigkeiten werden einmal pro Frame ausgewertet, Glättung ist
  sitzungseigen. Fehlende/zyklische Formeln bleiben diagnostiziert; NaN/Inf wird
  abgewiesen. Nicht aktive End-Flash-Ausdrücke neuerer MC-Versionen bleiben offen.
- NativeWorldShadows zeichnet tatsächlich hochgeladene Chunks, auch außerhalb des
  Spielerblicks, über separate Queues. shadowtex1 ist opak, shadowtex0 enthält
  Transparenz; Schattenfarben und Sonnen-/Mondmatrizen sind echt. Noch keine
  Schatten für Entitäten/Blockentitäten/DH. Alle geladenen Chunks werden derzeit
  konservativ gezeichnet; Schatten-Depth-Mips/Perspektivkameras fehlen.
- PackTexturePlan dekodiert PNGs auf dem Worker, erhält rohe RGBA-/Graustufenbytes
  und behält keine ZIP-Handles. NativeWorldTextures lädt über Staging hoch.
  Stage-Texturersetzungen und Buffer-Flips werden respektiert. Echte GBuffer-
  Eingaben im Terrain folgen dem Deferred-Punkt. Raw-/16-Bit-PNG und weitere
  Minecraft-Ressourcen außer dem geliehenen Blockatlas fehlen.
- entityId=0 gilt ausdrücklich nur im Terrain-/Terrain-Schattenkontext (keine
  Entity). Das ersetzt keine echten IDs der noch fehlenden Geometriefamilien.

Sehr wichtiger gefundener Projektionsfehler, nicht erneut einbauen:
Reforgeds Matrix4fM erzwingt bei perspective/setPerspective ohne boolean Vulkan-
Tiefe 0..1. Der alte Host behandelte diese Matrix irrtümlich als GL -1..1.
ForwardDepthProjection.toLegacy verwendet jetzt rowZ = 2*rowZ - rowW vor der
Veröffentlichung an Shader. Die Vertex-Epilog-Konvertierung .5z+.5w erfolgt genau
EINMAL. GameRendererMixin behält die unendliche Fernprojektion nur im Standardmodus;
bei installiertem Shaderhost gilt die tatsächliche Minecraft-Fernweite
getRenderDistance()*4. Die JOML-Infinity-Epsilon-Projektion rekonstruiert bei
Sky-Tiefe 1 Punkte hinter der Kamera. Dies ließ Complementary Wolken über Wasser
statt im Himmel berechnen. Native GPU-Instrumentierung belegte vorher Blick-Y
+0.871, danach korrekt -0.871 bei nach unten blickender Kamera. Wolkenhöhe 192
war stets korrekt. Legacy-Matrix-Y wird für native Bilder als ZEILE umgekehrt.
Shader-Direktiven clouds=off/fast/fancy werden während der Sitzung beachtet, ohne
Benutzereinstellungen zu speichern. scene() stellt Vanilla-Fallback nur Ausgabe 0
bereit, auch wenn das echte gbuffers_basic mehrere MRTs beschreibt.

Nachweise:
- 56 CPU-Tests bestehen, einschließlich Packformeln, PNG-Rohwerte, Schattenkamera
  und reproduzierter Infinity-/Tiefenumrechnungsfehler.
- verify-0.7-client.log: Formeln/Handlicht/Regen in zwei Welten.
- verify-0.8-client.log und verify-0.9-client.log: echte Schatten und PNG-/Noise-
  Farbassertionen, alle bisherigen GPU-Gruppen je viermal, 100%/50% Scale.
- verify-realpack-world-7.log: tatsächliches Complementary auf realer Welt,
  Schatten, Packtexturen, Resize/Scale und Rückwechsel in den Standardmodus.
  REAL PACK CLOUD GPU VALUES prüft die tatsächliche Shader-Blickrichtung.
- verify-0.9-final-client.log ist der vollständige Diagnose-Regressionslauf NACH
  der Tiefenkorrektur. Neuesten Build-/Archivbericht tatsächlich kontrollieren.
- Ältere realpack-world-2/3/4/6-Läufe hatten zwar nichtschwarze Bilder, aber den
  sichtbaren Wolkenfehler. Diese Marker NICHT als korrekte Packgrafik ausgeben.
  realpack-world-5 scheiterte an einer fehlenden Diagnose-Samplerbindung; behoben.
- NativeWorld-Code ist physisch Client-only. Dedicated Server und Multiplayer
  sind nicht im Spiel geprüft. Produktions-JAR nicht separat im Launcher getestet.

Was zur Vervollständigung konkret noch fehlt:
1. Shaderseitige DH-Integration: DISTANT_HORIZONS bleibt absichtlich UNDEFINIERT,
   bis echte dh_terrain/dh_water-Programme und getrennte DH-Tiefen/Matrizen laufen.
   Der bestehende native DH-Composite zeichnet spät ins Szenenbild. Das ist noch
   keine Pack-DH-Unterstützung. Im echten Packbild bleibt ein auffälliger heller
   Übergang im Wasser. Ursache zuerst gezielt z.B. mit/ohne DH vergleichen und
   anhand von Tiefen/Materialdaten nachweisen, nicht wieder auf Verdacht ändern.
2. Übrige Geometriefamilien: Himmel, Sonne/Mond, Entitäten, Blockentitäten, Partikel,
   Wetter und Hand laufen bisher über Vanilla-Fallback in Farbausgabe 0. Sie
   schreiben keine vollständigen Pack-Materialdaten. NativeGraphicsProgram bindet
   bislang FULLSCREEN oder VULKAN_TERRAIN. Für generische Draws die tatsächlichen
   Vanilla-Vertexformate und Matrizen aus VShaderInstance/Drawer/VBO übernehmen,
   scoped Entity-/Item-/BlockEntity-IDs aufbauen und echte Packprogramme wählen.
3. Pack-Blend-/Cull-Regeln pro Attachment, richtige Handreihenfolge und getrennte
   depthtex1/2-Zeitpunkte. Derzeit beide Kopien vor Transparenz. Weiterhin fehlen
   Dimensionwechsel im aktiven Host, Origin-Rebasing für float-cameraPosition,
   vollständige Ressourcen-/Featureabdeckung und danach Auswahl/Hotreload.
4. Compiler nutzt LocalZeroes derzeit noch nicht. Bei Nachweisen undefinierter
   Pack-Lokaler die vorhandene portable Implementierung mit GPU-Tests prüfen,
   nicht blind zuschalten. Direkt als Shaderuniform verwendete variable.-Einträge
   brauchen bei Bedarf noch Wurzeln aus aktiven Programmlesezugriffen.

Keine öffentliche vollständige Shader-Auswahl oder automatische Aktivierung als
fertig ausgeben. Nichtschwarze Pixel und Compiler-Erfolg beweisen keine komplette
Kompatibilität. Zwischenstände erhalten; keine alten dist-Verzeichnisse ersetzen.

Bauen: gradlew.bat build verifyBundledLwjgl
Audit: gradlew.bat auditWorldHost -PshaderPack=<absoluter Pack-ZIP-Pfad>
Diagnosewelt: gradlew.bat runSmokeClient -PsmokeShaderPack=<absoluter Pack-ZIP-Pfad>
Echtes Pack: zusätzlich -PsmokeLivePack=true
Alle Smoke-Änderungen betreffen frisch erstellte isolierte Welten. Bestehende Welten,
Inventare und gespeicherte Benutzereinstellungen schützen. GPU-Arbeit nur auf dem
Renderthread, Freigaben verzögert. Keine blockierende Dateioperation pro Frame.

Referenzen liegen privat außerhalb des Projekts unter
einem lokalen Referenzordner außerhalb des Repositorys.
Pins und Lizenzen stehen in AGENTS.md, NOTICE und docs/upstream. Keine Original-
Packdateien, übersetzten Packquellen oder privaten dekompilierten Referenzen in
Lieferungen übernehmen. Vor dem Ende HANDOFF, Prüfbericht und diesen Prompt auf
den tatsächlich geprüften Stand bringen. Weiter implementieren und testen.

## Stand 2026-11-11 (Kompatibilitätsschritt 0.26)
- Neue Fähigkeiten und Grenzen: docs/NATIVE_PACK_FEATURES.md. Status: docs/MIGRATION_STATUS.md oben.
- Alle 14 Testpacks in build/reference/packs bestehen die CPU-Vorbereitung mit MEDIUM und Standardprofil
  (run-vsdh-026-pack-audit.ps1 -Profile 'MEDIUM' bzw. ''), 85 Unit-Tests. Der CPU-Audit baut keine
  GPU-Programme: Laufzeitprüfungen (Stufen, Bildnamen) zeigen sich erst im Spiel.
- Im Spiel gegen Iris (build/reference/compare-026; run-vsdh-026-compare.ps1 -ReuseIris [-Only ...],
  run-vsdh-026-single.ps1 -Pack X [-Dump programmEndung] [-ShotTick 1600]; pair.ps1 im Scratchpad):
  Photon, Eclipse Unstable, Bliss, Complementary R/U, Miniature gleich; Solas, Rethinking Voxels nahe.
  BSL: Volumenwolken aus deferred1 fehlen (Uniforms per Dump plausibel; als Nächstes Zwischenbilder
  von deferred1 per Frame-Observer oder GPU-Readback prüfen). DH-Landschaft wird in frischen Welten
  langsam erzeugt: fehlende ferne Inseln bei Tick 800 sind kein Renderfehler (ShotTick 1600 nutzen).
- Bekannte Lücken: Entity-Schattenwerfer mit Geometry-Stufe (entityId/entityColor im GS ohne Array),
  typisierte Custom-Image-Views, indirect.*, shadowcolorimgN, shadowcomp-Vollbildprogramme.
  Laufzeitfehler beim Zeichnen (nicht beim Graphaufbau) beenden das Spiel noch.
- Vor einer Veröffentlichung: Smoke (run-vsdh-026-smoke.ps1), Release-Matrix, Benchmark erneut, dist-Paket.
- Review durch Fable-Subagent (11.11.): umgesetzt sind Barrieren mit ALL_GRAPHICS|COMPUTE, drei
  Frame-Phasen-Barrieren in WorldShaderSession.storageBarrier (nach Schatten, vor DEFERRED, vor COMPOSITE),
  Layout im ImageDescriptor.State (Layoutwechsel schreibt Deskriptor neu), Entfernen pack-eigener
  binding=/set= bei Samplern/Bildern. Die DhApi-renderingEnabled-Abschaltung wurde wieder entfernt
  (Iris macht das nicht; AstraLex/Insanity-Kante bleibt offen: DH-Engine zeichnet dort bereits nichts,
  frameReady=false; Ursache in Sky/Fog des Packs suchen).
- Offen aus dem Review: (1) Frame-Guard gegen Laufzeitfehler beim Zeichnen (Entwurf: alle per-Frame-
  Einstiege in WorldShaderSession über guarded(...), abortFrame: endRenderPass, active=false, delegate
  rebinden, Graph erst im nächsten beginWorld freigeben); (2) robustere Umbenennung (auf vorverarbeitetem
  Text, `out flat`, `.name` ausschließen) und Prüfung doppelter Input-Locations nach linkStages;
  (3) Barriere nur bei schreibenden SSBO-Programmen (NonWritable erkennen); (4) Validierungslauf mit
  VK_LAYER_KHRONOS_validation.
- BSL-Wolken: Debugbild zeigt, dass fernes DH-Wasser unter VSDH nicht nach deferred1 über dem Bild liegt
  (unter Iris schon). Prüfen: Reihenfolge dh_water vs. deferred (NativeDistantTerrain/flushDistantDraws)
  und dhDepthTex0-Inhalt am Himmel (BSL: isSky = z==1 && dhZ==1).

## Stand 2026-11-11 nachmittags (Fortsetzung 0.26)
- Lies zuerst docs/MIGRATION_STATUS.md (oberster Abschnitt) und docs/PERFORMANCE.md.
- Erledigt und im Spiel geprüft: DH-Generic-Objekte (VkGenericRenderer/VkGenericObjectVboContainer,
  NativeDistantTerrain.enqueueGeneric, DistantPackPlan.generic/clouds, ChainPlan dh_generic),
  SkyHorizon (AstraLex/Insanity), near aus der Projektion, Frame-Guard, Uniform-Cache (PER_DRAW).
- Testclient-Falle: DH-Konfiguration nie aus Code schreiben. Alle Skripte setzen rendererMode zurück;
  Bench-Zusammenfassung nennt "DH land draws" — 0 bei DH-Packs heißt: Lauf ungültig.
- Werkzeuge: run-vsdh-026-bench.ps1 [-HighResolution] (Packnamen ohne "dbg"), run-vsdh-026-bench-noshader.ps1,
  run-vsdh-026-profile.ps1 -Pack X [-NoJfr] (GPU_PROFILE + JFR; Render-Thread-Wartezeit nur in
  jdk.NativeMethodSample sichtbar), run-vsdh-026-single.ps1 -Pack X [-Extra '-PtestGenericTrace'].
  Debug-Packs nur befristet nach build/reference/packs legen (Präfix-Kollision mit echten Packs).
- Nächste sinnvolle Schritte: (1) GPU-Abstand zu Iris mit Shaderpack: gleiche Szene in RenderDoc/Nsight
  für Iris und VSDH, teuerste Pässe (Bliss composite3, dhWater, translucent) vergleichen; (2) BSL-Inselbäume;
  (3) DH-Wolken im Standardrenderer im Bild prüfen; (4) Validierungslauf mit VK_LAYER_KHRONOS_validation.
- Fable-Subagent (read-only) ist für schwierige Analysen fortsetzbar; er liest JARs nur per javap.

## Stand 2026-11-13 vormittags (Leistung, `dist/0.26.0-dev-perf`)
- Nutzerwunsch: „komplett ohne Hänger und mehr FPS ohne Grafikverlust“. Gemessen: spürbare Hänger im Flug
  1–3 je 30 s (G1-Pause ≈ 25 ms, Present-Wartezeit im Fenster 26–53 ms). Allokationsfixes (Weltgen-Schleifen,
  DH-Warteschleife) wirken auf Speicher, nicht messbar auf FPS/Hänger. Java-Optionen: keine Empfehlung.
- GPU gleicher Flug: VSDH 4,51 ms, Iris ≈ 4,9 ms. Offen: Complementary `composite1` (+0,2 ms) und
  `deferred1` samt Mip-Abschnitt (+0,15 ms) gegenüber Iris — nächster sinnvoller Leistungsschritt, z. B.
  mit Nsight/RenderDoc denselben Pass in beiden Clients vergleichen (Registerdruck, Sampler, Präzision).
- Weitere Hänger-Quelle: Present im Fenster (D3D12-Interop). Im Vollbild nach dieser Änderung nicht gemessen.
- Offene Nutzerfragen: DH-Schalter umbenennen + Tooltip erweitern (nur Sprachdateien); NeoForge-Pin A/B.
- Nachtrag 13.11. mittags: Vollbild gegen Iris gemessen (PERFORMANCE.md). Lange VSDH-Frames = G1 (Remark mit
  Class Unloading 10–32 ms) + Treiber-Present 20–25 ms. Spieler-Empfehlung `-XX:-ClassUnloadingWithConcurrentMark`
  (Remark ≈ 1 ms). Offen: einzelne nicht zugeordnete Render-Thread-Spitzen (z. B. 42,8 ms), Iris mit derselben
  Option, GPU-Abstand composite1/deferred1.
- Nachtrag 13.11. nachmittags: Lange Vollbild-Frames sind meist Treiber-Present (20–25 ms). FPS-Grenze 160 bei
  165 Hz: VSDH 0 Frames > 25 ms (Iris 2–3). Testclients kennen `-PtestFlightFps=<n>` und `-PtestJfrSettings=<jfc>`
  (1-ms-Sampling hilft unter Windows kaum). GPU-Abstand composite1/deferred1: ohne Nsight/RenderDoc keine Spur;
  Download nur mit Erlaubnis des Nutzers.

- Nachtrag 13.11. abends (nur kompiliert, nicht im Spiel getestet): GL-Emulation für Mod-Texturen –
  `GlStateManager._texSubImage2D` und `_pixelStore` waren leere Overwrites (Raffman's Detailed Map lud so keine
  Kacheln), `glTexSubImage2D` schreibt jetzt in die angegebene Mip-Stufe, `AbstractTexture.setFilter` behält ein
  ausdrücklich gesetztes CLAMP_TO_EDGE. Offen: welches Menü der Nutzer meint; Jurgens-Shader CINEMATIC scheitert an
  der festen 512-MiB-Schattengrenze (`NativeWorldShadows`); ImmediatelyFast lief mit dem Vulkan-Renderer ohne Warnung.

- Nachtrag 13.11. spät (kompiliert, 96 Unit-Tests, nicht im Spiel): GUI-Zustandsgrenze für Gegenstände/Figuren schließt
  an `GuiGraphics.flush` und wird je Frame verworfen (`GuiDeferredDrawState`), damit ImmediatelyFasts eigene
  BufferSource sie nicht mehr übergeht (Esc-Menü zeigte nur „Zurück zum Spiel“). Weltfiguren zählen während
  `GameRenderer.renderLevel` nicht als GUI-Vorschau. JARs: `dist/0.26.0-dev-compat`.

- Nachtrag 13.11. nachts (kompiliert, 107 Unit-Tests, `verifyDhLinks` PASS für 3.2.0-b/3.3.0–3.3.3, NICHT im Spiel):
  Eine JAR für alle DH-Versionen (verschachtelter Coremod `src/dh330` passt VSDH nur bei DH 3.3.0 beim Laden an,
  `DhSupport` schaltet den Adapter bei Unstimmigkeit ab; `jarDh330`/`RemapDh330` entfallen). GL-Emulation: 7 Aufrufe
  nachgerüstet, ~500 nicht emulierte melden sich einmal (`GlEmulationLog.unimplemented`). Hinweis beim Start für nicht
  voll unterstützte Mods (`UnsupportedModNotice`, nichts gesperrt). Release-Matrix mit der Einzel-JAR steht aus.

- Nachtrag 14.11. (kompiliert, 110 Unit-Tests, nicht im Spiel): Schattenbudget richtet sich nach dem Grafikspeicher
  (`NativeWorldShadows.shadowBudgetBytes`: 1/6 des Geräte-Heaps, 512 MiB bis 3 GiB; vorher fest 512 MiB, Jurgens
  CINEMATIC brauchte 1 GiB). Hinweis: Minecrafts „Chunk-Aktualisierung“ (prioritizeChunkUpdates) hat im VulkanMod-
  Renderer keine Wirkung; Spike-Zeilen „build“ sind Worker-Zeit, nicht Render-Thread. Die Hänger im Monkeytalk-Pack
  (25–110 ms ohne zugeordnete Ursache) sind noch nicht profiliert.

- Nachtrag 14.11. nachmittags (kompiliert, 111 Unit-Tests, verifyDhLinks PASS, nicht im Spiel): Review-Funde behoben –
  `glTexSubImage2D` überspringt nicht umrechenbare Uploads mit Warnung statt Absturz, Region auch für Stufe 0 geprüft;
  Weltpass als Zähler (verschachteltes renderLevel); `Dh330Mapping` ignoriert Strings, die keine Deskriptoren sind;
  Schattenbudget höchstens 3/4 des freien VMA-Heaps (mind. 512 MiB); keine Warnung mehr für MRT-glDrawBuffers in Reihenfolge.
  Sodium bleibt inkompatibel (OpenGL-4.5-Renderer, GLFW_NO_API-Fenster, Polyform-Shield-Lizenz); Iris-Koexistenz gestrichen.

- Nachtrag 14.11. abends (kompiliert, 111 Unit-Tests): zwei Allokationen pro Aufruf entfernt (Warntext, glDrawBuffers(int[])),
  GUI-transformierte Gegenstände im Weltpass öffnen keine GUI-Zustandsgrenze mehr (`ItemRendererM`).
  Fable-Vorschlagsliste (Rangfolge): Pack-JAR tauschen und testen; Spark-/JVM-A/B; Schattenauflösung bei Budgetüberschreitung
  halbieren statt Pack abschalten; Einzel-JAR-Matrix; Statusblock auf der Shader-Seite; GL-Lücken je Mod (Aufrufer);
  Smoke-Tests gängiger GL-Mods (Flywheel/Veil brauchen GL 4.x); Jurgens in Audit/Smoke; NeoForge-Pin weiten; DH-Adapter-aus
  sichtbar machen; Kamera-Frame beim Welteintritt; Build-ID im Log, eine dist pro Release, englische README.

- Nachtrag 14.11. abends (kompiliert, 114 Unit-Tests, nicht im Spiel): Passt die Schattenkarte nicht ins GPU-Budget, bereitet
  `ShaderPackStartup` das Pack mit dem nächstkleineren eigenen Wert von `shadowMapResolution` neu vor (Werteliste des Packs,
  `ShadowResolutionFallback`), statt es beim Welteintritt abzuschalten; Toast „Schatten verkleinert“, gespeicherte Optionen
  bleiben unverändert. Schätzung ohne Gerät: `NativeWorldShadows.estimateBytes`. Prüfung beim Welteintritt (freier Speicher) bleibt.

- Nachtrag 14.11. abends (kompiliert, 114 Unit-Tests, nicht im Spiel): Borderless Window macht aus dem exklusiven Vollbild ein
  gleich großes randloses Fenster, ohne Größenereignis; die Swapchain blieb auf dem Vollbild-Pfad (Vulkan statt DXGI-Flip).
  `SwapChain.windowModeChanged()` + Prüfung in `Renderer.beginFrame` bauen sie dann neu. Solas und Eclipse Unstable in den
  shaderpacks-Ordner von Monkeytalk kopiert (nicht ausgeliefert, nur lokal).

- Nachtrag 14.11. abends (kompiliert, 118 Unit-Tests, NICHT im Spiel): Shader-Wechsel in der Welt (`WorldShaderSession.replaceInWorld`):
  Chunk-Builder stoppen, Terrain-Vertexformat unter `PipelineManager.runTerrainSwitchInWorld` tauschen, alte Session freigeben,
  neue für die aktuelle Dimension installieren, `WorldRenderer.allChanged` baut alles neu. `-Dvsdh.liveShaderSwitch=false`
  stellt das alte Verhalten (erst nach Verlassen) her. Fehlt die eingestellte Pack-Datei, nimmt `PackFileFallback` die
  neueste Version desselben Packs (gespeicherte Wahl bleibt), Toast „Andere Pack-Version verwendet“. Tooltips angepasst.

- Nachtrag 14.11. spät (Fable; kompiliert, 124 Unit-Tests, verifyDhLinks PASS, NICHT im Spiel): JFR des Monkeytalk-Packs:
  60 % der Render-Thread-Zeit ist Client-Tick, `BiomeManager.getBiome` allein 34 % (Subtle Effects, Raffman's Map, Vanilla).
  Neu: `BiomeCornerCache`/`BiomeManagerCornerMixin` merkt sich auf dem Render-Thread die Zellwahl je Position
  (`-Dvsdh.biomeCornerCache=false`). Fehler behoben: unbegrenzter View-Cache im Shader-Modus (`NativeShaderChain` geometry/scene
  erzeugten je Aufruf eine neue Liste), veraltete Schatten-Draw-Listen nach `allChanged`, Rollback beim Live-Wechsel, Schatten-
  Fallback beim Spielstart vor dem Gerät (`ShadowResolutionFallback.needsRecheck`). Weniger Allokation: PipelineState.hashCode,
  NativeBlend, Schatten-Samplernamen ohne Regex. Harness `vsdh.releaseQA.worldSwitch` erwartet noch „wirkt beim Verlassen“.

- Nachtrag 14.11. spät (kompiliert, 124 Unit-Tests, verifyDhLinks PASS, nicht im Spiel): Mod-ID des mitgelieferten Renderers
  `vulkanmod` → `vsdh_renderer` (`Initializer`, mods.toml, CORE_MOD_IDS); das echte VulkanMod ist als `incompatible`
  eingetragen. Keine der 127 anderen Mods im Monkeytalk-Pack nennt die ID `vulkanmod`. Testclient 026-packs prüft `vsdh_renderer`.

- 14.11.2026: Öffentliches Repository https://github.com/raffman180/VSDH (LGPL-3.0, Standardzweig `main` = Stand 258f0f3 von
  `codex/renderer-shaders`). Lokaler Zweig verfolgt `origin/main`. Noch keine CurseForge-Veröffentlichung; vorher Spieltests,
  Version/Name ohne „dev“ und NeoForge-Bereich prüfen.

- 14.11.2026 abends: Veröffentlichungs-Vorbereitung 0.27.0-beta (kompiliert, 124 Unit-Tests, verifyDhLinks PASS, NICHT im Spiel):
  Version `0.27.0-beta`, Name „VSDH“, Logo (`vsdh_logo.png`, Projekt-Icon `docs/curseforge/icon-400.png`), Links auf GitHub,
  `credits` in mods.toml, englische Projektbeschreibung `docs/curseforge/DESCRIPTION.md`, `CHANGELOG.md`, `dist/0.27.0-beta`
  mit SHA256SUMS. Frame-Diagnosezeilen nur noch mit `-Dvsdh.chunkDiag=true` (Testclients setzen es). Testskripte auf
  0.27.0-beta umgestellt. Vor dem Upload offen: Release-Matrix/Smoke/Menü-/Weltwechsel mit der Einzel-JAR, NeoForge-Bereich.

## Tick-Frames und Uniform-Wege (14.11.2026 nachts, Fable) – im Spiel noch zu prüfen

Nur aus Code und den vorhandenen JFR-Aufnahmen gearbeitet (Spieltests waren untersagt). Neu und ergebnisgleich:
`BiomeFiddleCache` (`-Dvsdh.biomeFiddleCache=false`), `SubImageRange` in `VTextureSelector.uploadSubTexture`
(`-Dvsdh.tightSpriteUpload=false`), `UniformRoutes` in `WorldUniforms.write` (`-Dvsdh.uniformRoutes=false`).
Build, 138 Tests und `verifyDhLinks` grün. Nächste Schritte, sobald der Nutzer Tests freigibt:
1. `run-vsdh-026-flight.ps1` in Paaren je Schalter (Mediane, Frames > 16,7 ms; im 128-Mod-Pack mit `-Dvsdh.flight.turn`).
2. Animierte Texturen ansehen (Wasser, Lava, Feuer, Portal, Mod-Sprites, interpolierte Sprites) mit
   `tightSpriteUpload` an und aus; Smoke-Lauf mit Validation-Layern, falls das SDK da ist.
3. `zzUniformProbe.zip` gegen Iris mit `uniformRoutes` an und aus (gleiche Werte erwartet).
4. Kandidaten aus `docs/PERFORMANCE.md` (Ende): Himmel-/Nebelfarbe je Frame merken, sichtbare Sprites, Schattentest.

## Flackern in der Ferne (14.11.2026 nachts, Fable) – im Spiel noch zu bestätigen

Nutzer meldete mit der Abend-JAR „alles weiter weg flackert etwas" (Eclipse Unstable, DH 3.3.3, 1440p). Per
Code-Analyse (kein Spieltest erlaubt) der DH-Tiefen-Feedback-Schleife zugeordnet: `dh_water` schreibt Tiefe und
Eclipses Spiegelungs-Marsch liest `dhDepthTex` an anderen Pixeln desselben Passes, ohne Barriere dazwischen
(undefiniert, auch im Feedback-Layout). Standard ist wieder die opake Kopie; `-Dvsdh.dhDepthFeedbackLoop=true`
bindet die Live-Tiefe. Farb-Schleifen und Schattenliste bleiben an. Stand: kompiliert, 133 Unit-Tests,
`verifyDhLinks` PASS; die Dist-JAR in `dist/0.27.0-beta` ist NICHT neu gepackt.

Sobald der Nutzer Spieltests freigibt (Memory: keine Testclients, solange er spielt):
1. Eclipse, DH-Wasser am Horizont und der Übergang nah/fern: ruhig? Log muss `DH depth read from the opaque copy
   (dhDepthTex in [dh_water])` zeigen und keine Zeile `DH depth feedback loop`.
2. Bleibt Flackern: `-Dvsdh.feedbackLoops=false` (Farb-Schleifen aus), dann `-Dvsdh.shadowSectionList=false`, je
   einzeln; `=verify` darf kein `SHADOW_LIST mismatch` loggen. Jurgens-Shader hat gar keine Schleife (weder Farbe
   noch DH-Tiefe): flackert es dort ebenfalls, liegt es nicht an den Schleifen.
3. Gegenprobe `-Dvsdh.dhDepthFeedbackLoop=true`: kommt das Flackern zurück, ist die Ursache bestätigt.
4. Eine korrekte Live-Tiefe bräuchte Selbstabhängigkeit des Subpasses plus Barriere (BY_REGION | FEEDBACK_LOOP)
   zwischen den Draws und hilft nicht gegen Überdeckung innerhalb eines Draws: nicht weiterverfolgen, solange der
   Gewinn (Obergrenze ≈ 0,14 ms) so klein ist.

## Offene Läufe nach den Feedback-Schleifen (14.11.2026 abends, Fable)

Code steht (kompiliert, 129 Unit-Tests, `verifyDhLinks` PASS); Stand und Zahlen in `docs/PERFORMANCE.md` (Ende).
Der Nutzer spielte (Rocket League, dann CS2), deshalb fehlen diese Läufe. Vor JEDEM Lauf prüfen: kein `javaw.exe`
mit „PrismLauncher“, kein `cs2`/`RocketLeague` oder anderes Spiel; läuft eines an, Client sofort stoppen und das
Ergebnis verwerfen. Jeder Lauf mit Wachhund (Log länger als 6 Minuten still → stoppen), nie zwei Clients.

1. Bildvergleich gegen Iris: `run-vsdh-026-compare.ps1 -Only <Pack>` für Eclipse, ComplementaryReimagined, photon,
   BSL; dazu je ein Lauf mit Kopien: `run-vsdh-026-single.ps1 -Pack <Pack> -Tag fbcopy -Extra
   '-PtestSysProps=vsdh.feedbackLoops=false,vsdh.shadowSectionList=verify'`. Auf Wasser, Nah/Fern-Übergang,
   Transparentes und Spiegelungen achten; mehrere Läufe, die Szene schwankt (Iris gegen Iris ≈ 7,6 mittlere Abweichung).
   Logzeilen: `Attachment feedback loops (colortex sampled while drawn into): {…}` nennt die Programme je Pack,
   `SHADOW_LIST mismatch` darf nicht erscheinen.
2. GPU je Pass für ein zweites Pack mit Schleifen (laut Logzeile wählen), je 2 Läufe mit und ohne
   `vsdh.feedbackLoops=false`: `run-vsdh-026-profile.ps1 -Pack <Pack> -Tag fbA1 -NoJfr -Extra '-Ptest1440p',
   '-PtestSysProps=vsdh.gpuProfileFeedback=true'`. Eclipse wiederholen und klären, warum auch `composite`,
   `composite3`, `composite4` mit Schleifen schneller waren; einen Iris-Lauf derselben Sitzung dazunehmen
   (`run-iris-gpu-profile.ps1 -Pack Eclipse -HighResolution`).
3. `run-vsdh-026-release-matrix.ps1` einmal; danach Smoke, Menü- und Weltwechsel wie vor jeder Veröffentlichung.
4. Schattenliste im großen Pack messen (`-Dvsdh.shadowSectionList=verify` loggt `walkUs`/`listUs`).
5. Ohne Validation-Layer geprüft: bei installiertem Vulkan-SDK einmal mit Layern starten (Feedback-Layout, Flags).

- 14.11. spät: Farb-Feedback-Loops standardmäßig aus (`-Dvsdh.feedbackLoops=true` schaltet sie ein): Im Monkeytalk-Pack „bewegte sich das Wasser komisch mit“ (Kamera), wahrscheinlich Lesen von colortex7/11 während überlappende Wasserfragmente schreiben. Nicht im Spiel bestätigt.

- 14.11. spät: Vollbild ist standardmäßig randlos (`Config.exclusiveFullscreen=false`, Fenstermodus „Exklusives Vollbild“ schaltet es ein). Ohne Borderless Window schaltete das exklusive Vollbild beim Start und bei jedem Alt-Tab den Bildschirm um; der Nutzer hörte den Ton kurz auf ein anderes Gerät springen. Kompiliert, 140 Unit-Tests, nicht im Spiel geprüft: F11 hin und zurück, Alt-Tab, Start mit `fullscreen:true`, Optionswechsel aller drei Modi, mit und ohne Borderless Window.

- 14.11. nachts: „Wasser bewegt sich noch immer mit“ nach Wechsel Jurgens → Eclipse in der Welt. Nur Code + Logs, KEINE Codeänderung. `WorldShaderSession.replaceInWorld` erreicht denselben VSDH-Zustand wie ein Weltbeitritt (Sitzung, Uniforms, Kette, SSBOs, Custom-Images, Builder, Materialtabelle, Bereiche); kein überlebender Zustand gefunden. Rest-Unterschiede: Vanillas `LevelRenderer.allChanged()` läuft nicht (andere Mods), DH-LOD-Puffer bleiben. Laut Nutzer-Logs wurde Eclipse mit abgeschalteten Farb-Schleifen nie frisch gestartet, der Bezug zum Wechsel ist unbelegt. Zuerst im Spiel klären (nur nach frischem „go“ des Nutzers): Eclipse frisch starten ohne Wechsel; nach Wechsel F3+A, dann Fenstergröße ändern; Eclipse `WATER_INTERACTION=0`; `-Dvsdh.liveShaderSwitch=false`; `-Dvsdh.uniformDump=gbuffers_water`. Verdächtige im Pack-Pfad: Vorframe-Spiegelung `colortex5` (Eclipse `lib/specular.glsl:2196-2216`), Wellen-Simulation `WATER_INTERACTION 2` (`dimensions/prepare*.csh`, SSBO, `waveSim`), Jurgens `rippleImg*`. Vergleich mit Iris: `build/reference/iris-client`, gleiche Welt und Kamera. Details: `docs/MIGRATION_STATUS.md` (oben).

- 14.11. spät: „Wasser bewegt sich mit“ gefunden und behoben. Ursache: Abschnitte werden erst gebaut, wenn der Sichtgraph sie erreicht; nach Drehen/Laufen fehlte das nahe Gelände ~1 s und das flache DH-Wasser schien durch (Walk-Serie im Testclient, ohne DH sichtbar als Lücken an Chunk-Grenzen). Nicht schuld: Uniforms (Dumps pro Bild geprüft, `-Dvsdh.uniformDumpEvery=N`), Halbtausch/copyBack, UniformRoutes, Culling, Viertel-SSR, Beta von 14.11. hatte es auch. Fix: `SectionGraph.prebuildHidden` (4 nächste nie gebaute Abschnitte pro Bild, nur ohne wartende sichtbare). Walk-Test bestätigt; im Modpack und Flight-Messung mit Fix noch offen.

- 15.11. früh: DH-Projektion folgt der Kamera (Bobbing, FOV), unbebaute Abschnitte offen im Sichtgraph, ChunkCatchUp. Pack-Wechsel-Lücke im Testclient von ~4 Aufnahmen auf ≤1. Gehtest (ground) zeigt in Screenshot-Differenzen keinen Unterschied durch den DH-Fix; die Differenzen mischen Bob-Phasen und sind kein sauberes Maß. Bestätigung im Modpack durch den Nutzer offen.

- 15.11. 00:40: Eigentliche Ursache von „Wasser und Schatten rutschen beim Gehen mit“ (nur mit Shader, nur beim Gehen): View Bobbing steckte in gbufferProjection (Dump: Nebendiagonalen ≠ 0), Packs erwarten reine Projektion. Fix: Bob in die Modelview (`CameraBobbing`, `WorldShaderGameMixin`, wie Iris). 145 Unit-Tests, verifyDhLinks PASS. ACHTUNG: Der Mixin ist im Client NICHT gestartet worden (Nutzer testete selbst, Testlauf abgebrochen); JAR liegt im Modpack (SHA256 47DF11D6…). Zuerst klären: startet das Spiel, ist der Fehler weg? Danach ground-Test gegen Iris wiederholen.

- 15.11. 01:00: Pack-Cache für sofortigen Shader-Wechsel (LRU 3, Vorwärmen beim Öffnen der Shader-Seite), 147 Unit-Tests, verifyDhLinks PASS, im Client NICHT gestartet. Offen: Testläufe (Bobbing-Mixin startet? Wechsel-Zeiten mit Cache, „Shader pack … prepared in N ms“ im Log) und Borderless gegen exklusives Vollbild messen (Nutzer: Borderless nicht smooth, exklusiv perfekt; Borderless = DXGI-Flip-Pfad). Die Modpack-JAR (47DF11D6…, Bobbing-Fix) hat der Nutzer noch nicht gestartet; sein Vollbild-Vergleich stammt aus dem Lauf 00:25–00:28 mit der JAR davor.

- 15.11. 00:45: Nutzer bestätigt: Wasser/Schatten/Shader-Wechsel funktionieren („sonst funktioniert alles“). Borderless auf Wunsch des Nutzers ganz entfernt (Vollbild = exklusiv, `WindowMode` nur noch Fenster/Vollbild, `exclusiveFullscreen`/`windowedFullscreen` aus der Config). Der Ton kann beim Alt-Tab im exklusiven Vollbild wieder kurz das Gerät wechseln; dem Nutzer bekannt.

- 15.11. nachts: JVM-Absturz im Testclient (`hs_err_pid33456`, nvoglv64, `vkCmdCopyImageToBuffer`, Lesen von 0x108) bei `Screenshot.grab` nach `windowSteps=150:toggle;260:iconify;340:restore;440:iconify;520:restore;620:toggle`. Ursache laut Code und Log: Nach dem Toggle bei 620 lief `SwapChain.createSwapChain` in den Früh-Ausstieg „Extent 0“ (kein „Swapchain:“/„DXGI flip“-Eintrag mehr, Kamera und Abschnittszahl blieben stehen); der zerstört die Vulkan-Swapchain, ließ aber `swapChainImages` und die Textur-IDs auf den toten Bildern, und der weiterlaufende Client-Tick las eines davon. Fix: IDs im Früh-Ausstieg, in `closeDxgi` und beim Wechsel Vulkan→DXGI lösen, IDs wiederverwenden statt je Recreate neue (Leck), `getColorAttachmentGlId` = 0 / `getColorAttachment` = null ohne Bilder (`SwapChainSlots`), `Screenshot._grab` meldet dann „screenshot.failure“, Tiefen-ID wird beim Freigeben gelöst, `cleanUp` verträgt fehlende Tiefe, `Renderer.beginFrame` versucht eine verlorene Swapchain alle 250 ms neu, solange das Fenster eine Größe meldet. NICHT geklärt: warum der Extent nach dem Toggle 0 war und kein weiteres Resize-Ereignis kam (ein Lauf zwei Minuten später mit derselben Schrittfolge lief durch). NICHT im Client gestartet: der neue `_grab`-Inject und die Wiederbelebung; zuerst dieselbe `windowSteps`-Folge mehrmals laufen lassen.

- 15.11. GPU-Seite (nur Code, NICHT im Spiel gemessen): leere Terrain-Layer werden in Pack-Frames übersprungen (`TerrainLayerDraws`, `-Dvsdh.skipEmptyTerrainLayers=false` für A/B; Eclipse: 2 von 5 Feedback-Kopien je Frame, geschätzt 0,08 ms bei 1440p) und eine erweiterte Tausch-Regel als Opt-in (`PersistentSwap`, `-Dvsdh.swapAfterGeometry=true`; Eclipse colortex7/8, geschätzt 0,07 ms, braucht Bildvergleich je Pack). Tabelle und Messvorschlag in `docs/PERFORMANCE.md` (letzter Abschnitt). Offen: Farb-Feedback-Schleifen allein (ohne Live-DH-Tiefe) gegen Kopien neu messen.
