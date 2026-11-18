#!/usr/bin/env bash
# Runs the EncoreCore Swift package tests (Swift Testing) with `swift test`.
#
# With only the Command Line Tools selected, Testing.framework and
# lib_TestingInterop.dylib are not on the default search paths, so the
# framework path and both rpaths are passed explicitly (verified 2026-11-18;
# without the usr/lib rpath the test bundle crashes at load). With full Xcode
# selected, plain `swift test` is used.
#
# Usage: ios/scripts/test-core.sh [extra swift test args, e.g. --filter Parser]
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
package_dir="$script_dir/../EncoreCore"

if [[ ! -f "$package_dir/Package.swift" ]]; then
  echo "test-core: no Swift package at ios/EncoreCore (created in Stage 1)" >&2
  exit 2
fi

developer_dir="$(xcode-select -p)"
cd "$package_dir"

case "$developer_dir" in
  */CommandLineTools)
    frameworks="$developer_dir/Library/Developer/Frameworks"
    libs="$developer_dir/Library/Developer/usr/lib"
    if [[ ! -d "$frameworks/Testing.framework" ]]; then
      echo "test-core: Testing.framework not found in $frameworks" >&2
      exit 3
    fi
    exec swift test \
      -Xswiftc "-F$frameworks" \
      -Xlinker "-F$frameworks" \
      -Xlinker -rpath -Xlinker "$frameworks" \
      -Xlinker -rpath -Xlinker "$libs" \
      "$@"
    ;;
  *)
    exec swift test "$@"
    ;;
esac
