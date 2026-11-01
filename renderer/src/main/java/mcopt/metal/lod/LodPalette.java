package mcopt.metal.lod;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.level.block.state.BlockState;
import org.lwjgl.system.MemoryUtil;

final class LodPalette {
	static final int MAX = 1024;
	static final int STRIDE = 20;
	private static final ConcurrentHashMap<BlockState, Integer> IDS = new ConcurrentHashMap<>();
	private static final AtomicInteger NEXT = new AtomicInteger(1);
	private static long table;

	private LodPalette() {
	}

	static synchronized void bind(long address) {
		table = address;
		MemoryUtil.memSet(address, 0, (long) MAX * STRIDE * 4);
		IDS.forEach(LodPalette::write);
	}

	static int id(BlockState state) {
		if (state.isAir() || !state.getFluidState().isEmpty() && state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) return 0;
		Integer id = IDS.get(state);
		if (id != null) return id;
		synchronized (LodPalette.class) {
			id = IDS.get(state);
			if (id != null) return id;
			int n = NEXT.get();
			if (n >= MAX) return 0;
			NEXT.incrementAndGet();
			write(state, n);
			STATES.put(n, state);
			IDS.put(state, n);
			return n;
		}
	}

	private static void write(BlockState state, int n) {
		if (table == 0) return;
		LodColors.Look l = LodColors.look(state);
		long at = table + (long) n * STRIDE * 4;
		for (int i = 0; i < 4; i++) {
			MemoryUtil.memPutFloat(at + i * 4L, l.topUv()[i]);
			MemoryUtil.memPutFloat(at + 16 + i * 4L, l.sideUv()[i]);
		}
		putRgb(at + 32, l.top());
		putRgb(at + 48, l.side());
		for (int i = 0; i < 4; i++) MemoryUtil.memPutInt(at + 64 + i * 4L, l.profile()[i]);
	}

	private static void putRgb(long at, int rgb) {
		MemoryUtil.memPutFloat(at, ((rgb >> 16) & 255) / 255.0F);
		MemoryUtil.memPutFloat(at + 4, ((rgb >> 8) & 255) / 255.0F);
		MemoryUtil.memPutFloat(at + 8, (rgb & 255) / 255.0F);
		MemoryUtil.memPutFloat(at + 12, 0);
	}

	private static final ConcurrentHashMap<Integer, BlockState> STATES = new ConcurrentHashMap<>();

	static @org.jspecify.annotations.Nullable BlockState stateOf(int id) {
		return STATES.get(id);
	}

	static String nameOf(int id) {
		BlockState s = STATES.get(id);
		return s == null ? "" : net.minecraft.world.level.block.state.BlockState.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, s).result()
			.map(Object::toString).orElse("");
	}

	static int idOf(String name) {
		if (name.isEmpty()) return 0;
		Integer cached = BY_NAME.get(name);
		if (cached != null) return cached;
		int id = net.minecraft.world.level.block.state.BlockState.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, com.google.gson.JsonParser.parseString(name))
			.result().map(LodPalette::id).orElse(0);
		BY_NAME.put(name, id);
		return id;
	}

	private static final ConcurrentHashMap<String, Integer> BY_NAME = new ConcurrentHashMap<>();

	static int word(int top, int side, int below) {
		return (top & 1023) | (side & 1023) << 10 | (below & 1023) << 20;
	}
}
