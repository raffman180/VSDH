package de.stranger.vsdh.shader;

import dev.vitrail.glsl.TranslatedUnit.Uniform;
import dev.vitrail.pack.source.ShaderProperties.CustomUniform;
import dev.vitrail.uniform.ExpressionFrame;
import dev.vitrail.uniform.Smoothed;
import dev.vitrail.uniform.Val;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PackExpressionsTest {
    private static CustomUniform uniform(String name,String expression) { return new CustomUniform(true,"float",name,expression); }
    private static final class Frame implements ExpressionFrame {
        int counter,biome; float rain,altitude;
        @Override public long frameCounter() { return counter; }
        @Override public void read(String name,Val out) {
            switch(name) {
                case "frameCounter"->out.set(counter);case "worldTime"->out.set(0);
                case "rainStrength"->out.set(rain);case "eyeAltitude"->out.set(altitude);
                case "biome"->out.set(biome);default->throw new IllegalArgumentException(name);
            }
        }
    }
    private static float read(PackExpressions.Runtime runtime,String name,Frame frame) {
        var value=new Val();runtime.read(name,frame,value);return value.f(0);
    }

    @Test void outOfOrderDependenciesAndRealInputsAreEvaluatedBeforeConsumers() {
        var plan=PackExpressions.prepare(List.of(uniform("result","max(intermediate, 2.0) + frameCounter % 8"),
                new CustomUniform(false,"float","intermediate","rainStrength * 6.0")));
        assertEquals(List.of(),plan.problems());
        var frame=new Frame();frame.counter=11;frame.rain=.75f;
        var runtime=plan.runtime();runtime.update(frame,.016f);
        assertEquals(7.5f,read(runtime,"result",frame));
    }
    @Test void smoothingHasSeparateRiseFallAndDoesNotAdvanceWhenReadByMorePasses() {
        var plan=PackExpressions.prepare(List.of(uniform("smoothed","smooth(4, rainStrength, 1, 2)")));
        var runtime=plan.runtime();var frame=new Frame();runtime.update(frame,.1f);
        assertEquals(0,read(runtime,"smoothed",frame));
        frame.rain=1;runtime.update(frame,.1f);
        for(int pass=0;pass<12;pass++)assertEquals(.5f,read(runtime,"smoothed",frame),1e-6);
        frame.rain=0;runtime.update(frame,.2f);
        assertEquals(.25f,read(runtime,"smoothed",frame),1e-6);
        var newWorld=plan.runtime();frame.rain=.8f;newWorld.update(frame,0);
        assertEquals(.8f,read(newWorld,"smoothed",frame),1e-6);
    }
    @Test void distinctCallSitesWithSameLegacyIdDoNotShareTheirAccumulator() {
        var runtime=PackExpressions.prepare(List.of(uniform("a","smooth(4, rainStrength, 1, 1)"),
                uniform("b","smooth(4, eyeAltitude, 2, 2)"))).runtime();
        var frame=new Frame();frame.altitude=1;runtime.update(frame,.1f);
        frame.rain=1;frame.altitude=0;runtime.update(frame,.1f);
        assertEquals(.5f,read(runtime,"a",frame),1e-6);
        assertEquals((float)Math.sqrt(.5),read(runtime,"b",frame),1e-6);
    }
    @Test void conditionalIsLazyAndRuntimeFailureNeverBecomesAZeroProvider() {
        var runtime=PackExpressions.prepare(List.of(uniform("result","if(frameCounter > 0, 8 / worldTime, 3)"))).runtime();
        var frame=new Frame();runtime.update(frame,.1f);assertEquals(3,read(runtime,"result",frame));
        frame.counter=1;
        assertThrows(IllegalStateException.class,()->runtime.update(frame,.1f));
    }
    @Test void cyclesMissingDependenciesAndBuiltinShadowingRemainDiagnosed() {
        var plan=PackExpressions.prepare(List.of(uniform("a","b + 1.0"),uniform("b","a + 1.0"),
                uniform("missing","notImplemented + 1.0"),uniform("dependent","missing * 2.0"),
                uniform("rainStrength","0.0"),uniform("valid","rainStrength + 2.0")));
        assertEquals(java.util.Set.of("valid"),plan.resolvedNames());assertFalse(plan.problems().isEmpty());
        var runtime=plan.runtime();var frame=new Frame();frame.rain=.5f;runtime.update(frame,0);
        assertEquals(2.5f,read(runtime,"valid",frame));
        assertThrows(IllegalArgumentException.class,()->runtime.read("a",frame,new Val()));
    }
    @Test void vectorsBooleansAndLargeIntegersReachTheirExactStd140Slots() {
        var runtime=PackExpressions.prepare(List.of(new CustomUniform(true,"int","material","33554559"),
                new CustomUniform(true,"bool","enabled","rainStrength > 0.25"),
                new CustomUniform(true,"vec4","color","vec4(rainStrength, 0.5, 0.75, 1.0)"))).runtime();
        var frame=new Frame();frame.rain=.25f;runtime.update(frame,0);
        var layout=new Std140Layout(List.of(Uniform.of("material","int material"),Uniform.of("enabled","bool enabled"),Uniform.of("color","vec4 color")));
        var bytes=ByteBuffer.allocate(layout.size()).order(ByteOrder.nativeOrder());
        for(var member:layout.members())assertTrue(runtime.write(member,layout,bytes));
        assertEquals(33554559,bytes.getInt(layout.member("material").offset()));
        assertEquals(0,bytes.getInt(layout.member("enabled").offset()));
        assertEquals(.25f,bytes.getFloat(layout.member("color").offset()));
        assertEquals(1,bytes.getFloat(layout.member("color").offset()+12));
    }
    @Test void absentFutureBiomesCanNeverMatchAnUnrelatedActualBiome() {
        var plan=PackExpressions.prepare(List.of(uniform("pale","if(in(biome, BIOME_PALE_GARDEN), 1, 0)"),
                uniform("nether","if(in(biome, BIOME_NETHER_WASTES), 1, 0)")));
        assertEquals(-1,plan.biomeId("minecraft","plains"));
        var frame=new Frame();frame.biome=plan.biomeId("minecraft","nether_wastes");
        var runtime=plan.runtime();runtime.update(frame,0);
        assertEquals(0,read(runtime,"pale",frame));assertEquals(1,read(runtime,"nether",frame));
    }
    @Test void smoothingIsIndependentOfFrameRateAndHandlesInstantOrZeroTimeUpdates() {
        var fast=new Smoothed();var slow=new Smoothed();fast.updateAndGet(0,10,10,0);slow.updateAndGet(0,10,10,0);
        float a=0,b=0;
        for(int i=0;i<50;i++)a=fast.updateAndGet(1,10,10,.02f);
        for(int i=0;i<20;i++)b=slow.updateAndGet(1,10,10,.05f);
        assertEquals(.5f,a,1e-5);assertEquals(a,b,1e-5);
        assertEquals(a,fast.updateAndGet(0,0,0,0));
        assertEquals(0,fast.updateAndGet(0,0,0,.01f));
    }
}
