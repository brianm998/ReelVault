# kotlin-desktop/Makefile
# Convenience wrapper around Gradle for building the desktop client.
#
# Common targets:
#   make / make build           gradle build
#   make run                    gradle run
#   make package                gradle packageReleaseDistributable
#   make clean                  gradle clean

DESKTOP_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
GRADLEW     := $(DESKTOP_DIR)/gradlew

.PHONY: build run package clean

.DEFAULT_GOAL := build

build:
	$(GRADLEW) build

run:
	$(GRADLEW) run

package:
	$(GRADLEW) packageReleaseDistributable

clean:
	$(GRADLEW) clean
