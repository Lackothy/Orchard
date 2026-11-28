package mcopt.metal.lod;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LodColorsTest {

    private static final int SAND = 0xDBCFA3;
    private static final int WARM_WATER = 0x2E93A5;

    private static int sum(int rgb) {
        return (rgb >> 16 & 255) + (rgb >> 8 & 255) + (rgb & 255);
    }

    @Test
    void theFloorFadesWithDepth() {
        int shallow = LodColors.underWater(SAND, WARM_WATER, 2);
        int middle = LodColors.underWater(SAND, WARM_WATER, 8);
        int deep = LodColors.underWater(SAND, WARM_WATER, 20);
        assertTrue(sum(shallow) > sum(middle) && sum(middle) > sum(deep));
    }

    @Test
    void deepWaterIsItsOwnColourOverDarkness() {
        assertEquals(LodColors.mix(0, WARM_WATER, 0.71F), LodColors.underWater(SAND, WARM_WATER, 15));
        assertEquals(LodColors.underWater(SAND, WARM_WATER, 15), LodColors.underWater(SAND, WARM_WATER, 60));
    }

    @Test
    void theWaterNeverHidesAShallowFloor() {
        assertNotEquals(LodColors.underWater(0x000000, WARM_WATER, 1), LodColors.underWater(SAND, WARM_WATER, 1));
    }
}
