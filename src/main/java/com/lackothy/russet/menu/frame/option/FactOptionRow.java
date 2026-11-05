package com.lackothy.russet.menu.frame.option;

import com.lackothy.russet.menu.layout.LayoutBounds;
import com.lackothy.russet.menu.option.MenuFact;
import com.lackothy.russet.menu.state.OptionStateStore;
import com.lackothy.russet.menu.theme.GuiTheme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/** A row with nothing to set: a fact with its value where a control would be, or a note in grey. */
final class FactOptionRow extends AbstractOptionRow {
    private static final int VALUE_COLOR = 0xFFAAAAAA;

    private final MenuFact option;

    FactOptionRow(LayoutBounds dim, GuiTheme theme, OptionStateStore optionStateStore, MenuFact option) {
        super(dim, theme, optionStateStore, option);
        this.option = option;
    }

    @Override
    public MenuFact getOption() {
        return this.option;
    }

    @Override
    protected int controlContentWidth() {
        return this.option.isNote() ? 0 : Math.min(this.font.width(this.option.value()), this.getWidth() / 2);
    }

    @Override
    protected void renderControl(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float delta) {
        if (this.option.isNote()) {
            return;
        }

        String value = this.truncateTextToFit(this.option.value().getString(), this.controlContentWidth());
        this.drawString(guiGraphics, value, this.rightAlignedControlX(this.font.width(value)), this.centeredTextY(), VALUE_COLOR);
    }

    @Override
    protected boolean activateControl() {
        return false;
    }

    @Override
    protected @Nullable Component narrationValue() {
        return this.option.isNote() ? null : this.option.value();
    }
}
