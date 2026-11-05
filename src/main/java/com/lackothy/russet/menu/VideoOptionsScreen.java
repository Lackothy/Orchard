// From Reese's Sodium Options 2.2.5 by FlashyReese (MIT, see licenses/reeses-sodium-options.txt), modified for Russet.
package com.lackothy.russet.menu;

import com.lackothy.russet.menu.option.MenuChanges;
import com.lackothy.russet.menu.option.MenuSetting;
import com.lackothy.russet.video.Pages;
import com.lackothy.russet.video.VideoOptions;

import com.mojang.blaze3d.platform.InputConstants;
import com.lackothy.russet.menu.frame.AbstractFrame;
import com.lackothy.russet.menu.frame.BasicFrame;
import com.lackothy.russet.menu.frame.option.OptionRow;
import com.lackothy.russet.menu.frame.option.action.OptionUndoAction;
import com.lackothy.russet.menu.widget.FlatButtonWidget;
import com.lackothy.russet.menu.search.SearchTextFieldWidget;
import com.lackothy.russet.menu.frame.tab.Tab;
import com.lackothy.russet.menu.frame.tab.TabFrame;
import com.lackothy.russet.menu.layout.LayoutBounds;
import com.lackothy.russet.menu.option.OptionExtended;
import com.lackothy.russet.menu.state.OptionsScreenUiState;
import com.lackothy.russet.menu.widget.BaseWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.navigation.FocusNavigationEvent;
import net.minecraft.client.gui.navigation.ScreenDirection;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NonNull;
import org.lwjgl.sdl.SDLKeycode;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The video settings: Reese's Sodium Options' screen, over Russet's groups of settings instead of
 * Sodium's. It takes the place of Minecraft's Video Settings, and only those (see {@code GuiMixin}).
 * Its search bar is as wide as RSO's once Sodium's donate button is put away.
 */
public class VideoOptionsScreen extends Screen {

    private static final OptionsScreenUiState SHARED_UI_STATE = new OptionsScreenUiState();

    private static final double ASPECT_RATIO_16_9 = 16.0 / 9.0;
    private static final int TOOLBAR_BUTTON_WIDTH = 65;
    private static final int TOOLBAR_BUTTON_GAP = 4;
    private static final int TOOLBAR_BUTTON_Y_GAP = 5;
    private static final int TOP_ROW_HEIGHT = 20;
    private static final int TOP_ROW_Y_GAP = 26;

    /** Set while Minecraft's own Video Settings are being opened from here (Shift+P), so they are not swapped for this. */
    public static boolean openingVanilla;

    private final Screen prevScreen;
    private final OptionsScreenUiState uiState;
    private final MenuChanges changes;
    private int oldMipmaps;
    private int oldAnisotropy;
    private TextureFilteringMethod oldFiltering;
    private FlatButtonWidget applyButton, closeButton, undoButton;
    private boolean hasPendingChanges;
    private SearchTextFieldWidget searchTextField;
    private AbstractFrame rootFrame;
    private TabFrame tabFrame;
    private @Nullable ComponentPath previousArrowFocusPath;
    private @Nullable GuiEventListener currentArrowFocusLeaf;
    private @Nullable ScreenDirection lastArrowDirection;

    public VideoOptionsScreen(Screen prev) {
        super(Component.translatable("options.videoTitle"));
        this.prevScreen = prev;
        this.uiState = SHARED_UI_STATE;
        this.changes = new MenuChanges();

        Options options = Minecraft.getInstance().options;
        this.oldMipmaps = options.mipmapLevels().get();
        this.oldAnisotropy = options.maxAnisotropyBit().get();
        this.oldFiltering = options.textureFiltering().get();
    }

    /** Makes the next options open on the page called {@code page}. */
    public static void startOn(String page) {
        if (page.equals(MenuChanges.MENU_PAGE)) {
            openOn(MenuChanges.MENU_GROUP, page);
            return;
        }
        for (Pages.Group group : Pages.groups()) {
            if (group.pages().stream().anyMatch(p -> p.name().equals(page))) {
                openOn(MenuChanges.configId(group), page);
                return;
            }
        }
    }

