package de.stranger.vsdh.shader;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CameraCoordinatesTest {
    @Test void negativePositionsUseFloorAndPositiveFractions() {
        var whole=new int[3];var fraction=new float[3];
        CameraCoordinates.split(-3.25,100.625,-7.125,whole,fraction);
        assertArrayEquals(new int[]{-4,100,-8},whole);assertArrayEquals(new float[]{.75f,.625f,.875f},fraction);
    }
    @Test void largeBorderCoordinatesKeepSubBlockPrecision() {
        var whole=new int[3];var fraction=new float[3];
        CameraCoordinates.split(29999999.125,-64.75,-29999999.875,whole,fraction);
        assertEquals(29999999.125,whole[0]+(double)fraction[0],1e-6);
        assertEquals(-64.75,whole[1]+(double)fraction[1],1e-6);
        assertEquals(-29999999.875,whole[2]+(double)fraction[2],1e-6);
    }
    @Test void invalidCoordinatesCannotBeUploadedAsIntegers() {
        assertThrows(IllegalArgumentException.class,()->CameraCoordinates.split(Double.NaN,0,0,new int[3],new float[3]));
        assertThrows(IllegalArgumentException.class,()->CameraCoordinates.split(Double.POSITIVE_INFINITY,0,0,new int[3],new float[3]));
    }
}
