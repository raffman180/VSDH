# Nächster Schritt: Shader-Backend

Noch nicht implementiert. Erstes Ziel: Complementary Reimagined, vom Nutzer gewählt.
Die native DH-Anbindung ist der erste Prototyp, kein Shaderpack-Loader.

## Konkrete Grenze im geprüften Renderer

Reforged 0.3.5-ALPHA+1.21.1, Commit
`0618965fbedb71ca2ee2e5d4528fcedc4fb27551`:

- `Framebuffer.java` hält einen Farbanhang; der Builder prüft ausdrücklich
  `colorAttachments <= 1` (Zeile 247 im geprüften Original).
- `GraphicsPipeline.java` erstellt einen Farb-Mischzustand.
- `RenderPass.java` verwendet ebenfalls einen einzelnen Farbanhang.

Quelle: [Framebuffer des festgelegten Reforged-Stands](https://github.com/TrulyRin/VulkanMod-Reforged/blob/0618965fbedb71ca2ee2e5d4528fcedc4fb27551/src/main/java/net/vulkanmod/vulkan/framebuffer/Framebuffer.java#L247).

Ein größerer Shaderpack-Backend benötigt unter anderem mehrere Bildpuffer,
Schattenausgabe und die passende Zuordnung der Gelände- und Materialdaten.
Eine Shaderdatei lediglich nach SPIR-V zu kompilieren reicht dafür nicht.

## Architektur zur Entscheidung

| Weg | Nutzen | Aufwand / Kompatibilität |
|---|---|---|
| Eigener Reforged-Fork — Empfehlung | Renderer direkt erweitern, klare Schnittstellen für VSDH schaffen | Großer Aufwand; Reforged-Updates müssen übernommen werden. Der Nutzer ersetzt die originale Renderer-JAR durch unseren Fork. |
| Separates VSDH-Addon beibehalten | Originale Reforged-JAR bleibt installiert | Ebenfalls großer Aufwand; erhebliche Eingriffe per Mixins oder parallele eigene Vulkan-Pipelines, stärkere Bindung an interne Details. |

Beide Wege haben noch keinen Nachweis für Complementary. Es gibt keine
begründete FPS-Zusage. Diese Entscheidung verändert Installation und Wartung
und wird deshalb vor der Implementierung mit dem Nutzer abgestimmt.

Danach in getrennten prüfbaren Schritten: mehrere Farbpuffer → passendes
Terrain-/Materialformat → Schatten → Pack-Übersetzung und Render-Reihenfolge →
DH-Puffer mit demselben Shader verarbeiten → Reload/Resize/Weltwechsel testen.
Weitere Packs und DH-Versionen folgen erst nach eigenen Prüfungen.
