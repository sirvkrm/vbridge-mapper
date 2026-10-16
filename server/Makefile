CC ?= aarch64-linux-android-clang
CFLAGS ?= -O3 -Wall -Wextra
TARGET_PC = touch_injector
TARGET_STANDALONE = vbridge_engine

all: $(TARGET_PC) $(TARGET_STANDALONE)

$(TARGET_PC): injector.c ../common/protocol.h
	$(CC) $(CFLAGS) -pthread injector.c -o $(TARGET_PC)

$(TARGET_STANDALONE): vbridge_engine.c
	$(CC) $(CFLAGS) vbridge_engine.c -lm -o $(TARGET_STANDALONE)

clean:
	rm -f $(TARGET_PC) $(TARGET_STANDALONE)

# Deploy directly via ADB to rooted device
deploy: all
	adb push $(TARGET_PC) /data/local/tmp/
	adb push $(TARGET_STANDALONE) /data/local/tmp/
	adb shell "su -c 'chmod 755 /data/local/tmp/$(TARGET_PC) /data/local/tmp/$(TARGET_STANDALONE)'"
	@echo "[+] Deployed touch_injector and vbridge_engine to /data/local/tmp/"

run-standalone: deploy
	adb shell "su -c '/data/local/tmp/$(TARGET_STANDALONE)'"

run-pc: deploy
	adb forward tcp:8888 tcp:8888
	adb shell "su -c '/data/local/tmp/$(TARGET_PC)'"

.PHONY: all clean deploy run-standalone run-pc
