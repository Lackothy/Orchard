package com.lackothy.orchard.video;

import com.lackothy.Orchard;
import com.lackothy.orchard.mixin.ChestRendererAccess;
import com.mojang.blaze3d.platform.Transparency;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Transformation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.model.monster.shulker.ShulkerModel;
import net.minecraft.client.model.object.chest.ChestModel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.SingleVariant;
import net.minecraft.client.renderer.blockentity.ChestRenderer;
import net.minecraft.client.renderer.blockentity.ShulkerBoxRenderer;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.SimpleModelWrapper;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.QuadCollection;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.LidBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.entity.TrappedChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

public final class BakedBlockEntities {

    private static final Identifier NORMAL = Identifier.withDefaultNamespace("entity/chest/normal");
    private static final float SNAP = 64f;
    private static final Set<Long> CRACKING = ConcurrentHashMap.newKeySet();

    private static volatile @Nullable Textures textures;

    private BakedBlockEntities() {
    }

    private record Textures(TextureAtlas atlas, TextureAtlasSprite normal, boolean chests, boolean shulkerBoxes, boolean christmas,
                            Map<Key, BlockStateModel> models) {
    }

    private record Key(Identifier texture, @Nullable ChestType type, Direction facing) {
    }

    record Face(float[] corners, float[] uv, Direction direction) {
    }

    private record Part(List<Face> faces, float[] min, float[] max) {
    }

    record Texture(int width, int height, Shown shown) {
    }

    interface Shown {
        boolean at(int x, int y);
    }

    public static @Nullable BlockStateModel model(BlockState state, BlockEntity blockEntity) {
        VideoOptions.Toggle toggle = toggle(blockEntity);
        if (toggle == null || !toggle.on() || CRACKING.contains(blockEntity.getBlockPos().asLong())) {
            return null;
        }
        if (blockEntity instanceof ShulkerBoxBlockEntity box) {
            return shulkerBox(state, box);
        }
        return chest(state, blockEntity);
    }

    private static VideoOptions.@Nullable Toggle toggle(BlockEntity blockEntity) {
        Class<?> type = blockEntity.getClass();
        if (type == ChestBlockEntity.class || type == TrappedChestBlockEntity.class || type == EnderChestBlockEntity.class) {
            return VideoOptions.BAKED_CHESTS;
        }
        return type == ShulkerBoxBlockEntity.class ? VideoOptions.BAKED_SHULKER_BOXES : null;
    }

    private static @Nullable BlockStateModel chest(BlockState state, BlockEntity blockEntity) {
        LidBlockEntity lid = (LidBlockEntity) blockEntity;
        if (lid.getOpenNess(0f) > 0f || lid.getOpenNess(1f) > 0f || !state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return null;
        }
        Textures now = textures();
        if (now == null || !now.chests()) {
            return null;
        }
        ChestType type = state.hasProperty(ChestBlock.TYPE) ? state.getValue(ChestBlock.TYPE) : ChestType.SINGLE;
        Identifier texture = Sheets.chooseSprite(ChestRendererAccess.orchard$material(blockEntity, now.christmas()), type).texture();
        return baked(now, new Key(texture, type, state.getValue(BlockStateProperties.HORIZONTAL_FACING)));
    }

    private static @Nullable BlockStateModel shulkerBox(BlockState state, ShulkerBoxBlockEntity box) {
        if (box.getProgress(1f) > 0f || !state.hasProperty(BlockStateProperties.FACING)) {
            return null;
        }
        Textures now = textures();
        if (now == null || !now.shulkerBoxes()) {
            return null;
        }
        DyeColor color = box.getColor();
        Identifier texture = (color == null ? Sheets.DEFAULT_SHULKER_TEXTURE_LOCATION : Sheets.getShulkerBoxSprite(color)).texture();
        return baked(now, new Key(texture, null, state.getValue(BlockStateProperties.FACING)));
    }

    private static @Nullable BlockStateModel baked(Textures now, Key key) {
        TextureAtlasSprite sprite = sprite(now.atlas(), key.texture());
        if (sprite == null) {
            return null;
        }
        return now.models().computeIfAbsent(key, k -> {
            try {
                return bake(k.type() != null ? chestFaces(k.type(), k.facing()) : shulkerBoxFaces(k.facing(), texture(sprite)), sprite);
            } catch (IllegalStateException e) {
                return null;
            }
        });
    }

    private static Texture texture(TextureAtlasSprite sprite) {
        SpriteContents contents = sprite.contents();
        return new Texture(contents.width(), contents.height(), (x, y) -> !contents.isTransparent(0, x, y));
    }

