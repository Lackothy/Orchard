package com.lackothy.russet.menu.frame.option;

import com.lackothy.russet.menu.EntryScreen;
import com.lackothy.russet.menu.layout.LayoutBounds;
import com.lackothy.russet.menu.option.MenuEntry;
import com.lackothy.russet.menu.state.OptionStateStore;
import com.lackothy.russet.menu.theme.GuiTheme;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class EntryOptionRow extends AbstractOptionRow {
    private static final int MAX_CONTENT_WIDTH = 90;

    private final Screen screen;
    private final MenuEntry option;

    EntryOptionRow(Screen screen, LayoutBounds dim, GuiTheme theme, OptionStateStore optionStateStore, MenuEntry option) {
        super(dim, theme, optionStateStore, option);
        this.screen = screen;
        this.option = option;
    }

    @Override
    public MenuEntry getOption() {
        return this.option;
    }

    @Override
    protected int controlContentWidth() {
        return Math.min(MAX_CONTENT_WIDTH, this.font.width(this.displayValue()));
    }

    @Override
    protected void renderControl(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float delta) {
        Component value = this.displayValue();
        int x = this.rightAlignedControlX(this.font.width(value));
        this.drawString(guiGraphics, value, x, this.centeredTextY(), 0xFFFFFFFF);
        if (this.option.isEnabled()) {
            this.requestPointerCursorIfHovered(guiGraphics);
        }
    }

    @Override
    protected boolean activateControl() {
        if (!this.option.isEnabled()) {
            return false;
        }
        Minecraft.getInstance().gui.setScreen(new EntryScreen(this.screen, this.option));
        this.playClickSound();
        return true;
    }

    private Component displayValue() {
        String text = this.option.getValidatedValue();
        if (text == null || text.isEmpty()) {
            return Component.literal("Not set").withStyle(ChatFormatting.GRAY);
        }
        if (this.font.width(text) > MAX_CONTENT_WIDTH) {
            text = this.font.plainSubstrByWidth(text, MAX_CONTENT_WIDTH - this.font.width("...")) + "...";
        }
        Component value = Component.literal(text);
        return this.option.isEnabled() ? value : this.formatDisabledControlValue(value);
    }

    @Override
    protected Component narrationValue() {
        return Component.literal(this.option.getValidatedValue());
    }

    @Override
    protected void updateControlNarration(NarrationElementOutput builder) {
        if (!this.option.isEnabled()) {
            builder.add(NarratedElementType.HINT, Component.translatable("rso.narration.option_unavailable"));
            return;
        }
        this.addUsageNarration(builder, "narration.button.usage.focused", "narration.button.usage.hovered");
    }
}
