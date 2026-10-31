#!/usr/bin/env bash
# Publica la versión que dice app/build.gradle.kts como Release de GitHub, con el APK: los teléfonos con
# Vortex Player la ven en sus próximas 6 horas (o al abrir la app) y se actualizan solos.
#
#   ./publicar.sh "Lo nuevo de esta versión"
#
# Antes: subir versionCode y versionName en app/build.gradle.kts, y hacer commit y push.
set -euo pipefail
cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME:-$HOME/Android/jdk21}" ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/sdk}"
version=$(grep -oP 'versionName = "\K[^"]+' app/build.gradle.kts)
notas="${1:?Escribe qué trae esta versión}"
./gradlew -q assembleRelease testDebugUnitTest
apk="VortexPlayer-$version.apk"
cp app/build/outputs/apk/release/app-release.apk "/tmp/$apk"
gh release create "v$version" "/tmp/$apk" --title "Vortex Player $version" --notes "$notas" --latest
echo "Publicada la $version: los teléfonos se actualizan solos."
