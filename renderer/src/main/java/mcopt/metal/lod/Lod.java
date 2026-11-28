// Modified for Russet: the world's far terrain is let go when LodConfig turns it off, and built again when its settings change;
// CompFrame carries look (grain, distance shading, where the real terrain ends) and glow (far lights at night); the world's saved chunks
// are read in (LodSaved).
package mcopt.metal.lod;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import mcopt.metal.MetalBridge;
import mcopt.metal.MetalHooks;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.level.storage.LevelResource;
import org.joml.FrustumIntersection;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector4d;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

public final class Lod {
	private static @Nullable Lod instance;
	private static boolean failed;
	private static final boolean SODIUM = FabricLoader.getInstance().isModLoaded("sodium");
	private static final int MAX_LEVELS = 12;
	static final int COL_FRAME_BYTES = 10 * 16 + MAX_LEVELS * 32;
	static final int COMP_FRAME_BYTES = 64 + 13 * 16 + 16 * 16;
	private static final int MASK_MAX = 128;
	private static final int RING = 4;

	private final Object encoder;
	private final long ctx, lod;
	private final long colFrame = MemoryUtil.nmemCalloc(1, COL_FRAME_BYTES), compFrame = MemoryUtil.nmemCalloc(1, COMP_FRAME_BYTES);
	private final long err = MemoryUtil.nmemCalloc(1, 4096);
	private final long[] maskBufs = new long[RING], maskAddrs = new long[RING];
	private final long dbgBuf, dbgAddr;
	private final long paletteBuf;
	private long outBuf;
	private int outW, outH;
	private final java.util.ArrayDeque<long[]> releaseLater = new java.util.ArrayDeque<>();
	private @Nullable LodField field;
	private @Nullable LodClip clip;
	private @Nullable LodMesh mesh;
	private @Nullable LodPk pk;
	private @Nullable Object worldOwner;
	private int builtFor = -1;
	private final @Nullable LodSeam seam;
	private final @Nullable LodTaa taa;
	private final @Nullable LodTaaTile taaTile;
	private boolean seamArmed;
	private @Nullable LodPublish publish;
	private final int[] seamMaskPrev = new int[MASK_MAX * MASK_MAX / 32];
	private static volatile float activeReach;

	private double camX, camY, camZ;
	private final Matrix4f viewProj = new Matrix4f();
	private final Vector3f forward = new Vector3f();
	private final FrustumIntersection frustum = new FrustumIntersection();
	private float fogR, fogG, fogB, fogStart, fogEnd, envStart, envEnd;
	private boolean fogOn, haveFrame;
	private long frames;
	private final int[] mask = new int[MASK_MAX * MASK_MAX / 32];
	private int maskX, maskZ, maskSize, maskWords;
	private boolean maskOn;
	private double nearestFar;
	private long statStart = System.nanoTime();
	private int statFrames;
	private long statCpuNanos;
	private final long[] partNanos = new long[4];
	private final long[] maskNanos = new long[4];
	private long maskBuilt, maskHeights, maskBuiltNanos, maskMax;
	private int lastColumns, lastR0, lastR1;

	private Lod(Object encoder) {
		this.encoder = encoder;
		this.ctx = MetalBridge.ctx(encoder);
		this.lod = LodNative.create(this.ctx, source());
		this.paletteBuf = LodNative.buffer(this.ctx, (long) LodPalette.MAX * LodPalette.STRIDE * 4);
		LodPalette.bind(LodNative.contents(this.paletteBuf));
		this.dbgBuf = LodNative.buffer(this.ctx, 64);
		this.dbgAddr = LodNative.contents(this.dbgBuf);
		MemoryUtil.memSet(this.dbgAddr, 0, 64);
		for (int i = 0; i < RING; i++) {
			this.maskBufs[i] = LodNative.buffer(this.ctx, MASK_MAX * MASK_MAX / 8);
			this.maskAddrs[i] = LodNative.contents(this.maskBufs[i]);
			MemoryUtil.memSet(this.maskAddrs[i], 0, MASK_MAX * MASK_MAX / 8);
		}
		this.seam = LodSeam.ON ? new LodSeam(this.ctx) : null;
		this.taa = LodTaa.ON ? new LodTaa(this.ctx) : null;
		this.taaTile = LodTaaTile.ON ? new LodTaaTile(this.ctx) : null;
	}

