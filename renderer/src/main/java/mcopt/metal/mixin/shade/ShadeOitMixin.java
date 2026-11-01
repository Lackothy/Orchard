/*
 * Copyright 2026 mcopt-shade contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by applicable
 * law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
 * OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package mcopt.metal.mixin.shade;

import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
abstract class ShadeOitMixin {
	@Unique private static boolean mcopt$logged;

	@Inject(method = "useImprovedTransparency", at = @At("HEAD"), cancellable = true)
	private void mcopt$noOit(CallbackInfoReturnable<Boolean> cir) {
		if ("false".equals(System.getProperty("mcopt.shade.oitOff"))) return;
		if (!mcopt$logged && net.minecraft.client.Minecraft.getInstance().options.improvedTransparency().get()) {
			mcopt$logged = true;
			System.out.println("[mcopt] shade: Improved Transparency is on in the video settings: kept off while the shaders run (they draw water, glass and rain"
				+ " themselves; -Dmcopt.shade.oitOff=false uses it anyway)");
		}
		cir.setReturnValue(false);
	}
}
