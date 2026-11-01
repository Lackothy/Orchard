package com.lackothy.orchard.menu.option;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MenuSettingTest {

    private static final Identifier ID = Identifier.fromNamespaceAndPath("orchard", "test");

    private static MenuToggle toggle(AtomicReference<Boolean> value, AtomicInteger writes) {
        return new MenuToggle(ID, Component.literal("Test"), null, () -> true, value::get,
            on -> {
                writes.incrementAndGet();
                value.set(on);
            }, true);
    }

    @Test
    void holdsAChangeUntilApply() {
        AtomicReference<Boolean> value = new AtomicReference<>(true);
        AtomicInteger writes = new AtomicInteger();
        MenuToggle toggle = toggle(value, writes);

        toggle.modifyValue(false);
        assertTrue(toggle.hasChanged());
        assertFalse(toggle.getValidatedValue());
        assertTrue(value.get(), "nothing is set before Apply");

        toggle.apply();
        assertFalse(value.get());
        assertEquals(1, writes.get());
    }

    @Test
    void undoLetsGo() {
        AtomicReference<Boolean> value = new AtomicReference<>(true);
        AtomicInteger writes = new AtomicInteger();
        MenuToggle toggle = toggle(value, writes);

        toggle.modifyValue(false);
        toggle.undo();
        assertFalse(toggle.hasChanged());
        assertTrue(toggle.getValidatedValue());
        toggle.apply();
        assertEquals(0, writes.get());
    }

    @Test
    void followsChangesMadeElsewhereUntilOneIsHeld() {
        AtomicReference<Boolean> value = new AtomicReference<>(true);
        AtomicInteger writes = new AtomicInteger();
        MenuToggle toggle = toggle(value, writes);

        value.set(false);
        assertFalse(toggle.getValidatedValue());
        assertFalse(toggle.hasChanged());
        toggle.apply();
        assertEquals(0, writes.get(), "a value nobody touched in the menu is not written back");
    }

    @Test
    void pickingTheSameValueBackIsNoChange() {
        AtomicReference<Boolean> value = new AtomicReference<>(true);
        MenuToggle toggle = toggle(value, new AtomicInteger());

        toggle.modifyValue(false);
        toggle.modifyValue(true);
        assertFalse(toggle.hasChanged());
    }

    @Test
    void resetPicksTheStartingValue() {
        AtomicReference<Boolean> value = new AtomicReference<>(false);
        MenuToggle toggle = toggle(value, new AtomicInteger());

        toggle.resetToDefault();
        assertTrue(toggle.getValidatedValue());
        assertTrue(toggle.hasChanged());
    }

    @Test
    void sliderStepsSkipPositionsWithTheSameValue() {
        // Hundredths of a track whose values come in tens, as Minecraft's frame rate does.
        AtomicReference<Integer> value = new AtomicReference<>(30);
        MenuSlider<Integer> slider = new MenuSlider<>(ID, Component.literal("Max frame rate"), null, () -> true,
            value::get, value::set, 120, 100, v -> (v - 10) * 100 / 250, step -> 10 + Math.round(step * 2.5f / 10) * 10,
            v -> Component.literal(v + " fps"));

        assertEquals(40, slider.step(30, 1));
        assertEquals(20, slider.step(30, -1));
        assertEquals(260, slider.step(260, 1), "the end of the track stays put");
        assertEquals(10, slider.step(10, -1));
    }

    @Test
    void sliderPositionsStayOnTheTrack() {
        AtomicReference<Integer> value = new AtomicReference<>(5);
        MenuSlider<Integer> slider = new MenuSlider<>(ID, Component.literal("Search Result Limit"), null, () -> true,
            value::get, value::set, 15, 49, v -> v - 1, step -> step + 1, v -> Component.literal(v + " results"));

        assertEquals(0, slider.position(-20));
        assertEquals(49, slider.position(500));
        assertEquals(50, slider.at(1000));
        assertEquals(1, slider.at(-3));
    }
}
