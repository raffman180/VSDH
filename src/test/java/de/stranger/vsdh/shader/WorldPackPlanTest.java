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
            assertEquals(java.util.List.of(0,1,2,3,4,5,6,7),prepared.chain().targets().writes("gbuffers_terrain"));
            assertEquals(java.util.List.of(0),prepared.chain().targets().writes("gbuffers_basic"));
            assertTrue(prepared.chain().externalSamplers().contains("depthtex0"));
            assertEquals(ParticlePackPlan.Order.MIXED,prepared.particles().order());
            assertEquals(dev.vitrail.pack.source.ShadowCullState.DEFAULT,prepared.shadow().cull());
            assertFalse(prepared.shadow().voxelizes(),"no geometry stage on the shadow program");
            assertEquals(3,prepared.particles().programs().size());
            assertEquals(java.util.List.of(9),prepared.chain().targets().writes("gbuffers_weather"));
            assertEquals(dev.vitrail.pack.model.TargetFormat.RGBA16_FLOAT,prepared.chain().targets().directives().format(9).used());
            for(String layer:prepared.terrain().keySet()) {
                var program=prepared.terrain().get(layer);
                var layout=new Std140Layout(program.translated().uniforms());
                assertNotNull(layout.member("neverReadFromWorld"));
                var active=UniformUsage.names(program.compiled(),layout);
                assertFalse(active.contains("neverReadFromWorld"));
                assertTrue(active.containsAll(java.util.Set.of("of_Fog","cameraPositionInt","atlasSize")));
                boolean cutout=layer.equals("cutout")||layer.equals("cutout_mipped")||layer.equals("tripwire");
                assertEquals(cutout,hasKill(prepared.terrain().get(layer).compiled().stages().get(ProgramStage.FRAGMENT)),layer);
            }
        }
    }
    @Test void reusesTheRootPlanWhenThePackHasNoDimensionFolders() throws Exception {
        var path=Path.of(getClass().getResource("/shaderpacks/vsdh-world").toURI());
        try(var pack=OpenedPack.open(path,Map.of(),"")) {
            var plans=WorldPackSet.prepare(pack,false);
            assertEquals(3,plans.dimensions().size());
            assertSame(plans.overworld(),plans.forDimension("minecraft:the_nether"));
            assertSame(plans.overworld(),plans.forDimension("minecraft:the_end"));
        }
    }

    @Test void twoPreparationsOfOnePackGiveTheSameProgramsWhateverTheWorkersDid() throws Exception {
        var path=Path.of(getClass().getResource("/shaderpacks/vsdh-world").toURI());
        WorldPackSet first,second;
        try(var pack=OpenedPack.open(path,Map.of(),"")) { first=WorldPackSet.prepare(pack,false); }
        try(var pack=OpenedPack.open(path,Map.of(),"")) { second=WorldPackSet.prepare(pack,false); }
        assertEquals(first.overworld().terrain().keySet(),second.overworld().terrain().keySet());
        for(var layer:first.overworld().terrain().keySet()) {
            var a=first.overworld().terrain().get(layer).compiled();
            var b=second.overworld().terrain().get(layer).compiled();
            assertEquals(a.stages().keySet(),b.stages().keySet());
            for(var stage:a.stages().keySet()) {
                assertArrayEquals(a.stages().get(stage),b.stages().get(stage),layer+" "+stage);
                assertEquals(a.sources().get(stage),b.sources().get(stage));
            }
            assertEquals(a.bindings(),b.bindings());
        }
        var passesA=first.overworld().chain().stages();
        var passesB=second.overworld().chain().stages();
        for(var stage:passesA.keySet()) {
            assertEquals(passesA.get(stage).size(),passesB.get(stage).size());
            for(int i=0;i<passesA.get(stage).size();i++) {
                var a=passesA.get(stage).get(i);
                var b=passesB.get(stage).get(i);
                assertEquals(a.name(),b.name());
                if(a.program()!=null) for(var kind:a.program().compiled().stages().keySet())
                    assertArrayEquals(a.program().compiled().stages().get(kind),b.program().compiled().stages().get(kind),a.name());
            }
        }
    }

    @Test void terrainStaysOnlyBetweenPacksWithTheSameMaterialTable() throws Exception {
        var path=Path.of(getClass().getResource("/shaderpacks/vsdh-world").toURI());
        try(var pack=OpenedPack.open(path,Map.of(),"")) {
            var plans=WorldPackSet.prepare(pack,false);
            var again=WorldPackSet.prepare(pack,false);
            assertTrue(plans.sameTerrainContract(again));
            assertFalse(plans.sameTerrainContract(null)); // the standard renderer has another vertex format
            var plan=plans.overworld();
            var other=new java.util.ArrayList<>(plan.materials());
            other.add(new dev.vitrail.pack.id.BlockIds.Entry(false,"minecraft","vsdh_test_block",Map.of(),4321));
            var changed=WorldPackSet.single(new WorldPackPlan(plan.chain(),plan.terrain(),other));
            assertFalse(plans.sameTerrainContract(changed));
            assertFalse(changed.sameTerrainContract(plans));
            if(plan.materials().size()>1) { // the order decides which entry wins for a block named twice
                var reordered=new java.util.ArrayList<>(plan.materials());
                java.util.Collections.reverse(reordered);
                if(!reordered.equals(plan.materials()))
                    assertFalse(plans.sameTerrainContract(WorldPackSet.single(new WorldPackPlan(plan.chain(),plan.terrain(),reordered))));
            }
        }
    }

    private static boolean hasKill(byte[] spirv) {
        var words=ByteBuffer.wrap(spirv).order(ByteOrder.LITTLE_ENDIAN);
        for(int at=20;at<words.limit();) {int instruction=words.getInt(at);if((instruction&65535)==252)return true;at+=(instruction>>>16)*4;}
        return false;
    }
}
