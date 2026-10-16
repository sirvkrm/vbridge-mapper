#!/bin/bash
set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
cd "$DIR"

echo "[*] Compiling touch_injector..."

# Check if compiling inside Termux or with cross-compiler
if command -v aarch64-linux-android-clang &> /dev/null; then
    CC="aarch64-linux-android-clang"
elif command -v clang &> /dev/null; then
    CC="clang"
elif command -v gcc &> /dev/null; then
    CC="gcc"
else
    echo "[-] Error: No C compiler found (clang or gcc)."
    exit 1
fi

echo "[+] Using compiler: $CC"
$CC -O3 -Wall -Wextra injector.c -o touch_injector

echo "[+] Successfully built: $DIR/touch_injector"
