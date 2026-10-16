import time
from typing import List, Optional
from rich.console import Console, Group
from rich.layout import Layout
from rich.panel import Panel
from rich.table import Table
from rich.text import Text
from rich.style import Style

SPARK_CHARS = [" ", " ", "▂", "▃", "▄", "▅", "▆", "▇", "█"]

def make_sparkline(values: List[float], min_val: float = 0.0, max_val: float = 65.0, length: int = 24) -> Text:
    if not values:
        return Text(" " * length, style="dim")
    recent = list(values)[-length:]
    if len(recent) < length:
        recent = [min_val] * (length - len(recent)) + recent

    text = Text()
    val_range = max(1.0, max_val - min_val)
    for v in recent:
        clamped = max(min_val, min(max_val, v))
        ratio = (clamped - min_val) / val_range
        idx = int(round(ratio * (len(SPARK_CHARS) - 1)))
        ch = SPARK_CHARS[idx]

        if v >= 55.0:
            style = "bold bright_green"
        elif v >= 45.0:
            style = "bright_yellow"
        elif v > 0:
            style = "bright_red"
        else:
            style = "dim"
        text.append(ch, style=style)
    return text

def make_bar(percent: float, width: int = 14, color: str = "green") -> Text:
    ratio = max(0.0, min(1.0, percent / 100.0))
    filled = int(round(ratio * width))
    bar = "█" * filled + "░" * (width - filled)
    return Text(f"[{bar}] {percent:.0f}%", style=color)

def create_dashboard_layout() -> Layout:
    layout = Layout()
    layout.split_column(
        Layout(name="header", size=3),
        Layout(name="body", ratio=1),
        Layout(name="footer", size=8)
    )
    layout["body"].split_row(
        Layout(name="hardware_panel", ratio=1),
        Layout(name="input_panel", ratio=1)
    )
    return layout

