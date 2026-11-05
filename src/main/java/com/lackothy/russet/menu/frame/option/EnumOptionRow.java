// From Reese's Sodium Options 2.2.5 by FlashyReese (MIT, see licenses/reeses-sodium-options.txt), modified for Russet.
package com.lackothy.russet.menu.frame.option;

import com.lackothy.russet.menu.option.MenuChoice;

import com.mojang.blaze3d.platform.InputConstants;
import com.lackothy.russet.menu.config.MenuConfig;
import com.lackothy.russet.menu.control.ControlGuide;
import com.lackothy.russet.menu.layout.LayoutBounds;
import com.lackothy.russet.menu.state.OptionStateStore;
import com.lackothy.russet.menu.theme.GuiTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Objects;

final class EnumOptionRow<E> extends AbstractOptionRow {
    private static final int MAX_CONTENT_WIDTH = 70;

    private final MenuChoice<E> option;

    EnumOptionRow(LayoutBounds dim, GuiTheme theme, OptionStateStore optionStateStore, MenuChoice<E> option) {
        super(dim, theme, optionStateStore, option);
        this.option = option;
    }

    @Override
    public MenuChoice<E> getOption() {
        return this.option;
    }

    @Override
    protected int controlContentWidth() {
        return Math.min(MAX_CONTENT_WIDTH, this.font.width(this.displayValue()));
    }

    @Override
    public List<ControlGuide> controlGuides() {
        return this.canShowControlGuide() ? List.of(ControlGuide.press(Component.translatable("rso.controller.guide.next_value"))) : List.of();
    }

    @Override
    protected void renderControl(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float delta) {
        if (this.option.shouldHideControl()) {
            return;
        }

        Component value = this.displayValue();
        int valueWidth = this.font.width(value);
        int x = this.rightAlignedControlX(valueWidth);
        int y = this.centeredTextY();

        this.drawString(guiGraphics, value, x, y, 0xFFFFFFFF);

        if (this.option.isEnabled()) {
            this.requestPointerCursorIfHovered(guiGraphics);
        }
    }

    @Override
    protected boolean controlMouseClicked(MouseButtonEvent event, boolean doubleClick) {
        boolean reverse = Minecraft.getInstance().hasShiftDown();
        if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
            if (!MenuConfig.config().isReverseCyclingControls()) {
                return false;
            }

            reverse = true;
        } else if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
            return false;
        }

        if (!this.option.isEnabled()
                || this.option.shouldHideControl()
                || !this.isMouseOverRow(event.x(), event.y())) {
            return false;
        }

        this.cycleControl(reverse);

        return true;
    }

    @Override
    protected boolean controlKeyPressed(KeyEvent event) {
        if (!this.isRowFocused() || !event.isSelection()) {
            return false;
        }

        this.cycleControl(Minecraft.getInstance().hasShiftDown());

        return true;
    }

    @Override
    protected boolean activateControl() {
        this.cycleControl(Minecraft.getInstance().hasShiftDown());

        return true;
    }

    private Component displayValue() {
        Component value = this.option.getElementName(this.option.getValidatedValue());

        return this.option.isEnabled() ? value : this.formatDisabledControlValue(value);
    }

    @Override
    protected Component narrationValue() {
        return !this.option.shouldHideControl() ? this.option.getElementName(this.option.getValidatedValue()) : null;
    }

    @Override
    protected void updateControlNarration(NarrationElementOutput builder) {
        if (!this.option.isEnabled()) {
            builder.add(NarratedElementType.HINT, Component.translatable("rso.narration.option_unavailable"));
            return;
        }

        if (this.option.shouldHideControl()) {
            return;
        }

        Component nextValue = this.option.getElementName(this.nextValue(false));
        if (this.isFocused()) {
            builder.add(NarratedElementType.USAGE, Component.translatable("narration.cycle_button.usage.focused", nextValue));
        } else if (this.isHovered()) {
            builder.add(NarratedElementType.USAGE, Component.translatable("narration.cycle_button.usage.hovered", nextValue));
        }
    }

    private void cycleControl(boolean reverse) {
        E nextValue = this.nextValue(reverse);
        if (Objects.equals(nextValue, this.option.getValidatedValue())) {
            return;
        }

        this.option.modifyValue(nextValue);
        this.playClickSound();
    }

    private E nextValue(boolean reverse) {
        List<E> values = this.option.values();
        E currentValue = this.option.getValidatedValue();
        int valueIndex = Math.max(0, values.indexOf(currentValue));

        for (int i = 0; i < values.size(); i++) {
            valueIndex = reverse
                    ? (valueIndex + values.size() - 1) % values.size()
                    : (valueIndex + 1) % values.size();
            E nextValue = values.get(valueIndex);

            if (this.option.isValueAllowed(nextValue)) {
                return nextValue;
            }
        }

        return currentValue;
    }
}
