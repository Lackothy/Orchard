package com.lackothy.russet.fabulous;

import com.lackothy.russet.video.VideoOptions;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biomes;

public final class CaveDust {

    public static final boolean STOOD_DOWN = FabricLoader.getInstance().isModLoaded("cavedust");

    private static final int REACH = 10;
    private static final int TRIES = 250;
    private static final int TOP = 64;

    private static final RandomSource RANDOM = RandomSource.create();

    private CaveDust() {
    }

    public static void tick(Minecraft mc) {
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (STOOD_DOWN || !VideoOptions.CAVE_DUST.on() || level == null || player == null || mc.isPaused()
            || level.dimension() != Level.OVERWORLD || player.isUnderWater()) {
            return;
        }
        int bottom = level.getMinY();
        double share = 1 - (player.getBlockY() - bottom) / (double) (TOP - bottom);
        int tries = (int) (Math.min(1, share) * TRIES);
        int sea = level.getSeaLevel();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 0; i < tries; i++) {
            pos.set(player.getBlockX() + RANDOM.nextIntBetweenInclusive(-REACH, REACH),
                (int) Math.floor(player.getEyeY()) + RANDOM.nextIntBetweenInclusive(-REACH, REACH),
                player.getBlockZ() + RANDOM.nextIntBetweenInclusive(-REACH, REACH));
            if (pos.getY() < bottom || pos.getY() + 2 >= sea || !level.getBlockState(pos).isAir() || level.canSeeSky(pos)
                || level.getBiome(pos).is(Biomes.LUSH_CAVES)) {
                continue;
            }
            level.addParticle(ParticleTypes.WHITE_ASH, pos.getX() + RANDOM.nextDouble(), pos.getY() + RANDOM.nextDouble(),
                pos.getZ() + RANDOM.nextDouble(), 0, 0, 0);
        }
    }
}
