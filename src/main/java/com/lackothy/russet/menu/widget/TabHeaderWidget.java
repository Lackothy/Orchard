// From Reese's Sodium Options 2.2.5 by FlashyReese (MIT, see licenses/reeses-sodium-options.txt), modified for Russet.
package com.lackothy.russet.menu.widget;

import com.lackothy.russet.menu.option.MenuGroup;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import com.lackothy.russet.menu.config.MenuConfig;
import com.lackothy.russet.menu.layout.LayoutBounds;
import com.lackothy.russet.menu.theme.GuiTheme;
import com.lackothy.russet.menu.theme.GuiThemes;
import com.lackothy.russet.menu.theme.IconRenderer;
import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.FocusNavigationEvent;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NonNull;

public class TabHeaderWidget extends BaseWidget {

    // tweak these to taste
    private static final float SCROLL_SPEED_PX_PER_SEC = 10f;
    private static final int TEXT_PADDING_RIGHT = 2;
    private static final long DWELL_MS = 1000;
    private static final int HEADER_BACKGROUND = 0xBE000000;
    private static final int HEADER_BACKGROUND_HOVERED = 0xE0000000;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int SECONDARY_TEXT_COLOR = 0xFFAAAAAA;
    private static final int TEXT_X = 5;
    private static final int ICON_SPACING = 4;

    private final MenuGroup modOptions;
    private final @Nullable Runnable action;
    private boolean selected;

    public TabHeaderWidget(LayoutBounds dim, MenuGroup modOptions) {
        this(dim, modOptions, null, false);
    }

