CC ?= aarch64-linux-android-clang
CFLAGS ?= -O3 -Wall -Wextra -pthread
TARGET = touch_injector

all: $(TARGET)

$(TARGET): injector.c ../common/protocol.h
	$(CC) $(CFLAGS) injector.c -o $(TARGET)

clean:
	rm -f $(TARGET)

# Deploy directly via ADB to rooted device
deploy: $(TARGET)
	adb push $(TARGET) /data/local/tmp/
	adb shell "su -c 'chmod 755 /data/local/tmp/$(TARGET)'"
	@echo "[+] Deployed to /data/local/tmp/$(TARGET)"

run: deploy
	adb forward tcp:8888 tcp:8888
	adb shell "su -c '/data/local/tmp/$(TARGET)'"

.PHONY: all clean deploy run
