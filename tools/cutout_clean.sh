#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
out=app/build/hostfx
mkdir -p "$out"
clang++ -std=c++17 -O2 -Wall -Wextra -Werror -Iapp/src/main/cpp app/src/main/cpp/cutout.cpp tools/cutout/test_clean.cpp -o "$out/test_clean"
"$out/test_clean"
sanitize=""
for kinds in address,undefined undefined; do
    if (echo 'int main() {}' | clang++ -x c++ -fsanitize=$kinds - -o "$out/sanitizer_probe" && perl -e 'alarm 5; exec @ARGV' "$out/sanitizer_probe") >/dev/null 2>&1; then
        sanitize="-fsanitize=$kinds -fno-sanitize-recover=all"
        break
    fi
done
clang++ -std=c++17 -O1 -g -Wall -Wextra -Werror $sanitize -Iapp/src/main/cpp tools/cutout/test_alloc.cpp -o "$out/test_alloc"
"$out/test_alloc"
