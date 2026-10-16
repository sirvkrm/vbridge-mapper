#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <dirent.h>
#include <signal.h>
#include <math.h>
#include <stdbool.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/inotify.h>
#include <poll.h>
#include <linux/input.h>
#include <ctype.h>

#define MAX_SLOTS 10
#define MAX_BATCH_EVENTS 32
#define MAX_KEY_MAPPINGS 64
#define MAX_INPUT_DEVS 16

#define BITS_PER_LONG (sizeof(long) * 8)
#define NBITS(x) ((((x)-1)/BITS_PER_LONG)+1)
#define OFF(x) ((x)%BITS_PER_LONG)
#define BIT(x) (1UL<<OFF(x))
#define LONG(x) ((x)/BITS_PER_LONG)
#define test_bit(bit, array) ((array[LONG(bit)] >> OFF(bit)) & 1)

// Slots allocation
#define SLOT_CAMERA   0
#define SLOT_JOYSTICK 1
#define SLOT_KEY_MIN  2
#define SLOT_KEY_MAX  9

// -------------------------------------------------------------
// Data Structures
// -------------------------------------------------------------

typedef struct {
    int screen_width;
    int screen_height;
    int digitizer_max_x;
    int digitizer_max_y;
    int orientation; // 0, 90, 270
} DisplayConfig;

typedef struct {
    int scancode; // Linux KEY_* or BTN_*
    char label[32];
    float x;
    float y;
    bool is_pressed;
    int active_slot; // -1 if inactive, or 2..9
} KeyMapping;

typedef struct {
    bool enabled;
    float pivot_center_x;
    float pivot_center_y;
    float reset_radius;
    float sens_x;
    float sens_y;
    int toggle_key; // e.g. KEY_GRAVE (41)
} CameraConfig;

typedef struct {
    float curr_x;
    float curr_y;
    bool is_touching;
    bool recenter_pending;
    bool aim_mode; // true: Aim look active (mouse grabbed), false: Menu/Cursor mode (mouse ungrabbed)
} CameraState;

typedef struct {
    bool enabled;
    float center_x;
    float center_y;
    float radius;
    int key_w, key_s, key_a, key_d;
    int sprint_key;
} JoystickConfig;

typedef struct {
    bool pressed_w;
    bool pressed_s;
    bool pressed_a;
    bool pressed_d;
    bool pressed_sprint;
    bool is_active;
} JoystickState;

typedef struct {
    int fd;
    char path[256];
    char name[256];
    int min_x, max_x;
    int min_y, max_y;
    int tracking_id_counter;
    int active_slots[MAX_SLOTS]; // tracking_id or -1
    int active_slot_count;
} TouchDevice;

typedef struct {
    int fd;
    char path[256];
    char name[256];
    bool is_mouse;
    bool is_keyboard;
    bool is_grabbed;
} InputDevice;

// Global engine state
static volatile bool g_running = true;
static TouchDevice g_touch_dev;
static DisplayConfig g_display = { 2340, 1080, 1080, 2340, 90 };
static CameraConfig g_camera_cfg = { true, 1650.0f, 540.0f, 420.0f, 1.4f, 1.2f, KEY_GRAVE };
static CameraState g_camera_state = { 1650.0f, 540.0f, false, false, true };
static JoystickConfig g_joy_cfg = { true, 420.0f, 800.0f, 140.0f, KEY_W, KEY_S, KEY_A, KEY_D, KEY_LEFTSHIFT };
static JoystickState g_joy_state = { false, false, false, false, false, false };

static KeyMapping g_key_mappings[MAX_KEY_MAPPINGS];
static int g_key_mapping_count = 0;

static InputDevice g_input_devs[MAX_INPUT_DEVS];
static int g_input_dev_count = 0;

// -------------------------------------------------------------
// Signal Handling
// -------------------------------------------------------------

static void sig_handler(int sig) {
    (void)sig;
    g_running = false;
}

// -------------------------------------------------------------
// Coordinate Transformation
// -------------------------------------------------------------

static void to_digitizer_coords(float screen_x, float screen_y, int *dig_x, int *dig_y) {
    float sx = screen_x;
    float sy = screen_y;

    if (sx < 0.0f) sx = 0.0f;
    if (sx > (float)g_display.screen_width) sx = (float)g_display.screen_width;
    if (sy < 0.0f) sy = 0.0f;
    if (sy > (float)g_display.screen_height) sy = (float)g_display.screen_height;

    float dx = 0.0f;
    float dy = 0.0f;

    if (g_display.orientation == 90) {
        // Landscape (ear speaker left):
        // dig_x = ((screen_height - sy) / screen_height) * digitizer_max_x
        // dig_y = (sx / screen_width) * digitizer_max_y
        dx = ((g_display.screen_height - sy) / (float)g_display.screen_height) * (float)g_display.digitizer_max_x;
        dy = (sx / (float)g_display.screen_width) * (float)g_display.digitizer_max_y;
    } else if (g_display.orientation == 270) {
        dx = (sy / (float)g_display.screen_height) * (float)g_display.digitizer_max_x;
        dy = (((float)g_display.screen_width - sx) / (float)g_display.screen_width) * (float)g_display.digitizer_max_y;
    } else {
        dx = (sx / (float)g_display.screen_width) * (float)g_display.digitizer_max_x;
        dy = (sy / (float)g_display.screen_height) * (float)g_display.digitizer_max_y;
    }

    int max_x = g_display.digitizer_max_x > 0 ? (g_display.digitizer_max_x - 1) : 1079;
    int max_y = g_display.digitizer_max_y > 0 ? (g_display.digitizer_max_y - 1) : 2339;

    int res_x = (int)roundf(dx);
    int res_y = (int)roundf(dy);

    if (res_x < 0) res_x = 0;
    if (res_x > max_x) res_x = max_x;
    if (res_y < 0) res_y = 0;
    if (res_y > max_y) res_y = max_y;

    *dig_x = res_x;
    *dig_y = res_y;
}

