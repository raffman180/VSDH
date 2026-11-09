# Wetter und Partikel in VSDH 0.12

Die NeoForge-1.21.1-Aufrufe für Partikel und Niederschlag liefern das
Minecraft-Format PARTICLE. NativeGeometryPass zeichnet das vorhandene
Mesh. Die feste Zuordnung Position, UV0, Color und UV2 bleibt auch bei
optimierten Eingaben erhalten.

Die Partikelreihenfolge übernimmt before, mixed und after aus dem Pack.
Im gemischten Modus verwendet das Spiel seinen Filter für opake und
transparente Partikel. Wo keine Reihenfolge angegeben ist, richtet
sich der Standard nach vorhandenen Deferred-Programmen. Wetter liegt
hinter Deferred. Der Wetterpass übernimmt die Packregeln für Alpha,
Mischung, Niederschlag, Spritzer und rain.depth.

Die Spielprobe erzeugt opake Flammen und transparente Wolkenpartikel.
Sie zählt Zeichenbefehle auf beiden Seiten von Deferred. Der
Diagnose-Shader schreibt nur dann in colortex9, wenn Regengeometrie
in der richtigen Phase mit echter Niederschlagstextur ankommt.
Der Test liest das RGBA16F-Bild von der GPU und prüft die Markerfarbe.

Die getestete Konfiguration verwendet die normalen Fast-/Fancy-Wege
in NeoForge. Die getrennten Fabulous-Zielbilder und eigene
Partikelshader anderer Mods benötigen eigene Tests.

[Packprogramme](https://shaders.properties/current/reference/programs/gbuffers/)
und [Partikelreihenfolge](https://shaders.properties/current/reference/shadersproperties/ordering/).
