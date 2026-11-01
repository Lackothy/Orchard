# Orchard

A Minecraft launcher for the Mac, with a renderer built on Apple's Metal.

Orchard installs and starts Minecraft: Java Edition 26.3 with Fabric, and loads one mod of its own
into the game. That mod draws the world through Metal instead of OpenGL, with no Sodium.

**This is alpha software.** Expect bugs, and back up your worlds.

## What it does

The launcher:

- Keeps separate instances, each with its own mods, worlds and settings
- Browses and installs mods, resource packs and shader packs from Modrinth
- Downloads the game and its Java from Mojang, so Orchard carries neither
- Adds a set of performance mods to each instance once, which you can remove
- Reads crash reports and game logs for you

In the game, on Apple silicon:

- A Metal renderer with its own terrain drawing and culling, from [mcopt](https://github.com/noahdunnagan/mcopt)
- Optional shaders drawn in Metal, from [Metal Shaders](https://github.com/GaelQuinta/minecraft-metal-shaders): shadows, god rays, volumetric clouds and reflective water
- Render scale through MetalFX, with the HUD left sharp
- Chests and shulker boxes drawn as terrain, and see-through blocks skipped when they're hidden
- A render distance of up to 64
- Frame rate caps for battery, Low Power Mode and heat
- Video settings based on [Reese's Sodium Options](https://github.com/FlashyReese/reeses-sodium-options), and a custom loading screen

## Requirements

- An Apple silicon Mac on macOS 26 or later
- Your own copy of Minecraft: Java Edition

## Install

1. Download `Orchard-<version>.zip` from [Releases](https://github.com/Lackothy/Orchard/releases).
2. Unzip it and move `Orchard.app` to Applications.
3. Open it. macOS will refuse the first time, because Orchard has no Developer ID signature yet.
   Go to System Settings > Privacy & Security and choose Open Anyway. If macOS says the app is
   damaged, run `xattr -dr com.apple.quarantine /Applications/Orchard.app` once.

## Turning on shaders

In the game, open Options > Video Settings > Metal, turn on Shaders, press Apply and restart.
They run hotter and lower the frame rate, and they're off until you ask for them.

They're drawn by Orchard's renderer, so they don't load Iris or OptiFine packs. Shader packs still
work through Iris, but that takes the game off Metal.

## Known limits

- **Microsoft sign-in isn't available yet.** It's built, but it needs an app ID approved by Mojang.
  Until then Orchard plays with offline accounts only, so online-mode servers and Realms won't work.
  To try your own approved ID: `defaults write com.lackothy.Orchard microsoftClientID <id>`
- **Minecraft 26.3 only**, with Fabric.
- **Not notarized**, hence the extra step when you first open it.

## Building

You need Xcode's command line tools with Swift 6.2, and Java 25.

```sh
./gradlew build                      # the mod, into jars/
launcher/Scripts/build-app.sh        # the app, into launcher/build/Orchard.app
```

`./gradlew runClient` starts the game from source, and `cd launcher && swift test` runs the
launcher's tests.

## Licence

Orchard is under the [Apache License 2.0](LICENSE). `renderer/` and the video settings are modified
copies of other projects and keep their own licences: see [NOTICE](NOTICE).

## Credits

- [mcopt](https://github.com/noahdunnagan/mcopt) by Noah Dunnagan, the Metal renderer
- [Metal Shaders](https://github.com/GaelQuinta/minecraft-metal-shaders) by Gael Quintanilla and contributors, the shaders
- [Reese's Sodium Options](https://github.com/FlashyReese/reeses-sodium-options) by FlashyReese, the video settings

Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft.
