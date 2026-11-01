// From Reese's Sodium Options 2.2.5 by FlashyReese (MIT, see licenses/reeses-sodium-options.txt), modified for Orchard.
package com.lackothy.orchard.menu.frame.option;

import com.lackothy.orchard.menu.option.MenuSlider;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import com.lackothy.orchard.menu.config.MenuConfig;
import com.lackothy.orchard.menu.control.ControlGuide;
import com.lackothy.orchard.menu.layout.LayoutBounds;
import com.lackothy.orchard.menu.state.OptionStateStore;
import com.lackothy.orchard.menu.theme.GuiTheme;
import com.lackothy.orchard.menu.widget.BaseWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.Objects;

final class IntegerSliderOptionRow<V> extends AbstractOptionRow {
    private static final int SLIDER_WIDTH = 90;
    private static final int TRACK_HEIGHT = 10;
    private static final int THUMB_WIDTH = 4;
    private static final int VALUE_GAP = 6;

    private final MenuSlider<V> option;
    private double thumbPosition;
    private boolean sliderHeld;
    private boolean editMode;
    private boolean drawSlider;
    private int contentWidth;

    IntegerSliderOptionRow(LayoutBounds dim, GuiTheme theme, OptionStateStore optionStateStore, MenuSlider<V> option) {
        super(dim, theme, optionStateStore, option);
        this.option = option;
        this.thumbPosition = this.thumbPositionForValue(option.getValidatedValue());
        this.contentWidth = this.valueWidth();
    }

    @Override
    public MenuSlider<V> getOption() {
        return this.option;
    }

    @Override
    protected void prepareRender(int mouseX, int mouseY, float delta) {
        boolean canDrawSlider = this.option.isEnabled() && !this.option.shouldHideControl();
        this.drawSlider = canDrawSlider && (this.isMouseOverRow(mouseX, mouseY) || this.isRowFocused() || this.sliderHeld);
        int valueWidth = this.valueWidth();
        this.contentWidth = this.drawSlider ? SLIDER_WIDTH + VALUE_GAP + valueWidth : valueWidth;
    }

    @Override
    protected int controlContentWidth() {
        return this.contentWidth;
    }

    @Override
    public List<ControlGuide> controlGuides() {
        if (!this.canShowControlGuide()) {
            return List.of();
        }

        return this.editMode
                ? List.of(ControlGuide.navigationLeftRight(Component.translatable("rso.controller.guide.adjust_value")), ControlGuide.press(Component.translatable("rso.controller.guide.done")))
                : List.of(ControlGuide.press(Component.translatable("rso.controller.guide.edit_slider")));
    }

    @Override
    protected void renderControl(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float delta) {
        if (this.option.shouldHideControl()) {
            return;
        }

        Component value = this.displayValue();
        int valueWidth = this.font.width(value);
        int sliderX = this.sliderX();
        int sliderY = this.sliderY();

        if (this.drawSlider) {
            if (!this.sliderHeld) {
                this.thumbPosition = this.thumbPositionForValue(this.option.getValidatedValue());
            }

            int thumbX = sliderX + (int) (this.thumbPosition * SLIDER_WIDTH) - (THUMB_WIDTH / 2);
            int trackY = (int) (sliderY + (TRACK_HEIGHT / 2.0F) - 0.5D);

            this.drawRect(guiGraphics, sliderX, trackY, sliderX + SLIDER_WIDTH, trackY + 1, this.theme.themeLighter);
            this.drawRect(guiGraphics, thumbX, sliderY, thumbX + THUMB_WIDTH, sliderY + TRACK_HEIGHT, 0xFFFFFFFF);
            if (this.isRowFocused() && this.editMode && BaseWidget.isKeyboardFocusVisible()) {
                this.drawBorder(guiGraphics, thumbX - 1, sliderY - 1, thumbX + THUMB_WIDTH + 1, sliderY + TRACK_HEIGHT + 1, 0xFFFFFFFF);
            }

            this.drawString(guiGraphics, value, sliderX - valueWidth - VALUE_GAP, sliderY + (TRACK_HEIGHT / 2) - 4, 0xFFFFFFFF);
        } else {
            this.drawString(guiGraphics, value, sliderX + SLIDER_WIDTH - valueWidth, sliderY + (TRACK_HEIGHT / 2) - 4, 0xFFFFFFFF);
        }

        if (this.isMouseOverSlider(mouseX, mouseY)) {
            guiGraphics.requestCursor(this.sliderHeld ? CursorTypes.RESIZE_EW : CursorTypes.POINTING_HAND);
        }
    }