	private static String source() {
		try (InputStream in = Lod.class.getResourceAsStream("/mcopt/lod/columns.metal")) {
			if (in == null) throw new IllegalStateException("columns.metal missing");
			String src = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			if (LodMesh.DISSOLVE_MS > 0) src = "#define SEAM_DISSOLVE_MS " + LodMesh.DISSOLVE_MS + "\n" + src;
			if (LodTaa.ON) src = "#define SEAM_TAA 1\n" + src;
			if (HANDOFF_PUSH > 0 && HANDOFF_PUSH < 1) src = "#define SEAM_DEPTH_PUSH " + HANDOFF_PUSH + "\n" + src;
			if (LodTaaTile.CODES) src = "#define SEAM_TAA_TILE 1\n" + src;
			if (Boolean.getBoolean("mcopt.lod.thinTex")) src = "#define SEAM_THIN_TEX 1\n" + src;
			if (Boolean.getBoolean("mcopt.lod.thin")) src = "#define SEAM_THIN 1\n#define SEAM_CROWN_LEVELS " + Math.max(1, LodConfig.CROWN_LEVELS) + "\n" + src;
			return src;
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	private static @Nullable Lod get() {
		if (instance != null || failed || !LodConfig.ENABLED) return instance;
		try {
			Object encoder = MetalBridge.encoder(((FrontendCommandEncoder) RenderSystem.getDevice().createCommandEncoder()).backend());
			if (encoder == null) {
				System.out.println("mcopt-lod: the game isn't on the Metal backend; far terrain disabled");
				failed = true;
				return null;
			}
			instance = new Lod(encoder);
			System.out.println("mcopt-lod: far terrain ready");
			return instance;
		} catch (RuntimeException e) {
			failed = true;
			System.out.println("mcopt-lod: far terrain disabled: " + e);
			e.printStackTrace(System.out);
			return null;
		}
	}

	public static float activeReach() {
		return activeReach;
	}

	public static int savedPending() {
		Lod l = instance;
		LodField w = l != null ? l.field : null;
		return w != null && w.saved != null ? w.saved.pending() : -1;
	}

	public static void adjustFog(FogData fog, FogType type) {
		float reach = activeReach;
		if (reach <= 0 || type != FogType.ATMOSPHERIC) return;
		fog.renderDistanceStart = LodConfig.FOG_START >= 0 ? (float) (reach * LodConfig.FOG_START) : reach - Math.clamp(reach / 10.0F, 4.0F, 64.0F);
		fog.renderDistanceEnd = reach;
		if (fog.environmentalEnd >= 512) {
			float scale = Math.max(1.0F, (float) (LodConfig.HAZE * reach) / 1024.0F);
			fog.environmentalStart *= scale;
			fog.environmentalEnd *= scale;
		}
	}

	public static void beginLevel(CameraRenderState camera) {
		Lod l = get();
		if (l == null) return;
		l.begin(camera);
	}

	public static void drawSolid() {
		Lod l = instance;
		if (l == null || !l.haveFrame) return;
		l.haveFrame = false;
		try {
			l.draw();
		} catch (RuntimeException e) {
			failed = true;
			instance = null;
			activeReach = 0;
			System.out.println("mcopt-lod: far terrain stopped: " + e);
			e.printStackTrace(System.out);
		}
	}

	public static boolean noSectionFade() {
		return activeReach > 0;
	}

	public static void endLevel() {
		Lod l = instance;
		if (l == null || !l.seamArmed) return;
		l.seamArmed = false;
		try {
			if (l.taaTile != null) {
				var t = Minecraft.getInstance().gameRenderer.mainRenderTarget();
				if (l.taaTile.frame(MetalBridge.enc(l.encoder), t.width, t.height, l.viewProj, l.camX, l.camY, l.camZ)) MetalBridge.reapplyPipeline(l.encoder);
			}
			if (l.seam != null) l.seamFrame();
		} catch (RuntimeException e) {
			System.out.println("mcopt-lod seam: probe failed: " + e);
			e.printStackTrace(System.out);
		}
	}

	private LodSeam.ChunkMask maskSnapshot() {
		int[] m = this.mask.clone();
		int x0 = this.maskX, z0 = this.maskZ, size = this.maskSize, words = this.maskWords;
		boolean on = this.maskOn;
		return (cx, cz) -> {
			int mx = cx - x0, mz = cz - z0;
			if (!on || mx < 0 || mz < 0 || mx >= size || mz >= size) return false;
			int bit = mz * words * 32 + mx;
			return (m[bit >> 5] >>> (bit & 31) & 1) != 0;
		};
	}

	private boolean chunkMasked(int cx, int cz) {
		int mx = cx - this.maskX, mz = cz - this.maskZ;
		if (!this.maskOn || mx < 0 || mz < 0 || mx >= this.maskSize || mz >= this.maskSize) return false;
		int bit = mz * this.maskWords * 32 + mx;
		return (this.mask[bit >> 5] >>> (bit & 31) & 1) != 0;
	}

	private void seamFrame() {
		LodSeam seam = this.seam;
		LodClip clip = this.clip;
		LodField field = this.field;
		if (seam == null || clip == null || field == null) return;
		var target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		if (target.getColorTexture() == null || target.getDepthTexture() == null) return;
		int changed = 0;
		for (int i = 0; i < this.mask.length; i++) {
			changed += Integer.bitCount(this.mask[i] ^ this.seamMaskPrev[i]);
			this.seamMaskPrev[i] = this.mask[i];
		}
		int rd = Minecraft.getInstance().options.getEffectiveRenderDistance();
		LodSeam.EngineState es = seam.engineState(clip, field, this.mesh, this.frustum, this.viewProj, this.camX, this.camY, this.camZ, target.width, target.height,
			this::chunkMasked);
		es.maskChanged = changed;
		seam.frame(MetalBridge.enc(this.encoder), MetalBridge.textureHandle(target.getColorTexture()), MetalBridge.textureHandle(target.getDepthTexture()),
			target.width, target.height, this.viewProj, this.camX, this.camY, this.camZ, rd, this.fogR, this.fogG, this.fogB, es);
	}

	private static final java.lang.invoke.MethodHandle SHADE_FAR = shadeFar();

	private static java.lang.invoke.@Nullable MethodHandle shadeFar() {
		try {
			Class<?> c = Class.forName("mcopt.metal.shade.ShadeFar");
			return java.lang.invoke.MethodHandles.publicLookup().findStatic(c, "setReach", java.lang.invoke.MethodType.methodType(void.class, float.class));
		} catch (ReflectiveOperationException | LinkageError e) {
			return null;
		}
	}

	private static void shadeReach(float blocks) {
		if (SHADE_FAR == null) return;
		try {
			SHADE_FAR.invokeExact(blocks);
		} catch (Throwable t) {
		}
	}

	private void begin(CameraRenderState camera) {
		Minecraft mc = Minecraft.getInstance();
		this.ensureWorld(mc);
		if (this.field == null) {
			activeReach = 0;
			shadeReach(0);
			return;
		}
		activeReach = (float) LodConfig.reachBlocks();
		shadeReach(activeReach);
		this.camX = camera.pos.x;
		this.camY = camera.pos.y;
		this.camZ = camera.pos.z;
		Matrix4f proj = new Matrix4f(camera.projectionMatrix);
		this.viewProj.set(proj).mul(camera.viewRotationMatrix);
		camera.viewRotationMatrix.positiveZ(this.forward).negate();
		this.frustum.set(this.viewProj, false);
		FogData fog = camera.fogData;
		this.fogR = fog.color.x;
		this.fogG = fog.color.y;
		this.fogB = fog.color.z;
		this.fogStart = fog.renderDistanceStart;
		this.fogEnd = fog.renderDistanceEnd;
		this.envStart = fog.environmentalStart;
		this.envEnd = fog.environmentalEnd;
		this.fogOn = camera.fogType != FogType.LAVA;
		this.haveFrame = true;
		LodTaa taa = this.taa;
		if (taa != null && this.mesh != null) {
			var target = mc.gameRenderer.mainRenderTarget();
			if (target.getColorTexture() != null) taa.beginLevel(MetalBridge.enc(this.encoder), MetalBridge.textureHandle(target.getColorTexture()), target.width,
				target.height, this.lastR0, this.lastR1);
		}
	}

	private void ensureWorld(Minecraft mc) {
		ClientLevel level = mc.level;
		MinecraftServer server = mc.getSingleplayerServer();
		Object owner = level == null || server == null || !LodConfig.ENABLED ? null : level;
		int generation = LodConfig.generation;
		if (owner == this.worldOwner && generation == this.builtFor) return;
		this.builtFor = generation;
		if (this.field != null) {
			this.field.close();
			this.field = null;
		}
		if (this.mesh != null) {
			this.mesh.close();
			for (long b : this.mesh.buffers()) this.releaseLater.add(new long[] {this.frames, b});
			this.mesh = null;
		}
		if (this.pk != null) {
			for (long b : this.pk.buffers()) this.releaseLater.add(new long[] {this.frames, b});
			this.pk = null;
		}
		if (this.clip != null) {
			this.releaseLater.add(new long[] {this.frames, this.clip.geomBuf});
			this.releaseLater.add(new long[] {this.frames, this.clip.colorBuf});
			this.releaseLater.add(new long[] {this.frames, this.clip.mipBuf});
			this.releaseLater.add(new long[] {this.frames, this.clip.crownBuf});
			this.clip = null;
		}
		this.worldOwner = owner;
		if (owner == null) return;
		ServerLevel sl = server.getLevel(level.dimension());
		if (sl == null) return;
		LodNoise noise = LodNoise.of(sl);
		if (noise == null) {
			System.out.println("mcopt-lod: no far terrain in " + level.dimension().identifier() + " (not noise-generated, or a ceiling)");
			return;
		}
		Path root = LodConfig.CACHE_DIR != null ? Path.of(LodConfig.CACHE_DIR) : mc.gameDirectory.toPath().resolve("mcopt-lod");
		String save = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName().toString();
		Path cache = root.resolve(save).resolve(level.dimension().identifier().getNamespace() + "_" + level.dimension().identifier().getPath());
		if (LodConfig.CROWN_LEVELS != 1 || LodConfig.TREE_LEVELS != 1) cache = cache.resolve("c" + LodConfig.CROWN_LEVELS + "t" + LodConfig.TREE_LEVELS);
		this.clip = new LodClip(this.ctx, LodConfig.N, LodConfig.reachBlocks());
		if (LodConfig.MESH) this.mesh = new LodMesh(this.ctx, this.clip);
		if (this.mesh != null && LodPk.ENABLED) {
			if (LodMesh.FENCES) System.out.println("mcopt-lod: -Dmcopt.lod.pk ignores -Dmcopt.lod.meshFences");
			this.pk = new LodPk(this.ctx);
			this.pk.switchDist = this.clip.switchDist;
			this.mesh.pk = this.pk;
		}
		if (LodConfig.MESH && LodPublish.ON) {
			if (this.publish == null) this.publish = new LodPublish(this.ctx);
			this.clip.publisher = this.publish;
		}
		this.field = new LodField(noise, this.clip, cache);
		if (LodSaved.ON) this.field.saved = new LodSaved(sl, server, this.field, this.clip);
		if (LodGenBench.ENABLED) LodGenBench.start(noise, this.field);
		if (LodYield.ON) LodYield.top = this.clip.levels - 1;
	}

	private void draw() {
		LodField w = this.field;
		LodClip clip = this.clip;
		if (w == null || clip == null) return;
		long start = System.nanoTime();
		Minecraft mc = Minecraft.getInstance();
		this.frames++;
		this.seamArmed = this.seam != null || this.taaTile != null;
		w.frame = this.frames;
		while (!this.releaseLater.isEmpty() && this.frames - this.releaseLater.peek()[0] >= RING) LodNative.release(this.releaseLater.poll()[1]);
		w.integrate();
		if (LodMesh.REAL_OCC && !this.edited.isEmpty()) this.resnapshot(mc, start);
		long t1 = System.nanoTime();
		this.partNanos[0] += t1 - start;
		w.rdBlocks = mc.options.getEffectiveRenderDistance() * 16.0;
		if (LodField.PRIO_SCREEN) w.updateScreen(this.camX, this.camZ, this.frustum, this.camY, this.viewProj, this.forward, this::maskSnapshot);
		else w.update(this.camX, this.camZ, this.frustum, this.camY);
		w.saveDirty(false);
		long t2 = System.nanoTime();
		this.partNanos[1] += t2 - t1;
		int rd = mc.options.getEffectiveRenderDistance();
		if (LodGenStats.ON) LodGenStats.frame(mc.level, w, this.camX, this.camZ, rd);
		if (LodYield.ON) LodYield.frame(mc, this.camX, this.camZ, rd);
		if (!(HANDOFF_DRAWN && SODIUM) && (!mcopt.metal.own.OwnSeam.ON || !this.updateMaskOwn())) this.updateMask(mc, rd, this.frames % 8 == 1);
		if (HANDOFF_DRAWN && SODIUM && CHUNK_HOLD) {
			ClientLevel cl = mc.level;
			w.hold = cl == null ? null : key -> this.heldInView((int) (key >> 32), (int) key, cl);
			w.releaseHeld();
		}
		this.partNanos[2] += System.nanoTime() - t2;
		boolean probeOn = this.probe(start);
		if (!LodConfig.DRAW || !probeOn || !MetalBridge.inRenderPass(this.encoder)) {
			this.statCpuNanos += System.nanoTime() - start;
			this.sample(w, start);
			if (LodConfig.STATS) this.stats(w);
			return;
		}
		long enc = MetalBridge.enc(this.encoder);
		int attachments = LodNative.attachments(enc);
		int size = LodNative.encSize(enc);
		int width = size >>> 16, height = size & 0xFFFF;
		if (attachments > 0 && width > 0 && height > 0) {
			this.ensureOut(width, height);
			long maskBuf = this.maskBufs[(int) (this.frames % RING)];
			MemoryUtil.memSet(this.maskAddrs[(int) (this.frames % RING)], 0, MASK_MAX * MASK_MAX / 8);
			if (this.maskOn) {
				for (int i = 0; i < this.maskSize * this.maskWords; i++) MemoryUtil.memPutInt(this.maskAddrs[(int) (this.frames % RING)] + i * 4L, this.mask[i]);
			}
			if (this.mesh != null) {
				LodPublish pub = clip.publisher;
				long t3 = System.nanoTime();
				if (pub != null) pub.begin(this.frames);
				this.mesh.integrate(this.frames);
				if (pub != null) pub.encode(enc, clip);
				this.partNanos[3] += System.nanoTime() - t3;
			}
			int columns = this.packFrames(clip, width, height, rd);
			LodMesh mesh = this.mesh;
			if (columns > 0 && mesh != null) {
				boolean gbuffer = false;
				if (this.dumpNow(w)) this.dump(clip, this.maskAddrs[(int) (this.frames % RING)], width, height);
				mesh.pack(this.viewProj, this.camX, this.camY, this.camZ, LodConfig.reachBlocks(), this.maskOn, this.maskX, this.maskZ, this.maskSize, this.maskWords,
					rd * 16.0F + 48.0F);
				long table = mesh.table(this.frames);
				LodPk pk = this.pk;
				boolean sample = (this.frames % LodPk.TIME_EVERY) == 0;
				int slot = (int) ((this.frames / LodPk.TIME_EVERY) & 15);
				boolean timed = pk != null && LodPk.LOAD && this.cullTimes && sample && LodNative.cullTimeSlot(this.lod, slot);
				if (pk != null && LodPk.LOAD && sample && !timed) this.cullTimes = false;
				boolean listsRan = pk != null && pk.lists(this.camX, this.camY, this.camZ, this.viewProj, LodConfig.reachBlocks(), LodPk.LOAD && this.cullTimes,
					timed);
				if (listsRan) {
					pk.prepare(this.viewProj, this.camX, this.camY, this.camZ, LodConfig.reachBlocks(), this.forward.x, this.forward.z,
						this.maskOn ? rd * 16.0F + 48.0F : 0);
					pk.frameFields(mesh.frame, this.camX, this.camZ);
					long pkBufs = pk.bufs(table, mesh.arena(), maskBuf, mesh, clip);
					pk.prune(this.lod, enc, mesh.frame, LodMesh.FRAME_BYTES, this.compFrame, COMP_FRAME_BYTES, pkBufs);
					LodNative.pkCull(this.lod, enc, mesh.frame, LodMesh.FRAME_BYTES, this.compFrame, COMP_FRAME_BYTES, pk.params, LodPk.PARAMS_BYTES,
						pkBufs, pk.rebuilding(), pk.liveNow ? 2 : 1, mesh.blocks());
					pk.encoded(this.camX, this.camY, this.camZ);
				} else {
					LodNative.meshCull(this.lod, enc, mesh.frame, LodMesh.FRAME_BYTES, this.compFrame, COMP_FRAME_BYTES, table, mesh.arena(), maskBuf, mesh.argsBuf,
						mesh.instBuf, mesh.plantBuf, mesh.survBuf, mesh.horizonBuf, mesh.listBuf, clip.geomBuf, clip.crownBuf, mesh.blocks(), LodMesh.FENCES);
				}
				if (timed) {
					pk.timedPath(slot, listsRan);
					int old = (slot - 1) & 15;
					pk.cullTime(old, LodNative.cullTime(this.lod, old));
				}
				long atlas = this.atlasHandle();
				if (this.taa != null) this.taa.beforeDraw(this.camX, this.camY, this.camZ, rd * 16.0F + 48.0F);
				LodNative.meshDraw(this.lod, enc, gbuffer, this.compFrame, COMP_FRAME_BYTES, mesh.arena(), mesh.argsBuf, mesh.instBuf, mesh.plantBuf, mesh.survBuf,
					clip.geomBuf, clip.colorBuf, clip.crownBuf, clip.texBuf, this.paletteBuf, atlas, mesh.currentTable(), mesh.frame, LodMesh.FRAME_BYTES,
					LodMesh.FENCES && pk == null, this.err, 4096);
				if (this.taa != null) this.taa.afterDraw(this.viewProj, this.camX, this.camY, this.camZ);
				MetalBridge.reapplyPipeline(this.encoder);
			} else if (columns > 0) {
				boolean gbuffer = false;
				if (this.dumpNow(w)) this.dump(clip, this.maskAddrs[(int) (this.frames % RING)], width, height);
				LodNative.march(this.lod, enc, this.colFrame, COL_FRAME_BYTES, clip.geomBuf, clip.colorBuf, clip.mipBuf, maskBuf, this.outBuf, this.dbgBuf,
					clip.crownBuf, columns, LodConfig.ROWS ? -LodConfig.ROW_SEGS : LodConfig.SEGMENTED ? clip.levels : 0, this.segMaxBuffer(columns * clip.levels),
					clip.texBuf, this.paletteBuf);
				long atlas = this.atlasHandle();
				LodNative.composite(this.lod, enc, gbuffer, this.compFrame, COMP_FRAME_BYTES, this.outBuf, clip.geomBuf, clip.colorBuf, clip.crownBuf, clip.texBuf,
					this.paletteBuf, atlas, this.err, 4096);
				MetalBridge.reapplyPipeline(this.encoder);
			}
		}
		this.statCpuNanos += System.nanoTime() - start;
		this.sample(w, start);
		if (LodConfig.STATS) this.stats(w);
	}

	private long settledFrame = -1;

	private boolean dumpNow(LodField w) {
		if (LodConfig.DUMP == null) return false;
		if (LodConfig.DUMP_FRAME >= 0) return this.frames == LodConfig.DUMP_FRAME;
		if (this.settledFrame < 0 && w.settled) this.settledFrame = this.frames;
		return this.settledFrame >= 0 && this.frames == this.settledFrame - LodConfig.DUMP_FRAME;
	}

	private void dump(LodClip clip, long mask, int width, int height) {
		try {
			Path dir = Path.of(LodConfig.DUMP);
			java.nio.file.Files.createDirectories(dir);
			write(dir.resolve("frame.bin"), this.colFrame, COL_FRAME_BYTES);
			write(dir.resolve("comp.bin"), this.compFrame, COMP_FRAME_BYTES);
			write(dir.resolve("mask.bin"), mask, MASK_MAX * MASK_MAX / 8);
			write(dir.resolve("geom.bin"), clip.geom, clip.levelWords * 4 * clip.levels);
			write(dir.resolve("color.bin"), clip.color, clip.levelWords * 4 * clip.levels);
			write(dir.resolve("mip.bin"), clip.mip, (clip.tileMaxOffset(clip.levels - 1) + (long) clip.tilesPerSide * clip.tilesPerSide) * 2);
			write(dir.resolve("crown.bin"), clip.crown, Math.max(16, clip.levelWords * 8 * clip.crownLevels));
			if (clip.plants) {
				write(dir.resolve("tex.bin"), clip.tex, clip.levelWords * 4 * 3);
				write(dir.resolve("palette.bin"), LodNative.contents(this.paletteBuf), (long) LodPalette.MAX * LodPalette.STRIDE * 4);
			}
			java.nio.file.Files.writeString(dir.resolve("info.txt"), String.format("%d %d %d %d %.4f %.4f %.4f%n", width, height, clip.n, clip.levels, this.camX, this.camY,
				this.camZ));
			System.out.println("mcopt-lod: dumped frame " + this.frames + " to " + dir);
		} catch (IOException e) {
			System.out.println("mcopt-lod: dump failed: " + e);
		}
	}

	private static void write(Path p, long address, long bytes) throws IOException {
		try (var ch = java.nio.channels.FileChannel.open(p, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE,
			java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
			java.nio.ByteBuffer b = MemoryUtil.memByteBuffer(address, (int) bytes);
			while (b.hasRemaining()) ch.write(b);
		}
	}

	private int atlasMips = -1;
	private boolean atlasLogged;

	private long atlasHandle() {
		try {
			var atlas = Minecraft.getInstance().getTextureManager().getTexture(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS);
			var tex = atlas.getTexture();
			this.atlasMips = Math.max(0, tex.getMipLevels() - 1);
			long handle = MetalBridge.textureHandle(tex);
			if (!this.atlasLogged) {
				this.atlasLogged = true;
				System.out.println("mcopt-lod: block atlas " + tex.getWidth(0) + "x" + tex.getHeight(0) + ", " + tex.getMipLevels() + " mips, handle " + Long.toHexString(handle));
			}
			return handle;
		} catch (RuntimeException e) {
			if (this.atlasMips != -2) System.out.println("mcopt-lod: no block atlas for the near textures: " + e);
			this.atlasMips = -2;
			return 0;
		}
	}

	private long segMaxBuf;
	private int segMaxCap;

	private long segMaxBuffer(int floats) {
		if (floats > this.segMaxCap) {
			if (this.segMaxBuf != 0) this.releaseLater.add(new long[] {this.frames, this.segMaxBuf});
			this.segMaxCap = Math.max(floats, this.segMaxCap * 2);
			this.segMaxBuf = LodNative.privateBuffer(this.ctx, this.segMaxCap * 4L);
		}
		return this.segMaxBuf;
	}

	private void ensureOut(int width, int height) {
		if (this.outBuf != 0 && width == this.outW && height == this.outH) return;
		if (this.outBuf != 0) this.releaseLater.add(new long[] {this.frames, this.outBuf});
		this.outBuf = LodNative.privateBuffer(this.ctx, (long) width * height * 8);
		this.outW = width;
		this.outH = height;
	}

	private final Matrix4d m = new Matrix4d(), inv = new Matrix4d();
	private final Vector3d pa = new Vector3d(), pb = new Vector3d();
	private final double[] A = new double[3], B = new double[3], C = new double[3];

	private void rays() {
		this.m.set(this.viewProj);
		this.m.invert(this.inv);
		double[][] d = new double[3][];
		double[][] at = {{0, 0}, {1, 0}, {0, 1}};
		for (int i = 0; i < 3; i++) {
			this.inv.transformProject(at[i][0], at[i][1], 0.25, this.pa);
			this.inv.transformProject(at[i][0], at[i][1], 0.75, this.pb);
			d[i] = new double[] {this.pa.x - this.pb.x, this.pa.y - this.pb.y, this.pa.z - this.pb.z};
		}
		double sign = d[0][0] * this.forward.x + d[0][1] * this.forward.y + d[0][2] * this.forward.z >= 0 ? 1 : -1;
		for (int k = 0; k < 3; k++) {
			this.C[k] = sign * d[0][k];
			this.A[k] = sign * (d[1][k] - d[0][k]);
			this.B[k] = sign * (d[2][k] - d[0][k]);
		}
	}

	private double tAt(double x, double y) {
		double dx = this.A[0] * x + this.B[0] * y + this.C[0], dy = this.A[1] * x + this.B[1] * y + this.C[1], dz = this.A[2] * x + this.B[2] * y + this.C[2];
		return dy / Math.max(1e-9, Math.sqrt(dx * dx + dz * dz));
	}

	private double yForT(double x, double t) {
		double lo = -1, hi = 1;
		if (this.tAt(x, lo) >= t) return -1;
		if (this.tAt(x, hi) <= t) return 1;
		for (int i = 0; i < 40; i++) {
			double mid = (lo + hi) * 0.5;
			if (this.tAt(x, mid) < t) lo = mid;
			else hi = mid;
		}
		return (lo + hi) * 0.5;
	}

	private int packFrames(LodClip clip, int width, int height, int rd) {
		this.rays();
		double cy = this.camY;
		double dMin = Math.max(1, this.nearestFar);
		double tLo = Double.POSITIVE_INFINITY, tHi = Double.NEGATIVE_INFINITY;
		int top = Integer.MIN_VALUE;
		for (int l = 0; l < clip.levels; l++) {
			int span = clip.span(l);
			long[] keys = clip.slotKey[l];
			for (int s = 0; s < keys.length; s++) {
				long k = keys[s];
				if (k == -1L) continue;
				double x0 = (double) LodTile.txOf(k) * span, z0 = (double) LodTile.tzOf(k) * span;
				double dx = Math.max(0, Math.max(x0 - this.camX, this.camX - (x0 + span))), dz = Math.max(0, Math.max(z0 - this.camZ, this.camZ - (z0 + span)));
				double near = Math.max(dMin, Math.sqrt(dx * dx + dz * dz));
				double far = Math.max(dMin, Math.sqrt(Math.pow(Math.max(Math.abs(x0 - this.camX), Math.abs(x0 + span - this.camX)), 2)
					+ Math.pow(Math.max(Math.abs(z0 - this.camZ), Math.abs(z0 + span - this.camZ)), 2)));
				double lo = clip.slotMin[l][s] - cy, hi = clip.slotMax[l][s] + 1 - cy;
				top = Math.max(top, clip.slotMax[l][s] + 1);
				tLo = Math.min(tLo, lo < 0 ? lo / near : lo / far);
				tHi = Math.max(tHi, hi > 0 ? hi / near : hi / far);
			}
		}
		if (top == Integer.MIN_VALUE) return 0;
		tLo = Math.max(tLo, -3);
		tHi = Math.min(tHi, 3);
		double yBot = 1, yTop = -1;
		for (int i = 0; i <= 32; i++) {
			double x = -1 + 2.0 * i / 32;
			yBot = Math.min(yBot, this.yForT(x, tLo));
			yTop = Math.max(yTop, this.yForT(x, tHi));
		}
		int r0 = Math.max(0, (int) Math.floor((yBot + 1) * height / 2 - 0.5) - 2);
		int r1 = Math.min(height - 1, (int) Math.ceil((yTop + 1) * height / 2 - 0.5) + 2);
		Vector4d v = this.m.transform(new Vector4d(0, 1, 0, 0));
		boolean parallel = Math.abs(v.w) <= 1e-9 * Math.abs(v.y);
		double vx = parallel ? 0 : v.x / v.w, vy = parallel ? 0 : v.y / v.w;
		if (!parallel) {
			int rv = (int) Math.floor((vy + 1) * height / 2 - 0.5);
			if (rv >= r0 - 2 && rv <= r1 + 2) {
				if (vy < 0) r0 = Math.max(r0, rv + 4);
				else r1 = Math.min(r1, rv - 4);
			}
		}
		if (r1 < r0) return 0;
		double yb = 2.0 * (r0 + 0.5) / height - 1, yt = 2.0 * (r1 + 0.5) / height - 1;
		double kappa = 0, yRef = (yb + yt) * 0.5, xLo = -1, xHi = 1;
		if (!parallel) {
			double yNear;
			if (vy <= yb) {
				yRef = yt;
				yNear = yb;
			} else {
				yRef = yb;
				yNear = yt;
			}
			if ((yNear - vy) / (yRef - vy) < 0.5) {
				yNear = vy + 0.5 * (yRef - vy);
				if (vy <= yb) r0 = Math.max(r0, (int) Math.ceil((yNear + 1) * height / 2 - 0.5));
				else r1 = Math.min(r1, (int) Math.floor((yNear + 1) * height / 2 - 0.5));
				if (r1 < r0) return 0;
				yb = 2.0 * (r0 + 0.5) / height - 1;
				yt = 2.0 * (r1 + 0.5) / height - 1;
				yNear = vy <= yb ? yb : yt;
			}
			kappa = 1 / (yRef - vy);
			double g = 1 + (yNear - yRef) * kappa;
			xLo = vx + (-1 - vx) / g;
			xHi = vx + (1 - vx) / g;
		}
		double dX = 2.0 / width;
		int columns = (int) Math.ceil((xHi - xLo) / dX) + 2;
		columns = Math.min(columns, width * 4);
		this.lastColumns = columns;
		this.lastR0 = r0;
		this.lastR1 = r1;

		long f = this.colFrame;
		putD(f, this.A);
		putD(f + 16, this.B);
		putD(f + 32, this.C);
		int bx = (int) Math.floor(this.camX), by = (int) Math.floor(this.camY), bz = (int) Math.floor(this.camZ);
		MemoryUtil.memPutFloat(f + 48, (float) (this.camX - bx));
		MemoryUtil.memPutFloat(f + 52, (float) this.camY);
		MemoryUtil.memPutFloat(f + 56, (float) (this.camZ - bz));
		MemoryUtil.memPutFloat(f + 60, (float) LodConfig.reachBlocks());
		MemoryUtil.memPutInt(f + 64, bx);
		MemoryUtil.memPutInt(f + 68, by);
		MemoryUtil.memPutInt(f + 72, bz);
		MemoryUtil.memPutInt(f + 76, clip.levels);
		MemoryUtil.memPutFloat(f + 80, (float) (xLo - dX));
		MemoryUtil.memPutFloat(f + 84, (float) dX);
		MemoryUtil.memPutFloat(f + 88, (float) yRef);
		MemoryUtil.memPutFloat(f + 92, columns);
		MemoryUtil.memPutFloat(f + 96, (float) vx);
		MemoryUtil.memPutFloat(f + 100, (float) kappa);
		MemoryUtil.memPutFloat(f + 104, (float) (top - this.camY));
		MemoryUtil.memPutFloat(f + 108, LodConfig.ROW_SEGS);
		MemoryUtil.memPutFloat(f + 112, width);
		MemoryUtil.memPutFloat(f + 116, height);
		MemoryUtil.memPutFloat(f + 120, r0);
		MemoryUtil.memPutFloat(f + 124, r1);
		MemoryUtil.memPutInt(f + 128, this.maskOn ? this.maskX : 0);
		MemoryUtil.memPutInt(f + 132, this.maskOn ? this.maskZ : 0);
		MemoryUtil.memPutInt(f + 136, this.maskOn ? this.maskSize : 0);
		MemoryUtil.memPutInt(f + 140, Math.max(1, this.maskWords));
		MemoryUtil.memPutFloat(f + 144, this.maskOn ? rd * 16.0F + 48.0F : 0.0F);
		MemoryUtil.memPutFloat(f + 148, clip.crownLevels);
		MemoryUtil.memPutFloat(f + 152, clip.runsOffset());
		MemoryUtil.memPutFloat(f + 156, clip.plants ? 1 : 0);
		for (int l = 0; l < MAX_LEVELS; l++) {
			long lp = f + 160 + l * 32L;
			if (l < clip.levels) {
				MemoryUtil.memPutInt(lp, clip.logN);
				MemoryUtil.memPutInt(lp + 4, (int) (l * clip.levelWords));
				MemoryUtil.memPutInt(lp + 8, (int) (l * clip.levelMipWords));
				MemoryUtil.memPutInt(lp + 12, (int) clip.tileMaxOffset(l));
				MemoryUtil.memPutFloat(lp + 16, clip.switchDist[l]);
			} else {
				MemoryUtil.memSet(lp, 0, 32);
			}
		}

		long c = this.compFrame;
		this.viewProj.getToAddress(c);
		putD(c + 64, this.A);
		putD(c + 80, this.B);
		putD(c + 96, this.C);
		MemoryUtil.memPutFloat(c + 112, width);
		MemoryUtil.memPutFloat(c + 116, height);
		MemoryUtil.memPutFloat(c + 120, r0);
		MemoryUtil.memPutFloat(c + 124, r1);
		MemoryUtil.memPutFloat(c + 128, this.fogR);
		MemoryUtil.memPutFloat(c + 132, this.fogG);
		MemoryUtil.memPutFloat(c + 136, this.fogB);
		MemoryUtil.memPutFloat(c + 140, this.fogOn ? 1 : 0);
		MemoryUtil.memPutFloat(c + 144, this.fogStart);
		MemoryUtil.memPutFloat(c + 148, this.fogEnd);
		MemoryUtil.memPutFloat(c + 152, this.envStart);
		MemoryUtil.memPutFloat(c + 156, this.envEnd);
		float[] sky = this.skyLight();
		MemoryUtil.memPutFloat(c + 160, sky[0]);
		MemoryUtil.memPutFloat(c + 164, sky[1]);
		MemoryUtil.memPutFloat(c + 168, sky[2]);
		MemoryUtil.memPutFloat(c + 172, 1);
		MemoryUtil.memPutFloat(c + 176, 1.0F);
		MemoryUtil.memPutFloat(c + 180, 0.5F);
		MemoryUtil.memPutFloat(c + 184, 0.8F);
		MemoryUtil.memPutFloat(c + 188, 0.6F);
		Vector4d q = this.m.transform(new Vector4d(this.forward.x * dMin * 0.5, this.forward.y * dMin * 0.5, this.forward.z * dMin * 0.5, 1));
		MemoryUtil.memPutFloat(c + 192, (float) Math.clamp(q.z / q.w, 0, 1));
		MemoryUtil.memPutFloat(c + 196, LodConfig.NEAR_DETAIL ? 1 : 0);
		MemoryUtil.memPutFloat(c + 200, (float) LodConfig.CROWN_SHADE);
		MemoryUtil.memPutFloat(c + 204, clip.crownLevels > 0 ? 1 : 0);
		MemoryUtil.memPutInt(c + 208, bx);
		MemoryUtil.memPutInt(c + 212, by);
		MemoryUtil.memPutInt(c + 216, bz);
		MemoryUtil.memPutInt(c + 220, clip.logN);
		MemoryUtil.memPutFloat(c + 224, (float) (this.camX - bx));
		MemoryUtil.memPutFloat(c + 228, (float) (this.camY - by));
		MemoryUtil.memPutFloat(c + 232, (float) (this.camZ - bz));
		MemoryUtil.memPutFloat(c + 236, LodMesh.DISSOLVE_MS > 0 ? LodMesh.clockMs() : 0);
		double tanY = Math.hypot(this.B[0], Math.hypot(this.B[1], this.B[2])) / Math.max(1e-9, Math.hypot(this.C[0], Math.hypot(this.C[1], this.C[2])));
		MemoryUtil.memPutFloat(c + 240, (float) (2 * Math.atan(tanY) / height));
		MemoryUtil.memPutFloat(c + 244, this.atlasMips);
		MemoryUtil.memPutFloat(c + 248, LodConfig.TEXTURES && this.atlasMips >= 0 ? 1 : 0);
		MemoryUtil.memPutFloat(c + 252, Integer.getInteger("mcopt.lod.debugView", 0));
		MemoryUtil.memPutFloat(c + 256, LodConfig.GRAIN);
		MemoryUtil.memPutFloat(c + 260, LodConfig.FAR_SHADE);
		MemoryUtil.memPutFloat(c + 264, this.maskOn ? rd * 16.0F : 0.0F);
		MemoryUtil.memPutFloat(c + 268, 0.0F);
		for (int b = 0; b < 16; b++) {
			float[] lm = LodConfig.GLOW ? this.lightmap(b) : null;
			for (int k = 0; k < 3; k++) MemoryUtil.memPutFloat(c + 272 + b * 16 + k * 4, lm != null ? lm[k] : 0.0F);
			float w = lm == null ? 0 : b == 0 ? clip.crownLevels : b == 1 ? LodConfig.QUALITY_DISTANCE : 0;
			MemoryUtil.memPutFloat(c + 272 + b * 16 + 12, w);
		}
		return columns;
	}

	private static void putD(long at, double[] v) {
		MemoryUtil.memPutFloat(at, (float) v[0]);
		MemoryUtil.memPutFloat(at + 4, (float) v[1]);
		MemoryUtil.memPutFloat(at + 8, (float) v[2]);
		MemoryUtil.memPutFloat(at + 12, 0);
	}

	private void updateMask(Minecraft mc, int rd, boolean refresh) {
		ClientLevel level = mc.level;
		if (level == null) return;
		int size = Math.min(MASK_MAX, 2 * rd + 5);
		int ccx = (int) Math.floor(this.camX) >> 4, ccz = (int) Math.floor(this.camZ) >> 4;
		int mx0 = ccx - size / 2, mz0 = ccz - size / 2;
		boolean moved = mx0 != this.readyX || mz0 != this.readyZ || size != this.readySize;
		if (refresh || moved) {
			if (moved) {
				boolean[] old = this.ready.clone();
				int dx = mx0 - this.readyX, dz = mz0 - this.readyZ;
				java.util.Arrays.fill(this.ready, false);
				if (this.readyX != Integer.MIN_VALUE && size == this.readySize) {
					for (int mz = 0; mz < size; mz++) {
						int oz = mz + dz;
						if (oz < 0 || oz >= size) continue;
						for (int mx = 0; mx < size; mx++) {
							int ox = mx + dx;
							if (ox >= 0 && ox < size) this.ready[mz * MASK_MAX + mx] = old[oz * MASK_MAX + ox];
						}
					}
				}
				this.readyX = mx0;
				this.readyZ = mz0;
				this.readySize = size;
			}
			int inner = Math.max(0, rd - 3);
			for (int mz = 0; mz < size; mz++) {
				for (int mx = 0; mx < size; mx++) {
					int cx = mx0 + mx, cz = mz0 + mz, k = mz * MASK_MAX + mx;
					if (this.ready[k] && Math.max(Math.abs(cx - ccx), Math.abs(cz - ccz)) <= inner && !refreshAll()) continue;
					boolean ready = false;
					LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, false);
					if (chunk != null) {
						ready = true;
					}
					this.ready[k] = ready;
				}
			}
		}
		if (!refresh && !moved && this.maskOn && Math.abs(this.camX - this.maskCamX) < 0.5 && Math.abs(this.camZ - this.maskCamZ) < 0.5) return;
		this.maskCamX = this.camX;
		this.maskCamZ = this.camZ;
		LodPk pk = this.pk;
		int[] oldMask = pk != null && this.maskOn ? this.mask.clone() : null;
		int oldX = this.maskX, oldZ = this.maskZ, oldSize = this.maskSize, oldWords = this.maskWords;
		this.maskX = mx0;
		this.maskZ = mz0;
		this.maskSize = size;
		this.maskWords = (size + 31) / 32;
		java.util.Arrays.fill(this.mask, 0);
		double max = rd * 16.0, max2 = max * max;
		for (int mz = 0; mz < size; mz++) {
			int oz = (mz0 + mz) * 16;
			double dz = Math.max(0, Math.max(oz - 1 - this.camZ, this.camZ - (oz + 17)));
			if (dz >= max) continue;
			for (int mx = 0; mx < size; mx++) {
				if (!this.ready[mz * MASK_MAX + mx]) continue;
				int ox = (mx0 + mx) * 16;
				double dx = Math.max(0, Math.max(ox - 1 - this.camX, this.camX - (ox + 17)));
				if (dx * dx + dz * dz >= max2) continue;
				int bit = mz * this.maskWords * 32 + mx;
				this.mask[bit >> 5] |= 1 << (bit & 31);
			}
		}
		this.maskOn = true;
		if (pk != null) this.maskChanges(pk, oldMask, oldX, oldZ, oldSize, oldWords);
		double near = (size / 2 - 1) * 16.0;
		for (int mz = 0; mz < size; mz++) {
			int oz = (mz0 + mz) * 16;
			double dz = Math.max(0, Math.max(oz - this.camZ, this.camZ - (oz + 16)));
			if (dz >= near) continue;
			for (int mx = 0; mx < size; mx++) {
				int bit = mz * this.maskWords * 32 + mx;
				if ((this.mask[bit >> 5] >>> (bit & 31) & 1) != 0) continue;
				int ox = (mx0 + mx) * 16;
				double dx = Math.max(0, Math.max(ox - this.camX, this.camX - (ox + 16)));
				near = Math.min(near, Math.sqrt(dx * dx + dz * dz));
			}
		}
		this.nearestFar = near;
	}

	private void maskChanges(LodPk pk, int @Nullable [] old, int oldX, int oldZ, int oldSize, int oldWords) {
		if (old == null) return;
		if (MASK_FAST && oldX == this.maskX && oldZ == this.maskZ && oldSize == this.maskSize && oldWords == this.maskWords) {
			if (MASK_VERIFY) {
				int slow = 0, fast = 0;
				for (int mz = 0; mz < this.maskSize; mz++) {
					for (int mx = 0; mx < this.maskSize; mx++) {
						if (bit(old, mx, mz, oldSize, oldWords) != bit(this.mask, mx, mz, this.maskSize, this.maskWords)) slow++;
					}
				}
				for (int k = 0; k < this.maskSize * this.maskWords; k++) fast += Integer.bitCount(old[k] ^ this.mask[k]);
				this.verifyCells++;
				if (slow != fast) this.verifyDiffs++;
			}
			for (int mz = 0; mz < this.maskSize; mz++) {
				for (int w = 0; w < this.maskWords; w++) {
					int k = mz * this.maskWords + w, x = old[k] ^ this.mask[k];
					while (x != 0) {
						int mx = w * 32 + Integer.numberOfTrailingZeros(x);
						x &= x - 1;
						if (mx < this.maskSize) pk.chunkChanged(this.maskX + mx, this.maskZ + mz);
					}
				}
			}
			return;
		}
		int x0 = Math.min(oldX, this.maskX), z0 = Math.min(oldZ, this.maskZ);
		int x1 = Math.max(oldX + oldSize, this.maskX + this.maskSize), z1 = Math.max(oldZ + oldSize, this.maskZ + this.maskSize);
		for (int cz = z0; cz < z1; cz++) {
			for (int cx = x0; cx < x1; cx++) {
				if (bit(old, cx - oldX, cz - oldZ, oldSize, oldWords) != bit(this.mask, cx - this.maskX, cz - this.maskZ, this.maskSize, this.maskWords)) pk.chunkChanged(cx, cz);
			}
		}
	}

	private static boolean bit(int[] mask, int mx, int mz, int size, int words) {
		if (mx < 0 || mz < 0 || mx >= size || mz >= size) return false;
		int b = mz * words * 32 + mx;
		return (mask[b >> 5] >>> (b & 31) & 1) != 0;
	}

	private final boolean[] ready = new boolean[MASK_MAX * MASK_MAX];

	private boolean updateMaskOwn() {
		mcopt.metal.own.OwnSeam.Window w = mcopt.metal.own.OwnSeam.mask(this.camX, this.camY, this.camZ, this.mask, MASK_MAX);
		if (w == null) return false;
		this.maskX = w.x0;
		this.maskZ = w.z0;
		this.maskSize = w.size;
		this.maskWords = w.words;
		this.maskOn = true;
		this.nearestFar = w.nearestFar;
		return true;
	}

	private static final boolean HANDOFF_DRAWN = "drawn".equals(System.getProperty("mcopt.lod.handoff", ""));
	private static final int HANDOFF_FRAMES = Integer.getInteger("mcopt.lod.handoffFrames", 2);
	private final boolean[] listed = new boolean[MASK_MAX * MASK_MAX], handed = new boolean[MASK_MAX * MASK_MAX];
	private final long[] listedSections = new long[MASK_MAX * MASK_MAX];
	private final byte[] listedRun = new byte[MASK_MAX * MASK_MAX];

	private static final boolean MASK_FAST = !"false".equals(System.getProperty("mcopt.lod.maskFast"));
	private static final boolean MASK_VERIFY = Boolean.getBoolean("mcopt.lod.maskVerify");
	static final boolean HANDOFF_UNSEEN = Boolean.parseBoolean(System.getProperty("mcopt.lod.handoffUnseen", String.valueOf(LodConfig.SMALL)));

	static final boolean CHUNK_HOLD = Boolean.parseBoolean(System.getProperty("mcopt.lod.chunkHold", String.valueOf(LodConfig.SMALL)));

	private boolean heldInView(int cx, int cz, ClientLevel level) {
		int mx = cx - this.readyX, mz = cz - this.readyZ;
		if (this.readyX == Integer.MIN_VALUE || mx < 0 || mz < 0 || mx >= this.readySize || mz >= this.readySize) return false;
		if (this.handed[mz * MASK_MAX + mx]) return false;
		return !this.unseen(cx * 16, cz * 16, level);
	}

	private boolean unseen(int ox, int oz, ClientLevel level) {
		float g = 32;
		return !this.frustum.testAab((float) (ox - this.camX) - g, (float) (level.getMinY() - this.camY), (float) (oz - this.camZ) - g,
			(float) (ox + 16 - this.camX) + g, (float) (level.getMaxY() - this.camY), (float) (oz + 16 - this.camZ) + g);
	}

	static final float HANDOFF_PUSH = Float.parseFloat(System.getProperty("mcopt.lod.handoffPush", LodConfig.SMALL ? "0.9995" : "0"));
	private static final boolean MASK_SPREAD = !"false".equals(System.getProperty("mcopt.lod.maskSpread"));
	private final int[] maskPrev = new int[MASK_MAX * MASK_MAX / 32];
	private final boolean[] handedPrev = new boolean[MASK_MAX * MASK_MAX];
	private final byte[] regionNeed = new byte[(MASK_MAX / 8 + 2) * (MASK_MAX / 8 + 2)];
	private long verifyCells, verifyDiffs;

	private static int[] copyInto(int[] src, int[] dst) {
		System.arraycopy(src, 0, dst, 0, src.length);
		return dst;
	}

	private static boolean[] copyInto(boolean[] src, boolean[] dst) {
		System.arraycopy(src, 0, dst, 0, src.length);
		return dst;
	}

	private boolean cellRead(int mx, int mz, double max2) {
		if (this.handed[mz * MASK_MAX + mx]) return false;
		int oz = (this.readyZ + mz) * 16, ox = (this.readyX + mx) * 16;
		double dz = Math.max(0, Math.max(oz - 1 - this.camZ, this.camZ - (oz + 17)));
		double dx = Math.max(0, Math.max(ox - 1 - this.camX, this.camX - (ox + 17)));
		return dx * dx + dz * dz < max2;
	}

	private boolean regionNeeded(int rx, int rz, int size, double max2) {
		int ix = (rx + 8) >> 3, iz = (rz + 8) >> 3, n = MASK_MAX / 8 + 2;
		if (ix < 0 || iz < 0 || ix >= n || iz >= n) return false;
		int m = iz * n + ix;
		if (this.regionNeed[m] != 0) return this.regionNeed[m] == 1;
		boolean need = false;
		for (int mz = Math.max(0, rz); mz < Math.min(size, rz + 8) && !need; mz++) {
			for (int mx = Math.max(0, rx); mx < Math.min(size, rx + 8); mx++) {
				if (this.cellRead(mx, mz, max2)) {
					need = true;
					break;
				}
			}
		}
		this.regionNeed[m] = (byte) (need ? 1 : 2);
		return need;
	}

	private final boolean[] listedRef = MASK_VERIFY ? new boolean[MASK_MAX * MASK_MAX] : new boolean[0];
	private final long[] listedSectionsRef = MASK_VERIFY ? new long[MASK_MAX * MASK_MAX] : new long[0];

	private double maskCamX = Double.NaN, maskCamZ = Double.NaN;

	private boolean refreshAll() {
		return this.frames % 512 == 1;
	}
	private int readyX = Integer.MIN_VALUE, readyZ, readySize;

	private final float[] sky = new float[3];
	private long probeLast, probeWindow = -1, probeReport;
	private final long[] probeNanos = new long[2], probeFrames = new long[2];
	private boolean probeOn;

	private boolean probe(long now) {
		if (LodConfig.PROBE_MS <= 0) return true;
		if (this.probeLast != 0) {
			int k = this.probeOn ? 1 : 0;
			this.probeNanos[k] += now - this.probeLast;
			this.probeFrames[k]++;
		}
		this.probeLast = now;
		long window = now / (LodConfig.PROBE_MS * 1_000_000L);
		if (window != this.probeWindow) {
			this.probeWindow = window;
			this.probeOn = !this.probeOn;
			this.probeLast = 0;
		}
		if (this.probeReport == 0) this.probeReport = now;
		if (now - this.probeReport > 5_000_000_000L && this.probeFrames[0] > 0 && this.probeFrames[1] > 0) {
			double off = this.probeNanos[0] / 1e6 / this.probeFrames[0], on = this.probeNanos[1] / 1e6 / this.probeFrames[1];
			System.out.printf("mcopt-lod probe: on %.4f ms (%d frames, %.0f fps), off %.4f ms (%d, %.0f fps), cost %.4f ms, columns %d, rows %d%n", on,
				this.probeFrames[1], 1000 / on, off, this.probeFrames[0], 1000 / off, on - off, this.lastColumns, this.lastR1 - this.lastR0 + 1);
			this.probeNanos[0] = this.probeNanos[1] = this.probeFrames[0] = this.probeFrames[1] = 0;
			this.probeReport = now;
		}
		return this.probeOn;
	}

	private float[] skyLight() {
		return this.lightmap(0);
	}

	private float[] lightmap(int block) {
		var s = Minecraft.getInstance().gameRenderer.gameRenderState().lightmapRenderState;
		float nv = s.nightVisionEffectIntensity;
		float r = Math.max(s.ambientColor.x(), s.nightVisionColor.x() * nv) + s.skyLightColor.x() * s.skyFactor;
		float g = Math.max(s.ambientColor.y(), s.nightVisionColor.y() * nv) + s.skyLightColor.y() * s.skyFactor;
		float b = Math.max(s.ambientColor.z(), s.nightVisionColor.z() * nv) + s.skyLightColor.z() * s.skyFactor;
		if (block > 0 && s.blockLightTint != null) {
			float level = block / 15.0F, lit = level / (4 - 3 * level) * s.blockFactor, white = 0.9F * (2 * level - 1) * (2 * level - 1);
			r += (s.blockLightTint.x() + (1 - s.blockLightTint.x()) * white) * lit;
			g += (s.blockLightTint.y() + (1 - s.blockLightTint.y()) * white) * lit;
			b += (s.blockLightTint.z() + (1 - s.blockLightTint.z()) * white) * lit;
		}
		float boss = s.bossOverlayWorldDarkening;
		r = r + (r * 0.7F - r) * boss;
		g = g + (g * 0.6F - g) * boss;
		b = b + (b * 0.6F - b) * boss;
		r = Math.clamp(r - s.darknessEffectScale, 0, 1);
		g = Math.clamp(g - s.darknessEffectScale, 0, 1);
		b = Math.clamp(b - s.darknessEffectScale, 0, 1);
		float max = Math.max(r, Math.max(g, b));
		if (max > 0) {
			float inv = 1 - max;
			float scaled = (1 - inv * inv * inv * inv) / max;
			r += (r * scaled - r) * s.brightness;
			g += (g * scaled - g) * s.brightness;
			b += (b * scaled - b) * s.brightness;
		}
		this.sky[0] = r;
		this.sky[1] = g;
		this.sky[2] = b;
		return this.sky;
	}

	private void stats(LodField w) {
		this.statFrames++;
		long now = System.nanoTime();
		if (now - this.statStart < 1_000_000_000L) return;
		this.guardReport();
		System.out.printf("mcopt-lod stats: parts ms/frame results %.4f update %.4f mask %.4f installs %.4f%n", this.partNanos[0] / 1e6 / this.statFrames,
			this.partNanos[1] / 1e6 / this.statFrames, this.partNanos[2] / 1e6 / this.statFrames, this.partNanos[3] / 1e6 / this.statFrames);
		java.util.Arrays.fill(this.partNanos, 0);
		if (HANDOFF_DRAWN) {
			System.out.printf("mcopt-lod stats: mask ms/frame walk %.4f cells %.4f changes %.4f nearest %.4f, builtColumn %.1f heights %.1f a frame, builtColumn ms/frame %.4f, worst frame %.3f ms%n",
				this.maskNanos[0] / 1e6 / this.statFrames, this.maskNanos[1] / 1e6 / this.statFrames, this.maskNanos[2] / 1e6 / this.statFrames,
				this.maskNanos[3] / 1e6 / this.statFrames, (double) this.maskBuilt / this.statFrames, (double) this.maskHeights / this.statFrames,
				this.maskBuiltNanos / 1e6 / this.statFrames, this.maskMax / 1e6);
			java.util.Arrays.fill(this.maskNanos, 0);
			this.maskBuilt = this.maskHeights = this.maskBuiltNanos = this.maskMax = 0;
		}
		System.out.printf("mcopt-lod stats: %d fps, cpu %.3f ms/frame, columns %d, band rows %d..%d, tiles needed %d missing %d, generated %d (%.1f ms avg), "
				+ "loaded %d, pending %d, queued %d, chunks %d, nearest far %.0f, settled %s%s%n",
			this.statFrames, this.statCpuNanos / 1e6 / this.statFrames, this.lastColumns, this.lastR0, this.lastR1, w.needed, w.missing, w.generated.get(),
			w.generated.get() == 0 ? 0 : w.genNanos.get() / 1e6 / w.generated.get(), w.loaded.get(), w.pendingJobs(), w.queued(), w.chunksSummarized.get(),
			this.nearestFar, w.settled ? String.format("%.1fs", (w.settledNanos - w.startNanos) / 1e9) : "no",
			(LodField.CHUNK_TILES ? String.format(", chunk tiles %d (generations skipped %d)", w.chunkTiles.get(), w.chunkSkipped.get()) : "")
				+ (CHUNK_HOLD ? String.format(", chunks held %d (released %d)", w.heldCount(), w.heldReleased) : "")
				+ (w.saved != null ? String.format(", saved chunks read %d (%.2f ms avg) applied %d skipped %d", w.saved.read.get(),
					w.saved.read.get() == 0 ? 0 : w.saved.nanos.get() / 1e6 / w.saved.read.get(), w.saved.applied.get(), w.saved.skipped.get()) : ""));
		StringBuilder gen = new StringBuilder();
		for (int i = 0; i < 16; i++) {
			long n = w.noise.stageNanos.get(i * 4 + 3);
			if (n == 0) continue;
			gen.append(String.format(" L%d:%d tiles %.0f/%.0f/%.0f ms", i, n, w.noise.stageNanos.get(i * 4) / 1e6 / n, w.noise.stageNanos.get(i * 4 + 1) / 1e6 / n,
				w.noise.stageNanos.get(i * 4 + 2) / 1e6 / n));
		}
		if (gen.length() > 0) System.out.println("mcopt-lod stats: generation density/biomes/materials per tile:" + gen);
		LodMesh mesh = this.mesh;
		if (mesh != null) {
			long n = mesh.meshed.get();
			System.out.printf("mcopt-lod stats: mesh %d tiles installed, %d meshed (%.2f ms, %.0f quads a tile), %d pending, arena %.1f of %.0f MB%n", mesh.installed, n,
				n == 0 ? 0 : mesh.meshNanos.get() / 1e6 / n, n == 0 ? 0 : (double) mesh.meshQuads.get() / n, mesh.pending(), mesh.usedMb(), mesh.arenaMb());
		}
		LodPk pk = this.pk;
		if (pk != null) {
			System.out.printf("mcopt-lod stats: pk %d frames (+%d fast: full cull, %d switches), %d with stale sectors in view (%d sectors drawn live; stale in view:"
					+ " %d unbuilt, %d dirty, %d expired), %d sectors rebuilt (%d of the hand-off band), %d generations, %d overflows, %d mesh changes skipped (live ring, level not drawn"
					+ " there), %d occluder drops, records %.0f MB, %d sectors pruned to the visible set%n",
				pk.statFrames, pk.statFastFrames, pk.statSwitches, pk.statFallbacks, pk.statLive, pk.statUnbuilt, pk.statDirty, pk.statExpired, pk.statRebuilt,
				pk.statRebuiltNear, pk.statGenerations, pk.statOverflows, pk.statSkipped, pk.statOccDrops, pk.recordsMb(), pk.statVisPruned);
			pk.statVisPruned = 0;
			if (LodPk.LOAD) System.out.println("mcopt-lod stats: pk " + pk.loadState());
			this.pkSwitches += pk.statSwitches;
			pk.statOccDrops = 0;
			this.pkFast += pk.statFastFrames;
			pk.statSkipped = pk.statLive = pk.statUnbuilt = pk.statDirty = pk.statExpired = pk.statFastFrames = pk.statSwitches = 0;
			this.pkFrames += pk.statFrames;
			this.pkFallbacks += pk.statFallbacks;
			this.pkRebuilt += pk.statRebuilt;
			this.pkRebuiltNear += pk.statRebuiltNear;
			this.pkGenerations += pk.statGenerations;
			pk.statFrames = pk.statFallbacks = pk.statRebuilt = pk.statRebuiltNear = pk.statGenerations = pk.statOverflows = 0;
		}
		this.statStart = now;
		this.statFrames = 0;
		this.statCpuNanos = 0;
	}

	private long pkFrames, pkFallbacks, pkRebuilt, pkRebuiltNear, pkGenerations, pkFast, pkSwitches;
	private boolean cullTimes = true;
	private int guardSeen;

	private void guardReport() {
		int trips = MemoryUtil.memGetInt(this.dbgAddr);
		if (trips == this.guardSeen) return;
		this.guardSeen = trips;
		long a = this.dbgAddr;
		System.out.printf("mcopt-lod: walk guard tripped %d times; first: column %d dIn %.3f level %d cell %d,%d tMax %.3f,%.3f h %.6f,%.6f row %d t %.5f%n", trips,
			MemoryUtil.memGetInt(a + 4), Float.intBitsToFloat(MemoryUtil.memGetInt(a + 8)), MemoryUtil.memGetInt(a + 12), MemoryUtil.memGetInt(a + 16),
			MemoryUtil.memGetInt(a + 20), Float.intBitsToFloat(MemoryUtil.memGetInt(a + 24)), Float.intBitsToFloat(MemoryUtil.memGetInt(a + 28)),
			Float.intBitsToFloat(MemoryUtil.memGetInt(a + 32)), Float.intBitsToFloat(MemoryUtil.memGetInt(a + 36)), MemoryUtil.memGetInt(a + 40),
			Float.intBitsToFloat(MemoryUtil.memGetInt(a + 44)));
	}

	public static void chunk(net.minecraft.world.level.chunk.LevelChunk chunk) {
		Lod l = instance;
		if (l == null || l.field == null || !LodConfig.CHUNKS || chunk == null) return;
		l.field.chunk(chunk);
	}

	private final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap edited = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
	private static final long EDIT_QUIET_NS = 150_000_000L;

	private static int editLog = Integer.getInteger("mcopt.lod.editLog", 0);
	long statEdits, statResnapshots;

	public static void blockChanged(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState from,
		net.minecraft.world.level.block.state.BlockState to) {
		Lod l = instance;
		if (l == null || l.field == null || !LodMesh.REAL_OCC || !LodConfig.CHUNKS) return;
		l.statEdits++;
		if (editLog > 0) {
			editLog--;
			System.out.println("mcopt-lod: block changed at " + pos.toShortString() + ": " + from + " -> " + to);
		}
		l.edited.put(net.minecraft.world.level.ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4), System.nanoTime());
	}

	private void resnapshot(Minecraft mc, long now) {
		if (mc.level == null) {
			this.edited.clear();
			return;
		}
		var it = this.edited.long2LongEntrySet().fastIterator();
		while (it.hasNext()) {
			var e = it.next();
			if (now - e.getLongValue() < EDIT_QUIET_NS) continue;
			long k = e.getLongKey();
			LevelChunk c = mc.level.getChunkSource().getChunk(net.minecraft.world.level.ChunkPos.getX(k), net.minecraft.world.level.ChunkPos.getZ(k), false);
			if (c != null) {
				this.field.chunk(c);
				this.statResnapshots++;
			}
			it.remove();
		}
	}

	public static void unloaded(net.minecraft.world.level.chunk.LevelChunk chunk) {
		Lod l = instance;
		if (l == null || !HANDOFF_DRAWN || chunk == null) return;
		int mx = chunk.getPos().x() - l.readyX, mz = chunk.getPos().z() - l.readyZ;
		if (l.readyX == Integer.MIN_VALUE || mx < 0 || mz < 0 || mx >= l.readySize || mz >= l.readySize) return;
		l.handed[mz * MASK_MAX + mx] = false;
		l.listedRun[mz * MASK_MAX + mx] = 0;
	}

	private final java.util.List<long[]> timeline = new java.util.ArrayList<>();
	private long lastSample;

	private void sample(LodField w, long now) {
		if (now - this.lastSample < 1_000_000_000L) return;
		this.lastSample = now;
		if (this.timeline.size() < 36000) {
			this.timeline.add(new long[] {System.currentTimeMillis(), w.pendingJobs(), w.queued(), w.settled ? 1 : 0, this.lastColumns, w.missing});
		}
	}

	public static boolean settled() {
		Lod l = instance;
		return l != null && l.field != null && l.field.settled;
	}

	public static double settleSeconds() {
		Lod l = instance;
		if (l == null || l.field == null || !l.field.settled) return Double.NaN;
		return (l.field.settledNanos - l.field.startNanos) / 1e9;
	}

	public static java.util.Map<String, Object> report() {
		java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
		Lod l = instance;
		if (l == null || l.field == null || l.clip == null) return m;
		LodField w = l.field;
		m.put("reachChunks", LodConfig.RADIUS_CHUNKS);
		m.put("n", l.clip.n);
		m.put("levels", l.clip.levels);
		m.put("clipMb", l.clip.bytes() / 1048576.0);
		m.put("outMb", (double) l.outW * l.outH * 8 / 1048576.0);
		m.put("tilesNeeded", w.needed);
		m.put("tilesMissing", w.missing);
		m.put("generated", w.generated.get());
		m.put("genMsTotal", w.genNanos.get() / 1e6);
		m.put("loadedFromDisk", w.loaded.get());
		double settle = settleSeconds();
		m.put("settleSeconds", Double.isNaN(settle) ? null : settle);
		m.put("threads", LodConfig.THREADS);
		m.put("firstSettleSeconds", w.firstSettledNanos == 0 ? null : (w.firstSettledNanos - w.startNanos) / 1e9);
		m.put("chunksSummarized", w.chunksSummarized.get());
		if (LodField.CHUNK_TILES) {
			m.put("chunkTiles", w.chunkTiles.get());
			m.put("chunkSkipped", w.chunkSkipped.get());
		}
		m.put("snapshotUs", w.snapshots == 0 ? 0 : w.snapNanos / 1e3 / w.snapshots);
		m.put("blockEdits", l.statEdits);
		m.put("resnapshots", l.statResnapshots);
		m.put("columns", l.lastColumns);
		m.put("render", LodConfig.MESH ? "mesh" : "walk");
		if (l.mesh != null) {
			m.put("meshTiles", l.mesh.installed);
			m.put("meshArenaMb", l.mesh.usedMb());
			m.put("meshMsPerTile", l.mesh.meshed.get() == 0 ? null : l.mesh.meshNanos.get() / 1e6 / l.mesh.meshed.get());
		}
		if (l.pk != null) {
			m.put("pkFrames", l.pkFrames);
			m.put("pkFallbacks", l.pkFallbacks);
			m.put("pkRebuilt", l.pkRebuilt);
			m.put("pkRebuiltNear", l.pkRebuiltNear);
			m.put("pkGenerations", l.pkGenerations);
			m.put("pkFastFrames", l.pkFast);
			m.put("pkSwitches", l.pkSwitches);
			m.put("pkProbes", l.pk.statProbes);
		}
		m.put("bandRows", l.lastR1 - l.lastR0 + 1);
		m.put("timeline", l.timeline);
		return m;
	}
}
