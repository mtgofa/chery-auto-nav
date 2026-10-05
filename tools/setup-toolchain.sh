#!/usr/bin/env bash
# Install the cegcc / mingw32ce cross toolchain that builds Windows CE ARM
# binaries, into $PREFIX (default /opt).
#
# The published toolchain is a 32-bit x86 build from 2009 linked against
# libgmp.so.3 and libmpfr.so.1, which no current distribution ships, so we also
# drop those two legacy shared objects next to it and point ld.so at them.
set -euo pipefail

PREFIX="${PREFIX:-/opt}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

CEGCC_URL="https://sourceforge.net/projects/cegcc/files/cegcc/0.59.1/mingw32ce-0.59.1.tar.bz2/download"
GMP_DEB="http://old-releases.ubuntu.com/ubuntu/pool/main/g/gmp/libgmp3c2_4.3.2+dfsg-1ubuntu1_i386.deb"
MPFR_DEB="http://old-releases.ubuntu.com/ubuntu/pool/main/m/mpfr/libmpfr1ldbl_2.4.2-3ubuntu1_i386.deb"

echo "==> enabling i386 runtime"
sudo dpkg --add-architecture i386
sudo apt-get update -qq
sudo apt-get install -y -qq libc6:i386 libstdc++6:i386 zlib1g:i386

echo "==> fetching mingw32ce"
curl -sSfL -o "$WORK/mingw32ce.tar.bz2" "$CEGCC_URL"
sudo tar -xjf "$WORK/mingw32ce.tar.bz2" -C "$(dirname "$PREFIX")"

echo "==> fetching legacy gmp/mpfr"
curl -sSfL -o "$WORK/gmp.deb" "$GMP_DEB"
curl -sSfL -o "$WORK/mpfr.deb" "$MPFR_DEB"
dpkg-deb -x "$WORK/gmp.deb" "$WORK/ext"
dpkg-deb -x "$WORK/mpfr.deb" "$WORK/ext"
sudo mkdir -p "$PREFIX/mingw32ce/legacy-lib"
sudo cp -a "$WORK"/ext/usr/lib/lib*.so.* "$PREFIX/mingw32ce/legacy-lib/"

echo "==> toolchain ready at $PREFIX/mingw32ce"
"$PREFIX/mingw32ce/bin/arm-mingw32ce-gcc" --version | head -1
