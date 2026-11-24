// Russet: the names Sodium's config API gives mods, declared by Russet so mods that build Sodium pages
// work in Russet's menu without Sodium. Only loaded when Sodium isn't installed (SodiumConfigApi).
package net.caffeinemc.mods.sodium.api.config.option;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

public enum OptionImpact implements NameProvider {
    LOW(ChatFormatting.GREEN, "low"),
    MEDIUM(ChatFormatting.YELLOW, "medium"),
    HIGH(ChatFormatting.GOLD, "high"),
    VARIES(ChatFormatting.WHITE, "varies");

    private final Component name;

    OptionImpact(ChatFormatting colour, String key) {
        this.name = Component.translatable("sodium.option_impact." + key).withStyle(colour);
    }

    @Override
    public Component getName() {
        return name;
    }
}