    @Override
    protected boolean controlMouseClicked(MouseButtonEvent event, boolean doubleClick) {
        this.sliderHeld = false;
        if (!this.option.isEnabled()
                || this.option.shouldHideControl()
                || event.button() != InputConstants.MOUSE_BUTTON_LEFT
                || !this.isMouseOverRow(event.x(), event.y())) {
            return false;
        }

        if (this.isMouseOverSlider(event.x(), event.y())) {
            this.setValueFromMouse(event.x());
            this.sliderHeld = true;
            this.actionButtons.holdLayout(true);
        }

        return true;
    }

    @Override
    public boolean mouseDragged(@NonNull MouseButtonEvent event, double deltaX, double deltaY) {
        if (!this.sliderHeld || event.button() != InputConstants.MOUSE_BUTTON_LEFT || !this.option.isEnabled()) {
            return false;
        }

        this.actionButtons.holdLayout(true);
        this.setValueFromMouse(event.x());

        return true;
    }

    @Override
    public boolean mouseReleased(@NonNull MouseButtonEvent event) {
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT || !this.sliderHeld) {
            return false;
        }

        this.sliderHeld = false;
        this.actionButtons.releaseLayoutHold();
        this.playClickSound();

        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (!MenuConfig.config().isShiftScrollSliderAdjustments()
                || !this.option.isEnabled()
                || this.option.shouldHideControl()
                || !Minecraft.getInstance().hasShiftDown()
                || !this.isMouseOverSlider(mouseX, mouseY)) {
            return false;
        }

        return this.adjustValue((int) verticalAmount);
    }

    @Override
    protected boolean controlKeyPressed(KeyEvent event) {
        if (!this.isRowFocused()) {
            return false;
        }

        if (event.isSelection()) {
            this.editMode = !this.editMode;
            return true;
        }

        if (this.editMode) {
            if (event.isLeft()) {
                return this.adjustValue(-1);
            } else if (event.isRight()) {
                return this.adjustValue(1);
            }
        }

        return false;
    }

    @Override
    protected boolean activateControl() {
        this.editMode = !this.editMode;

        return true;
    }

    @Override
    public boolean handleBackNavigation() {
        if (!this.editMode) {
            return false;
        }

        this.editMode = false;
        return true;
    }

    @Override
    protected void releaseMouseHold() {
        this.sliderHeld = false;
    }

    @Override
    protected void onControlFocusLost() {
        this.editMode = false;
    }

    private Component displayValue() {
        Component value = this.option.formatValue(this.option.getValidatedValue());

        return this.option.isEnabled() ? value : this.formatDisabledControlValue(value);
    }

    @Override
    protected Component narrationValue() {
        return !this.option.shouldHideControl() ? this.option.formatValue(this.option.getValidatedValue()) : null;
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

        if (this.isFocused()) {
            builder.add(NarratedElementType.USAGE, Component.translatable(this.editMode
                    ? "narration.slider.usage.focused"
                    : "narration.slider.usage.focused.keyboard_cannot_change_value"));
        } else if (this.isHovered()) {
            builder.add(NarratedElementType.USAGE, Component.translatable("narration.slider.usage.hovered"));
        }
    }

    private int valueWidth() {
        return this.font.width(this.displayValue());
    }

    private int sliderX() {
        return this.rightAlignedControlX(SLIDER_WIDTH);
    }

    private int sliderY() {
        return this.getDimensions().getCenterY() - TRACK_HEIGHT / 2;
    }

    private boolean isMouseOverSlider(double mouseX, double mouseY) {
        int sliderX = this.sliderX();
        int sliderY = this.sliderY();

        return mouseX >= sliderX
                && mouseX < sliderX + SLIDER_WIDTH
                && mouseY >= sliderY
                && mouseY < sliderY + TRACK_HEIGHT;
    }

    private double thumbPositionForValue(V value) {
        int steps = this.option.steps();

        if (steps == 0) {
            return 0.0D;
        }

        return Mth.clamp((double) this.option.position(value) / (double) steps, 0.0D, 1.0D);
    }

    private V valueForThumbPosition() {
        return this.option.at((int) Math.round(this.thumbPosition * this.option.steps()));
    }

    private void setValueFromMouse(double mouseX) {
        this.thumbPosition = Mth.clamp((mouseX - this.sliderX()) / (double) SLIDER_WIDTH, 0.0D, 1.0D);
        this.option.modifyValue(this.valueForThumbPosition());
    }

    private boolean adjustValue(int direction) {
        if (direction == 0) {
            return false;
        }

        V value = this.option.getValidatedValue();
        V nextValue = value;
        for (int i = 0; i < Math.abs(direction); i++) {
            nextValue = this.option.step(nextValue, Integer.signum(direction));
        }

        if (Objects.equals(nextValue, value)) {
            return false;
        }

        this.option.modifyValue(nextValue);
        this.thumbPosition = this.thumbPositionForValue(this.option.getValidatedValue());

        return true;
    }
}
