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

/**
 * The Entities page's "Faster chests" and "Faster shulker boxes". Minecraft draws every one in view
 * by itself each frame through its block entity renderer, though a closed one never changes. So a
 * closed one goes into its section's terrain, as a block model made from its own model and texture
 * (which {@code assets/minecraft/atlases/blocks.json} adds to the block atlas), and isn't in the
 * section's list of block entities. While its lid moves, or while it shows cracks, it is in that
 * list and not in the terrain, so Minecraft draws it as usual; its section is redrawn each time it
 * changes over. A compile asks {@link #model} once for each block entity, so none is ever drawn
 * twice or not at all.
 * <p>
 * Runs on the workers that compile sections, except where a method says otherwise.
 */
public final class BakedBlockEntities {

    private static final Identifier NORMAL = Identifier.withDefaultNamespace("entity/chest/normal");
    /** How finely corners are placed: a rotated corner lands near its place, and the terrain wants faces exactly flat. */
    private static final float SNAP = 64f;
    /** The block entities showing cracks, by position. */
    private static final Set<Long> CRACKING = ConcurrentHashMap.newKeySet();

    private static volatile @Nullable Textures textures;

    private BakedBlockEntities() {
    }

    /** What the block atlas holds since the last reload, which replaces its sprites. */
    private record Textures(TextureAtlas atlas, TextureAtlasSprite normal, boolean chests, boolean shulkerBoxes, boolean christmas,
                            Map<Key, BlockStateModel> models) {
    }

    /** A model: its texture, way round, and for a chest whether it is one half of a double chest. */
    private record Key(Identifier texture, @Nullable ChestType type, Direction facing) {
    }

    /** One face in its block: its four corners (x, y, z each), their places on the texture (u, v, 0-1 each), and its side. */
    record Face(float[] corners, float[] uv, Direction direction) {
    }

    /** One cube of a model, with the box it fills in the block. */
    private record Part(List<Face> faces, float[] min, float[] max) {
    }

    /** A texture's size in pixels, and which of them show anything. */
    record Texture(int width, int height, Shown shown) {
    }

    interface Shown {
        boolean at(int x, int y);
    }

    /** The model for this block entity in its section's terrain, or null when Minecraft's renderer is to draw it. */
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

