#pragma once
#include <stdint.h>

#define PROTOCOL_MAGIC 0x544D  // "TM" (TouchMapper)
#define PROTOCOL_VERSION 1
#define DEFAULT_PORT 8888

// Action types
enum TouchAction {
    ACTION_UP    = 0,
    ACTION_DOWN  = 1,
    ACTION_MOVE  = 2,
    ACTION_SYNC  = 3,
    ACTION_RESET = 4,              // Release all active slots
    ACTION_CONFIG_TELEMETRY = 5   // Configure telemetry (x = interval_ms, 0 = disable)
};

// Fixed 8-byte binary packet for zero-overhead input transmission
#pragma pack(push, 1)
typedef struct {
    uint8_t action;   // TouchAction
    uint8_t slot;     // Multi-touch slot (0 - 9)
    uint16_t x;       // Absolute X coordinate or config param
    uint16_t y;       // Absolute Y coordinate
    uint16_t flags;   // Bit 0: auto-sync (SYN_REPORT), Bit 1-15: reserved
} TouchPacket;

#define FLAG_AUTO_SYNC 0x0001

#define TELEMETRY_MAGIC 0x544D5354 // "TMST"

// Fixed 24-byte device-to-host hardware telemetry packet
typedef struct {
    uint32_t magic;         // TELEMETRY_MAGIC
    uint16_t fps_x10;       // Display FPS * 10 (e.g. 594 = 59.4 FPS)
    uint16_t cpu_usage_pct; // Total CPU % * 10 (e.g. 345 = 34.5%)
    uint16_t cpu_temp_x10;  // CPU Temp °C * 10 (e.g. 521 = 52.1 °C)
    uint16_t gpu_usage_pct; // GPU % * 10 (e.g. 675 = 67.5%)
    uint16_t gpu_temp_x10;  // GPU Temp °C * 10 (e.g. 482 = 48.2 °C)
    uint16_t mem_used_mb;   // RAM used in MB
    uint16_t mem_total_mb;  // RAM total in MB
    uint16_t battery_temp_x10; // Battery Temp °C * 10
    uint16_t reserved1;
    uint16_t reserved2;
} TelemetryPacket;
#pragma pack(pop)
