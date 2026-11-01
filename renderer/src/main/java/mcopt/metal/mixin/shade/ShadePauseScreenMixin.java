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

import mcopt.metal.shade.ShadeOutput;
import mcopt.metal.shade.ShadeResolution;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PauseScreen.class)
abstract class ShadePauseScreenMixin extends Screen {
	protected ShadePauseScreenMixin(Component title) {
		super(title);
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void mcopt$resolutionButton(CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return;
		if (!mcopt.metal.shade.Shade.ENABLED) {
			Screen self = this;
			this.addRenderableWidget(Button.builder(Component.literal("Shaders..."), button -> mc.gui.setScreen(new mcopt.metal.shade.ShadeOffScreen(self)))
				.bounds(this.width / 2 - 102, this.height - 28, 204, 20).build());
			return;
		}
		boolean resolution = mcopt.metal.shade.taa.ShadeTaa.ON
			&& !(mcopt.metal.shade.taa.ShadeTaa.failed() && !"false".equals(System.getProperty("mcopt.shade.robustFallback")));
		boolean output = resolution;
		if (resolution) {
			int w = mc.getWindow().getWidth(), h = mc.getWindow().getHeight();
			this.addRenderableWidget(Button.builder(Component.literal(ShadeResolution.label(w, h)), button -> {
				ShadeResolution.cycle(mc.gameDirectory.toPath());
				button.setMessage(Component.literal(ShadeResolution.label(mc.getWindow().getWidth(), mc.getWindow().getHeight())));
			}).tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(ShadeResolution.tooltip()))).bounds(output ? this.width / 2 - 206 : this.width / 2 - 102, this.height - 28, 204, 20).build());
		}
		if (output) {
			this.addRenderableWidget(Button.builder(Component.literal(ShadeOutput.label()), button -> {
				if (ShadeOutput.available()) ShadeOutput.cycle(mc.gameDirectory.toPath());
				button.setMessage(Component.literal(ShadeOutput.label()));
			}).tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(ShadeOutput.tooltip())))
				.bounds(this.width / 2 + 2, this.height - 28, 204, 20).build());
		}
		int row = resolution ? this.height - 52 : this.height - 28;
		if (mcopt.metal.shade.Shade.ENABLED && !"false".equals(System.getProperty("mcopt.shade.settingsButton"))) {
			Screen self = this;
			this.addRenderableWidget(Button.builder(Component.literal(mcopt.metal.shade.ShadeLang.t("Shaders...", "Shaders...")),
					button -> mc.gui.setScreen(new mcopt.metal.shade.ShadeSettingsScreen(self)))
				.tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(mcopt.metal.shade.ShadeLang.t(
					"Ajustes de los shaders: estilo, brillo, rayos de sol, neblina, nubes y agua.",
					"Shader settings: style, brightness, god rays, haze, clouds and water."))))
				.bounds(this.width / 2 - 102, row, 204, 20).build());
		}
	}
}
