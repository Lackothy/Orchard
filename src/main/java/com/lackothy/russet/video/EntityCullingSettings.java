package com.lackothy.russet.video;

import com.lackothy.Russet;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

final class EntityCullingSettings {

    static final String ID = "entityculling";

    private static final List<String> SWITCHES = List.of("renderNametagsThroughWalls", "tickCulling", "disableF3",
        "skipEntityCulling", "skipBlockEntityCulling", "forceDisplayCulling", "blockEntityFrustumCulling", "solidLeaves");

    private EntityCullingSettings() {
    }

    static @Nullable List<Pages.Page> pages() {
        try {
            Object mod = Class.forName("dev.tr7zw.entityculling.EntityCullingModBase", false, EntityCullingSettings.class.getClassLoader())
                .getField("instance").get(null);
            if (mod == null) {
                return null;
            }
            Field config = mod.getClass().getField("config");
            Method write = mod.getClass().getMethod("writeConfig");
            Object defaults = config.getType().getConstructor().newInstance();
            List<Row> rows = new ArrayList<>();
            for (String name : SWITCHES) {
                Field field = config.getType().getField(name);
                String key = "text.entityculling." + name;
                String tooltip = key + ".tooltip";
                rows.add(new Row.Mod(Component.translatable(key),
                    Language.getInstance().has(tooltip) ? Component.translatable(tooltip) : null, field,
                    new Control.Check(() -> ClothSettings.unchecked(() -> field.getBoolean(config.get(mod))),
                        on -> ClothSettings.unchecked(() -> {
                            field.setBoolean(config.get(mod), on);
                            GraphicsMods.saveLater(ID, () -> ClothSettings.unchecked(() -> write.invoke(mod)));
                            return null;
                        }),
                        field.getBoolean(defaults)),
                    values -> true));
            }
            String general = Component.translatable("text.entityculling.tab.general_options").getString();
            return List.of(new Pages.Page(general, List.of(new Pages.Section(general, rows))));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Russet.LOG.warn("Couldn't read Entity Culling's settings, so its own screen opens instead", e);
            return null;
        }
    }
}
