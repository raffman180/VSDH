package de.stranger.vsdh.shader;

import dev.vitrail.pack.model.ProgramStage;
import dev.vitrail.pack.source.OpenedPack;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class WorldPackPlanTest {
    @Test void preparesLiveWorldGraphAndOnlyCutoutVariantsDiscard() throws Exception {
        var path=Path.of(getClass().getResource("/shaderpacks/vsdh-world").toURI());
        try(var pack=OpenedPack.open(path,Map.of(),"")) {
            var prepared=WorldPackPlan.prepare(pack,"");
            assertEquals(5,prepared.terrain().size());
            assertTrue(prepared.terrain().values().stream().flatMap(p->p.translated().uniforms().stream())
                    .noneMatch(u->u.name().equals("of_TexShrink")),"Sodium-only inputs do not belong in the native format");
            assertEquals(java.util.List.of(0,1),prepared.chain().targets().writes("gbuffers_terrain"));
            assertEquals(java.util.List.of(0),prepared.chain().targets().writes("gbuffers_basic"));
            assertTrue(prepared.chain().externalSamplers().contains("depthtex0"));
            for(String layer:prepared.terrain().keySet()) {
                boolean cutout=layer.equals("cutout")||layer.equals("cutout_mipped")||layer.equals("tripwire");
                assertEquals(cutout,hasKill(prepared.terrain().get(layer).compiled().stages().get(ProgramStage.FRAGMENT)),layer);
            }
        }
    }
    private static boolean hasKill(byte[] spirv) {
        var words=ByteBuffer.wrap(spirv).order(ByteOrder.LITTLE_ENDIAN);
        for(int at=20;at<words.limit();) {int instruction=words.getInt(at);if((instruction&65535)==252)return true;at+=(instruction>>>16)*4;}
        return false;
    }
}
