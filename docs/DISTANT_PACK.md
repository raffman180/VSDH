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

Weitere Arbeiten betreffen Packprogramme für übrige Minecraft-Geometrie,
DH-Schatten für Packs, die sie aktivieren, und allgemeine DH-Objekte. Complementary MEDIUM
deaktiviert DH-Schatten in seiner eigenen Konfiguration.
