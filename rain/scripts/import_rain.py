"""Copy Particle Rain (resolved for 26.3 fabric) into Orchard's rain/ folder.

Usage: python3 -I import_rain.py <resolved-java-root> <particle-rain-checkout> <orchard>
"""
import shutil
import sys
from pathlib import Path

resolved, upstream, orchard = (Path(a) for a in sys.argv[1:4])
rain = orchard / "rain"
java_out = rain / "src/main/java/com/lackothy/russet/rain"
res_out = rain / "src/main/resources"

SKIP = {
    "loaders/forge/ForgeEntrypoint.java",
    "loaders/neoforge/NeoforgeEntrypoint.java",
    "loaders/fabric/ModMenuEntrypoint.java",
    "mixin/tint/TextureSheetParticleMixin.java",
    "mixin/fabric/RegistrySyncManagerMixin.java",
}

if java_out.exists():
    shutil.rmtree(java_out)
src_root = resolved / "pigcart/particlerain"
for path in sorted(src_root.rglob("*.java")):
    rel = path.relative_to(src_root).as_posix()
    if rel in SKIP:
        continue
    text = path.read_text()
    text = text.replace("pigcart.particlerain", "com.lackothy.russet.rain")
    text = text.replace('"particlerain:', '"russet:')
    dest = java_out / rel
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_text(text)

assets = upstream / "src/main/resources/assets/particlerain"
russet = res_out / "assets/russet"
for sub in ("sounds/weather",):
    (russet / sub).mkdir(parents=True, exist_ok=True)
    for f in sorted((assets / sub).iterdir()):
        shutil.copy2(f, russet / sub / f.name)
(russet / "textures/particle").mkdir(parents=True, exist_ok=True)
for f in sorted((assets / "textures/particle").iterdir()):
    shutil.copy2(f, russet / "textures/particle" / f.name)
for name in ("particles.json", "sounds.json"):
    text = (assets / name).read_text().replace('"particlerain:', '"russet:')
    text = text.replace("#c:in_overworld", "#c:is_overworld")
    (russet / name).write_text(text)

lang_out = res_out / "assets/russet_rain/lang"
lang_out.mkdir(parents=True, exist_ok=True)
for f in sorted((assets / "lang").iterdir()):
    shutil.copy2(f, lang_out / f.name)
shutil.copy2(upstream / "LICENSE", rain / "LICENSE")
print("java files:", len(list(java_out.rglob("*.java"))))