// -------------------------------------------------------------
// Multitouch Type B Injection (Direct to /dev/input/eventX)
// -------------------------------------------------------------

static void add_event(struct input_event *batch, int *count, uint16_t type, uint16_t code, int32_t value) {
    if (*count >= MAX_BATCH_EVENTS) return;
    struct input_event *ev = &batch[*count];
    ev->time.tv_sec = 0;
    ev->time.tv_usec = 0;
    ev->type = type;
    ev->code = code;
    ev->value = value;
    (*count)++;
}

static void flush_events(int fd, struct input_event *batch, int *count) {
    if (*count <= 0) return;
    ssize_t written = write(fd, batch, sizeof(struct input_event) * (*count));
    (void)written;
    *count = 0;
}

static void touch_down(int slot, float sx, float sy) {
    if (slot < 0 || slot >= MAX_SLOTS) return;
    struct input_event batch[MAX_BATCH_EVENTS];
    int count = 0;

    int dx, dy;
    to_digitizer_coords(sx, sy, &dx, &dy);

    if (g_touch_dev.active_slots[slot] == -1) {
        g_touch_dev.tracking_id_counter++;
        if (g_touch_dev.tracking_id_counter > 65535) g_touch_dev.tracking_id_counter = 1;
        g_touch_dev.active_slots[slot] = g_touch_dev.tracking_id_counter;
        g_touch_dev.active_slot_count++;
    }

    add_event(batch, &count, EV_ABS, ABS_MT_SLOT, slot);
    add_event(batch, &count, EV_ABS, ABS_MT_TRACKING_ID, g_touch_dev.active_slots[slot]);
    add_event(batch, &count, EV_ABS, ABS_MT_POSITION_X, dx);
    add_event(batch, &count, EV_ABS, ABS_MT_POSITION_Y, dy);
    add_event(batch, &count, EV_KEY, BTN_TOUCH, 1);
    add_event(batch, &count, EV_SYN, SYN_REPORT, 0);

    flush_events(g_touch_dev.fd, batch, &count);
}

static void touch_move(int slot, float sx, float sy) {
    if (slot < 0 || slot >= MAX_SLOTS) return;
    if (g_touch_dev.active_slots[slot] == -1) {
        touch_down(slot, sx, sy);
        return;
    }

    struct input_event batch[MAX_BATCH_EVENTS];
    int count = 0;

    int dx, dy;
    to_digitizer_coords(sx, sy, &dx, &dy);

    add_event(batch, &count, EV_ABS, ABS_MT_SLOT, slot);
    add_event(batch, &count, EV_ABS, ABS_MT_POSITION_X, dx);
    add_event(batch, &count, EV_ABS, ABS_MT_POSITION_Y, dy);
    add_event(batch, &count, EV_SYN, SYN_REPORT, 0);

    flush_events(g_touch_dev.fd, batch, &count);
}

static void touch_up(int slot) {
    if (slot < 0 || slot >= MAX_SLOTS) return;
    if (g_touch_dev.active_slots[slot] == -1) return;

    struct input_event batch[MAX_BATCH_EVENTS];
    int count = 0;

    add_event(batch, &count, EV_ABS, ABS_MT_SLOT, slot);
    add_event(batch, &count, EV_ABS, ABS_MT_TRACKING_ID, -1);

    g_touch_dev.active_slots[slot] = -1;
    if (g_touch_dev.active_slot_count > 0) g_touch_dev.active_slot_count--;

    if (g_touch_dev.active_slot_count == 0) {
        add_event(batch, &count, EV_KEY, BTN_TOUCH, 0);
    }
    add_event(batch, &count, EV_SYN, SYN_REPORT, 0);

    flush_events(g_touch_dev.fd, batch, &count);
}

static void touch_reset_all(void) {
    struct input_event batch[MAX_BATCH_EVENTS];
    int count = 0;
    for (int i = 0; i < MAX_SLOTS; i++) {
        if (g_touch_dev.active_slots[i] != -1) {
            add_event(batch, &count, EV_ABS, ABS_MT_SLOT, i);
            add_event(batch, &count, EV_ABS, ABS_MT_TRACKING_ID, -1);
            g_touch_dev.active_slots[i] = -1;
        }
    }
    g_touch_dev.active_slot_count = 0;
    add_event(batch, &count, EV_KEY, BTN_TOUCH, 0);
    add_event(batch, &count, EV_SYN, SYN_REPORT, 0);
    flush_events(g_touch_dev.fd, batch, &count);
}

// -------------------------------------------------------------
// Slot Allocator (Slots 2..9)
// -------------------------------------------------------------

static int acquire_slot(void) {
    for (int s = SLOT_KEY_MIN; s <= SLOT_KEY_MAX; s++) {
        if (g_touch_dev.active_slots[s] == -1) {
            return s;
        }
    }
    return -1; // Out of hardware multi-touch slots
}

// -------------------------------------------------------------
// 360 Camera Pivot Engine (Slot 0)
// -------------------------------------------------------------

static void camera_start(void) {
    if (!g_camera_state.is_touching) {
        g_camera_state.curr_x = g_camera_cfg.pivot_center_x;
        g_camera_state.curr_y = g_camera_cfg.pivot_center_y;
        g_camera_state.recenter_pending = false;
        touch_down(SLOT_CAMERA, g_camera_state.curr_x, g_camera_state.curr_y);
        g_camera_state.is_touching = true;
    }
}