    private static void openOn(String configId, String page) {
        SHARED_UI_STATE.tabFrameSelectedTab().set(configId + ":" + page);
        SHARED_UI_STATE.tabFrameSelectedGroup().set(configId);
        SHARED_UI_STATE.scrollSelectedTabIntoView().set(true);
        SHARED_UI_STATE.optionPageScrollBarOffset().set(0);
    }

    /** What the menu shows and the changes it holds until Apply. */
    public MenuChanges changes() {
        return this.changes;
    }

    public @Nullable FlatButtonWidget rso$getApplyButton() {
        return this.applyButton;
    }

    public @Nullable FlatButtonWidget rso$getCloseButton() {
        return this.closeButton;
    }

    public @Nullable FlatButtonWidget rso$getUndoButton() {
        return this.undoButton;
    }

    public @Nullable SearchTextFieldWidget rso$getSearchTextField() {
        return this.searchTextField;
    }

    public @Nullable TabFrame rso$getTabFrame() {
        return this.tabFrame;
    }

    // Hackalicious! Rebuild UI
    public void rebuildUI() {
        boolean wasSearchBarFocused = this.searchTextField.isFocused();
        this.clearArrowNavigationMemory();
        this.rebuildWidgets();
        if (wasSearchBarFocused) this.focusSearchTextField();
    }

    private void refreshSearchResults() {
        this.clearArrowNavigationMemory();
        if (this.tabFrame != null) {
            this.tabFrame.refreshFromState();
        }
    }

    @Override
    protected void init() {
        super.init();

        BaseWidget.setKeyboardFocusVisible(this.minecraft.getLastInputType().isKeyboard());
        this.rootFrame = this.parentFrameBuilder().build();
        this.addRenderableWidget(this.rootFrame);

        if (this.searchTextField.isFocused()) {
            this.focusSearchTextField();
        } else if (this.restoreFocusedOptionForSelectedTab()) {
            this.rememberCurrentOptionFocus();
        } else {
            this.setFocused(this.rootFrame);
        }

    }

    private void focusSearchTextField() {
        this.searchTextField.setFocused(true);

        if (this.rootFrame != null) {
            this.rootFrame.setFocused(this.searchTextField);
            this.setFocused(this.rootFrame);
        } else {
            this.setFocused(this.searchTextField);
        }
    }

    private BasicFrame.Builder parentFrameBuilder() {
        // Calculates if resolution exceeds 16:9 ratio, force 16:9
        int newWidth = this.width;
        if ((float) this.width / (float) this.height > ASPECT_RATIO_16_9) {
            newWidth = (int) (this.height * ASPECT_RATIO_16_9);
        }

        LayoutBounds basicFrameDim = new LayoutBounds((this.width - newWidth) / 2, 0, newWidth, this.height);
        LayoutBounds tabFrameDim = new LayoutBounds(basicFrameDim.x() + basicFrameDim.width() / 20 / 2, basicFrameDim.y() + basicFrameDim.height() / 4 / 2, basicFrameDim.width() - (basicFrameDim.width() / 20), basicFrameDim.height() / 4 * 3);

        int toolbarY = tabFrameDim.getLimitY() + TOOLBAR_BUTTON_Y_GAP;
        LayoutBounds closeButtonDim = new LayoutBounds(toolbarButtonX(tabFrameDim, 0), toolbarY, TOOLBAR_BUTTON_WIDTH, TOP_ROW_HEIGHT);
        LayoutBounds applyButtonDim = new LayoutBounds(toolbarButtonX(tabFrameDim, 1), toolbarY, TOOLBAR_BUTTON_WIDTH, TOP_ROW_HEIGHT);
        LayoutBounds undoButtonDim = new LayoutBounds(toolbarButtonX(tabFrameDim, 2), toolbarY, TOOLBAR_BUTTON_WIDTH, TOP_ROW_HEIGHT);

        int topRowY = tabFrameDim.y() - TOP_ROW_Y_GAP;

        this.undoButton = new FlatButtonWidget(undoButtonDim, Component.literal("Undo"), this.changes::resetAllOptionsFromBindings, true, false);
        this.applyButton = new FlatButtonWidget(applyButtonDim, Component.literal("Apply"), this::applyChanges, true, false);
        this.closeButton = new FlatButtonWidget(closeButtonDim, Component.translatable("gui.done"), this::onClose, true, false);

        BasicFrame.Builder basicFrameBuilder = this.parentBasicFrameBuilder(basicFrameDim, tabFrameDim);

        LayoutBounds searchTextFieldDim = new LayoutBounds(tabFrameDim.x(), topRowY, tabFrameDim.width(), TOP_ROW_HEIGHT);

        this.searchTextField = new SearchTextFieldWidget(searchTextFieldDim, this.changes.getModOptions(), this.uiState,
                tabFrameDim.height(), this::refreshSearchResults);

        basicFrameBuilder.addChild(() -> this.searchTextField);

        return basicFrameBuilder;
    }

