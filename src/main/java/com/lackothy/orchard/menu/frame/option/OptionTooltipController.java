// From Reese's Sodium Options 2.2.5 by FlashyReese (MIT, see licenses/reeses-sodium-options.txt), modified for Orchard.
package com.lackothy.orchard.menu.frame.option;

import com.lackothy.orchard.menu.option.MenuGroup;
import com.lackothy.orchard.menu.option.MenuOption;

import com.lackothy.orchard.menu.config.MenuConfig;
import com.lackothy.orchard.menu.layout.LayoutBounds;
import com.lackothy.orchard.menu.option.OptionExtended;
import com.lackothy.orchard.menu.state.OptionStateStore;
import com.lackothy.orchard.menu.theme.GuiThemes;
import com.lackothy.orchard.menu.widget.BaseWidget;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

final class OptionTooltipController {
    private static final int DEFAULT_TOOLTIP_BORDER_COLOR = 0xFF94E4D3;
    private static final int TEXT_PADDING = 3;
    private static final int BOX_PADDING = 3;
    private static final int LINE_HEIGHT = 12;

    private final LayoutBounds viewportBounds;
    private final MenuGroup modOptions;
    private final OptionStateStore optionStateStore;
    private long targetStartTime;
    private @Nullable OptionRow targetElement;

    OptionTooltipController(LayoutBounds viewportBounds, MenuGroup modOptions, OptionStateStore optionStateStore) {
        this.viewportBounds = viewportBounds;
        this.modOptions = modOptions;
        this.optionStateStore = optionStateStore;
    }

    void render(GuiGraphicsExtractor guiGraphics, List<OptionRow> optionRows, int mouseX, int mouseY) {
        OptionRow targetElement = this.findTargetOptionRow(optionRows, mouseX, mouseY);
        if (targetElement != null && this.targetElement == targetElement) {
            if (this.targetStartTime == 0) {
                this.targetStartTime = System.currentTimeMillis();
            }

            this.renderTooltip(guiGraphics, targetElement);
        } else {
            this.targetStartTime = 0;
            this.targetElement = targetElement;
        }
    }

    private @Nullable OptionRow findTargetOptionRow(List<OptionRow> optionRows, int mouseX, int mouseY) {
        OptionRow hoveredElement = this.findHoveredOptionRow(optionRows, mouseX, mouseY);
        if (hoveredElement != null) {
            return hoveredElement;
        }

        OptionRow focusedElement = this.findFocusedOptionRow(optionRows);
        if (focusedElement != null) {
            return focusedElement;
        }

        return this.findSelectedSearchResultRow(optionRows);
    }

    private @Nullable OptionRow findHoveredOptionRow(List<OptionRow> optionRows, int mouseX, int mouseY) {
        if (!this.viewportBounds.contains(mouseX, mouseY)) {
            return null;
        }

        return optionRows.stream()
                .filter(this::isVisibleOptionRow)
                .filter(optionRow -> optionRow.isMouseOver(mouseX, mouseY))
                .findFirst()
                .orElse(null);
    }

    private @Nullable OptionRow findFocusedOptionRow(List<OptionRow> optionRows) {
        if (!BaseWidget.isKeyboardFocusVisible()) {
            return null;
        }

        return optionRows.stream()
                .filter(this::isVisibleOptionRow)
                .filter(OptionRow::isFocused)
                .findFirst()
                .orElse(null);
    }

    private @Nullable OptionRow findSelectedSearchResultRow(List<OptionRow> optionRows) {
        if (!this.optionStateStore.searchActive()) {
            return null;
        }

        return optionRows.stream()
                .filter(this::isVisibleOptionRow)
                .filter(this::isSelectedSearchResult)
                .findFirst()
                .orElse(null);
    }

    private boolean isSelectedSearchResult(OptionRow optionRow) {
        if (!(optionRow.getOption() instanceof OptionExtended optionExtended)) {
            return false;
        }

        return this.optionStateStore.optionUiState(optionExtended.rso$getId()).isSelected();
    }

    private boolean isVisibleOptionRow(OptionRow optionRow) {
        return this.viewportBounds.overlaps(optionRow.getDimensions());
    }

    private void renderTooltip(GuiGraphicsExtractor guiGraphics, OptionRow element) {
        if (this.targetStartTime + MenuConfig.config().getTooltipDelayMs() > System.currentTimeMillis()) {
            return;
        }

        LayoutBounds dim = element.getDimensions();
        int boxWidth = dim.width();
        int boxY = dim.getLimitY();
        int boxX = dim.x();
        List<FormattedCharSequence> tooltip = this.buildTooltip(element.getOption(), boxWidth);

        if (tooltip.isEmpty()) {
            return;
        }

        int boxHeight = (tooltip.size() * LINE_HEIGHT) + BOX_PADDING;
        int boxYLimit = boxY + boxHeight;
        int boxYCutoff = this.viewportBounds.getLimitY();

        if (boxYLimit > boxYCutoff) {
            boxY -= boxHeight + dim.height();
        }

        if (boxY < 0) {
            boxY = dim.getLimitY();
        }

        guiGraphics.fill(boxX, boxY, boxX + boxWidth, boxY + boxHeight, 0xE0000000);
        int borderColor = MenuConfig.config().isColorThemes() && MenuConfig.config().isThemedTooltipBorders()
                ? GuiThemes.fromTheme(this.modOptions.theme()).theme
                : DEFAULT_TOOLTIP_BORDER_COLOR;
        BaseWidget.border(guiGraphics, boxX, boxY, boxX + boxWidth, boxY + boxHeight, borderColor);

        for (int i = 0; i < tooltip.size(); i++) {
            guiGraphics.text(Minecraft.getInstance().font, tooltip.get(i), boxX + TEXT_PADDING, boxY + TEXT_PADDING + (i * LINE_HEIGHT), 0xFFFFFFFF, true);
        }
    }

    private List<FormattedCharSequence> buildTooltip(MenuOption option, int boxWidth) {
        List<FormattedCharSequence> tooltip = new ArrayList<>();

        if (MenuConfig.config().isTooltipOptionIds() && option instanceof OptionExtended optionExtended) {
            tooltip.add(Language.getInstance().getVisualOrder(Component.literal(optionExtended.rso$getId().toString()).withStyle(ChatFormatting.GRAY)));
            tooltip.add(Language.getInstance().getVisualOrder(Component.literal("")));
        }

        // Some of Orchard's lines say nothing more than their name.
        if (!option.getTooltip().getString().isBlank()) {
            tooltip.addAll(Minecraft.getInstance().font.split(option.getTooltip(), boxWidth - (TEXT_PADDING * 2)));
        }

        return tooltip;
    }
}