static void camera_stop(void) {
    if (g_camera_state.is_touching) {
        touch_up(SLOT_CAMERA);
        g_camera_state.is_touching = false;
        g_camera_state.recenter_pending = false;
    }
}

static void camera_update_mouse(int delta_x, int delta_y) {
    if (!g_camera_state.aim_mode) return;

    if (!g_camera_state.is_touching) {
        camera_start();
    }

    if (g_camera_state.recenter_pending) {
        g_camera_state.curr_x = g_camera_cfg.pivot_center_x;
        g_camera_state.curr_y = g_camera_cfg.pivot_center_y;
        touch_down(SLOT_CAMERA, g_camera_state.curr_x, g_camera_state.curr_y);
        g_camera_state.recenter_pending = false;
        if (delta_x == 0 && delta_y == 0) return;
    }

    if (delta_x == 0 && delta_y == 0) return;

    g_camera_state.curr_x += (float)delta_x * g_camera_cfg.sens_x;
    g_camera_state.curr_y += (float)delta_y * g_camera_cfg.sens_y;

    float dx = g_camera_state.curr_x - g_camera_cfg.pivot_center_x;
    float dy = g_camera_state.curr_y - g_camera_cfg.pivot_center_y;
    float dist = sqrtf(dx * dx + dy * dy);

    float effective_radius = g_camera_cfg.reset_radius > 300.0f ? g_camera_cfg.reset_radius : 300.0f;

    if (dist >= effective_radius) {
        // Reached boundary: cleanly lift (UP) and prepare recenter
        touch_up(SLOT_CAMERA);
        g_camera_state.recenter_pending = true;
    } else {
        // Smooth continuous 360 look
        touch_move(SLOT_CAMERA, g_camera_state.curr_x, g_camera_state.curr_y);
    }
}

// -------------------------------------------------------------
// WASD Joystick Engine (Slot 1)
// -------------------------------------------------------------

static void joystick_recalculate(void) {
    if (!g_joy_cfg.enabled) return;

    float vx = 0.0f;
    float vy = 0.0f;

    if (g_joy_state.pressed_w) vy -= 1.0f;
    if (g_joy_state.pressed_s) vy += 1.0f;
    if (g_joy_state.pressed_a) vx -= 1.0f;
    if (g_joy_state.pressed_d) vx += 1.0f;

    if (vx == 0.0f && vy == 0.0f) {
        if (g_joy_state.is_active) {
            touch_up(SLOT_JOYSTICK);
            g_joy_state.is_active = false;
        }
        return;
    }

    float len = sqrtf(vx * vx + vy * vy);
    float nvx = vx / len;
    float nvy = vy / len;

    // Sprint scaling: 1.0 for full sprint, 0.85 for normal run
    float scale = g_joy_state.pressed_sprint ? 1.0f : 0.85f;
    float target_x = g_joy_cfg.center_x + (nvx * g_joy_cfg.radius * scale);
    float target_y = g_joy_cfg.center_y + (nvy * g_joy_cfg.radius * scale);

    if (!g_joy_state.is_active) {
        touch_down(SLOT_JOYSTICK, g_joy_cfg.center_x, g_joy_cfg.center_y);
        g_joy_state.is_active = true;
    }
    touch_move(SLOT_JOYSTICK, target_x, target_y);
}

// -------------------------------------------------------------
// Scancode Parser & Key Mapper
// -------------------------------------------------------------

static int parse_scancode_name(const char *name) {
    if (!name || !*name) return 0;
    char lower[64];
    size_t i = 0;
    for (; name[i] && i < sizeof(lower) - 1; i++) {
        lower[i] = (char)tolower((unsigned char)name[i]);
    }
    lower[i] = '\0';

    if (strcmp(lower, "mouse_left") == 0 || strcmp(lower, "left_click") == 0 || strcmp(lower, "lmb") == 0) return BTN_LEFT;
    if (strcmp(lower, "mouse_right") == 0 || strcmp(lower, "right_click") == 0 || strcmp(lower, "rmb") == 0) return BTN_RIGHT;
    if (strcmp(lower, "mouse_middle") == 0 || strcmp(lower, "middle_click") == 0) return BTN_MIDDLE;
    if (strcmp(lower, "mouse_side") == 0 || strcmp(lower, "mouse_side1") == 0) return BTN_SIDE;
    if (strcmp(lower, "mouse_extra") == 0 || strcmp(lower, "mouse_side2") == 0) return BTN_EXTRA;

    if (strcmp(lower, "space") == 0) return KEY_SPACE;
    if (strcmp(lower, "shift") == 0 || strcmp(lower, "left_shift") == 0) return KEY_LEFTSHIFT;
    if (strcmp(lower, "right_shift") == 0) return KEY_RIGHTSHIFT;
    if (strcmp(lower, "ctrl") == 0 || strcmp(lower, "left_ctrl") == 0) return KEY_LEFTCTRL;
    if (strcmp(lower, "alt") == 0 || strcmp(lower, "left_alt") == 0) return KEY_LEFTALT;
    if (strcmp(lower, "tab") == 0) return KEY_TAB;
    if (strcmp(lower, "enter") == 0 || strcmp(lower, "return") == 0) return KEY_ENTER;
    if (strcmp(lower, "esc") == 0 || strcmp(lower, "escape") == 0) return KEY_ESC;
    if (strcmp(lower, "backspace") == 0) return KEY_BACKSPACE;
    if (strcmp(lower, "capslock") == 0) return KEY_CAPSLOCK;
    if (strcmp(lower, "`") == 0 || strcmp(lower, "~") == 0 || strcmp(lower, "tilde") == 0 || strcmp(lower, "grave") == 0) return KEY_GRAVE;

    if (lower[0] == 'f' && isdigit(lower[1])) {
        int fn = atoi(lower + 1);
        if (fn >= 1 && fn <= 10) return KEY_F1 + (fn - 1);
        if (fn == 11) return KEY_F11;
        if (fn == 12) return KEY_F12;
    }

    if (strlen(lower) == 1) {
        char c = lower[0];
        if (c >= '1' && c <= '9') return KEY_1 + (c - '1');
        if (c == '0') return KEY_0;

        switch (c) {
            case 'q': return KEY_Q; case 'w': return KEY_W; case 'e': return KEY_E;
            case 'r': return KEY_R; case 't': return KEY_T; case 'y': return KEY_Y;
            case 'u': return KEY_U; case 'i': return KEY_I; case 'o': return KEY_O;
            case 'p': return KEY_P; case 'a': return KEY_A; case 's': return KEY_S;
            case 'd': return KEY_D; case 'f': return KEY_F; case 'g': return KEY_G;
            case 'h': return KEY_H; case 'j': return KEY_J; case 'k': return KEY_K;
            case 'l': return KEY_L; case 'z': return KEY_Z; case 'x': return KEY_X;
            case 'c': return KEY_C; case 'v': return KEY_V; case 'b': return KEY_B;
            case 'n': return KEY_N; case 'm': return KEY_M;
            default: break;
        }
    }
    return 0;
}

