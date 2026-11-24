// From Enhanced Attack Indicator 1.2.0 by Minenash (MIT, see licenses/enhanced-attack-indicator.txt), modified for Russet:
// Minecraft 26.3's names, Russet's settings in place of its config, tools told apart by their item tags, the weapon's
// ready sign when Minecraft 26.3 would show it (an item's attack range too), anything eaten or drunk, bundles by how full
// they are, a charged crossbow in the off hand read from that hand, and standing down when the mod itself is installed.
package com.lackothy.russet.hud;

import com.lackothy.russet.video.VideoOptions;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.AttackRange;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import org.apache.commons.lang3.math.Fraction;

import java.util.List;

public final class AttackIndicator {

    public static final boolean STOOD_DOWN = FabricLoader.getInstance().isModLoaded("enhanced_attack_indicator");

    public static final float FULL = 2;

    public enum Priority {
        FIRST("First"), MIDDLE("Middle"), LAST("Last");

        public final String label;

        Priority(String label) {
            this.label = label;
        }
    }

    private AttackIndicator() {
    }

    public static float progress(float weaponProgress) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ItemStack mainHand = player.getMainHandItem();
        ItemStack offHand = player.getOffhandItem();
        boolean weaponShouldShow = weaponProgress < 1 || targetReady(mc, player);
        Priority priority = VideoOptions.ATTACK_PRIORITY.get();

        if (priority == Priority.FIRST && weaponShouldShow) {
            return weaponCooldown(mainHand, weaponProgress);
        }

        if (VideoOptions.ATTACK_SLEEP.on()) {
            int sleep = player.getSleepTimer();
            if (sleep > 0 && sleep <= 100) {
                return sleep == 100 ? FULL : sleep / 100.0F;
            }
        }

        if (VideoOptions.ATTACK_BLOCK_BREAKING.on()) {
            int breakingProgress = mc.gameMode.getDestroyStage();
            if (breakingProgress > 0) {
                return breakingProgress / 10.0F;
            }
        }

        if (VideoOptions.ATTACK_RANGED_DRAW.on()) {
            ItemStack stack = player.getUseItem();
            if (stack.is(Items.BOW)) {
                float progress = BowItem.getPowerForTime(player.getTicksUsingItem());
                return progress == 1.0F ? FULL : progress;
            }
            if (stack.is(Items.CROSSBOW)) {
                float progress = player.getTicksUsingItem() / (float) CrossbowItem.getChargeDuration(stack, player);
                return progress >= 1.0F ? FULL : progress;
            }
            if (stack.is(Items.TRIDENT)) {
                float progress = player.getTicksUsingItem() / 10.0F;
                return progress >= 1.0F ? FULL : progress;
            }
        }

        if (VideoOptions.ATTACK_EATING.on()) {
            ItemStack stack = player.getUseItem();
            if (stack.has(DataComponents.CONSUMABLE)) {
                float itemCooldown = (float) player.getTicksUsingItem() / stack.getUseDuration(player);
                return itemCooldown == 0.0F ? 1.0F : itemCooldown;
            }
        }

        if (VideoOptions.ATTACK_CONTAINERS.on()) {
            ItemContainerContents container = mainHand.get(DataComponents.CONTAINER);
            if (container != null) {
                List<ItemStack> items = container.nonEmptyItemCopyStream().toList();
                int total = 0;
                int maxTotal = 64 * (27 - items.size());
                for (ItemStack item : items) {
                    total += item.getCount();
                    maxTotal += item.getMaxStackSize();
                }
                float result = (float) total / maxTotal;
                return result == 1.0F ? FULL : result;
            }
            BundleContents bundle = mainHand.get(DataComponents.BUNDLE_CONTENTS);
            if (bundle != null) {
                float weight = bundle.weight().result().map(Fraction::floatValue).orElse(0F);
                return weight >= 1.0F ? FULL : weight;
            }
        }

        if (priority == Priority.MIDDLE && weaponShouldShow) {
            return weaponCooldown(mainHand, weaponProgress);
        }

        if (VideoOptions.ATTACK_ITEM_COOLDOWNS.on()) {
            float cooldown = player.getCooldowns().getCooldownPercent(offHand, 0);
            if (cooldown != 0.0F) {
                return cooldown;
            }
            cooldown = player.getCooldowns().getCooldownPercent(mainHand, 0);
            if (cooldown != 0.0F) {
                return cooldown;
            }
        }

        if (VideoOptions.ATTACK_RANGED_DRAW.on() && (mainHand.is(Items.CROSSBOW) && CrossbowItem.isCharged(mainHand)
            || offHand.is(Items.CROSSBOW) && CrossbowItem.isCharged(offHand))) {
            return FULL;
        }

        if (priority == Priority.LAST && weaponShouldShow) {
            return weaponCooldown(mainHand, weaponProgress);
        }

        return 1.0F;
    }

    private static boolean targetReady(Minecraft mc, LocalPlayer player) {
        if (!(mc.crosshairPickEntity instanceof LivingEntity target) || !target.isAlive()
            || player.getCurrentItemAttackStrengthDelay() <= 5 || mc.hitResult == null) {
            return false;
        }
        AttackRange range = player.getActiveItem().get(DataComponents.ATTACK_RANGE);
        return range == null || range.isInRange(player, mc.hitResult.getLocation());
    }

    private static float weaponCooldown(ItemStack stack, float weaponProgress) {
        if (VideoOptions.ATTACK_HIDE_DIGGERS.on() && (stack.is(ItemTags.PICKAXES) || stack.is(ItemTags.SHOVELS))) {
            return 1.0F;
        }
        if (VideoOptions.ATTACK_HIDE_AXES.on() && stack.is(ItemTags.AXES)) {
            return 1.0F;
        }
        return weaponProgress == 1 ? FULL : weaponProgress;
    }
}