    // Toolbar buttons are laid out right-to-left: slot 0 is the rightmost (close).
    private static int toolbarButtonX(LayoutBounds tabFrameDim, int slotFromRight) {
        return tabFrameDim.getLimitX() - (slotFromRight + 1) * TOOLBAR_BUTTON_WIDTH - slotFromRight * TOOLBAR_BUTTON_GAP;
    }

    private BasicFrame.Builder parentBasicFrameBuilder(LayoutBounds parentBasicFrameDim, LayoutBounds tabFrameDim) {
        return BasicFrame.builder()
                .withDimension(parentBasicFrameDim)
                .withRenderOutline(false)
                .withScreen(this)
                .addChild(() -> {
                    this.tabFrame = TabFrame.createBuilder()
                            .setDimension(tabFrameDim)
                            .withScreen(this)
                            .shouldRenderOutline(false)
                            .setTabRailScrollBarOffset(this.uiState.tabFrameScrollBarOffset())
                            .setScrollSelectedTabIntoView(this.uiState.scrollSelectedTabIntoView())
                            .setTabRailSelectedTab(this.uiState.tabFrameSelectedTab())
                            .setTabRailSelectedGroup(this.uiState.tabFrameSelectedGroup())
                            .setManuallyCollapsedTabGroups(this.uiState.manuallyCollapsedTabGroups())
                            .setOptionPageScrollBarOffset(this.uiState.optionPageScrollBarOffset())
                            .setOptionStateStore(this.uiState)
                            .addTabs(tabs -> this.changes.getModOptions()
                                    .forEach(config -> config.pages()
                                            .forEach(page -> tabs.add(Tab.builder().from(this, config, page, this.uiState.optionPageScrollBarOffset(), this.uiState))))
                            )
                            .onSetTab(() -> {
                                this.uiState.optionPageScrollBarOffset().set(0);
                            })
                            .build();
                    return this.tabFrame;
                })
                .addChild(() -> this.undoButton)
                .addChild(() -> this.applyButton)
                .addChild(() -> this.closeButton);
    }

    private void applyChanges() {
        boolean own = this.changes.applyAllOptions();
        this.reloadTexturesIfChanged();
        if (own) {
            // The menu's own settings change how it is drawn.
            this.rebuildUI();
        }
    }

    /** As Sodium does on Apply: textures are reloaded when what they are made with changed. */
    private void reloadTexturesIfChanged() {
        Options options = this.minecraft.options;
        if (options.mipmapLevels().get() != this.oldMipmaps || options.maxAnisotropyBit().get() != this.oldAnisotropy
                || options.textureFiltering().get() != this.oldFiltering) {
            this.minecraft.updateMaxMipLevel(options.mipmapLevels().get());
            this.minecraft.delayTextureReload();
            this.oldMipmaps = options.mipmapLevels().get();
            this.oldAnisotropy = options.maxAnisotropyBit().get();
            this.oldFiltering = options.textureFiltering().get();
        }
    }