// -------------------------------------------------------------
// Aim Look vs. Cursor / Loot Mode Toggle
// -------------------------------------------------------------

static void set_aim_mode(bool enable) {
    if (g_camera_state.aim_mode == enable) return;
    g_camera_state.aim_mode = enable;

    if (g_camera_state.aim_mode) {
        // Combat Aim Mode: Grab mouse exclusively so Android cursor disappears!
        for (int i = 0; i < g_input_dev_count; i++) {
            if (g_input_devs[i].is_mouse && !g_input_devs[i].is_grabbed) {
                if (ioctl(g_input_devs[i].fd, EVIOCGRAB, 1) == 0) {
                    g_input_devs[i].is_grabbed = true;
                    printf("[+] Mouse GRABBED exclusively. Aim Look active. (Android cursor hidden)\n");
                }
            }
        }
        camera_start();
    } else {
        // Loot / Menu Mode: Ungrab mouse so Android native cursor appears!
        camera_stop();
        for (int i = 0; i < g_input_dev_count; i++) {
            if (g_input_devs[i].is_mouse && g_input_devs[i].is_grabbed) {
                ioctl(g_input_devs[i].fd, EVIOCGRAB, 0);
                g_input_devs[i].is_grabbed = false;
                printf("[*] Mouse UNGRABBED. Cursor Mode active. (Android cursor visible for looting)\n");
            }
        }
    }
}

// -------------------------------------------------------------
// Lightweight Profile Parser (Direct from /sdcard/VbridgeBind/...)
// -------------------------------------------------------------

static void load_default_profile(void) {
    g_key_mapping_count = 0;
    // Pre-populate standard Free Fire / BGMI mapping
    KeyMapping defaults[] = {
        { BTN_LEFT, "Fire", 1950.0f, 750.0f, false, -1 },
        { BTN_RIGHT, "Scope / ADS", 1920.0f, 420.0f, false, -1 },
        { KEY_SPACE, "Jump", 2150.0f, 680.0f, false, -1 },
        { KEY_C, "Crouch", 1980.0f, 920.0f, false, -1 },
        { KEY_Z, "Prone", 2180.0f, 920.0f, false, -1 },
        { KEY_R, "Reload", 1820.0f, 920.0f, false, -1 },
        { KEY_F, "Loot 1", 1520.0f, 480.0f, false, -1 },
        { KEY_G, "Loot 2", 1520.0f, 560.0f, false, -1 },
        { KEY_1, "Gun 1", 1050.0f, 960.0f, false, -1 },
        { KEY_2, "Gun 2", 1300.0f, 960.0f, false, -1 },
        { KEY_3, "Melee", 1180.0f, 860.0f, false, -1 },
        { KEY_4, "Wall", 750.0f, 960.0f, false, -1 },
        { KEY_5, "Medkit", 620.0f, 960.0f, false, -1 },
        { KEY_TAB, "Backpack", 200.0f, 950.0f, false, -1 },
        { KEY_M, "Map", 2200.0f, 120.0f, false, -1 }
    };
    int count = sizeof(defaults) / sizeof(defaults[0]);
    for (int i = 0; i < count && i < MAX_KEY_MAPPINGS; i++) {
        g_key_mappings[i] = defaults[i];
    }
    g_key_mapping_count = count;
    printf("[+] Default Free Fire / BGMI profile loaded (%d key mappings)\n", count);
}

