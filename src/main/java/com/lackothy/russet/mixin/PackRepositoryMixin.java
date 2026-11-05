package com.lackothy.russet.mixin;

import com.lackothy.russet.pack.RussetPack;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.resources.ClientPackSource;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.RepositorySource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/** The client's resource packs (the repository with Minecraft's own client pack) get the Russet Pack; data packs don't. */
@Mixin(PackRepository.class)
abstract class PackRepositoryMixin {

    @Shadow
    @Final
    @Mutable
    private Set<RepositorySource> sources;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void russet$russetPack(RepositorySource[] given, CallbackInfo ci) {
        if (Arrays.stream(given).noneMatch(ClientPackSource.class::isInstance)) {
            return;
        }
        Set<RepositorySource> all = new LinkedHashSet<>(this.sources);
        all.add(new RussetPack(FabricLoader.getInstance().getGameDir().resolve(RussetPack.FOLDER)));
        this.sources = all;
    }
}
