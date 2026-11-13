# DH-Shaderpfad in VSDH

Der Entwicklungsclient kann die originalen Programme `dh_terrain` und
`dh_water` von Complementary Reimagined r5.9.3 MEDIUM auf DH 3.3.3 ausführen.
Dazu braucht der Start `-PsmokeLivePack=true -PsmokeDistantPack=true` und
`-PsmokeShaderPack=<Original-ZIP>`. Die Pack-Auswahl für den normalen
Client bleibt eine separate Aufgabe.

DH ruft seinen Renderer in Minecraft 1.21.1 vor der normalen Terrainzeichnung
auf. Die Integration nutzt die bereits hochgeladenen DH-Meshes. Ihre 16 Byte
enthalten Position, Licht und kleine Positionskorrekturen, RGBA, Material und
Flächennormale. Vulkan liest sie an drei festen Eingabepositionen. Ein
Push-Constant trägt den kamerabezogenen Ursprung des jeweiligen Abschnitts.

Der Host zeichnet opake Ferngeometrie vor der normalen Geometrie in die
Pack-Farbziele. DH erhält eigene D32-Tiefe. Vor Deferred wird sie nach
`dhDepthTex1` kopiert. Nach Deferred zeichnet der Host das Fernwasser.
Wenn DH beide Geometriesorten zusammen liefert, hält der Host nur die
Referenzen für den Wasserpass zurück. Die vorhandenen DH-Puffer bleiben
Eigentum des DH-Backends. Die Aliase `dhDepthTex` und `dhDepthTex0`
liefern außerhalb der DH-Pässe die vollständige DH-Tiefe. Während eines
DH-Passes bindet der Host die separate opake Kopie als Lesequelle.
Vulkan verwendet so verschiedene Bilder für Lesen und Schreiben.

Die DH-Projektion kommt aus dem echten DH-Aufruf und hat bereits OpenGL-
Tiefenkonvention. Sie unterscheidet sich darin von der Reforged-Projektion
der Nahwelt. Der Host passt die Y-Richtung an und setzt die Z-Umrechnung
im Vertex-Epilog genau einmal um. Die vorherige DH-Projektion übernimmt er
am erfolgreichen Frame-Ende. Die DH-Definition gilt für die jeweilige
Pack-Lesung. Inkludierte Dateien, finaler GLSL-Header und Cache verwenden
dieselbe Definition. Andernfalls kann das GLSL einen Zweig mit fehlendem
Include kompilieren.

