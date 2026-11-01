/*
 * Copyright 2026 mcopt-shade contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package mcopt.metal.mixin.shade;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import mcopt.metal.shade.ShadeFrameUpdater;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.world.level.CardinalLighting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(RenderSectionRegion.class)
abstract class ShadeFaceShadeMixin {
	@Unique
	private static volatile CardinalLighting mcopt$flat;

	@ModifyReturnValue(method = "cardinalLighting", at = @At("RETURN"))
	private CardinalLighting mcopt$faceShadeOff(CardinalLighting lighting) {
		if (!ShadeFrameUpdater.faceShadeOff()) return lighting;
		CardinalLighting flat = mcopt$flat;
		float up = lighting.up();
		if (flat == null || flat.up() != up) mcopt$flat = flat = new CardinalLighting(up, up, up, up, up, up);
		return flat;
	}
}
