package com.lackothy.russet.rain.config.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;

public class InputWidget extends EditBox {

    Function<Object, Component> valueFormatter;
    String formattedValue;
    int formattedColor;

    Pattern filteredChars;
    public static final Pattern NON_INTEGER = Pattern.compile("[^0-9-]");
    public static final Pattern NON_FLOAT = Pattern.compile("[^0-9.-]");
    public static final Pattern NON_PATH = Pattern.compile("[^a-z0-9#:/._-]");
    public static final Pattern NON_HEX = Pattern.compile("[^a-fA-F0-9#]");

    public InputWidget(int width, int x, String initialValue, Consumer<String> onValueChange, Function<Object, Component> valueFormatter) {
        super(Minecraft.getInstance().font, 0, 0, width, WidgetUtil.BUTTON_HEIGHT, Component.empty());
        ((AbstractWidgetAccess)this).pigcart$setOffset(x);
        this.valueFormatter = valueFormatter;
        this.setMaxLength(128);
        this.setValue(initialValue);
        this.setResponder((value) -> {
            try {
                onValueChange.accept(value);
                this.setTextColor(EditBox.DEFAULT_TEXT_COLOR);
                formatValue();
            } catch (NumberFormatException ignored) {
                this.setTextColor(0xFFFF5555);
            }
        });
    }

    @Override
    public void setValue(String text) {
        super.setValue(text);
        formatValue();
    }

    @Override
    public void setFocused(boolean focused) {
        super.setFocused(focused);
        formatValue();
    }

    public void formatValue() {
        if (this.isFocused()) {
            formattedColor = 0xFF555555;
            this.setTextColor(EditBox.DEFAULT_TEXT_COLOR);
        } else {
            formattedColor = EditBox.DEFAULT_TEXT_COLOR;
            this.setTextColor(0x00000000);
        }
        formattedValue = valueFormatter.apply(this.getValue()).getString();
    }

    @Override
        public void extractWidgetRenderState(net.minecraft.client.gui.GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float a) {
        super.extractWidgetRenderState(guiGraphics, mouseX, mouseY, a);

        if (formattedValue.isEmpty()) return;
        final Font font = Minecraft.getInstance().font;
        final int x = this.getX() + 4;
        final int y = this.getY() + (this.height - 8) / 2;
        guiGraphics.text(font, formattedValue, x, y, formattedColor);
        if (isFocused()) {
            guiGraphics.text(font, this.getValue(), x, y, EditBox.DEFAULT_TEXT_COLOR);
        }
    }

    public void setFilter(Pattern pattern) {
        filteredChars = pattern;
        }
}
