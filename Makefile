# Build both halves of Penny.
#   make android   → android/app/build/outputs/apk/<variant>/app-<variant>.apk
#   make mac       → bridge/.build/<config>/penny-bridge
# Override the build type with ANDROID_VARIANT=debug or BRIDGE_CONFIG=debug.

ANDROID_VARIANT ?= release
BRIDGE_CONFIG   ?= release

# Gradle needs JDK 17. This ignores a JAVA_HOME in the environment, which may point at another JDK;
# override on the command line (make android JAVA_HOME=...) if needed.
JAVA_HOME    := $(shell /usr/libexec/java_home -v 17 2>/dev/null)
ANDROID_HOME ?= $(HOME)/Library/Android/sdk
export JAVA_HOME ANDROID_HOME

capitalize = $(shell echo $(1) | awk '{print toupper(substr($$0,1,1)) substr($$0,2)}')
APK := android/app/build/outputs/apk/$(ANDROID_VARIANT)/app-$(ANDROID_VARIANT).apk

.PHONY: all android mac clean

all: android mac

android:
	@test -n "$(JAVA_HOME)" || { echo "JDK 17 not found (install one or set JAVA_HOME)"; exit 1; }
	cd android && ./gradlew assemble$(call capitalize,$(ANDROID_VARIANT))
	@echo "APK: $(APK)"

mac:
	cd bridge && swift build -c $(BRIDGE_CONFIG)
	@echo "Bridge: bridge/.build/$(BRIDGE_CONFIG)/penny-bridge"

clean:
	cd android && ./gradlew clean
	cd bridge && swift package clean
