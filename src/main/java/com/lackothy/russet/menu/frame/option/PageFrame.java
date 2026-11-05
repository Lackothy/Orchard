// From Reese's Sodium Options 2.2.5 by FlashyReese (MIT, see licenses/reeses-sodium-options.txt), modified for Russet.
package com.lackothy.russet.menu.frame.option;

import com.lackothy.russet.menu.option.MenuGroup;
import com.lackothy.russet.menu.option.MenuOption;
import com.lackothy.russet.menu.option.MenuPage;

import com.lackothy.russet.menu.config.MenuConfig;
import com.lackothy.russet.menu.frame.AbstractFrame;
import com.lackothy.russet.menu.layout.LayoutBounds;
import com.lackothy.russet.menu.option.OptionExtended;
import com.lackothy.russet.menu.state.OptionStateStore;
import com.lackothy.russet.menu.widget.BaseWidget;
import com.lackothy.russet.menu.theme.GuiThemes;
import com.lackothy.russet.menu.theme.GuiTheme;
import com.lackothy.russet.menu.state.SearchResultOrder;
import com.lackothy.russet.menu.widget.LabelWidget;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.Identifier;
import org.apache.commons.lang3.Validate;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public class PageFrame extends AbstractFrame {
    protected final MenuPage page;
    private final OptionStateStore optionStateStore;
    private final OptionRowFactory optionRowFactory;
    private final OptionTooltipController tooltipController;
    private final Map<Identifier, LabelWidget> groupHeaderWidgets = new HashMap<>();
    private final Map<Identifier, OptionRow> optionRowWidgets = new HashMap<>();
    private @Nullable Consumer<Identifier> groupToggleRebuildHandler;
    private PageLayout layout;

    public PageFrame(Screen screen, LayoutBounds dim, boolean renderOutline, MenuPage page, MenuGroup modOptions, OptionStateStore optionStateStore) {
        super(dim, screen, renderOutline, modOptions);
        this.page = page;
        this.optionStateStore = optionStateStore;
        this.optionRowFactory = new OptionRowFactory(screen, this.optionRowTheme(), this.optionStateStore);
        this.tooltipController = new OptionTooltipController(dim, modOptions, this.optionStateStore);
        this.setupFrame();
        this.buildFrame();
    }

    public static Builder builder() {
        return new Builder();
    }

    public void setupFrame() {
        MenuConfig.ConfigData config = MenuConfig.config();
        this.layout = PageLayout.create(this.page, config.isHideNonMatchingOptions() && this.optionStateStore.searchActive(),
                this.optionStateStore.searchResults(), SearchResultOrder.DEFAULT,
                config.getDisabledOptionVisibility() == MenuConfig.DisabledOptionVisibility.HIDDEN,
                config.isCollapsibleGroups(), this.optionStateStore.collapsedOptionGroups());
        this.setContentHeight(this.layout.contentHeight());
        this.optionRowFactory.registerParentBounds(this.layout, this.getFrameDim());
    }

    @Override
    public void rebuildFrameContent() {
        this.setupFrame();
        this.buildFrame();
    }

    @Override
    public void buildFrame() {
        this.children.clear();
        this.optionRows.clear();

        for (PageLayout.Row row : this.layout.rows()) {
            if (row instanceof PageLayout.LabelRow labelRow) {
                this.children.add(this.createLabelWidget(labelRow));
            } else if (row instanceof PageLayout.OptionRow optionRow) {
                this.addOptionRow(optionRow);
            }
        }

        super.buildFrame();
    }

    private LabelWidget createLabelWidget(PageLayout.LabelRow labelRow) {
        LayoutBounds dim = this.createRowDimension(labelRow.y());
        int labelColor = this.labelColor();

        if (!labelRow.collapsible()) {
            return new LabelWidget(dim, labelRow.text(), labelColor);
        }

        Identifier collapseKey = labelRow.collapseKey();
        LabelWidget widget = this.groupHeaderWidgets.computeIfAbsent(collapseKey, key ->
                new LabelWidget(dim, labelRow.text(), labelColor, this.optionRowTheme(), key, () -> this.toggleGroup(key), labelRow.collapsed()));
        widget.setDim(dim);
        widget.setCollapsed(labelRow.collapsed());

        return widget;
    }

    private void toggleGroup(Identifier collapseKey) {
        Set<Identifier> collapsed = this.optionStateStore.collapsedOptionGroups();
        if (!collapsed.remove(collapseKey)) {
            collapsed.add(collapseKey);
        }
        if (this.groupToggleRebuildHandler != null) {
            this.groupToggleRebuildHandler.accept(collapseKey);
        } else {
            this.rebuildFrameContent();
            this.focusGroupHeader(collapseKey);
        }
    }

    public void setGroupToggleRebuildHandler(@Nullable Consumer<Identifier> groupToggleRebuildHandler) {
        this.groupToggleRebuildHandler = groupToggleRebuildHandler;
    }

    public boolean focusGroupHeader(Identifier collapseKey) {
        for (GuiEventListener child : this.children) {
            if (child instanceof LabelWidget labelWidget && collapseKey.equals(labelWidget.collapseKey())) {
                this.setFocused(labelWidget);
                return true;
            }
        }

        return false;
    }

    private void addOptionRow(PageLayout.OptionRow row) {
        LayoutBounds rowDim = this.createRowDimension(row.y());
        this.children.add(this.getOptionRow(row.option(), rowDim));
    }

    private OptionRow getOptionRow(MenuOption option, LayoutBounds dim) {
        Identifier optionId = option instanceof OptionExtended optionExtended ? optionExtended.rso$getId() : null;
        if (optionId == null) {
            return this.optionRowFactory.create(option, dim);
        }

        OptionRow optionRow = this.optionRowWidgets.get(optionId);
        if (optionRow == null || optionRow.getOption() != option) {
            optionRow = this.optionRowFactory.create(option, dim);
            this.optionRowWidgets.put(optionId, optionRow);
            return optionRow;
        }

        if (optionRow instanceof BaseWidget widget) {
            widget.setDim(dim);
        }
        this.optionRowFactory.registerOptionBounds(optionRow, dim);

        return optionRow;
    }

    private LayoutBounds createRowDimension(int y) {
        return LayoutBounds.relativeTo(this.getFrameDim(), 0, y, this.getWidth(), PageLayout.ROW_HEIGHT);
    }

    private void setContentHeight(int height) {
        this.setDim(this.getDimensions().withHeight(height));
    }

    @Override
    public void updateFrameDim(LayoutBounds dim) {
        this.setDim(dim);
        this.relayoutRows();
    }

    private void relayoutRows() {
        this.optionRowFactory.registerParentBounds(this.layout, this.getFrameDim());

        int childIndex = 0;
        for (PageLayout.Row row : this.layout.rows()) {
            if (childIndex >= this.children.size()) {
                return;
            }

            GuiEventListener child = this.children.get(childIndex++);
            if (child instanceof BaseWidget widget) {
                LayoutBounds rowDim = this.createRowDimension(row.y());
                widget.setDim(rowDim);
                if (child instanceof OptionRow optionRow) {
                    this.optionRowFactory.registerOptionBounds(optionRow, rowDim);
                }
            }
        }
    }

    @Override
    public void extractRenderState(@NotNull GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(guiGraphics, mouseX, mouseY, delta);
        this.tooltipController.render(guiGraphics, this.optionRows, mouseX, mouseY);
    }

    private GuiTheme optionRowTheme() {
        return MenuConfig.config().isColorThemes() ? GuiThemes.fromTheme(this.modOptions.theme()) : GuiThemes.DEFAULT_BUTTON;
    }

    private int labelColor() {
        return MenuConfig.config().isColorThemes() && MenuConfig.config().isThemedHeadersAndLabels()
                ? GuiThemes.fromTheme(this.modOptions.theme()).themeLighter
                : 0xFFFFFFFF;
    }

    public static class Builder {
        private LayoutBounds dim;
        private boolean renderOutline;
        private MenuPage page;
        private Screen screen;
        private MenuGroup modOptions;
        private OptionStateStore optionStateStore;

        public Builder withDimension(LayoutBounds dim) {
            this.dim = dim;
            return this;
        }

        public Builder withRenderOutline(boolean renderOutline) {
            this.renderOutline = renderOutline;
            return this;
        }

        public Builder withPage(MenuPage page) {
            this.page = page;
            return this;
        }

        public Builder withScreen(Screen screen) {
            this.screen = screen;
            return this;
        }

        public Builder withModOptions(MenuGroup modConfig) {
            this.modOptions = modConfig;
            return this;
        }

        public Builder withOptionStateStore(OptionStateStore optionStateStore) {
            this.optionStateStore = optionStateStore;
            return this;
        }

        public PageFrame build() {
            Validate.notNull(this.dim, "Dimension must be specified");
            Validate.notNull(this.page, "MenuOption MenuPage must be specified");
            Validate.notNull(this.screen, "Screen must be specified");
            Validate.notNull(this.modOptions, "Mod Options must be specified");
            Validate.notNull(this.optionStateStore, "MenuOption state store must be specified");

            return new PageFrame(this.screen, this.dim, this.renderOutline, this.page, this.modOptions, this.optionStateStore);
        }
    }
}
