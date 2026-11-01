// From Reese's Sodium Options 2.2.5 by FlashyReese (MIT, see licenses/reeses-sodium-options.txt), modified for Orchard.
package com.lackothy.orchard.menu.frame.tab;

import com.lackothy.orchard.menu.option.MenuGroup;
import com.lackothy.orchard.menu.option.MenuPage;

import com.lackothy.orchard.menu.layout.LayoutBounds;
import com.lackothy.orchard.menu.state.Holder;
import com.lackothy.orchard.menu.state.OptionStateStore;
import com.lackothy.orchard.menu.frame.AbstractFrame;
import com.lackothy.orchard.menu.frame.option.PageFrame;
import com.lackothy.orchard.menu.frame.ScrollableFrame;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.Function;

public class Tab<T extends AbstractFrame> {
    private final MenuGroup modOptions;
    private final Component title;
    private final MenuPage page;
    private final Function<LayoutBounds, T> frameFunction;

    public Tab(MenuGroup modOptions, Component title, MenuPage page, Function<LayoutBounds, T> frameFunction) {
        this.modOptions = modOptions;
        this.title = title;
        this.page = page;
        this.frameFunction = frameFunction;
    }

    public static Tab.Builder<?> builder() {
        return new Tab.Builder<>();
    }

    public MenuGroup getModOptions() {
        return modOptions;
    }

    public Component getTitle() {
        return title;
    }

    String key() {
        return this.modOptions.configId() + ":" + this.title.getString();
    }

    public MenuPage getPage() {
        return page;
    }

    public Function<LayoutBounds, T> getFrameFunction() {
        return this.frameFunction;
    }

    public static class Builder<T extends AbstractFrame> {
        private MenuGroup modOptions;
        private Component title;
        private MenuPage page;
        private Function<LayoutBounds, T> frameFunction;

        public Builder<T> withTitle(Component title) {
            this.title = title;
            return this;
        }

        public Builder<T> withFrameFunction(Function<LayoutBounds, T> frameFunction) {
            this.frameFunction = frameFunction;
            return this;
        }

        public Builder<T> withPage(MenuPage page) {
            this.page = page;
            return this;
        }

        public Builder<T> withModOptions(MenuGroup modOptions) {
            this.modOptions = modOptions;
            return this;
        }


        public Tab<T> build() {
            return new Tab<T>(this.modOptions, this.title, this.page, this.frameFunction);
        }

        public Tab<ScrollableFrame> from(Screen screen, MenuGroup modOptions, MenuPage page, Holder<Integer> verticalScrollBarOffset, OptionStateStore optionStateStore) {
            return new Tab<>(modOptions, page.name(), page, bounds -> {
                PageFrame pageFrame = PageFrame
                        .builder()
                        .withDimension(bounds)
                        .withModOptions(modOptions)
                        .withPage(page)
                        .withScreen(screen)
                        .withOptionStateStore(optionStateStore)
                        .build();
                ScrollableFrame scrollableFrame = ScrollableFrame
                        .builder()
                        .withDimension(bounds)
                        .withModOptions(modOptions)
                        .withScreen(screen)
                        .withFrame(pageFrame)
                        .withVerticalScrollBarOffset(verticalScrollBarOffset)
                        .build();
                pageFrame.setGroupToggleRebuildHandler(collapseKey -> {
                    scrollableFrame.rebuildContentFrame();
                    if (pageFrame.focusGroupHeader(collapseKey)) {
                        scrollableFrame.setFocused(pageFrame);
                    }
                });
                return scrollableFrame;
            });
        }
    }
}