    public TabHeaderWidget(LayoutBounds dim, MenuGroup modOptions, @Nullable Runnable action, boolean selected) {
        super(dim);
        this.modOptions = modOptions;
        this.action = action;
        this.selected = selected;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    public void applyScissor(GuiGraphicsExtractor guiGraphics, int x, int y, int width, int height, Runnable action) {
        guiGraphics.enableScissor(x, y, x + width, y + height);
        action.run();
        guiGraphics.disableScissor();
    }

    private void drawScrollingString(GuiGraphicsExtractor g, String text, int x, int y, int color, int availableWidth) {
        Font font = Minecraft.getInstance().font;

        if (availableWidth <= 0 || text == null || text.isEmpty()) return;

        int textWidth = font.width(text);
        int lineHeight = font.lineHeight;

        if (textWidth <= availableWidth) {
            g.text(font, text, x, y, color, false);
            return;
        }

        int overflow = textWidth - availableWidth;

        if (MenuConfig.config().isReducedMotion()) {
            this.applyScissor(g, x, y, availableWidth, lineHeight, () -> g.text(font, text, x, y, color, false));
            return;
        }

        long nowMs = Util.getMillis();

        // Duration (in ms) to traverse overflow pixels at the chosen speed
        double speedPxPerMs = SCROLL_SPEED_PX_PER_SEC / 1000.0;
        long travelMs = Math.max(1L, (long) Math.ceil(overflow / speedPxPerMs));

        // Full cycle: dwell(start) + forward + dwell(end) + back
        long cycleMs = DWELL_MS + travelMs + DWELL_MS + travelMs;

        long t = nowMs % cycleMs;

        double offset; // [0..overflow]

        if (t < DWELL_MS) {
            // pause at start
            offset = 0.0;
        } else if (t < DWELL_MS + travelMs) {
            // scroll forward 0 -> overflow
            long tt = t - DWELL_MS;
            offset = Math.min(overflow, tt * speedPxPerMs);
        } else if (t < DWELL_MS + travelMs + DWELL_MS) {
            // pause at end
            offset = overflow;
        } else {
            // scroll back overflow -> 0
            long tt = t - (DWELL_MS + travelMs + DWELL_MS);
            offset = Math.max(0.0, overflow - tt * speedPxPerMs);
        }

        this.applyScissor(g, x, y, availableWidth, lineHeight, () -> g.text(font, text, (int) (x - offset), y, color, false));
    }

    @Override
    public void extractRenderState(@NotNull GuiGraphicsExtractor guiGraphics, int i, int j, float f) {
        this.hovered = this.action != null && this.isMouseOver(i, j);
        this.applyScissor(guiGraphics, this.getX(), this.getY(), this.getWidth(), this.getHeight(), () -> {
            int primaryTextColor = this.primaryTextColor();
            int secondaryTextColor = this.secondaryTextColor();
            this.drawRect(guiGraphics, this.getX(), this.getY(), this.getLimitX(), this.getLimitY(), this.hovered ? HEADER_BACKGROUND_HOVERED : HEADER_BACKGROUND);
            if (this.selected) {
                this.drawRect(guiGraphics, this.getX(), this.getY(), this.getX() + 2, this.getLimitY(), this.accentColor());
            }
            int xOffset;
            if (!MenuConfig.config().isTabHeaderIcons() || this.modOptions.icon() == null) {
                xOffset = TEXT_X;
            } else {
                xOffset = IconRenderer.renderIconWithSpacing(guiGraphics, this.modOptions.icon(), primaryTextColor, modOptions.iconMonochrome(), this.getX(), this.getY(), this.getHeight(), ICON_SPACING);
            }

            int textX = this.getX() + xOffset;
            int available = (this.getX() + this.getWidth()) - textX - TEXT_PADDING_RIGHT;

            if (MenuConfig.config().isTabHeaderVersionLabels()) {
                drawScrollingString(guiGraphics, this.modOptions.name(), textX, this.getY() + 2, primaryTextColor, available);
                drawScrollingString(guiGraphics, this.modOptions.version(), textX, this.getY() + 12, secondaryTextColor, available);
            } else {
                int centeredY = this.getY() + Math.ceilDiv(this.getHeight() - Minecraft.getInstance().font.lineHeight, 2);
                drawScrollingString(guiGraphics, this.modOptions.name(), textX, centeredY, primaryTextColor, available);
            }

            if (this.shouldRenderFocusBorder()) {
                this.drawBorder(guiGraphics, this.getX(), this.getY(), this.getLimitX(), this.getLimitY(), GuiThemes.OPTION_FOCUS_BORDER);
            }
        });

        if (this.hovered) {
            guiGraphics.requestCursor(CursorTypes.POINTING_HAND);
        }
    }

    @Override
    public boolean mouseClicked(@NonNull MouseButtonEvent event, boolean doubleClick) {
        if (this.action != null && event.button() == InputConstants.MOUSE_BUTTON_LEFT && this.isMouseOver(event.x(), event.y())) {
            this.action.run();
            this.playClickSound();
            return true;
        }

        return false;
    }

    @Override
    public boolean keyPressed(@NonNull KeyEvent event) {
        if (this.action != null && this.isFocused() && event.isSelection()) {
            this.action.run();
            this.playClickSound();
            return true;
        }

        return false;
    }

    @Override
    public @Nullable ComponentPath nextFocusPath(@NotNull FocusNavigationEvent navigation) {
        if (this.action == null || this.isFocused()) {
            return null;
        }

        return ComponentPath.leaf(this);
    }

    @Override
    public boolean isActive() {
        return this.action != null;
    }

    @Override
    public void updateNarration(@NonNull NarrationElementOutput builder) {
        if (this.action == null) {
            return;
        }

        Component label = Component.literal(this.modOptions.name());
        if (MenuConfig.config().isTabHeaderVersionLabels()) {
            label = CommonComponents.optionNameValue(label, Component.literal(this.modOptions.version()));
        }

        this.addButtonNarration(builder, label);
    }

    private boolean useThemedText() {
        return MenuConfig.config().isColorThemes() && MenuConfig.config().isThemedHeadersAndLabels();
    }

    private GuiTheme theme() {
        return GuiThemes.fromTheme(this.modOptions.theme());
    }

    private int primaryTextColor() {
        return this.useThemedText() ? this.theme().themeLighter : TEXT_COLOR;
    }

    private int secondaryTextColor() {
        return this.useThemedText() ? this.theme().themeDarker : SECONDARY_TEXT_COLOR;
    }

    private int accentColor() {
        return this.useThemedText() ? this.theme().theme : TEXT_COLOR;
    }
}
