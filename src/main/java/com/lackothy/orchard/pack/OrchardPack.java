package com.lackothy.orchard.pack;

import com.mojang.logging.LogUtils;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackMetadataResources;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionType;
import net.minecraft.server.packs.metadata.pack.PackFormat;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackCompatibility;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.RepositorySource;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.world.flag.FeatureFlagSet;
import org.slf4j.Logger;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * The Orchard Pack: resource packs the launcher downloads from Modrinth into the instance's
 * {@code orchard-pack} folder at Play (OrchardPack.swift), shown as one pack named "Orchard" and
 * loaded as one, always on. Orchard never carries their files, as most are All Rights Reserved. Each part opens
 * as it would on its own, its overlays included, lowest first, so where two parts have the same
 * file the one listed later wins. Without the folder's {@code order.txt} there is no pack.
 */
public final class OrchardPack implements RepositorySource {

    /** Not "orchard": that is Fabric's pack of Orchard's own resources (its shaders and menus), which this one would replace. */
    public static final String ID = "orchard_pack";
    public static final String FOLDER = "orchard-pack";
    private static final Logger LOGGER = LogUtils.getLogger();

    private final Path folder;

    public OrchardPack(Path folder) {
        this.folder = folder;
    }

    /** One of the packs inside, read as its own pack would be. */
    private record Part(PackLocationInfo info, Pack.ResourcesSupplier supplier, Pack.Metadata metadata) {
    }

    @Override
    public void loadPacks(Consumer<Pack> out) {
        List<Part> parts = parts();
        if (parts.isEmpty()) {
            return;
        }
        PackLocationInfo location = new PackLocationInfo(ID, Component.literal("Orchard"), PackSource.BUILT_IN, Optional.empty());
        Pack.Metadata metadata = new Pack.Metadata(Component.literal("QOL & Fixes"), PackCompatibility.COMPATIBLE, FeatureFlagSet.of(), List.of());
        // Required, so it can't be turned off; on top, as Fast Better Grass asks, but it can be moved.
        out.accept(new Pack(location, new Resources(parts), metadata, new PackSelectionConfig(true, Pack.Position.TOP, false)));
    }

    /** order.txt's packs that are there and read: a line each, project ID, file name and title split by tabs. */
    private List<Part> parts() {
        Path order = folder.resolve("order.txt");
        if (!Files.isRegularFile(order)) {
            return List.of();
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(order, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("Couldn't read the Orchard Pack's {}", order, e);
            return List.of();
        }
        PackFormat format = SharedConstants.getCurrentVersion().packVersion(PackType.CLIENT_RESOURCES);
        List<Part> parts = new ArrayList<>();
        for (String line : lines) {
            String[] cells = line.split("\t");
            if (cells.length < 3) {
                continue;
            }
            Path file = folder.resolve(cells[1]).normalize();
            if (!file.startsWith(folder) || !Files.isRegularFile(file)) {
                continue;
            }
            PackLocationInfo info = new PackLocationInfo(ID + "/" + cells[0], Component.literal(cells[2]), PackSource.BUILT_IN, Optional.empty());
            Pack.ResourcesSupplier supplier = new FilePackResources.FileResourcesSupplier(file);
            Pack.Metadata metadata = Pack.readPackMetadata(info, supplier, format, PackType.CLIENT_RESOURCES);
            if (metadata != null) { // null: Minecraft has logged why
                parts.add(new Part(info, supplier, metadata));
            }
        }
        return parts;
    }

    /** The parts' resources, lowest first, and Orchard's own icon for the pack list. */
    private record Resources(List<Part> parts) implements Pack.ResourcesSupplier {
        @Override
        public PackMetadataResources openMetadata(PackLocationInfo location) {
            return new Icon(location);
        }

        @Override
        public Stream<PackResources> openResources(PackLocationInfo location, Pack.Metadata metadata) {
            return parts.stream().flatMap(part -> part.supplier().openResources(part.info(), part.metadata()));
        }
    }

    /** What the pack list reads about the pack itself: only its icon, Orchard's apple tree. */
    private record Icon(PackLocationInfo location) implements PackMetadataResources {
        @Override
        public IoSupplier<InputStream> getRootResource(String... path) {
            if (path.length != 1 || !path[0].equals("pack.png")) {
                return null;
            }
            return () -> {
                InputStream icon = OrchardPack.class.getResourceAsStream("/assets/orchard/pack.png");
                if (icon == null) {
                    throw new FileNotFoundException("assets/orchard/pack.png");
                }
                return icon;
            };
        }

        @Override
        public <T> T getMetadataSection(MetadataSectionType<T> type) {
            return null;
        }

        @Override
        public void close() {
        }
    }
}