    /** Its switch, for Minecraft's own that can be baked; another mod's may be drawn its own way. */
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
                // (a reload has just freed this sprite's pixels; nothing is kept, and every section is compiled again after it)
                return null;
            }
        });
    }

    private static Texture texture(TextureAtlasSprite sprite) {
        SpriteContents contents = sprite.contents();
        return new Texture(contents.width(), contents.height(), (x, y) -> !contents.isTransparent(0, x, y));
    }

    /** Null while a reload has the block atlas empty. */
    private static @Nullable Textures textures() {
        TextureAtlas atlas = Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS);
        TextureAtlasSprite normal = sprite(atlas, NORMAL);
        if (normal == null) {
            return null;
        }
        Textures now = textures;
        return now != null && now.normal() == normal ? now : reloaded(atlas, normal);
    }

    /**
     * A sprite of the block atlas, or null when it has none by that name. A reload empties the atlas
     * on the render thread for a moment, when asking throws; every section is compiled again after a reload.
     */
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

    /** Whether a pack gives these another shape, or random or glowing textures, which EMF and ETF show through Minecraft's renderer only. */
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

    /**
     * The solid layer. Minecraft draws chests as solid too, see-through pixels and all. It draws
     * shulker boxes as cutout, but {@link #shown} has already left out their see-through pixels, so
     * there is nothing to test, and nothing to open up at a distance as this atlas's smaller mipmaps
     * blur the edges of a texture's see-through parts.
     */
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
            // (a face on the block's edge, looking out of it, is one that a neighbour can hide)
            float at = c[face.direction().getAxis().ordinal()];
            if (at == (face.direction().getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1f : 0f)) {
                quads.addCulledFace(face.direction(), quad);
            } else {
                quads.addUnculledFace(quad);
            }
        }
        return new SingleVariant(new SimpleModelWrapper(quads.build(), true, material));
    }

    /**
     * A closed chest's faces, as Minecraft's renderer would place them. The layers are the models'
     * own, built here rather than through EntityModelSet, which EMF hooks and which is for the
     * render thread; {@link #packsChange} has already ruled out any pack that replaces them.
     */
    static List<Face> chestFaces(ChestType type, Direction facing) {
        LayerDefinition layer = switch (type) {
            case SINGLE -> ChestModel.createSingleBodyLayer();
            case LEFT -> ChestModel.createDoubleBodyLeftLayer();
            case RIGHT -> ChestModel.createDoubleBodyRightLayer();
        };
        return faces(layer, ChestRenderer.modelTransformation(facing), type != ChestType.SINGLE, null);
    }

    /** A closed shulker box's faces; its layer's own pose is the closed one (ShulkerBoxModel.setupAnim at 0). */
    static List<Face> shulkerBoxFaces(Direction facing, Texture texture) {
        return faces(ShulkerModel.createBoxLayer(), ShulkerBoxRenderer.modelTransform(facing), false, texture);
    }

    /** {@code cutout} is the texture of a model Minecraft draws as cutout, whose see-through pixels make no faces. */
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

    /** Whether this is the side where a double chest's halves meet, on the block's edge: x 0 or 16 in the model, where nothing else reaches. */
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

    /** Whether a face looks into another part, as a closed lid's floor does: nothing can see it till the lid opens, and then it isn't baked. */
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

    /**
     * A face as rectangles of the pixels that show, or as it is when they all do. A shulker box needs
     * this: where its closed lid overlaps its base, the lid's side shows only its corners and the
     * base's only its middle, the two see-through elsewhere and so fitting together.
     */
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

    /** The part of a face from {@code s0} to {@code s1} along its first edge and {@code t0} to {@code t1} along its last, its corners in the same turn. */
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

    /**
     * A closed chest's lid overlaps its body by a strip, where their sides lie on each other. Minecraft's
     * renderer shows the one it draws last (depth tests pass when equal), and these are in its
     * order; the terrain has no order, so the one underneath is cut back to where the other ends.
     */
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

    /** {@code face} without what {@code over} covers of it, or null when that is all of it. Left whole when what remains wouldn't be a rectangle. */
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

    /** {@code face} with the span {@code from}-{@code to} of one axis taken off it, when that span reaches one of its ends. */
    private static Face cutAlong(Face face, int axis, float from, float to) {
        float low = lowest(face.corners(), axis);
        float high = highest(face.corners(), axis);
        if (from <= low && to > low && to < high) {
            return moved(face, axis, low, to);
        }
        return to >= high && from > low && from < high ? moved(face, axis, high, from) : face;
    }

    /** {@code face} with its edge at {@code from} moved to {@code to} on one axis, its place on the texture moved in step. */
    private static Face moved(Face face, int axis, float from, float to) {
        float[] c = face.corners();
        float[] corners = c.clone();
        float[] uv = face.uv().clone();
        for (int v = 0; v < 4; v++) {
            if (c[v * 3 + axis] == from) {
                // (of the two corners next to it, the one across the face on this axis)
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

    /** On the render thread: a lid started or stopped moving, so it changes over. */
    public static void moved(VideoOptions.Toggle toggle, BlockPos pos) {
        if (toggle.on()) {
            redraw(pos);
        }
    }

    private static void redraw(BlockPos pos) {
        Minecraft.getInstance().levelExtractor.setSectionDirty(SectionPos.blockToSectionCoord(pos.getX()),
            SectionPos.blockToSectionCoord(pos.getY()), SectionPos.blockToSectionCoord(pos.getZ()));
    }

    /** On the render thread: a block shows cracks. Only Minecraft's renderer draws these on a block entity. */
    public static void cracking(ClientLevel level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        VideoOptions.Toggle toggle = blockEntity == null ? null : toggle(blockEntity);
        if (toggle != null && toggle.on() && CRACKING.add(pos.asLong())) {
            redraw(pos);
        }
    }

    /** On the render thread, at the start of each frame: those whose cracks have gone go back into the terrain. */
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

    /** On the render thread: a switch. Everything it covers changes over, so every section is compiled again. */
    public static void set(VideoOptions.Toggle toggle, boolean on) {
        if (on != toggle.on()) {
            toggle.set(on);
            Minecraft.getInstance().levelExtractor.allChanged();
        }
    }
}