static bool parse_profile_json(const char *filepath) {
    FILE *f = fopen(filepath, "r");
    if (!f) {
        printf("[-] Could not open profile: %s (using defaults)\n", filepath);
        load_default_profile();
        return false;
    }

    fseek(f, 0, SEEK_END);
    long sz = ftell(f);
    fseek(f, 0, SEEK_SET);

    char *buf = malloc(sz + 1);
    if (!buf) {
        fclose(f);
        load_default_profile();
        return false;
    }

    fread(buf, 1, sz, f);
    buf[sz] = '\0';
    fclose(f);

    // Simple, robust token scanner for profile keys
    char *ptr = NULL;

    // Orientation
    if ((ptr = strstr(buf, "\"orientation\""))) {
        ptr = strchr(ptr, ':');
        if (ptr) g_display.orientation = atoi(ptr + 1);
    }
    // Screen Width
    if ((ptr = strstr(buf, "\"screen_width\""))) {
        ptr = strchr(ptr, ':');
        if (ptr) g_display.screen_width = atoi(ptr + 1);
    }
    // Screen Height
    if ((ptr = strstr(buf, "\"screen_height\""))) {
        ptr = strchr(ptr, ':');
        if (ptr) g_display.screen_height = atoi(ptr + 1);
    }

    // Camera pivot_center: [x, y]
    if ((ptr = strstr(buf, "\"pivot_center\""))) {
        ptr = strchr(ptr, '[');
        if (ptr) {
            float cx = 1650.0f, cy = 540.0f;
            if (sscanf(ptr + 1, "%f , %f", &cx, &cy) >= 2 || sscanf(ptr + 1, "%f,%f", &cx, &cy) >= 2) {
                g_camera_cfg.pivot_center_x = cx;
                g_camera_cfg.pivot_center_y = cy;
            }
        }
    }
    // Camera reset_radius
    if ((ptr = strstr(buf, "\"reset_radius\""))) {
        ptr = strchr(ptr, ':');
        if (ptr) g_camera_cfg.reset_radius = strtof(ptr + 1, NULL);
    }
    // Sensitivity
    if ((ptr = strstr(buf, "\"sensitivity_x\""))) {
        ptr = strchr(ptr, ':');
        if (ptr) g_camera_cfg.sens_x = strtof(ptr + 1, NULL);
    }
    if ((ptr = strstr(buf, "\"sensitivity_y\""))) {
        ptr = strchr(ptr, ':');
        if (ptr) g_camera_cfg.sens_y = strtof(ptr + 1, NULL);
    }

    // Joystick center: [x, y]
    if ((ptr = strstr(buf, "\"center\""))) {
        ptr = strchr(ptr, '[');
        if (ptr) {
            float jx = 420.0f, jy = 800.0f;
            if (sscanf(ptr + 1, "%f , %f", &jx, &jy) >= 2 || sscanf(ptr + 1, "%f,%f", &jx, &jy) >= 2) {
                g_joy_cfg.center_x = jx;
                g_joy_cfg.center_y = jy;
            }
        }
    }
    if ((ptr = strstr(buf, "\"radius\""))) {
        ptr = strchr(ptr, ':');
        if (ptr) g_joy_cfg.radius = strtof(ptr + 1, NULL);
    }

    // Parse key_mappings array
    g_key_mapping_count = 0;
    char *km = strstr(buf, "\"key_mappings\"");
    if (km) {
        char *obj = strchr(km, '{');
        while (obj && g_key_mapping_count < MAX_KEY_MAPPINGS) {
            char *end_obj = strchr(obj, '}');
            if (!end_obj) break;

            char key_str[32] = {0};
            char label_str[32] = {0};
            float kx = 0.0f, ky = 0.0f;

            char *kp = strstr(obj, "\"key\"");
            if (kp && kp < end_obj) {
                char *kstart = strchr(kp + 5, '"');
                if (kstart && kstart < end_obj) {
                    char *kend = strchr(kstart + 1, '"');
                    if (kend && (size_t)(kend - kstart - 1) < sizeof(key_str)) {
                        strncpy(key_str, kstart + 1, kend - kstart - 1);
                    }
                }
            }

            char *lp = strstr(obj, "\"label\"");
            if (lp && lp < end_obj) {
                char *lstart = strchr(lp + 7, '"');
                if (lstart && lstart < end_obj) {
                    char *lend = strchr(lstart + 1, '"');
                    if (lend && (size_t)(lend - lstart - 1) < sizeof(label_str)) {
                        strncpy(label_str, lstart + 1, lend - lstart - 1);
                    }
                }
            }

            char *xp = strstr(obj, "\"x\"");
            if (xp && xp < end_obj) {
                char *xval = strchr(xp, ':');
                if (xval) kx = strtof(xval + 1, NULL);
            }
            char *yp = strstr(obj, "\"y\"");
            if (yp && yp < end_obj) {
                char *yval = strchr(yp, ':');
                if (yval) ky = strtof(yval + 1, NULL);
            }

            int scancode = parse_scancode_name(key_str);
            if (scancode > 0) {
                KeyMapping *m = &g_key_mappings[g_key_mapping_count++];
                m->scancode = scancode;
                strncpy(m->label, label_str, sizeof(m->label) - 1);
                m->x = kx;
                m->y = ky;
                m->is_pressed = false;
                m->active_slot = -1;
            }

            obj = strchr(end_obj + 1, '{');
        }
    }

    free(buf);
    printf("[+] Profile parsed: %d key mappings, Pivot(%.0f, %.0f), Joy(%.0f, %.0f)\n",
           g_key_mapping_count, g_camera_cfg.pivot_center_x, g_camera_cfg.pivot_center_y,
           g_joy_cfg.center_x, g_joy_cfg.center_y);
    return true;
}

// -------------------------------------------------------------
// Hardware Discovery & Automatic Detection
// -------------------------------------------------------------

