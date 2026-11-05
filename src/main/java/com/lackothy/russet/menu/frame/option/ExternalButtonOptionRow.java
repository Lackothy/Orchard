// From Reese's Sodium Options 2.2.5 by FlashyReese (MIT, see licenses/reeses-sodium-options.txt), modified for Russet.
package com.lackothy.russet.menu.frame.option;

import com.lackothy.russet.menu.option.MenuButton;

import com.lackothy.russet.menu.layout.LayoutBounds;
import com.lackothy.russet.menu.state.OptionStateStore;
import com.lackothy.russet.menu.theme.GuiTheme;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

final class ExternalButtonOptionRow extends AbstractOptionRow {
    private static final int CONTENT_WIDTH = 65;

    private final Screen screen;
    private final MenuButton option;

    ExternalButtonOptionRow(Screen screen, LayoutBounds dim, GuiTheme theme, OptionStateStore optionStateStore, MenuButton option) {
        super(dim, theme, optionStateStore, option);
        this.screen = screen;
        this.option = option;
    }

    @Override
    public MenuButton getOption() {
        return this.option;
    }

    @Override
    protected int controlContentWidth() {
        return CONTENT_WIDTH;
    }

    @Override
    protected void renderControl(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float delta) {
        Component text = this.buttonText();
        int x = this.rightAlignedControlX(this.font.width(text));
        int y = this.centeredTextY();

        this.drawString(guiGraphics, text, x, y, 0xFFFFFFFF);

        if (this.option.isEnabled()) {
            this.requestPointerCursorIfHovered(guiGraphics);
        }
    }

    @Override
    protected boolean activateControl() {
        if (!this.option.isEnabled()) {
            return false;
        }

        this.option.run();
        this.playClickSound();

        return true;
    }

    private Component buttonText() {
        if (!this.option.isEnabled()) {
            return this.option.label().copy().withStyle(ChatFormatting.STRIKETHROUGH, ChatFormatting.GRAY);
        }

        return Component.empty()
                .append(this.option.label().copy().withStyle(ChatFormatting.UNDERLINE))
                .append(Component.literal(" >").withStyle(Style.EMPTY.withColor(this.theme.theme)));
    }

    @Override
    protected void updateControlNarration(NarrationElementOutput builder) {
        if (!this.option.isEnabled()) {
            builder.add(NarratedElementType.HINT, Component.translatable("rso.narration.option_unavailable"));
            return;
        }

        this.addUsageNarration(builder, "narration.link.usage.focused", "narration.link.usage.hovered");
    }
}
