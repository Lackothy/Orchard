package com.lackothy.russet.rain.config.gui;

import com.google.common.collect.ImmutableList;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.GuiGraphicsExtractor;

public class WidgetList extends ContainerObjectSelectionList<WidgetList.Row> {

    public WidgetList(Minecraft minecraft, int width, int height, int topY, int bottomY, int itemHeight) {
        super(minecraft, width, height, topY, itemHeight);
        this.centerListVertically = false;
    }

    public void addRow(AbstractWidget... widgets) {
        this.addEntry(new Row(widgets));
    }

    public int getRowWidth() {
        return 310;
    }

    protected static class Row extends Entry<Row> {
        private final List<AbstractWidget> widgets;

        Row(AbstractWidget... widgets) {
            this.widgets = ImmutableList.copyOf(widgets);
        }

        @Override
        public void extractContent(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
            renderEntryWidgets(guiGraphics, this.getContentY(), mouseX, mouseY, partialTick);
        }

        private void renderEntryWidgets(GuiGraphicsExtractor guiGraphics, int contentY, int mouseX, int mouseY, float partialTick) {
            int padding = Minecraft.getInstance().gui.screen().width / 2 - 155;
            for (AbstractWidget widget : this.widgets) {
                widget.setY(contentY);
                widget.setX(((AbstractWidgetAccess)widget).pigcart$getOffset() + padding);
                widget.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);
            }
        }

        public List<? extends GuiEventListener> children() {
            return this.widgets;
        }

        public List<? extends NarratableEntry> narratables() {
            return this.widgets;
        }
    }
}