Vulkan aktiviert getrennte Mischregeln je Farbausgabe, sofern die GPU
`independentBlend` anbietet. Die Terrain- und DH-Pässe wenden die Angaben
des Packs in der Reihenfolge der tatsächlichen Draw-Buffers an.
Der GPU-Test tauscht die Regeln zweier Ziele und liest beide Pixel zurück.
Die Vorgaben zur Reihenfolge und den Tiefenbildern stammen aus der
[Iris-Dokumentation für DH](https://github.com/IrisShaders/ShaderDoc/blob/master/dh-support.md).
Die Gerätebedingung steht in der
[Vulkan-Referenz zum Blend-Zustand](https://docs.vulkan.org/refpages/latest/refpages/source/VkPipelineColorBlendStateCreateInfo.html).

Bei 1280×720, vier Minecraft-Chunks Sichtweite, Seed 825765032 und
Profil MEDIUM erscheint der Detailübergang auch in der OpenGL-Referenz mit
Iris 1.8.12, Sodium 0.6.13 und DH 3.3.3. In der untersuchten Nahwasserregion
ist VSDH dunkler. Die mittleren RGB-Kanäle liegen bei 54/71/87 gegenüber
100/127/149. Im Fernwasser liegen die Mittelwerte bei 96/139/167 gegenüber
97/140/169. Das sind Stichproben aus zwei unabhängigen Spielsitzungen.
Für die Ursache sind Farbpuffer und Reflexionseingaben des Nahwasserpasses
auf beiden Renderern zu vergleichen. Aufnahmen, Koordinaten und Profilbeleg
liegen im Prüfarchiv von 0.12.

## Nahwasser in 0.15

Die Puffer vor Composite zeigten die Helligkeitsabweichung bereits in
colortex0. colortex3 und die Fernwasserfarbe lagen bei beiden Renderern
nah beieinander. Eine Diagnosekopie des Packs legte acht Zwischenwerte
aus dem Wasserpass in getrennte Pixelspalten. Über der Wasserfläche war
die verwendete Normale in VSDH nach unten gerichtet. Iris las dieselbe
Fläche mit der nach oben gerichteten Normalen.

Der Reforged-Zeichenpfad deaktivierte die Rückseitenausblendung für
transparente Chunks. Die Flüssigkeitsmeshes können dieselbe Fläche mit
beiden Umlaufrichtungen und je einer eigenen Normalen enthalten.
Bei aktivem backFaceCulling übernimmt der Shaderpfad jetzt die
Minecraft-Vorgabe für diese Geometrie. Im kontrollierten A/B-Lauf
wechselte die Normalenkomponente nach oben. Der Grünkanal nach
der Beleuchtung stieg von 0,256 auf 0,502. Iris ergab 0,505.
Das ursprüngliche Reforged-Verhalten gilt im Standardmodus.

Weitere Arbeiten betreffen Packprogramme für übrige Minecraft-Geometrie,
DH-Schatten für Packs, die sie aktivieren, und allgemeine DH-Objekte. Complementary MEDIUM
deaktiviert DH-Schatten in seiner eigenen Konfiguration.

## 0.26: Rückseiten der DH-Pack-Durchläufe

Bliss 2.1.2 zeigte über dem Ozean blockgroße weiße Kacheln. Unter Iris in
derselben Szene fehlten sie. Mit übersprungenem DH-Wasser verschwanden sie
auch im Nahbereich. Dort zeichnet Bliss DH-Wasser bereits ab 16 Blöcken.
`NativeDistantPass` zeichnete `dh_terrain` und `dh_water` ohne
Rückseitenausblendung. Die nach unten zeigenden Flächen von LOD-Wasser über
Seetang und Seegras schattierte Bliss wie Wasser, das man von unten sieht.
DH selbst und der normale Vulkan-DH-Weg nutzen Rückseitenausblendung mit
Vorderseiten gegen den Uhrzeigersinn. Die Pack-Durchläufe tun das nun auch.
Seit der GL-Ausrichtung der Pack-Kette (Zeilen von unten nach oben,
`NATIVE_PACK_FEATURES.md`) erscheint DHs Gegen-Uhrzeigersinn im Framebuffer
gespiegelt; die Pässe nutzen deshalb `VK_FRONT_FACE_CLOCKWISE`. Das Bild entspricht danach der
Iris-Referenz; das DH-Land bleibt vollständig.

## Packs ohne DH-Programme (11.11.2026)

Hat ein Pack in der Dimension kein vollständiges `dh_terrain`/`dh_water`,
verwendet VSDH ihn ohne Pack-DH-Programme (`WorldPackPlan.prepare`, Warnung im
Log) und zeichnet keine DH-LODs (`VulkanRenderEngine.beginFrame`). Ein
zwischenzeitlich eingebautes Abschalten über die DH-API
(`renderingEnabled().setValue(false)`) wurde wieder entfernt: Iris macht das
nicht, und es behob den AstraLex/Insanity-Rand nicht. Dieser Rand (harte
Chunk-Kante, hellerer Himmel als unter Iris) ist weiterhin offen.

## DH-Generic-Objekte: Wolken, Beacons, API-Boxen (11.11.2026)

DH zeichnet eigene Wolken (`CloudRenderHandler`, Gruppe `DistantHorizons:Clouds`),
Beacon-Strahlen und Boxen anderer Mods über seinen „Generic“-Renderer. VSDH
hatte dafür nur Platzhalter und schaltete `enableGenericRendering` pro Sitzung
ab. Unter Iris erscheinen die Wolken in `dhDepthTex0` und werden vom Pack
schattiert; in BSL fehlten sie deshalb.

Jetzt (`VkGenericRenderer`, `VkGenericObjectVboContainer`):

- Jede Box wird auf einem DH-Worker zu sechs Flächen im 16-Byte-LOD-Format
  (Position, Licht, Farbe, Material, Fläche); jede Fläche in beiden
  Wicklungen, damit das Rückseiten-Culling der LOD-Pässe sie nie verliert.
  Upload und Freigabe nur auf dem Render-Thread (DHs Doppelpuffer-Protokoll:
  `tryUpdateInstancedDataAsync`).
- DH ruft den Renderer nach dem opaken LOD-Terrain zweimal auf (mit/ohne SSAO);
  gezeichnet wird jeweils die passende Gruppe.
- Mit Pack: eigene Warteschlange in `NativeDistantTerrain`, gezeichnet mit dem
  Pack-Programm `dh_generic`, sonst `dh_terrain` (Iris-Fallback), in die
  DH-Tiefe vor deren Opak-Kopie. `dh_generic` steht dazu in `ChainPlan`.
- DHs Wolken entfallen bei `dhClouds=off` oder ohne `dhClouds`-Zeile bei
  `clouds=off` (Regel wie Iris).
- Ohne Pack: DHs eigene Terrain-Pipeline; die Flächenschattierung der Gruppe
  ist in die Farbe eingerechnet.

Geprüft: Unit-Test für das Vertex-Layout; im Spiel BSL (Wolken wie unter Iris),
Eclipse und Super Duper Vanilla (eigenes `dh_generic`), Complementary.
`-Dvsdh.genericTrace=true` loggt Gruppen, Zustände und gezeichnete Quads.
Nicht geprüft: Standardrenderer ohne Pack, Beacon-Strahlen, Boxen fremder Mods.
DH-Ereignisse `DhApiBeforeGenericObjectRenderEvent` werden nicht gefeuert.

Nachtrag (11.11.2026 nachmittags): Boxen fremder Mods über die DH-API
(`createAbsolutePositionedGroup` + `getRenderRegister().add`) werden jetzt auch
gezeichnet. Zwei Fehler verhinderten das: `VkGenericRenderer.add` löste den
ersten Aufbau nicht aus (DHs eigener Renderer ruft dort `triggerBoxChange()`),
und Gruppen mit SSAO (Standard für API-Boxen) wurden nie verarbeitet, weil DH
den SSAO-Aufruf nur bei eigenem SSAO macht; ohne diesen Aufruf zeichnet der
Aufruf ohne SSAO sie mit. Im Spiel geprüft mit einer roten Säule und grüner
Platte jenseits der Sichtweite: Standardrenderer, Complementary (`dh_terrain`),
Eclipse (`dh_generic`, inklusive Spiegelung im Wasser). DHs Beacon-Gruppe ist
registriert; in der Testszene hat DH den Test-Beacon nicht übernommen (Gruppe
leer), die Strahlen selbst sind daher nicht im Bild geprüft.

## Beacon-Strahlen (11.11.2026 abends)

DH übernimmt Beacons nur aus geladenen Chunks, wenn der Beacon beim Einlesen
schon aktiv ist (Prüfung alle 80 Ticks). Die Testszene (`-PtestBeacon`, ohne
Pack zusätzlich `-PtestBeaconNoShader`) baut deshalb Beacon und Sockel, ändert
nach der Aktivierung einen Block im selben Chunk und entfernt sich erst danach
170 Blöcke. Ergebnis: DH-Gruppe „Beacons“ mit einem Strahl, sichtbar mit
Complementary (`dh_terrain`, mit Spiegelung im Wasser) und ohne Shaderpack.
Ein Lauf ohne Pack registrierte den Beacon einmal nicht (DH-Zeitpunkt, die
Gruppe blieb leer); der Wiederholungslauf zeigte ihn.