    @Override
    public void extractRenderState(@NotNull GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float delta) {
        this.updateControls();
        super.extractRenderState(guiGraphics, mouseX, mouseY, delta);
    }

    private void updateControls() {
        boolean hasChanges = this.changes.anyOptionChanged();

        this.applyButton.setEnabled(hasChanges);
        this.undoButton.setVisible(hasChanges);
        this.closeButton.setEnabled(!hasChanges);

        this.hasPendingChanges = hasChanges;
    }

    @Override
    public boolean mouseClicked(@NonNull MouseButtonEvent event, boolean repeated) {
        this.clearArrowNavigationMemory();
        String previousTabKey = this.getSelectedTabKey();

        boolean handled = super.mouseClicked(event, repeated);
        this.afterInput(previousTabKey);

        return handled;
    }

    @Override
    public boolean mouseReleased(@NonNull MouseButtonEvent event) {
        boolean handled = super.mouseReleased(event);

        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && this.rootFrame != null) {
            this.rootFrame.releaseActionButtonLayoutHolds();
        }

        return handled;
    }

    @Override
    public boolean keyPressed(@NonNull KeyEvent event) {
        BaseWidget.setKeyboardFocusVisible(true);

        String previousTabKey = this.getSelectedTabKey();

        if (this.isSearchShortcut(event)) {
            this.focusSearchTextField();
            this.searchTextField.selectAllText();
            this.clearArrowNavigationMemory();

            return true;
        }

        if (event.isEscape() && this.handleFocusedOptionBackNavigation()) {
            this.clearArrowNavigationMemory();
            this.afterInput(previousTabKey);

            return true;
        }

        if (event.isEscape() && this.clearSearchText()) {
            this.clearArrowNavigationMemory();

            return true;
        }

        if (event.shortcutKey() == SDLKeycode.SDLK_P && event.hasShiftDown() && !this.isSearchTextFieldFocused()) {
            openingVanilla = true;
            try {
                this.minecraft.gui.setScreen(new VideoSettingsScreen(this.prevScreen, this.minecraft, this.minecraft.options));
            } finally {
                openingVanilla = false;
            }

            return true;
        }

        if (!this.isSearchTextFieldFocused()) {
            if (this.isUndoShortcut(event) && this.undoFocusedOption()) {
                this.clearArrowNavigationMemory();
                this.afterInput(previousTabKey);

                return true;
            }

            if (this.keyPressedOptionListNavigation(event)) {
                this.clearArrowNavigationMemory();
                this.afterInput(previousTabKey);

                return true;
            }

            if (this.isApplyShortcut(event)) {
                GuiEventListener focused = this.getFocused();
                if (focused != null && focused.keyPressed(event)) {
                    this.clearArrowNavigationMemory();
                    this.afterInput(previousTabKey);

                    return true;
                }

                if (this.changes.anyOptionChanged()) {
                    this.applyChanges();
                    this.updateControls();
                    this.clearArrowNavigationMemory();
                    this.afterInput(previousTabKey);

                    return true;
                }
            }
        }

        ScreenDirection arrowDirection = getArrowDirection(event);
        if (arrowDirection != null) {
            return this.keyPressedArrow(event, arrowDirection);
        }

        this.clearArrowNavigationMemory();

        boolean handled = super.keyPressed(event);
        if (handled) {
            this.afterInput(previousTabKey);
        }

        return handled;
    }

    private boolean isSearchShortcut(KeyEvent event) {
        return event.shortcutKey() == InputConstants.KEYCODE_F && event.hasControlDown();
    }

    private boolean isUndoShortcut(KeyEvent event) {
        return event.shortcutKey() == InputConstants.KEYCODE_Z && event.hasControlDown();
    }

    private boolean isApplyShortcut(KeyEvent event) {
        return event.isConfirmation();
    }

    private boolean isSearchTextFieldFocused() {
        return this.searchTextField != null && this.searchTextField.isFocused();
    }

    private boolean clearSearchText() {
        if (this.searchTextField == null || !this.searchTextField.hasText()) {
            return false;
        }

        this.searchTextField.clearText();
        this.focusSearchTextField();

        return true;
    }

    private boolean keyPressedOptionListNavigation(KeyEvent event) {
        if (this.tabFrame == null || this.rootFrame == null) {
            return false;
        }

        OptionRow target;
        boolean handled = true;

        switch (event.shortcutKey()) {
            case InputConstants.KEYCODE_HOME -> {
                this.tabFrame.scrollSelectedPageToStart();
                target = this.tabFrame.findFirstSelectedOptionRow();
            }
            case InputConstants.KEYCODE_END -> {
                this.tabFrame.scrollSelectedPageToEnd();
                target = this.tabFrame.findLastSelectedOptionRow();
            }
            case InputConstants.KEYCODE_PAGEUP -> {
                handled = this.tabFrame.scrollSelectedPage(-1);
                target = this.tabFrame.findFirstVisibleSelectedOptionRow();
            }
            case InputConstants.KEYCODE_PAGEDOWN -> {
                handled = this.tabFrame.scrollSelectedPage(1);
                target = this.tabFrame.findLastVisibleSelectedOptionRow();
            }
            default -> {
                return false;
            }
        }

        return this.focusOptionRow(target) || handled;
    }

    private boolean undoFocusedOption() {
        OptionRow focusedOptionRow = this.getFocusedOptionRow();
        if (focusedOptionRow == null || !(focusedOptionRow.getOption() instanceof MenuSetting<?> option)) {
            return false;
        }

        if (focusedOptionRow.undoFocusedActionButton()) {
            this.updateControls();
            return true;
        }

        if (!OptionUndoAction.canUndo(option)) {
            return false;
        }

        OptionUndoAction.undoChanges(option);
        focusedOptionRow.clearActionButtonFocus();
        this.updateControls();

        return true;
    }

    private boolean restoreFocusedOptionForSelectedTab() {
        if (this.tabFrame == null) {
            return false;
        }

        String tabKey = this.getSelectedTabKey();
        if (tabKey == null) {
            return false;
        }

        Identifier optionId = this.uiState.focusedOptionIdsByTab().get(tabKey);

        return optionId != null && this.focusOptionRow(this.tabFrame.findSelectedOptionRow(optionId));
    }

    private boolean focusOptionRow(@Nullable OptionRow optionRow) {
        if (optionRow == null || this.rootFrame == null || !this.rootFrame.focusOptionRow(optionRow)) {
            return false;
        }

        this.setFocused(this.rootFrame);

        return true;
    }

    private void afterInput(@Nullable String previousTabKey) {
        if (this.isSearchTextFieldFocused()) {
            return;
        }

        if (!Objects.equals(previousTabKey, this.getSelectedTabKey()) && this.restoreFocusedOptionForSelectedTab()) {
            return;
        }

        this.rememberCurrentOptionFocus();
    }

    private void rememberCurrentOptionFocus() {
        OptionRow focusedOptionRow = this.getFocusedOptionRow();
        String tabKey = this.getSelectedTabKey();

        if (tabKey != null && focusedOptionRow != null && focusedOptionRow.getOption() instanceof OptionExtended optionExtended) {
            this.uiState.focusedOptionIdsByTab().put(tabKey, optionExtended.rso$getId());
        }
    }

    private @Nullable OptionRow getFocusedOptionRow() {
        return this.rootFrame == null ? null : findFocusedOptionRow(this.rootFrame);
    }

    private boolean handleFocusedOptionBackNavigation() {
        OptionRow focusedOptionRow = this.getFocusedOptionRow();

        return focusedOptionRow != null && focusedOptionRow.handleBackNavigation();
    }

    private static @Nullable OptionRow findFocusedOptionRow(GuiEventListener listener) {
        if (listener instanceof OptionRow optionRow && optionRow.isFocused()) {
            return optionRow;
        }

        if (listener instanceof ContainerEventHandler container) {
            GuiEventListener focused = container.getFocused();
            if (focused != null) {
                return findFocusedOptionRow(focused);
            }
        }

        return null;
    }

    private @Nullable String getSelectedTabKey() {
        return this.tabFrame == null ? null : this.tabFrame.getSelectedTabKey().orElse(null);
    }

    public @Nullable String rso$getSelectedTabKey() {
        return this.getSelectedTabKey();
    }

    public boolean rso$cycleTab(int direction) {
        BaseWidget.setKeyboardFocusVisible(true);

        if (this.tabFrame == null || direction == 0) {
            return false;
        }

        List<Tab<?>> tabs = this.tabFrame.getTabs();
        if (tabs.isEmpty()) {
            return false;
        }

        int currentIndex = this.tabFrame.getSelectedTab()
                .map(tabs::indexOf)
                .filter(index -> index >= 0)
                .orElse(0);

        for (int offset = 1; offset <= tabs.size(); offset++) {
            int nextIndex = Math.floorMod(currentIndex + direction * offset, tabs.size());
            Tab<?> nextTab = tabs.get(nextIndex);

            this.clearArrowNavigationMemory();
            this.uiState.scrollSelectedTabIntoView().set(true);
            this.tabFrame.setTab(Optional.of(nextTab));
            this.focusFirstOptionInSelectedTab();
            this.rememberCurrentOptionFocus();
            return true;
        }

        return false;
    }

    public boolean rso$focusFirstOptionInSelectedTab() {
        BaseWidget.setKeyboardFocusVisible(true);

        return this.focusFirstOptionInSelectedTab();
    }

    private boolean focusFirstOptionInSelectedTab() {
        if (this.tabFrame == null) {
            return false;
        }

        return this.focusOptionRow(this.tabFrame.findFirstSelectedOptionRow());
    }

    public boolean rso$navigateController(ScreenDirection direction) {
        BaseWidget.setKeyboardFocusVisible(true);

        return this.keyPressedArrow(keyEventForDirection(direction), direction);
    }

    public boolean rso$handleControllerBack() {
        BaseWidget.setKeyboardFocusVisible(true);

        return this.keyPressed(new KeyEvent(InputConstants.KEY_ESCAPE, SDLKeycode.SDLK_ESCAPE, 0));
    }

    public boolean rso$handleControllerPress() {
        BaseWidget.setKeyboardFocusVisible(true);

        return this.keyPressed(new KeyEvent(InputConstants.KEY_RETURN, InputConstants.KEYCODE_RETURN, 0));
    }

    public void rso$afterControllerInput(@Nullable String previousTabKey) {
        this.afterInput(previousTabKey);
    }

    public @Nullable GuiEventListener rso$getFocusedLeaf() {
        return focusedLeaf(this.getFocused());
    }

    private static @Nullable GuiEventListener focusedLeaf(@Nullable GuiEventListener listener) {
        if (listener instanceof ContainerEventHandler container) {
            GuiEventListener focused = container.getFocused();
            if (focused != null) {
                return focusedLeaf(focused);
            }
        }

        return listener;
    }

    private boolean keyPressedArrow(KeyEvent event, ScreenDirection direction) {
        GuiEventListener focused = this.getFocused();
        if (focused != null && focused.keyPressed(event)) {
            this.clearArrowNavigationMemory();
            this.rememberCurrentOptionFocus();
            return true;
        }

        ComponentPath currentFocusPath = this.getCurrentFocusPath();
        if (this.restorePreviousArrowFocus(direction, currentFocusPath)) {
            this.rememberCurrentOptionFocus();
            return true;
        }

        ComponentPath nextFocusPath = this.nextFocusPath(new FocusNavigationEvent.ArrowNavigation(direction));
        if (nextFocusPath == null) {
            this.clearArrowNavigationMemory();
            return false;
        }

        this.changeFocus(nextFocusPath);
        this.rememberArrowNavigation(direction, currentFocusPath, nextFocusPath);
        this.rememberCurrentOptionFocus();

        return true;
    }

    private boolean restorePreviousArrowFocus(ScreenDirection direction, @Nullable ComponentPath currentFocusPath) {
        if (this.previousArrowFocusPath == null
                || this.lastArrowDirection == null
                || currentFocusPath == null
                || direction != this.lastArrowDirection.getOpposite()
                || currentFocusPath.leafComponent() != this.currentArrowFocusLeaf
                || !this.containsFocusLeaf(this.previousArrowFocusPath.leafComponent())) {
            return false;
        }

        ComponentPath previousPath = this.previousArrowFocusPath;
        this.changeFocus(previousPath);
        this.previousArrowFocusPath = currentFocusPath;
        this.currentArrowFocusLeaf = previousPath.leafComponent();
        this.lastArrowDirection = direction;

        return true;
    }

    private void rememberArrowNavigation(ScreenDirection direction, @Nullable ComponentPath previousPath, ComponentPath currentPath) {
        if (previousPath == null || previousPath.leafComponent() == currentPath.leafComponent()) {
            this.clearArrowNavigationMemory();
            return;
        }

        this.previousArrowFocusPath = previousPath;
        this.currentArrowFocusLeaf = currentPath.leafComponent();
        this.lastArrowDirection = direction;
    }

    private void clearArrowNavigationMemory() {
        this.previousArrowFocusPath = null;
        this.currentArrowFocusLeaf = null;
        this.lastArrowDirection = null;
    }

    private boolean containsFocusLeaf(GuiEventListener leaf) {
        for (GuiEventListener child : this.children()) {
            if (containsFocusLeaf(child, leaf)) {
                return true;
            }
        }

        return false;
    }

    private static boolean containsFocusLeaf(GuiEventListener component, GuiEventListener leaf) {
        if (component == leaf) {
            return true;
        }

        if (component instanceof ContainerEventHandler container) {
            for (GuiEventListener child : container.children()) {
                if (containsFocusLeaf(child, leaf)) {
                    return true;
                }
            }
        }

        return false;
    }

    private static @Nullable ScreenDirection getArrowDirection(KeyEvent event) {
        return switch (event.key()) {
            case InputConstants.KEY_LEFT -> ScreenDirection.LEFT;
            case InputConstants.KEY_RIGHT -> ScreenDirection.RIGHT;
            case InputConstants.KEY_UP -> ScreenDirection.UP;
            case InputConstants.KEY_DOWN -> ScreenDirection.DOWN;
            default -> null;
        };
    }

    private static KeyEvent keyEventForDirection(ScreenDirection direction) {
        return switch (direction) {
            case LEFT -> new KeyEvent(InputConstants.KEY_LEFT, InputConstants.KEYCODE_LEFT, 0);
            case RIGHT -> new KeyEvent(InputConstants.KEY_RIGHT, InputConstants.KEYCODE_RIGHT, 0);
            case UP -> new KeyEvent(InputConstants.KEY_UP, InputConstants.KEYCODE_UP, 0);
            case DOWN -> new KeyEvent(InputConstants.KEY_DOWN, InputConstants.KEYCODE_DOWN, 0);
        };
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return !this.hasPendingChanges;
    }

    @Override
    public void onClose() {
        this.uiState.lastSearch().set("");
        this.uiState.lastSearchIndex().set(0);
        this.uiState.updateSearchResults(false, List.of());
        this.uiState.clearOptionUiStates();
        this.uiState.focusedOptionIdsByTab().clear();
        this.minecraft.getWindow().changeFullscreenVideoMode();
        this.minecraft.gui.setScreen(this.prevScreen);
    }

    @Override
    public void removed() {
        this.reloadTexturesIfChanged();
        this.minecraft.options.save();
        VideoOptions.save();
        super.removed();
    }
}
