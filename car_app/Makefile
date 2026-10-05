# Cross-build CheryNav.exe for Windows CE 6.0 / ARMV4I from Linux (CI).
#
#   make                 build build/CheryNav.exe
#   make CEGCC=/opt/mingw32ce
#   make clean
#
# The toolchain is the same cegcc / mingw32ce installed by the repo's
# tools/setup-toolchain.sh.  Building CheryNav.sln in Visual Studio 2008 against
# the "Windows Mobile 5.0 Pocket PC SDK (ARMV4I)" platform produces the same
# program; this Makefile exists so CI (and anyone on Linux) can build it too.

# Repo root (one level up from car_app/), so we can reach tools/ regardless of
# where make is invoked.
ROOT       := $(abspath $(dir $(lastword $(MAKEFILE_LIST)))..)
TOOLS      := $(ROOT)/tools

CEGCC      ?= /opt/mingw32ce
CROSS      := $(CEGCC)/bin/arm-mingw32ce-
CXX        := $(CROSS)g++
LEGACY_LIB := $(CEGCC)/legacy-lib

OUT    := build/CheryNav.exe
SRC    := main.cpp map_cache.cpp net_client.cpp bt_client.cpp

# -mcpu=arm920t -marm : ARMV4I, plain ARM (not Thumb), like the factory images.
# -fno-exceptions -fno-rtti : the app uses neither; keeps the image small and
#                             avoids pulling the C++ unwinder into coredll-only
#                             space.
CXXFLAGS := -DUNICODE -D_UNICODE -mcpu=arm920t -marm -O2 -Wall \
            -fno-exceptions -fno-rtti

# NOTE (differs from NaviOne's Makefile): NaviOne uses only POD globals, so it
# can pass -nostartfiles and skip the C runtime startup.  CheryNav has global
# C++ objects with real constructors (NavBtClient g_btClient, MapCacheManager
# g_mapCache) that call InitializeCriticalSection / malloc.  Those constructors
# must run before WinMain, which is exactly what mingw32ce's default
# WinMainCRTStartup does, so we keep the startup files here.
#
# -static-libgcc : otherwise the image imports libgcc_s_sjlj-1.dll, which the
#                  head unit does not have, and it will not load at all.  The
#                  only run-time dependency left is coredll, which pe_check.py
#                  verifies.
# -lws2          : Winsock 2 (net_client.cpp); the MSVC #pragma comment(lib,
#                  "ws2.lib") has no effect under gcc, so link it explicitly.
# --stack        : 128 KB reserve, matching CheryNav.vcproj
#                  (StackReserveSize=131072).  Pass only the reserve value:
#                  through -Wl the comma is an argument separator, so a
#                  "reserve,commit" pair would hand ld "0x2000" as an input
#                  file name and fail the link.
LDFLAGS  := -s -static-libgcc \
            -Wl,--subsystem,wince:5.01 \
            -Wl,--stack,0x20000 \
            -lws2

# The 2009 cross compiler needs libgmp.so.3 / libmpfr.so.1 on its search path.
RUN := env LD_LIBRARY_PATH=$(LEGACY_LIB):$$LD_LIBRARY_PATH

.PHONY: all clean
all: $(OUT)

$(OUT): $(SRC) map_cache.h net_client.h bt_client.h ../protocol.h | build
	$(RUN) $(CXX) $(CXXFLAGS) -o $@ $(SRC) $(LDFLAGS)
	python3 $(TOOLS)/pe_fixup.py $@
	python3 $(TOOLS)/pe_check.py $@

build:
	mkdir -p build

clean:
	rm -rf build