static int detect_touchscreen(TouchDevice *dev) {
    char path[64];
    for (int i = 0; i < 32; i++) {
        snprintf(path, sizeof(path), "/dev/input/event%d", i);
        int fd = open(path, O_RDWR);
        if (fd < 0) continue;

        unsigned long ev_bits[NBITS(EV_MAX)];
        memset(ev_bits, 0, sizeof(ev_bits));
        ioctl(fd, EVIOCGBIT(0, sizeof(ev_bits)), ev_bits);

        if (test_bit(EV_ABS, ev_bits)) {
            unsigned long abs_bits[NBITS(ABS_MAX)];
            memset(abs_bits, 0, sizeof(abs_bits));
            ioctl(fd, EVIOCGBIT(EV_ABS, sizeof(abs_bits)), abs_bits);

            if (test_bit(ABS_MT_POSITION_X, abs_bits) && test_bit(ABS_MT_SLOT, abs_bits)) {
                dev->fd = fd;
                strncpy(dev->path, path, sizeof(dev->path) - 1);
                ioctl(fd, EVIOCGNAME(sizeof(dev->name)), dev->name);

                struct input_absinfo abs_x, abs_y;
                ioctl(fd, EVIOCGABS(ABS_MT_POSITION_X), &abs_x);
                ioctl(fd, EVIOCGABS(ABS_MT_POSITION_Y), &abs_y);
                dev->min_x = abs_x.minimum;
                dev->max_x = abs_x.maximum;
                dev->min_y = abs_y.minimum;
                dev->max_y = abs_y.maximum;

                for (int s = 0; s < MAX_SLOTS; s++) dev->active_slots[s] = -1;
                dev->active_slot_count = 0;
                dev->tracking_id_counter = 1;

                printf("[+] Found Hardware Touchscreen: %s (%s)\n", dev->path, dev->name);
                printf("[+] Touch Resolution: X [%d..%d], Y [%d..%d]\n", dev->min_x, dev->max_x, dev->min_y, dev->max_y);
                return 0;
            }
        }
        close(fd);
    }
    return -1;
}

static void scan_input_peripherals(void) {
    char path[64];
    for (int i = 0; i < 32; i++) {
        snprintf(path, sizeof(path), "/dev/input/event%d", i);

        // Skip our touchscreen device!
        if (strcmp(path, g_touch_dev.path) == 0) continue;

        // Check if already opened
        bool already_tracked = false;
        for (int d = 0; d < g_input_dev_count; d++) {
            if (strcmp(g_input_devs[d].path, path) == 0) {
                already_tracked = true;
                break;
            }
        }
        if (already_tracked) continue;

        int fd = open(path, O_RDWR | O_NONBLOCK);
        if (fd < 0) continue;

        unsigned long ev_bits[NBITS(EV_MAX)];
        memset(ev_bits, 0, sizeof(ev_bits));
        ioctl(fd, EVIOCGBIT(0, sizeof(ev_bits)), ev_bits);

        bool is_mouse = false;
        bool is_keyboard = false;

        if (test_bit(EV_REL, ev_bits)) {
            unsigned long rel_bits[NBITS(REL_MAX)];
            memset(rel_bits, 0, sizeof(rel_bits));
            ioctl(fd, EVIOCGBIT(EV_REL, sizeof(rel_bits)), rel_bits);
            if (test_bit(REL_X, rel_bits) && test_bit(REL_Y, rel_bits)) {
                is_mouse = true;
            }
        }

        if (test_bit(EV_KEY, ev_bits)) {
            unsigned long key_bits[NBITS(KEY_MAX)];
            memset(key_bits, 0, sizeof(key_bits));
            ioctl(fd, EVIOCGBIT(EV_KEY, sizeof(key_bits)), key_bits);
            if (test_bit(KEY_A, key_bits) && test_bit(KEY_Z, key_bits) && test_bit(KEY_SPACE, key_bits)) {
                is_keyboard = true;
            }
            if (test_bit(BTN_LEFT, key_bits)) {
                is_mouse = true;
            }
        }

        if ((is_mouse || is_keyboard) && g_input_dev_count < MAX_INPUT_DEVS) {
            InputDevice *dev = &g_input_devs[g_input_dev_count++];
            dev->fd = fd;
            strncpy(dev->path, path, sizeof(dev->path) - 1);
            ioctl(fd, EVIOCGNAME(sizeof(dev->name)), dev->name);
            dev->is_mouse = is_mouse;
            dev->is_keyboard = is_keyboard;
            dev->is_grabbed = false;

            printf("[+] Detected Input Peripheral #%d: %s (%s) [Mouse:%d, KB:%d]\n",
                   g_input_dev_count, dev->path, dev->name, dev->is_mouse, dev->is_keyboard);

            // Exclusive grab for keyboard immediately
            if (dev->is_keyboard) {
                if (ioctl(dev->fd, EVIOCGRAB, 1) == 0) {
                    dev->is_grabbed = true;
                    printf("    -> Keyboard GRABBED exclusively (Android shortcuts disabled)\n");
                }
            }
            // Exclusive grab for mouse if in aim mode
            if (dev->is_mouse && g_camera_state.aim_mode) {
                if (ioctl(dev->fd, EVIOCGRAB, 1) == 0) {
                    dev->is_grabbed = true;
                    printf("    -> Mouse GRABBED exclusively (Android cursor hidden)\n");
                }
            }
        } else {
            close(fd);
        }
    }
}

// -------------------------------------------------------------
// Event Processing (Hardware Event Loops)
// -------------------------------------------------------------

