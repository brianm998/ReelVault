# android/Makefile
# Convenience wrapper: compile libreelvault_core.so for the right ABI(s) and
# Rust profile, then hand off to Gradle.  Stamps track which profile was last
# built per ABI so a profile switch always re-runs cargo-ndk, while unchanged
# Rust sources are skipped.
#
# Common targets:
#   make / make installDebug    build debug .so + gradle installDebug
#   make installRelease         build release .so + gradle installRelease
#   make assembleDebug          build debug .so + gradle assembleDebug
#   make assembleRelease        build release .so + gradle assembleRelease
#   make core                   build debug .so for ABIS only (no gradle)
#   make core-release           build release .so for ABIS only
#   make core-all               debug .so for all four ABIs (CI / multi-device)
#   make core-all-release       release .so for all four ABIs
#   make clean                  gradle clean + wipe jniLibs stamps
#   make clean-core             remove jniLibs .so files + cargo artefacts
#
# Overridable variables:
#   DEVICE            target device serial (run 'adb devices' to list); default = first device
#                     e.g. DEVICE=emulator-5554 make installDebug
#   ABIS              space-separated ABI list; default = device ABI from adb
#                     (falls back to all four when no device is reachable)
#   ANDROID_NDK_HOME  path to the NDK (auto-detected from the SDK when unset)

ANDROID_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
REPO_ROOT   := $(abspath $(ANDROID_DIR)/..)
GRADLEW     := $(ANDROID_DIR)/gradlew
JNI_OUT     := $(ANDROID_DIR)/src/main/jniLibs
CORE_DIR    := $(REPO_ROOT)/core

# ── Device selection ───────────────────────────────────────────────────────────
# If DEVICE is specified, pass it to adb and gradle via -s flag.
# Otherwise use the first connected device (adb's default).
ifdef DEVICE
  _ADB_SERIAL := -s $(DEVICE)
  _GRADLE_SERIAL := -PtargetDevice=$(DEVICE)
else
  _ADB_SERIAL :=
  _GRADLE_SERIAL :=
endif

# ── NDK ───────────────────────────────────────────────────────────────────────
# Mirror build-core.sh: honour an explicit ANDROID_NDK_HOME, otherwise pick
# the highest-versioned NDK found under the SDK directory.
ifndef ANDROID_NDK_HOME
  _SDK := $(or $(ANDROID_HOME),$(ANDROID_SDK_ROOT),$(HOME)/Library/Android/sdk)
  ifneq ($(wildcard $(_SDK)/ndk),)
    export ANDROID_NDK_HOME := \
      $(lastword $(shell ls -d $(_SDK)/ndk/* 2>/dev/null | sort -V))
  endif
endif

# ── ABI auto-detection ─────────────────────────────────────────────────────────
# Ask the connected device/emulator; fall back to all four so CI and offline
# builds still produce a complete jniLibs tree.
_DEVICE_ABI := $(shell adb $(_ADB_SERIAL) shell getprop ro.product.cpu.abi 2>/dev/null | tr -d '\r\n')
ifeq ($(_DEVICE_ABI),)
  ABIS ?= arm64-v8a armeabi-v7a x86_64 x86
  ifdef DEVICE
    $(info android/Makefile: device $(DEVICE) not found or offline — ABIS=$(ABIS))
  else
    $(info android/Makefile: no adb device — ABIS=$(ABIS))
  endif
else
  ABIS ?= $(_DEVICE_ABI)
  ifdef DEVICE
    $(info android/Makefile: device $(DEVICE) ABI=$(ABIS))
  else
    $(info android/Makefile: device ABI=$(ABIS))
  endif
endif

# ── Rust prerequisites ─────────────────────────────────────────────────────────
# Pattern rules depend on every .rs file + the manifest/lockfile, so the .so
# is rebuilt whenever any Rust source changes.
_RUST_SRCS := \
  $(shell find $(CORE_DIR)/src -name '*.rs' 2>/dev/null) \
  $(wildcard $(CORE_DIR)/Cargo.toml $(CORE_DIR)/Cargo.lock)

# ── Stamp files ────────────────────────────────────────────────────────────────
# One hidden stamp per (profile, ABI) inside jniLibs/.  When a profile is
# built it touches its own stamp and removes the other profile's stamp for
# that ABI, so switching debug↔release always re-runs cargo-ndk even if no
# Rust source changed.
_DEBUG_STAMPS   := $(foreach abi,$(ABIS),$(JNI_OUT)/.stamp-debug-$(abi))
_RELEASE_STAMPS := $(foreach abi,$(ABIS),$(JNI_OUT)/.stamp-release-$(abi))

$(JNI_OUT)/.stamp-debug-%: $(_RUST_SRCS)
	@mkdir -p $(JNI_OUT)
	@echo "==> cargo-ndk [debug] ABI=$* NDK=$(or $(ANDROID_NDK_HOME),<auto>)"
	cd $(CORE_DIR) && cargo ndk -t $* --platform 26 -o $(JNI_OUT) build --lib
	@touch $@
	@rm -f $(JNI_OUT)/.stamp-release-$*

$(JNI_OUT)/.stamp-release-%: $(_RUST_SRCS)
	@mkdir -p $(JNI_OUT)
	@echo "==> cargo-ndk [release] ABI=$* NDK=$(or $(ANDROID_NDK_HOME),<auto>)"
	cd $(CORE_DIR) && cargo ndk -t $* --platform 26 -o $(JNI_OUT) build --lib --release
	@touch $@
	@rm -f $(JNI_OUT)/.stamp-debug-$*


# ── Gradle targets ─────────────────────────────────────────────────────────────
.PHONY: installDebug installRelease assembleDebug assembleRelease \
        core core-release core-all core-all-release \
        clean clean-core list-devices

.DEFAULT_GOAL := installDebug

installDebug: core
	$(GRADLEW) installDebug $(_GRADLE_SERIAL)

installRelease: core-release
	$(GRADLEW) installRelease $(_GRADLE_SERIAL)

assembleDebug: core
	$(GRADLEW) assembleDebug $(_GRADLE_SERIAL)

assembleRelease: core-release
	$(GRADLEW) assembleRelease $(_GRADLE_SERIAL)

# ── Native-only targets ────────────────────────────────────────────────────────
core: $(_DEBUG_STAMPS)
core-release: $(_RELEASE_STAMPS)

# Build all four ABIs regardless of what's currently connected — useful before
# distributing builds or on CI where no device is attached.
core-all:
	$(MAKE) ABIS="arm64-v8a armeabi-v7a x86_64 x86" core

core-all-release:
	$(MAKE) ABIS="arm64-v8a armeabi-v7a x86_64 x86" core-release

# ── Cleanup ────────────────────────────────────────────────────────────────────
clean: clean-core
	$(GRADLEW) clean

clean-core:
	rm -rf $(JNI_OUT)
	cd $(CORE_DIR) && cargo clean

# ── Utilities ──────────────────────────────────────────────────────────────────
list-devices:
	@echo "Connected devices and emulators:"
	@adb devices -l
