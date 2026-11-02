#!/bin/zsh
set -euo pipefail

here=${0:A:h}
launcher=${here:h}
repo=${launcher:h}
config=${1:-release}
app=$launcher/build/Orchard.app

jar=$(ls -t $repo/jars/*.jar 2>/dev/null | head -1 || true)
if [[ -z $jar ]]; then
    echo "No mod jar in $repo/jars. Run ./gradlew build in $repo first." >&2
    exit 1
fi

prop() { grep "^$1=" $repo/gradle.properties | cut -d= -f2- }
version=$(sed -n 's/^version = "\(.*\)"/\1/p' $repo/build.gradle.kts)

cd $launcher
swift build -c $config --arch arm64
bin=$(swift build -c $config --arch arm64 --show-bin-path)

rm -rf $app
mkdir -p $app/Contents/MacOS $app/Contents/Resources
cp $bin/Orchard $app/Contents/MacOS/Orchard
if [[ $config == release ]]; then strip -x -S $app/Contents/MacOS/Orchard; fi
cp $jar $app/Contents/Resources/orchard.jar
swift $here/make-icon.swift $app/Contents/Resources/AppIcon.icns

cat > $app/Contents/Resources/edition.json <<EOF
{
  "minecraft": "$(prop minecraftVersion)",
  "loader": "$(prop loaderVersion)",
  "fabricApi": "$(prop fabricApiVersion)"
}
EOF

cat > $app/Contents/Info.plist <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleExecutable</key><string>Orchard</string>
    <key>CFBundleIdentifier</key><string>com.lackothy.Orchard</string>
    <key>CFBundleName</key><string>Orchard</string>
    <key>CFBundleDisplayName</key><string>Orchard</string>
    <key>CFBundlePackageType</key><string>APPL</string>
    <key>CFBundleShortVersionString</key><string>$version</string>
    <key>CFBundleVersion</key><string>1</string>
    <key>CFBundleIconFile</key><string>AppIcon</string>
    <key>LSMinimumSystemVersion</key><string>26.0</string>
    <key>LSApplicationCategoryType</key><string>public.app-category.games</string>
    <key>NSHighResolutionCapable</key><true/>
</dict>
</plist>
EOF

codesign --force --sign - --timestamp=none $app
echo $app