    private static @Nullable Textures textures() {
        TextureAtlas atlas = Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS);
        TextureAtlasSprite normal = sprite(atlas, NORMAL);
        if (normal == null) {
            return null;
        }
        Textures now = textures;
        return now != null && now.normal() == normal ? now : reloaded(atlas, normal);
    }

    private static @Nullable TextureAtlasSprite sprite(TextureAtlas atlas, Identifier id) {
        try {
            TextureAtlasSprite sprite = atlas.getSprite(id);
            return sprite == atlas.missingSprite() ? null : sprite;
        } catch (IllegalStateException e) {
            return null;
        }
    }

    private static synchronized Textures reloaded(TextureAtlas atlas, TextureAtlasSprite normal) {
        Textures now = textures;
        if (now != null && now.normal() == normal) {
            return now;
        }
        ResourceManager resources = Minecraft.getInstance().getResourceManager();
        boolean chests = !packsChange(resources, "chest");
        boolean shulkerBoxes = !packsChange(resources, "shulker");
        now = new Textures(atlas, normal, chests, shulkerBoxes, ChestRenderer.xmasTextures(), new ConcurrentHashMap<>());
        textures = now;
        Orchard.LOG.info("Drawn with the terrain when closed: chests {}, shulker boxes {}", chests ? "yes" : "no, a resource pack changes them",
            shulkerBoxes ? "yes" : "no, a resource pack changes them");
        return now;
    }

    private static boolean packsChange(ResourceManager resources, String name) {
        FabricLoader loader = FabricLoader.getInstance();
        if (!loader.isModLoaded("entity_model_features") && !loader.isModLoaded("entity_texture_features")) {
            return false;
        }
        return !resources.listResources("optifine/cem", id -> id.getPath().contains(name)).isEmpty()
            || !resources.listResources("emf/cem", id -> id.getPath().contains(name)).isEmpty()
            || !resources.listResources("optifine/random/entity/" + name, id -> true).isEmpty()
            || !resources.listResources("optifine/mob/" + name, id -> true).isEmpty()
            || !resources.listResources("etf/random/entity/" + name, id -> true).isEmpty()
            || !resources.listResources("textures/entity/" + name, id -> id.getPath().matches(".*(\\.properties|(_e|\\d)\\.png)")).isEmpty();
    }

    private static BlockStateModel bake(List<Face> faces, TextureAtlasSprite sprite) {
        Material.Baked material = new Material.Baked(sprite, false);
        BakedQuad.MaterialInfo info = BakedQuad.MaterialInfo.of(material, Transparency.NONE, -1, null, 0);
        QuadCollection.Builder quads = new QuadCollection.Builder();
        for (Face face : faces) {
            float[] c = face.corners();
            float[] uv = face.uv();
            BakedQuad quad = new BakedQuad(new Vector3f(c[0], c[1], c[2]), new Vector3f(c[3], c[4], c[5]),
                new Vector3f(c[6], c[7], c[8]), new Vector3f(c[9], c[10], c[11]),
                UVPair.pack(sprite.getU(uv[0]), sprite.getV(uv[1])), UVPair.pack(sprite.getU(uv[2]), sprite.getV(uv[3])),
                UVPair.pack(sprite.getU(uv[4]), sprite.getV(uv[5])), UVPair.pack(sprite.getU(uv[6]), sprite.getV(uv[7])),
                face.direction(), info);
            float at = c[face.direction().getAxis().ordinal()];
            if (at == (face.direction().getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1f : 0f)) {
                quads.addCulledFace(face.direction(), quad);
            } else {
                quads.addUnculledFace(quad);
            }
        }
        return new SingleVariant(new SimpleModelWrapper(quads.build(), true, material));
    }

    static List<Face> chestFaces(ChestType type, Direction facing) {
        LayerDefinition layer = switch (type) {
            case SINGLE -> ChestModel.createSingleBodyLayer();
            case LEFT -> ChestModel.createDoubleBodyLeftLayer();
            case RIGHT -> ChestModel.createDoubleBodyRightLayer();
        };
        return faces(layer, ChestRenderer.modelTransformation(facing), type != ChestType.SINGLE, null);
    }

    static List<Face> shulkerBoxFaces(Direction facing, Texture texture) {
        return faces(ShulkerModel.createBoxLayer(), ShulkerBoxRenderer.modelTransform(facing), false, texture);
    }

    private static List<Face> faces(LayerDefinition layer, Transformation transformation, boolean halves, @Nullable Texture cutout) {
        PoseStack pose = new PoseStack();
        pose.mulPose(transformation);
        List<Part> parts = new ArrayList<>();
        layer.bakeRoot().visit(pose, (at, path, index, cube) -> {
            List<Face> faces = new ArrayList<>();
            float[] min = {1f, 1f, 1f};
            float[] max = {0f, 0f, 0f};
            for (ModelPart.Polygon polygon : cube.polygons) {
                Face face = face(at, polygon);
                for (int v = 0; v < 12; v++) {
                    min[v % 3] = Math.min(min[v % 3], face.corners()[v]);
                    max[v % 3] = Math.max(max[v % 3], face.corners()[v]);
                }
                if (!halves || !seam(polygon)) {
                    faces.add(face);
                }
            }
            parts.add(new Part(faces, min, max));
        });
        List<Face> kept = new ArrayList<>();
        for (Part part : parts) {
            for (Face face : part.faces()) {
                if (parts.stream().noneMatch(other -> other != part && inside(face, other))) {
                    kept.addAll(cutout == null ? List.of(face) : shown(face, cutout));
                }
            }
        }
        return trimmed(kept);
    }

    private static boolean seam(ModelPart.Polygon polygon) {
        if (Math.abs(polygon.normal().x()) < 0.5f) {
            return false;
        }
        float x = polygon.vertices()[0].x();
        return x == 0f || x == 16f;
    }

    private static Face face(PoseStack.Pose at, ModelPart.Polygon polygon) {
        ModelPart.Vertex[] vertices = polygon.vertices();
        float[] corners = new float[12];
        float[] uv = new float[8];
        Vector3f position = new Vector3f();
        for (int v = 0; v < 4; v++) {
            at.pose().transformPosition(vertices[v].worldX(), vertices[v].worldY(), vertices[v].worldZ(), position);
            corners[v * 3] = Math.round(position.x * SNAP) / SNAP;
            corners[v * 3 + 1] = Math.round(position.y * SNAP) / SNAP;
            corners[v * 3 + 2] = Math.round(position.z * SNAP) / SNAP;
            uv[v * 2] = vertices[v].u();
            uv[v * 2 + 1] = vertices[v].v();
        }
        Vector3f normal = at.transformNormal(polygon.normal(), new Vector3f());
        return new Face(corners, uv, Direction.getApproximateNearest(normal.x, normal.y, normal.z));
    }

    private static boolean inside(Face face, Part other) {
        int axis = face.direction().getAxis().ordinal();
        float plane = face.corners()[axis];
        boolean into = face.direction().getAxisDirection() == Direction.AxisDirection.POSITIVE
            ? plane >= other.min()[axis] && plane < other.max()[axis]
            : plane > other.min()[axis] && plane <= other.max()[axis];
        if (!into) {
            return false;
        }
        float[] c = face.corners();
        for (int a = 0; a < 3; a++) {
            if (a != axis && (lowest(c, a) < other.min()[a] || highest(c, a) > other.max()[a])) {
                return false;
            }
        }
        return true;
    }

    private static List<Face> shown(Face face, Texture texture) {
        float[] uv = face.uv();
        int across = Math.round(Math.max(Math.abs(uv[2] - uv[0]) * texture.width(), Math.abs(uv[3] - uv[1]) * texture.height()));
        int down = Math.round(Math.max(Math.abs(uv[6] - uv[0]) * texture.width(), Math.abs(uv[7] - uv[1]) * texture.height()));
        boolean[][] left = new boolean[down][across];
        int count = 0;
        for (int j = 0; j < down; j++) {
            for (int i = 0; i < across; i++) {
                float s = (i + 0.5f) / across;
                float t = (j + 0.5f) / down;
                int x = (int) ((uv[0] + s * (uv[2] - uv[0]) + t * (uv[6] - uv[0])) * texture.width());
                int y = (int) ((uv[1] + s * (uv[3] - uv[1]) + t * (uv[7] - uv[1])) * texture.height());
                left[j][i] = texture.shown().at(Math.clamp(x, 0, texture.width() - 1), Math.clamp(y, 0, texture.height() - 1));
                count += left[j][i] ? 1 : 0;
            }
        }
        if (count == across * down) {
            return List.of(face);
        }
        List<Face> pieces = new ArrayList<>();
        for (int j = 0; j < down; j++) {
            for (int i = 0; i < across; i++) {
                if (left[j][i]) {
                    int end = i + 1;
                    while (end < across && left[j][end]) {
                        end++;
                    }
                    int below = j + 1;
                    while (below < down && allLeft(left[below], i, end)) {
                        below++;
                    }
                    for (int row = j; row < below; row++) {
                        Arrays.fill(left[row], i, end, false);
                    }
                    pieces.add(piece(face, (float) i / across, (float) end / across, (float) j / down, (float) below / down));
                }
            }
        }
        return pieces;
    }

    private static boolean allLeft(boolean[] row, int from, int to) {
        for (int i = from; i < to; i++) {
            if (!row[i]) {
                return false;
            }
        }
        return true;
    }

    private static Face piece(Face face, float s0, float s1, float t0, float t1) {
        float[] c = face.corners();
        float[] uv = face.uv();
        float[] corners = new float[12];
        float[] places = new float[8];
        float[] ss = {s0, s1, s1, s0};
        float[] ts = {t0, t0, t1, t1};
        for (int v = 0; v < 4; v++) {
            for (int a = 0; a < 3; a++) {
                corners[v * 3 + a] = Math.round((c[a] + ss[v] * (c[3 + a] - c[a]) + ts[v] * (c[9 + a] - c[a])) * SNAP) / SNAP;
            }
            for (int a = 0; a < 2; a++) {
                places[v * 2 + a] = uv[a] + ss[v] * (uv[2 + a] - uv[a]) + ts[v] * (uv[6 + a] - uv[a]);
            }
        }
        return new Face(corners, places, face.direction());
    }

    private static List<Face> trimmed(List<Face> faces) {
        List<Face> kept = new ArrayList<>(faces.size());
        for (int i = 0; i < faces.size(); i++) {
            Face face = faces.get(i);
            for (int j = i + 1; j < faces.size() && face != null; j++) {
                face = trimmed(face, faces.get(j));
            }
            if (face != null) {
                kept.add(face);
            }
        }
        return kept;
    }

    private static @Nullable Face trimmed(Face face, Face over) {
        int axis = face.direction().getAxis().ordinal();
        if (face.direction() != over.direction() || face.corners()[axis] != over.corners()[axis]) {
            return face;
        }
        float[] a = face.corners();
        float[] b = over.corners();
        int u = (axis + 1) % 3;
        int w = (axis + 2) % 3;
        boolean coversU = lowest(b, u) <= lowest(a, u) && highest(b, u) >= highest(a, u);
        boolean coversW = lowest(b, w) <= lowest(a, w) && highest(b, w) >= highest(a, w);
        if (coversU && coversW) {
            return null;
        }
        if (coversU) {
            return cutAlong(face, w, lowest(b, w), highest(b, w));
        }
        return coversW ? cutAlong(face, u, lowest(b, u), highest(b, u)) : face;
    }

    private static Face cutAlong(Face face, int axis, float from, float to) {
        float low = lowest(face.corners(), axis);
        float high = highest(face.corners(), axis);
        if (from <= low && to > low && to < high) {
            return moved(face, axis, low, to);
        }
        return to >= high && from > low && from < high ? moved(face, axis, high, from) : face;
    }

    private static Face moved(Face face, int axis, float from, float to) {
        float[] c = face.corners();
        float[] corners = c.clone();
        float[] uv = face.uv().clone();
        for (int v = 0; v < 4; v++) {
            if (c[v * 3 + axis] == from) {
                int across = c[(v + 1) % 4 * 3 + axis] != from ? (v + 1) % 4 : (v + 3) % 4;
                float share = (to - from) / (c[across * 3 + axis] - from);
                corners[v * 3 + axis] = to;
                uv[v * 2] += share * (face.uv()[across * 2] - face.uv()[v * 2]);
                uv[v * 2 + 1] += share * (face.uv()[across * 2 + 1] - face.uv()[v * 2 + 1]);
            }
        }
        return new Face(corners, uv, face.direction());
    }

    private static float lowest(float[] corners, int axis) {
        return Math.min(Math.min(corners[axis], corners[3 + axis]), Math.min(corners[6 + axis], corners[9 + axis]));
    }

    private static float highest(float[] corners, int axis) {
        return Math.max(Math.max(corners[axis], corners[3 + axis]), Math.max(corners[6 + axis], corners[9 + axis]));
    }

    public static void moved(VideoOptions.Toggle toggle, BlockPos pos) {
        if (toggle.on()) {
            redraw(pos);
        }
    }

    private static void redraw(BlockPos pos) {
        Minecraft.getInstance().levelExtractor.setSectionDirty(SectionPos.blockToSectionCoord(pos.getX()),
            SectionPos.blockToSectionCoord(pos.getY()), SectionPos.blockToSectionCoord(pos.getZ()));
    }

    public static void cracking(ClientLevel level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        VideoOptions.Toggle toggle = blockEntity == null ? null : toggle(blockEntity);
        if (toggle != null && toggle.on() && CRACKING.add(pos.asLong())) {
            redraw(pos);
        }
    }

    public static void frame(@Nullable ClientLevel level) {
        if (CRACKING.isEmpty()) {
            return;
        }
        if (level == null) {
            CRACKING.clear();
            return;
        }
        for (long at : CRACKING) {
            if (!level.destructionProgress().containsKey(at)) {
                CRACKING.remove(at);
                redraw(BlockPos.of(at));
            }
        }
    }

    public static void set(VideoOptions.Toggle toggle, boolean on) {
        if (on != toggle.on()) {
            toggle.set(on);
            Minecraft.getInstance().levelExtractor.allChanged();
        }
    }
}
