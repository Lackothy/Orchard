package com.lackothy.orchard.video;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BakedBlockEntitiesTest {

    private static final Direction[] FACINGS = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
    private static final BakedBlockEntities.Texture ALL = new BakedBlockEntities.Texture(64, 64, (x, y) -> true);

    /** Minecraft's own texture, from the game on the test classpath. */
    private static BakedBlockEntities.Texture minecrafts(String name) throws IOException {
        try (InputStream in = BakedBlockEntitiesTest.class.getResourceAsStream("/assets/minecraft/textures/entity/" + name + ".png")) {
            BufferedImage image = ImageIO.read(in);
            return new BakedBlockEntities.Texture(image.getWidth(), image.getHeight(), (x, y) -> image.getRGB(x, y) >>> 24 != 0);
        }
    }

    /** How far a face's corner lies along {@code side}: 1 is on that edge of the block. */
    private static float along(float[] corners, int corner, Direction side) {
        float at = corners[corner * 3 + side.getAxis().ordinal()];
        return side.getAxisDirection() == Direction.AxisDirection.POSITIVE ? at : 1f - at;
    }

    private static boolean reaches(BakedBlockEntities.Face face, Direction side) {
        for (int corner = 0; corner < 4; corner++) {
            if (along(face.corners(), corner, side) == 1f) {
                return true;
            }
        }
        return false;
    }

    private static List<List<BakedBlockEntities.Face>> everyModel() {
        List<List<BakedBlockEntities.Face>> models = new ArrayList<>();
        for (ChestType type : ChestType.values()) {
            for (Direction facing : FACINGS) {
                models.add(BakedBlockEntities.chestFaces(type, facing));
            }
        }
        for (Direction facing : Direction.values()) {
            models.add(BakedBlockEntities.shulkerBoxFaces(facing, ALL));
        }
        return models;
    }

    @Test
    @DisplayName("every face is exactly flat, inside its block, and wound to face its side, as the terrain needs")
    void flatAndWound() {
        for (List<BakedBlockEntities.Face> model : everyModel()) {
            for (BakedBlockEntities.Face face : model) {
                float[] c = face.corners();
                for (int v = 0; v < 12; v++) {
                    assertTrue(c[v] >= 0f && c[v] <= 1f, "corner outside the block");
                }
                int axis = face.direction().getAxis().ordinal();
                assertEquals(c[axis], c[3 + axis]);
                assertEquals(c[axis], c[6 + axis]);
                assertEquals(c[axis], c[9 + axis]);
                int u = (axis + 1) % 3;
                int w = (axis + 2) % 3;
                float normal = (c[3 + u] - c[u]) * (c[6 + w] - c[w]) - (c[3 + w] - c[w]) * (c[6 + u] - c[u]);
                assertEquals(face.direction().getAxisDirection() == Direction.AxisDirection.POSITIVE, normal > 0f, face.direction().toString());
            }
        }
    }

    @Test
    @DisplayName("no two faces on the same side lie on each other, which the terrain couldn't order")
    void nothingOverlaps() {
        for (List<BakedBlockEntities.Face> model : everyModel()) {
            assertNoOverlap(model);
        }
    }

    private static void assertNoOverlap(List<BakedBlockEntities.Face> model) {
        for (BakedBlockEntities.Face a : model) {
            for (BakedBlockEntities.Face b : model) {
                int axis = a.direction().getAxis().ordinal();
                if (a == b || a.direction() != b.direction() || a.corners()[axis] != b.corners()[axis]) {
                    continue;
                }
                boolean overlap = true;
                for (int other = 0; other < 3; other++) {
                    if (other != axis) {
                        overlap &= low(a, other) < high(b, other) && low(b, other) < high(a, other);
                    }
                }
                assertFalse(overlap, "two faces overlap on " + a.direction());
            }
        }
    }

    @Test
    @DisplayName("Minecraft's shulker boxes have no holes when closed: the lid's and base's see-through parts fit together")
    void shulkerBoxWhole() throws IOException {
        for (String name : List.of("shulker/shulker", "shulker/shulker_red")) {
            BakedBlockEntities.Texture texture = minecrafts(name);
            for (Direction facing : Direction.values()) {
                List<BakedBlockEntities.Face> faces = BakedBlockEntities.shulkerBoxFaces(facing, texture);
                assertNoOverlap(faces);
                for (Direction side : Direction.values()) {
                    int axis = side.getAxis().ordinal();
                    int u = (axis + 1) % 3;
                    int w = (axis + 2) % 3;
                    float area = 0f;
                    for (BakedBlockEntities.Face face : faces) {
                        if (face.direction() == side) {
                            assertEquals(1f, along(face.corners(), 0, side), name + ": a face inside the block");
                            area += (high(face, u) - low(face, u)) * (high(face, w) - low(face, w));
                        }
                    }
                    assertEquals(1f, area, 1e-4f, name + " " + facing + ": a hole on its " + side + " side");
                }
            }
        }
    }

    @Test
    @DisplayName("nothing shows from a see-through pixel, and pieces keep to their place on the texture")
    void piecesOnlyOfWhatShows() throws IOException {
        BakedBlockEntities.Texture texture = minecrafts("shulker/shulker");
        for (BakedBlockEntities.Face face : BakedBlockEntities.shulkerBoxFaces(Direction.UP, texture)) {
            float[] uv = face.uv();
            float inU = (uv[0] + uv[2] + uv[4] + uv[6]) / 4f;
            float inV = (uv[1] + uv[3] + uv[5] + uv[7]) / 4f;
            assertTrue(texture.shown().at((int) (inU * 64f), (int) (inV * 64f)), "a piece of see-through pixels");
        }
    }

    private static float low(BakedBlockEntities.Face face, int axis) {
        float[] c = face.corners();
        return Math.min(Math.min(c[axis], c[3 + axis]), Math.min(c[6 + axis], c[9 + axis]));
    }

    private static float high(BakedBlockEntities.Face face, int axis) {
        float[] c = face.corners();
        return Math.max(Math.max(c[axis], c[3 + axis]), Math.max(c[6 + axis], c[9 + axis]));
    }

    @Test
    @DisplayName("a chest's sides still cover it from floor to lid, and its texture moved with any cut")
    void chestSidesWhole() {
        float side = 0f;
        for (BakedBlockEntities.Face face : BakedBlockEntities.chestFaces(ChestType.SINGLE, Direction.SOUTH)) {
            if (face.direction() == Direction.EAST) {
                side += (high(face, 1) - low(face, 1)) * 16f;
                float texture = Math.abs(face.uv()[1] - face.uv()[3]) + Math.abs(face.uv()[3] - face.uv()[5]);
                assertEquals(high(face, 1) - low(face, 1), texture * 4f, 1e-4f, "texture out of step with the face");
            }
        }
        // body 0-10 and lid 9-14 overlap by one, and the lock's side is two more on this side's axis at another plane
        assertEquals(14f + 4f, side, 1e-4f);
    }

    @Test
    @DisplayName("the lock reaches the edge of the block on the side the chest faces, and no other side's does")
    void lockOnTheFacingSide() {
        for (Direction facing : FACINGS) {
            for (Direction side : FACINGS) {
                boolean atEdge = BakedBlockEntities.chestFaces(ChestType.SINGLE, facing).stream()
                    .anyMatch(f -> f.direction() == side && along(f.corners(), 0, side) == 1f);
                assertEquals(side == facing, atEdge, facing + " chest, " + side + " side");
            }
        }
    }

    @Test
    @DisplayName("a double chest's halves reach each other, with no face between them")
    void halvesMeet() {
        for (Direction facing : FACINGS) {
            for (ChestType type : List.of(ChestType.LEFT, ChestType.RIGHT)) {
                // (ChestBlock.getConnectedDirection)
                Direction other = type == ChestType.LEFT ? facing.getClockWise() : facing.getCounterClockWise();
                List<BakedBlockEntities.Face> faces = BakedBlockEntities.chestFaces(type, facing);
                assertFalse(faces.stream().anyMatch(f -> f.direction() == other && along(f.corners(), 0, other) == 1f),
                    type + " " + facing + ": a face left between the halves");
                assertTrue(faces.stream().anyMatch(f -> f.direction() == Direction.UP && reaches(f, other)),
                    type + " " + facing + ": the lid stops short of the other half");
            }
        }
    }

    @Test
    @DisplayName("a chest's bottom is on the block's floor, where a neighbour can hide it")
    void bottomOnTheFloor() {
        assertTrue(BakedBlockEntities.chestFaces(ChestType.SINGLE, Direction.NORTH).stream()
            .anyMatch(f -> f.direction() == Direction.DOWN && f.corners()[1] == 0f));
    }

    @Test
    @DisplayName("what can't be seen closed is left out: the lid's floor and the body's ceiling")
    void hiddenFacesLeftOut() {
        assertEquals(16, BakedBlockEntities.chestFaces(ChestType.SINGLE, Direction.NORTH).size());
        assertEquals(13, BakedBlockEntities.chestFaces(ChestType.LEFT, Direction.NORTH).size());
        assertEquals(13, BakedBlockEntities.chestFaces(ChestType.RIGHT, Direction.NORTH).size());
        assertEquals(10, BakedBlockEntities.shulkerBoxFaces(Direction.UP, ALL).size());
    }

    @Test
    @DisplayName("a shulker box fills its block, its lid toward the way it faces and its base away")
    void shulkerBoxLidOnTheFacingSide() {
        for (Direction facing : Direction.values()) {
            List<BakedBlockEntities.Face> faces = BakedBlockEntities.shulkerBoxFaces(facing, ALL);
            for (Direction side : Direction.values()) {
                assertTrue(faces.stream().anyMatch(f -> f.direction() == side && along(f.corners(), 0, side) == 1f),
                    facing + " box: nothing on its " + side + " edge");
            }
            // (the lid's texture is in the sheet's upper rows, from 0; the base's from row 28 of 64)
            for (BakedBlockEntities.Face face : faces) {
                if (face.direction() == facing) {
                    assertTrue(face.uv()[1] < 28f / 64f, facing + " box: its base on the lid's side");
                } else if (face.direction() == facing.getOpposite()) {
                    assertTrue(face.uv()[1] >= 28f / 64f, facing + " box: its lid on the base's side");
                }
            }
        }
    }
}
