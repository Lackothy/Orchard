# Orchard Metal

A Fabric mod for Minecraft: Java Edition 26.3 that draws the game through Apple's Metal instead of
OpenGL. It works in any Fabric launcher, Lunar Client's Fabric add-on included.

**This is alpha software.** Expect bugs, and back up your worlds.

## What it does

On an Apple silicon Mac:

- A Metal renderer with its own terrain drawing and culling, from [mcopt](https://github.com/noahdunnagan/mcopt)
- Optional shaders drawn in Metal, from [Metal Shaders](https://github.com/GaelQuinta/minecraft-metal-shaders): shadows, god rays, volumetric clouds and reflective water
- Optional far terrain past your render distance
- Render scale through MetalFX, with the HUD left sharp
- See-through blocks skipped when they're hidden behind solid ones
- The Orchard Pack: seven resource packs (Low Shield, Low Fire, Icons, Fancy Crops, Even Better Enchants, Fancy Beds and Fast Better Grass), which the game downloads from Modrinth on its first start
- Video settings based on [Reese's Sodium Options](https://github.com/FlashyReese/reeses-sodium-options), and a custom loading screen

## Requirements

- An Apple silicon Mac on macOS 26 or later
- Minecraft 26.3 with Fabric Loader, Fabric API and Java 25

Off a Mac, or on an older macOS, the renderer switches itself off and the game draws as usual.

## Install

Put `OrchardMetal-<version>.jar` in your instance's `mods` folder, next to Fabric API. In Lunar
Client, add it in the Fabric add-on's Mods tab.

## With Sodium and Iris

Beside Sodium, only the Metal backend runs: Sodium draws the terrain, through Metal. The renderer's
own terrain, far terrain and shaders stay off.

Iris needs Minecraft's Graphics API set to Vulkan (`preferredGraphicsBackend:"vulkan"` in
`options.txt`) and a restart. It then steps aside, and Metal goes first anyway. Left on Default,
Iris keeps OpenGL and Metal switches itself off, saying so in the log.

## Turning on shaders

In the game, open Options > Video Settings > Metal, turn on Shaders, press Apply and restart.
They run hotter and lower the frame rate, and they're off until you ask for them.

They're drawn by the renderer itself, so they don't load Iris or OptiFine packs.

## Building

You need Xcode's command line tools (for the native library) and Java 25.

```sh
./gradlew build              # the mod, into jars/
./gradlew runClient          # the game from source (game dir run/)
./gradlew runClientGameTest  # a real client that screenshots the menus into build/orchard-screenshots
```

`runClient` takes `-PextraMods=a.jar:b.jar` to add other mods beside it, and `-Pjvm="-Da=b"` to add
properties. `runClientGameTest` runs on Mojang's renderer unless given `-Pmetal`.

## Licence

Orchard Metal is under the [PolyForm Shield License 1.0.0](LICENSE), so it's source-available rather
than open source. You can use it, change it and share your changes, but you can't provide anything
that competes with it, even for free, and selling it or a copy of it counts as competing.

`renderer/` and the video settings are modified copies of other projects, whose code keeps its own
licences: see [NOTICE](NOTICE). The Orchard Pack's packs are their authors' and are never carried in
the jar.

## Credits

- [mcopt](https://github.com/noahdunnagan/mcopt) by Noah Dunnagan, the Metal renderer
- [Metal Shaders](https://github.com/GaelQuinta/minecraft-metal-shaders) by Gael Quintanilla and contributors, the shaders
- [Reese's Sodium Options](https://github.com/FlashyReese/reeses-sodium-options) by FlashyReese, the video settings

Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft.
