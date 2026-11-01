package mcopt.metal;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import java.util.List;
import org.jspecify.annotations.Nullable;

public interface PassDelegate {
	int @Nullable [] setPipeline(long enc, Object pipeline, List<BindGroupLayout.UniformDescription> uniforms);

	default void begin(long enc, boolean hasDepth) {
	}

	default void texture(long enc, int slot, Object view) {
	}

	boolean beforeDraw(long enc);

	void end(long enc);
}