static void handle_keyboard_event(struct input_event *ev) {
    if (ev->type != EV_KEY) return;
    int scancode = ev->code;
    int value = ev->value; // 1 = press, 0 = release, 2 = autorepeat

    // Aim Mode Toggle Key (Tilde or F1)
    if ((scancode == g_camera_cfg.toggle_key || scancode == KEY_GRAVE || scancode == KEY_F1) && value == 1) {
        set_aim_mode(!g_camera_state.aim_mode);
        return;
    }

    // WASD Movement Keys
    if (scancode == g_joy_cfg.key_w) {
        g_joy_state.pressed_w = (value != 0);
        joystick_recalculate();
        return;
    }
    if (scancode == g_joy_cfg.key_s) {
        g_joy_state.pressed_s = (value != 0);
        joystick_recalculate();
        return;
    }
    if (scancode == g_joy_cfg.key_a) {
        g_joy_state.pressed_a = (value != 0);
        joystick_recalculate();
        return;
    }
    if (scancode == g_joy_cfg.key_d) {
        g_joy_state.pressed_d = (value != 0);
        joystick_recalculate();
        return;
    }
    if (scancode == g_joy_cfg.sprint_key) {
        g_joy_state.pressed_sprint = (value != 0);
        joystick_recalculate();
        return;
    }

    // Action Key Mappings
    for (int i = 0; i < g_key_mapping_count; i++) {
        KeyMapping *m = &g_key_mappings[i];
        if (m->scancode == scancode) {
            if (value == 1) { // Press
                if (m->active_slot == -1) {
                    int slot = acquire_slot();
                    if (slot >= 0) {
                        m->active_slot = slot;
                        m->is_pressed = true;
                        touch_down(slot, m->x, m->y);
                    }
                }
            } else if (value == 0) { // Release
                if (m->active_slot != -1) {
                    touch_up(m->active_slot);
                    m->active_slot = -1;
                    m->is_pressed = false;
                }
            }
            return;
        }
    }
}

static void handle_mouse_event(struct input_event *ev) {
    if (ev->type == EV_REL) {
        if (ev->code == REL_X) {
            camera_update_mouse(ev->value, 0);
        } else if (ev->code == REL_Y) {
            camera_update_mouse(0, ev->value);
        }
        return;
    }

    if (ev->type == EV_KEY) {
        int scancode = ev->code;
        int value = ev->value;

        // Mouse buttons mapped to actions (Left Click = Fire, Right Click = Scope)
        for (int i = 0; i < g_key_mapping_count; i++) {
            KeyMapping *m = &g_key_mappings[i];
            if (m->scancode == scancode) {
                if (value == 1) {
                    if (m->active_slot == -1) {
                        int slot = acquire_slot();
                        if (slot >= 0) {
                            m->active_slot = slot;
                            m->is_pressed = true;
                            touch_down(slot, m->x, m->y);
                        }
                    }
                } else if (value == 0) {
                    if (m->active_slot != -1) {
                        touch_up(m->active_slot);
                        m->active_slot = -1;
                        m->is_pressed = false;
                    }
                }
                return;
            }
        }
    }
}

// -------------------------------------------------------------
// Local Control Socket (IPC with VBridgeMapper Android App)
// -------------------------------------------------------------

static int create_ctrl_server(void) {
    int fd = socket(AF_UNIX, SOCK_DGRAM, 0);
    if (fd < 0) return -1;

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    // Abstract socket namespace @vbridge_ctrl
    addr.sun_path[0] = '\0';
    strncpy(addr.sun_path + 1, "vbridge_ctrl", sizeof(addr.sun_path) - 2);

    unlink(addr.sun_path);
    if (bind(fd, (struct sockaddr *)&addr, sizeof(sa_family_t) + strlen("vbridge_ctrl") + 1) < 0) {
        close(fd);
        return -1;
    }
    return fd;
}

static void handle_ctrl_message(int fd) {
    char buf[512];
    ssize_t n = recv(fd, buf, sizeof(buf) - 1, 0);
    if (n <= 0) return;
    buf[n] = '\0';

    if (strncmp(buf, "RELOAD_PROFILE:", 15) == 0) {
        char *path = buf + 15;
        while (*path == ' ') path++;
        printf("[*] IPC Command: Reloading profile from %s\n", path);
        parse_profile_json(path);
    } else if (strncmp(buf, "TOGGLE_AIM:", 11) == 0) {
        int val = atoi(buf + 11);
        set_aim_mode(val != 0);
    } else if (strncmp(buf, "UPDATE_CAMERA:", 14) == 0) {
        float px, py, rad, sx, sy;
        if (sscanf(buf + 14, "%f %f %f %f %f", &px, &py, &rad, &sx, &sy) >= 3) {
            g_camera_cfg.pivot_center_x = px;
            g_camera_cfg.pivot_center_y = py;
            g_camera_cfg.reset_radius = rad;
            if (sx > 0.0f) g_camera_cfg.sens_x = sx;
            if (sy > 0.0f) g_camera_cfg.sens_y = sy;
            printf("[*] IPC: Updated Camera Pivot (%.0f, %.0f), Radius=%.0f\n", px, py, rad);
        }
    } else if (strncmp(buf, "UPDATE_JOYSTICK:", 16) == 0) {
        float jx, jy, rad;
        if (sscanf(buf + 16, "%f %f %f", &jx, &jy, &rad) >= 3) {
            g_joy_cfg.center_x = jx;
            g_joy_cfg.center_y = jy;
            g_joy_cfg.radius = rad;
            printf("[*] IPC: Updated Joystick (%.0f, %.0f), Radius=%.0f\n", jx, jy, rad);
        }
    }
}

// -------------------------------------------------------------
// Main Entrypoint
// -------------------------------------------------------------

