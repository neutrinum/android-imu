#!/usr/bin/env bash
set -euo pipefail

printf "sdk.dir=%s\n" "$ANDROID_SDK_ROOT" > local.properties

echo "Android SDK root: $ANDROID_SDK_ROOT"
echo "Java:"
java -version
echo "Gradle:"
gradle --version
