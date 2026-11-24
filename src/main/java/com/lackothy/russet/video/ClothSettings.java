package com.lackothy.russet.video;

import com.lackothy.Russet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

final class ClothSettings {

    private static final String SCREEN = "me.shedaniel.clothconfig2.gui.AbstractConfigScreen";
    private static final String ENTRY = "me.shedaniel.clothconfig2.api.AbstractConfigEntry";

    private ClothSettings() {
    }

    static @Nullable List<Pages.Page> pages(String id, Function<Screen, @Nullable Screen> opens) {
        Object screen = opens.apply(Minecraft.getInstance().gui.screen());
        Class<?> base = screen == null ? null : ancestor(screen.getClass(), SCREEN);
        if (base == null) {
            return null;
        }
        try {
            Map<?, ?> categories = (Map<?, ?>) base.getMethod("getCategorizedEntries").invoke(screen);
            Runnable saving = (Runnable) field(base, "savingRunnable").get(screen);
            Runnable save = saving == null ? () -> { } : saving;
            List<Pages.Page> pages = new ArrayList<>();
            for (Map.Entry<?, ?> category : categories.entrySet()) {
                String title = ((Component) category.getKey()).getString();
                List<Pages.Section> sections = new ArrayList<>();
                List<Row> rows = new ArrayList<>();
                for (Object entry : (List<?>) category.getValue()) {
                    add(id, save, entry, rows, sections);
                }
                if (!rows.isEmpty()) {
                    sections.addFirst(new Pages.Section(title, rows));
                }
                if (!sections.isEmpty()) {
                    pages.add(new Pages.Page(title, sections));
                }
            }
            return pages;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Russet.LOG.warn("Couldn't read {}'s settings, so its own screen opens instead", id, e);
            return null;
        }
    }

    private static void add(String id, Runnable save, Object entry, List<Row> rows, List<Pages.Section> sections)
        throws ReflectiveOperationException {
        Component name = (Component) call(entry, "getFieldName");
        Object value = call(entry, "getValue");
        if (value instanceof List<?> list && !list.isEmpty() && list.stream().allMatch(item -> item != null && ancestor(item.getClass(), ENTRY) != null)) {
            List<Row> inner = new ArrayList<>();
            for (Object item : list) {
                add(id, save, item, inner, sections);
            }
            if (!inner.isEmpty()) {
                sections.add(new Pages.Section(name.getString(), inner));
            }
            return;
        }
        Row row = row(id, save, entry, name, value);
        if (row != null) {
            rows.add(row);
        }
    }

    @SuppressWarnings("unchecked")
    private static @Nullable Row row(String id, Runnable save, Object entry, Component name, Object value)
        throws ReflectiveOperationException {
        Object saver = value(entry, "saveConsumer");
        if (saver == null) {
            saver = value(entry, "saveCallback");
        }
        if (!(saver instanceof Consumer<?>)) {
            return null;
        }
        Consumer<Object> saveTo = (Consumer<Object>) saver;
        AtomicReference<Object> now = new AtomicReference<>(value);
        Consumer<Object> set = next -> {
            now.set(next);
            saveTo.accept(next);
            GraphicsMods.saveLater(id, save);
        };
        Object fallback = call(entry, "getDefaultValue") instanceof Optional<?> optional ? optional.orElse(null) : null;
        Control control = control(entry, value, now, set, fallback);
        if (control == null) {
            return null;
        }
        boolean usable = !Boolean.FALSE.equals(callIf(entry, "isEditable")) && !Boolean.TRUE.equals(callIf(entry, "isLocked"));
        Predicate<Values> when = values -> usable;
        return new Row.Mod(name, description(entry), entry, control, when);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static @Nullable Control control(Object entry, Object value, AtomicReference<Object> now, Consumer<Object> set,
                                             @Nullable Object fallback) throws ReflectiveOperationException {
        Function<Object, Component> text = callIf(entry, "getTextGetter") instanceof Function<?, ?> getter
            ? (Function<Object, Component>) getter : v -> Component.literal(String.valueOf(v));
        if (value instanceof Boolean) {
            return new Control.Check(() -> (Boolean) now.get(), set::accept, (Boolean) fallback);
        }
        if (value instanceof Integer && value(entry, "minimum") instanceof Integer min && value(entry, "maximum") instanceof Integer max && max > min) {
            return new Control.Slider<Integer>(max - min, () -> (Integer) now.get(), set::accept, v -> v - min, step -> min + step,
                text::apply, (Integer) fallback);
        }
        if (value instanceof Float && value(entry, "minimum") instanceof Float min && value(entry, "maximum") instanceof Float max && max > min) {
            float step = value(entry, "step") instanceof Float s && s > 0 ? s : (max - min) / 100;
            int steps = Math.max(1, Math.round((max - min) / step));
            return new Control.Slider<Float>(steps, () -> (Float) now.get(), set::accept, v -> Math.round((v - min) / step),
                at -> Math.min(max, Math.round((min + at * step) * 1000f) / 1000f), text::apply, (Float) fallback);
        }
        List<Object> choices = value(entry, "values") instanceof List<?> list ? (List<Object>) list
            : value instanceof Enum<?> e ? Arrays.asList((Object[]) e.getDeclaringClass().getEnumConstants()) : null;
        if (choices != null && !choices.isEmpty()) {
            Function<Object, Component> label = value(entry, "nameProvider") instanceof Function<?, ?> names
                ? (Function<Object, Component>) names : ClothSettings::label;
            return new Control.Cycle(() -> choices, now::get, set, label, fallback);
        }
        return null;
    }

    private static Component label(Object value) {
        Object key = unchecked(() -> callIf(value, "getKey"));
        return key instanceof String string ? Component.translatable(string) : Component.literal(String.valueOf(value));
    }

    private static @Nullable Component description(Object entry) throws ReflectiveOperationException {
        Object tooltip = callIf(entry, "getTooltip");
        MutableComponent text = null;
        if (tooltip instanceof Optional<?> optional && optional.orElse(null) instanceof Component[] lines) {
            for (Component line : lines) {
                text = text == null ? line.copy() : text.append("\n").append(line);
            }
        }
        if (Boolean.TRUE.equals(callIf(entry, "isRequiresRestart"))) {
            text = (text == null ? Component.empty() : text.append("\n\n")).append(Row.RESTART);
        }
        return text;
    }

    private static @Nullable Class<?> ancestor(Class<?> type, String name) {
        for (Class<?> at = type; at != null; at = at.getSuperclass()) {
            if (at.getName().equals(name)) {
                return at;
            }
        }
        return null;
    }

    private static Object call(Object target, String name) throws ReflectiveOperationException {
        Method method = target.getClass().getMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }

    private static @Nullable Object callIf(Object target, String name) throws ReflectiveOperationException {
        try {
            return call(target, name);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> at = type; at != null; at = at.getSuperclass()) {
            try {
                Field field = at.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException e) {
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static @Nullable Object value(Object target, String name) throws IllegalAccessException {
        try {
            return field(target.getClass(), name).get(target);
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    static <T> T unchecked(Callable<T> call) {
        try {
            return call.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
