# Russet

A quality-of-life mod for Minecraft: Java Edition 26.3 that brings better graphics, a more useful
attack indicator and every video setting in one place, and on Apple silicon Macs draws the game
through Metal, Apple's own graphics API, in place of OpenGL. It works in any Fabric launcher, Lunar
Client's Fabric add-on included. Off a Mac, the Metal part switches itself off and Minecraft draws
with OpenGL or Vulkan as usual; everything else works the same.

**This is alpha software.** Expect bugs, and back up your worlds.

## What it does

Better graphics:

- Fabulous graphics of its own: Fancy, plus a sprite for each potion effect, [Particle Rain](https://github.com/PigCart/particle-rain)'s weather (rain and snow as particles, wind, splashes, fog, desert dust), dust drifting in caves like [Cave Dust](https://modrinth.com/mod/cave-dust)'s, and moving lights like [LambDynamicLights](https://modrinth.com/mod/lambdynamiclights)' (held torches, dropped items, anything on fire), smooth as they move, with sliders to make them lighter on slower computers, and off by themselves where they wouldn't show (full bright, night vision, daylight)
- The Russet Pack: seven resource packs by other creators, which the game downloads from Modrinth on its first start (see below)

Quality of life:

- The attack indicator shows more than your attack, from [Enhanced Attack Indicator](https://github.com/Minenash/Enhanced-Attack-Indicator): breaking blocks, drawing a bow, eating, item cooldowns, sleeping and how full a shulker box is
- Video settings based on [Reese's Sodium Options](https://github.com/FlashyReese/reeses-sodium-options), which also hold the settings of other graphics mods you have (Entity Culling, More Culling, Sodium Extra, Iris and others), including the pages mods make for Sodium's own settings
- A loading screen of its own
- Fixes for Minecraft's own annoyances, each with its own switch: a game you can't see, such as full screen on another Space on a Mac, slows down as a minimized one does

On an Apple silicon Mac (M1 or newer) with macOS 26 or later:

- A Metal renderer with its own terrain drawing and culling, from [mcopt](https://github.com/noahdunnagan/mcopt)
- Optional shaders drawn in Metal, from [Metal Shaders](https://github.com/GaelQuinta/minecraft-metal-shaders): shadows, god rays, volumetric clouds and reflective water
- Optional far terrain past your render distance
- Render scale through MetalFX, with the HUD left sharp
- See-through blocks skipped when they're hidden behind solid ones

## Requirements

- Minecraft 26.3 with Fabric Loader, Fabric API and Java 25
- For the Metal renderer: an Apple silicon Mac on macOS 26 or later. On any other computer, or an older macOS, the
  renderer switches itself off and the game draws as usual

## Install

Put `Russet-<version>.jar` in your instance's `mods` folder, next to Fabric API. In Lunar
Client, add it in the Fabric add-on's Mods tab.

## With Sodium and Iris

Beside Sodium, only the Metal backend runs: Sodium draws the terrain, through Metal. The renderer's
own terrain, far terrain and shaders stay off.

Iris needs Minecraft's Graphics API set to Vulkan (`preferredGraphicsBackend:"vulkan"` in
`options.txt`) and a restart. It then steps aside, and Metal goes first anyway. Left on Default,
Iris keeps OpenGL and Metal switches itself off, saying so in the log.

## The Russet Pack

These resource packs show in your pack list as one pack named Russet:

- [Low Shield](https://modrinth.com/resourcepack/low-shield-pack) and [Low Fire](https://modrinth.com/resourcepack/low-fire-pack) by Oculie
- [Icons](https://modrinth.com/resourcepack/icons) by Zartrix
- [(Bee's) Fancy Crops](https://modrinth.com/resourcepack/fancy-crops) by bebebea_loste
- [Even Better Enchants](https://modrinth.com/resourcepack/even-better-enchants) by mythitorium
- [Fancy Beds](https://modrinth.com/resourcepack/fancy-beds) by Agente_511
- [Fast Better Grass](https://modrinth.com/resourcepack/fast-better-grass) by robotkoer

They're their authors' work, under their own licences. Russet doesn't carry or change their files: it downloads
each one from its Modrinth page, as a launcher would.

The pack can't be switched off in the pack list. To turn it off, open Options > Video Settings > Russet > Packs and
turn off Russet Pack: Russet then stops downloading it, and the pack leaves your list. Beside Sodium, whose video
settings take the place of Russet's, set `pack.russetPack=false` in `config/russet.properties` instead.

## Network use

Russet goes online only to fetch the Russet Pack, and not at all with the pack turned off. On a start where any of
the seven packs is missing from the game
folder's `russet-pack` folder, it asks Modrinth's API (api.modrinth.com) for each missing pack's newest version for
your Minecraft, downloads the files from Modrinth (cdn.modrinth.com) and checks each file's SHA-1. Those requests
carry your IP address, as any download does, and a User-Agent naming Russet and its version. Nothing else is sent,
and Russet collects no data. Once all seven packs are there, it doesn't go online again.

## Turning on shaders

In the game, open Options > Video Settings > Metal, turn on Shaders, press Apply and restart.
They run hotter and lower the frame rate, and they're off until you ask for them.

They're drawn by the renderer itself, so they don't load Iris or OptiFine packs.

## Building

You need Xcode's command line tools (for the native library) and Java 25.

```sh
./gradlew build              # the mod, into jars/
./gradlew runClient          # the game from source (game dir run/)
./gradlew runClientGameTest  # a real client that screenshots the menus into build/russet-screenshots
```

`runClient` takes `-PextraMods=a.jar:b.jar` to add other mods beside it, and `-Pjvm="-Da=b"` to add
properties. `runClientGameTest` runs on Mojang's renderer unless given `-Pmetal`.

## Licence

Russet is under the [PolyForm Shield License 1.0.0](LICENSE), so it's source-available rather
than open source. You can use it, change it and share your changes, but you can't provide anything
that competes with it, even for free, and selling it or a copy of it counts as competing.

`renderer/`, `rain/`, the video settings and the attack indicator are modified copies of other projects, whose code keeps its own
licences: see [NOTICE](NOTICE). The Russet Pack's packs are their authors' and are never carried in
the jar.

## Credits

- [mcopt](https://github.com/noahdunnagan/mcopt) by Noah Dunnagan, the Metal renderer
- [Metal Shaders](https://github.com/GaelQuinta/minecraft-metal-shaders) by Gael Quintanilla and contributors, the shaders
- [Reese's Sodium Options](https://github.com/FlashyReese/reeses-sodium-options) by FlashyReese, the video settings
- [Particle Rain](https://github.com/PigCart/particle-rain) by pigcart, the weather particles
- [Enhanced Attack Indicator](https://github.com/Minenash/Enhanced-Attack-Indicator) by Minenash, the attack indicator
- [Sodium](https://github.com/CaffeineMC/sodium) by CaffeineMC, whose settings API's names Russet declares so that mods' pages for Sodium show in its settings (no Sodium code is copied)
- [Cave Dust](https://modrinth.com/mod/cave-dust) by LizIsTired and [LambDynamicLights](https://modrinth.com/mod/lambdynamiclights) by LambdAurora, whose ideas Russet's cave dust and dynamic lights follow (in Russet's own code; none of theirs is copied)

Russet's own code was written with Claude (Anthropic), an AI model, and Metal Shaders' README says Claude wrote most of
its code too.

## Who's responsible

Russet is made and published by Lackothy, who is responsible for it. For problems or questions, open an issue at
https://github.com/Lackothy/Orchard/issues.

NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT. Russet is also not made
by, affiliated with or endorsed by Lunar Client or by the authors of the projects credited above.