def render_dashboard(
    layout: Layout,
    app_name: str,
    active_pkg: str,
    fps: float,
    fps_history: List[float],
    cpu_pct: float,
    cpu_temp: float,
    gpu_pct: float,
    gpu_temp: float,
    mem_used: int,
    mem_total: int,
    battery_temp: float,
    telemetry_delay_ms: int,
    polling_hz: float,
    target_hz: int,
    loop_latency_ms: float,
    aim_mode: bool,
    toggle_key: str,
    cam_pos: tuple,
    joy_active: bool,
    active_slots: dict,
    hotkeys: list,
    total_packets: int
):
    # 1. Header
    header_table = Table.grid(expand=True)
    header_table.add_column(justify="left", ratio=1)
    header_table.add_column(justify="center", ratio=2)
    header_table.add_column(justify="right", ratio=1)

    status_style = "bold green" if aim_mode else "bold yellow"
    status_text = "🔒 AIM LOCKED (360° LOOK)" if aim_mode else "🔓 CURSOR DESKTOP"

    header_table.add_row(
        Text(f" 📱 Redmi K20 Pro (raphael) | Android 17", style="bold cyan"),
        Text(f"🎮 KERNEL TOUCH MAPPER & SYSTEM MONITOR", style="bold bright_white"),
        Text(f"[{status_text}] [Key: '{toggle_key}'] ", style=status_style)
    )
    layout["header"].update(Panel(header_table, style="cyan", border_style="bright_cyan"))

    # 2. Hardware & FPS Panel (Left)
    hw_table = Table.grid(expand=True, padding=(0, 1))
    hw_table.add_column(style="dim", justify="left")
    hw_table.add_column(justify="right")

    fps_color = "bold bright_green" if fps >= 55 else ("bold yellow" if fps >= 40 else "bold red")
    spark = make_sparkline(fps_history, min_val=0, max_val=62, length=24)

    cpu_color = "bold green" if cpu_pct < 60 else ("bold yellow" if cpu_pct < 85 else "bold red")
    gpu_color = "bold green" if gpu_pct < 75 else ("bold yellow" if gpu_pct < 90 else "bold red")

    hw_table.add_row("Active Game:", Text(active_pkg, style="bold bright_cyan"))
    hw_table.add_row("Hardware Display FPS:", Text(f"{fps:.1f} FPS", style=fps_color))
    hw_table.add_row("FPS History Graph:", spark)
    hw_table.add_row("CPU Load & Temp:", Text.assemble(make_bar(cpu_pct, 12, cpu_color), f"  {cpu_temp:.1f}°C"))
    hw_table.add_row("GPU Load & Temp:", Text.assemble(make_bar(gpu_pct, 12, gpu_color), f"  {gpu_temp:.1f}°C"))

    mem_pct = (mem_used / mem_total * 100.0) if mem_total > 0 else 0.0
    hw_table.add_row("RAM Memory:", Text(f"{mem_used/1024:.1f} / {mem_total/1024:.1f} GB ({mem_pct:.0f}%)", style="bright_white"))
    hw_table.add_row("Battery Temp:", Text(f"{battery_temp:.1f}°C", style="yellow" if battery_temp > 42 else "green"))

    telem_desc = f"{telemetry_delay_ms} ms (Kernel Socket Stream)" if telemetry_delay_ms > 0 else "DISABLED"
    hw_table.add_row("Telemetry Stream:", Text(telem_desc, style="dim cyan"))

    layout["hardware_panel"].update(Panel(hw_table, title="[bold bright_green]⚡ Kernel Hardware Telemetry & FPS[/]", border_style="bright_green"))

    # 3. Input Pipeline Panel (Right)
    in_table = Table.grid(expand=True, padding=(0, 1))
    in_table.add_column(style="dim", justify="left")
    in_table.add_column(justify="right")

    hz_style = "bold bright_cyan" if polling_hz >= (target_hz * 0.9) else "bold yellow"
    in_table.add_row("Client Polling Rate:", Text(f"{polling_hz:.1f} Hz (Target: {target_hz}Hz)", style=hz_style))
    in_table.add_row("Engine Latency:", Text(f"{loop_latency_ms:.2f} ms", style="bold bright_green"))
    in_table.add_row("Total Kernel Packets:", Text(f"{total_packets:,}", style="bright_white"))
    in_table.add_row("Kernel Protocol:", Text("Linux Multitouch B (8B pack)", style="dim"))
    in_table.add_row("Aim Pivot Center (Slot 0):", Text(f"X:{int(cam_pos[0])} Y:{int(cam_pos[1])}", style="cyan"))
    in_table.add_row("WASD Joystick (Slot 1):", Text("ACTIVE" if joy_active else "IDLE", style="bold green" if joy_active else "dim"))
    in_table.add_row("Active Touch Slots:", Text(f"{len(active_slots)}/10 used", style="yellow" if active_slots else "dim"))

    layout["input_panel"].update(Panel(in_table, title="[bold bright_cyan]🎯 Sub-5ms Input Injection[/]", border_style="bright_cyan"))

    # 4. Footer (Keybindings Table)
    keys_table = Table(box=None, expand=True, padding=(0, 1), show_header=True)
    keys_table.add_column("Key", style="bold yellow", width=12)
    keys_table.add_column("Action Label", style="bright_white", width=18)
    keys_table.add_column("Target Screen (X, Y)", style="dim cyan", width=22)
    keys_table.add_column("Hardware Slot", justify="center", width=14)
    keys_table.add_column("State", justify="right")

    if not hotkeys:
        keys_table.add_row(
            "-",
            "Waiting for Android app...",
            "Open VBridgeMapper on phone",
            "-",
            Text("⏳ STANDBY", style="bold yellow")
        )
    else:
        for item in hotkeys[:6]:
            pressed = item.get("pressed", False)
            slot = active_slots.get(item["name"], "-")
            state_text = Text("● DOWN", style="bold bright_green") if pressed else Text("○ IDLE", style="dim")
            slot_text = Text(f"Slot {slot}", style="bold yellow") if slot != "-" else Text("-", style="dim")
            keys_table.add_row(
                item["name"].upper(),
                item.get("label", item["name"]),
                f"({item.get('x', 0)}, {item.get('y', 0)})",
                slot_text,
                state_text
            )

    layout["footer"].update(Panel(
        Group(
            keys_table,
            Text(" [Press '`' (Tilde) to Lock/Unlock Mouse Aim | Press 'T' to Toggle Telemetry | Ctrl+C to Exit]", style="dim italic", justify="center")
        ),
        title=f"[bold bright_yellow]⌨️ Keymapping Profile: {app_name}[/]",
        border_style="bright_yellow"
    ))
