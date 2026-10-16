import struct
from enum import IntEnum
from typing import Optional, NamedTuple

class TouchAction(IntEnum):
    UP = 0
    DOWN = 1
    MOVE = 2
    SYNC = 3
    RESET = 4
    CONFIG_TELEMETRY = 5

FLAG_AUTO_SYNC = 0x0001
PACKET_STRUCT = struct.Struct("<BBHHH")
PACKET_SIZE = PACKET_STRUCT.size  # Exactly 8 bytes

TELEMETRY_MAGIC = 0x544D5354
TELEMETRY_STRUCT = struct.Struct("<I10H")
TELEMETRY_SIZE = TELEMETRY_STRUCT.size # Exactly 24 bytes

class DeviceTelemetry(NamedTuple):
    fps: float
    cpu_usage_pct: float
    cpu_temp_c: float
    gpu_usage_pct: float
    gpu_temp_c: float
    mem_used_mb: int
    mem_total_mb: int
    battery_temp_c: float

def pack_event(action: TouchAction, slot: int, x: int, y: int, auto_sync: bool = True) -> bytes:
    flags = FLAG_AUTO_SYNC if auto_sync else 0
    return PACKET_STRUCT.pack(int(action), int(slot) & 0xFF, int(x) & 0xFFFF, int(y) & 0xFFFF, flags)

def pack_sync() -> bytes:
    return PACKET_STRUCT.pack(int(TouchAction.SYNC), 0, 0, 0, 0)

def pack_reset() -> bytes:
    return PACKET_STRUCT.pack(int(TouchAction.RESET), 0, 0, 0, FLAG_AUTO_SYNC)

def pack_config_telemetry(interval_ms: int) -> bytes:
    return PACKET_STRUCT.pack(int(TouchAction.CONFIG_TELEMETRY), 0, interval_ms & 0xFFFF, 0, 0)

def unpack_telemetry(data: bytes) -> Optional[DeviceTelemetry]:
    if len(data) < TELEMETRY_SIZE:
        return None
    magic, fps_x10, cpu_pct_x10, cpu_t_x10, gpu_pct_x10, gpu_t_x10, mem_u, mem_t, bat_t_x10, r1, r2 = TELEMETRY_STRUCT.unpack_from(data)
    if magic != TELEMETRY_MAGIC:
        return None
    return DeviceTelemetry(
        fps=fps_x10 / 10.0,
        cpu_usage_pct=cpu_pct_x10 / 10.0,
        cpu_temp_c=cpu_t_x10 / 10.0,
        gpu_usage_pct=gpu_pct_x10 / 10.0,
        gpu_temp_c=gpu_t_x10 / 10.0,
        mem_used_mb=mem_u,
        mem_total_mb=mem_t,
        battery_temp_c=bat_t_x10 / 10.0
    )