int main(int argc, char **argv) {
    setvbuf(stdout, NULL, _IONBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);

    const char *custom_dev = NULL;
    const char *profile_path = "/sdcard/VbridgeBind/preset1/keybinding1/profile.json";

    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "-d") == 0 && i + 1 < argc) {
            custom_dev = argv[++i];
        } else if (strcmp(argv[i], "-p") == 0 && i + 1 < argc) {
            profile_path = argv[++i];
        }
    }

    printf("===============================================================\n");
    printf("   VBridge Standalone On-Device Input Engine (Zero-PC Mode)    \n");
    printf("===============================================================\n");

    // 1. Detect Touchscreen Output
    if (custom_dev) {
        g_touch_dev.fd = open(custom_dev, O_RDWR);
        if (g_touch_dev.fd < 0) {
            fprintf(stderr, "[-] Failed to open touchscreen %s: %s\n", custom_dev, strerror(errno));
            return 1;
        }
        strncpy(g_touch_dev.path, custom_dev, sizeof(g_touch_dev.path) - 1);
        ioctl(g_touch_dev.fd, EVIOCGNAME(sizeof(g_touch_dev.name)), g_touch_dev.name);
        for (int s = 0; s < MAX_SLOTS; s++) g_touch_dev.active_slots[s] = -1;
        g_touch_dev.active_slot_count = 0;
        g_touch_dev.tracking_id_counter = 1;
        printf("[+] Manually opened touchscreen: %s (%s)\n", g_touch_dev.path, g_touch_dev.name);
    } else {
        if (detect_touchscreen(&g_touch_dev) < 0) {
            fprintf(stderr, "[-] No physical multi-touch screen detected!\n");
            return 1;
        }
    }

    // 2. Load Mapping Profile
    parse_profile_json(profile_path);

    // 3. Scan & Grab Connected Peripherals (OTG / Bluetooth)
    scan_input_peripherals();

    // 4. Inotify for Hotplug Detection
    int inotify_fd = inotify_init1(IN_NONBLOCK);
    if (inotify_fd >= 0) {
        inotify_add_watch(inotify_fd, "/dev/input", IN_CREATE | IN_DELETE);
    }

    // 5. IPC Control Server (@vbridge_ctrl)
    int ctrl_fd = create_ctrl_server();
    if (ctrl_fd >= 0) {
        printf("[+] IPC Control Server listening on @vbridge_ctrl\n");
    }

    signal(SIGINT, sig_handler);
    signal(SIGTERM, sig_handler);
    signal(SIGHUP, SIG_IGN);
    signal(SIGPIPE, SIG_IGN);

    // 6. Start in Aim Mode
    set_aim_mode(true);

    printf("[+] Engine Active! Press ` (Tilde) to toggle Aim Look / Loot Cursor.\n");

    // Polling structure
    while (g_running) {
        struct pollfd pfd[MAX_INPUT_DEVS + 4];
        int pfd_count = 0;

        // Add input devices
        int dev_to_pfd[MAX_INPUT_DEVS];
        for (int i = 0; i < g_input_dev_count; i++) {
            if (g_input_devs[i].fd >= 0) {
                dev_to_pfd[i] = pfd_count;
                pfd[pfd_count].fd = g_input_devs[i].fd;
                pfd[pfd_count].events = POLLIN;
                pfd[pfd_count].revents = 0;
                pfd_count++;
            } else {
                dev_to_pfd[i] = -1;
            }
        }

        // Add inotify fd
        int inotify_idx = -1;
        if (inotify_fd >= 0) {
            inotify_idx = pfd_count;
            pfd[pfd_count].fd = inotify_fd;
            pfd[pfd_count].events = POLLIN;
            pfd[pfd_count].revents = 0;
            pfd_count++;
        }

        // Add ctrl fd
        int ctrl_idx = -1;
        if (ctrl_fd >= 0) {
            ctrl_idx = pfd_count;
            pfd[pfd_count].fd = ctrl_fd;
            pfd[pfd_count].events = POLLIN;
            pfd[pfd_count].revents = 0;
            pfd_count++;
        }

        int ret = poll(pfd, pfd_count, 100); // 100ms timeout
        if (ret < 0) {
            if (errno == EINTR) continue;
            break;
        }

        // Check input devices
        for (int i = 0; i < g_input_dev_count; i++) {
            int idx = dev_to_pfd[i];
            if (idx >= 0 && (pfd[idx].revents & POLLIN)) {
                struct input_event ev[32];
                ssize_t bytes = read(g_input_devs[i].fd, ev, sizeof(ev));
                if (bytes > 0) {
                    size_t count = bytes / sizeof(struct input_event);
                    for (size_t k = 0; k < count; k++) {
                        if (g_input_devs[i].is_mouse) {
                            handle_mouse_event(&ev[k]);
                        }
                        if (g_input_devs[i].is_keyboard) {
                            handle_keyboard_event(&ev[k]);
                        }
                    }
                } else if (bytes < 0 && errno != EAGAIN && errno != EWOULDBLOCK) {
                    printf("[-] Device %s disconnected.\n", g_input_devs[i].path);
                    close(g_input_devs[i].fd);
                    g_input_devs[i].fd = -1;
                }
            }
        }

        // Check inotify for hotplug
        if (inotify_idx >= 0 && (pfd[inotify_idx].revents & POLLIN)) {
            char ibuf[512];
            ssize_t n = read(inotify_fd, ibuf, sizeof(ibuf));
            (void)n;
            scan_input_peripherals();
        }

        // Check IPC control socket
        if (ctrl_idx >= 0 && (pfd[ctrl_idx].revents & POLLIN)) {
            handle_ctrl_message(ctrl_fd);
        }
    }

    // Clean shutdown
    printf("\n[*] Shutting down VBridge Engine...\n");
    touch_reset_all();

    for (int i = 0; i < g_input_dev_count; i++) {
        if (g_input_devs[i].fd >= 0) {
            if (g_input_devs[i].is_grabbed) {
                ioctl(g_input_devs[i].fd, EVIOCGRAB, 0);
            }
            close(g_input_devs[i].fd);
        }
    }

    if (ctrl_fd >= 0) close(ctrl_fd);
    if (inotify_fd >= 0) close(inotify_fd);
    if (g_touch_dev.fd >= 0) close(g_touch_dev.fd);

    printf("[+] Clean shutdown complete.\n");
    return 0;
}
