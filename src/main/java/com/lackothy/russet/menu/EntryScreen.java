package com.lackothy.russet.menu;

import com.lackothy.russet.menu.option.MenuEntry;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.Nullable;

public final class EntryScreen extends Screen {
    private static final int GAP = 4;

    private final Screen parent;
    private final MenuEntry option;
    private EditBox box;
    private @Nullable String problem;

    public EntryScreen(Screen parent, MenuEntry option) {
        super(option.getName());
        this.parent = parent;
        this.option = option;
    }

    @Override
    protected void init() {
        int width = Math.min(310, this.width - 40);
        int x = (this.width - width) / 2;
        int y = this.height / 2 - 10;
        String now = this.box == null ? this.option.getValidatedValue() : this.box.getValue();
        this.box = new EditBox(this.font, x, y, width, 20, this.title);
        this.box.setMaxLength(1024);
        this.box.setValue(now == null ? "" : now);
        this.box.setResponder(text -> this.problem = this.option.problem(text.strip()));
        this.addRenderableWidget(this.box);
        int button = (width - 2 * GAP) / 3;
        int buttons = y + 20 + 9 + 3 * GAP;
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> this.done()).bounds(x, buttons, button, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Default"), b -> {
            String fallback = this.option.getDefaultValue();
            this.box.setValue(fallback == null ? "" : fallback);
        }).bounds(x + button + GAP, buttons, button, 20).build());
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> this.onClose())
            .bounds(x + 2 * (button + GAP), buttons, width - 2 * (button + GAP), 20).build());
        this.setInitialFocus(this.box);
    }

    private void done() {
        String text = this.box.getValue().strip();
        this.problem = this.option.problem(text);
        if (this.problem != null) {
            return;
        }
        this.option.modifyValue(text);
        this.minecraft.gui.setScreen(this.parent);
    }

    @Override
    public boolean keyPressed(@NotNull KeyEvent event) {
        if (event.isConfirmation() && this.box.isFocused()) {
            this.done();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(this.parent);
    }

    @Override
    public void extractRenderState(@NotNull GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(guiGraphics, mouseX, mouseY, delta);
        int width = Math.min(310, this.width - 40);
        int x = (this.width - width) / 2;
        int y = this.height / 2 - 10;
        guiGraphics.centeredText(this.font, this.title, this.width / 2, y - 9 - 3 * GAP, 0xFFFFFFFF);
        String fallback = this.option.getDefaultValue();
        String under = this.problem != null ? this.problem
            : fallback == null || fallback.isEmpty() ? "Default: not set" : "Default: " + fallback;
        guiGraphics.text(this.font, this.font.plainSubstrByWidth(under, width), x, y + 20 + GAP,
            this.problem != null ? 0xFFFF5555 : 0xFFAAAAAA);
    }
}
