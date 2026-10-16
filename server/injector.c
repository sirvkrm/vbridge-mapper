#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <dirent.h>
#include <signal.h>
#include <pthread.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <linux/input.h>
#include <stdbool.h>

#include "../common/protocol.h"

#define MAX_SLOTS 10
#define MAX_BATCH_EVENTS 32

typedef struct {
    int fd;
    char path[256];
    char name[256];
    int min_x, max_x;
    int min_y, max_y;
    int tracking_id_counter;
    int active_slots[MAX_SLOTS]; // stores tracking_id, or -1 if inactive
    int active_slot_count;
} TouchDevice;

static volatile bool g_running = true;
static volatile int g_telemetry_interval_ms = 1000; // default 1000ms
static volatile int g_active_client_fd = -1;

static void sig_handler(int sig) {
    (void)sig;
    g_running = false;
}

// -------------------------------------------------------------
// Hardware Telemetry Functions (Direct Kernel /sys and /proc)
// -------------------------------------------------------------

static float get_measured_fps(void) {
    int fd = open("/sys/class/drm/sde-crtc-0/measured_fps", O_RDONLY);
    if (fd < 0) return 60.0f;
    char buf[128];
    ssize_t n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0) return 60.0f;
    buf[n] = '\0';
    float fps = 60.0f;
    if (sscanf(buf, "fps: %f", &fps) == 1) {
        return fps;
    }
    return 60.0f;
}

static uint16_t get_gpu_usage(void) {
    int fd = open("/sys/class/kgsl/kgsl-3d0/gpubusy", O_RDONLY);
    if (fd < 0) return 0;
    char buf[128];
    ssize_t n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0) return 0;
    buf[n] = '\0';
    unsigned long busy = 0, total = 0;
    if (sscanf(buf, "%lu %lu", &busy, &total) == 2 && total > 0) {
        return (uint16_t)((busy * 1000UL) / total); // % * 10
    }
    return 0;
}

static void get_cpu_stats(unsigned long long *idle, unsigned long long *total) {
    int fd = open("/proc/stat", O_RDONLY);
    if (fd < 0) return;
    char buf[256];
    ssize_t n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0) return;
    buf[n] = '\0';
    unsigned long long user = 0, nice = 0, system = 0, idle_t = 0, iowait = 0, irq = 0, softirq = 0;
    if (sscanf(buf, "cpu %llu %llu %llu %llu %llu %llu %llu",
               &user, &nice, &system, &idle_t, &iowait, &irq, &softirq) >= 4) {
        *idle = idle_t + iowait;
        *total = user + nice + system + idle_t + iowait + irq + softirq;
    }
}

static void get_mem_stats(uint16_t *used_mb, uint16_t *total_mb) {
    FILE *f = fopen("/proc/meminfo", "r");
    if (!f) return;
    char line[128];
    unsigned long total_kb = 0, avail_kb = 0;
    while (fgets(line, sizeof(line), f)) {
        if (strncmp(line, "MemTotal:", 9) == 0) {
            sscanf(line + 9, "%lu", &total_kb);
        } else if (strncmp(line, "MemAvailable:", 13) == 0) {
            sscanf(line + 13, "%lu", &avail_kb);
        }
        if (total_kb && avail_kb) break;
    }
    fclose(f);
    if (total_kb > 0) {
        *total_mb = (uint16_t)(total_kb / 1024);
        if (total_kb > avail_kb) {
            *used_mb = (uint16_t)((total_kb - avail_kb) / 1024);
        }
    }
}

static char g_cpu_temp_path[128] = "/sys/class/thermal/thermal_zone6/temp";
static char g_gpu_temp_path[128] = "/sys/class/thermal/thermal_zone15/temp";
static char g_bat_temp_path[128] = "/sys/class/thermal/thermal_zone80/temp";

static void discover_thermal_zones(void) {
    char type_path[128], buf[64];
    for (int i = 0; i < 90; i++) {
        snprintf(type_path, sizeof(type_path), "/sys/class/thermal/thermal_zone%d/type", i);
        int fd = open(type_path, O_RDONLY);
        if (fd < 0) continue;
        ssize_t n = read(fd, buf, sizeof(buf) - 1);
        close(fd);
        if (n <= 0) continue;
        buf[n] = '\0';
        char *nl = strchr(buf, '\n');
        if (nl) *nl = '\0';

        if (strstr(buf, "cpuss-1") || strstr(buf, "cpu-1-4")) {
            snprintf(g_cpu_temp_path, sizeof(g_cpu_temp_path), "/sys/class/thermal/thermal_zone%d/temp", i);
        } else if (strstr(buf, "gpuss-0") || strstr(buf, "gpuss")) {
            snprintf(g_gpu_temp_path, sizeof(g_gpu_temp_path), "/sys/class/thermal/thermal_zone%d/temp", i);
        } else if (strcmp(buf, "battery") == 0 || strstr(buf, "bms")) {
            snprintf(g_bat_temp_path, sizeof(g_bat_temp_path), "/sys/class/thermal/thermal_zone%d/temp", i);
        }
    }
}

static uint16_t read_thermal(const char *path) {
    int fd = open(path, O_RDONLY);
    if (fd < 0) return 0;
    char buf[32];
    ssize_t n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0) return 0;
    buf[n] = '\0';
    long temp_mC = atol(buf);
    if (temp_mC > 1000) return (uint16_t)(temp_mC / 100); // 54200 -> 542 (54.2 C)
    return (uint16_t)(temp_mC * 10);
}

static void *telemetry_worker(void *arg) {
    (void)arg;
    discover_thermal_zones();
    unsigned long long prev_idle = 0, prev_total = 0;
    get_cpu_stats(&prev_idle, &prev_total);

    while (g_running) {
        int interval = g_telemetry_interval_ms;
        if (interval <= 0) {
            usleep(250000);
            continue;
        }

        usleep(interval * 1000);

        int client_fd = g_active_client_fd;
        if (client_fd < 0 || !g_running) continue;

        unsigned long long idle = 0, total = 0;
        get_cpu_stats(&idle, &total);
        uint16_t cpu_pct = 0;
        if (total > prev_total) {
            unsigned long long diff_total = total - prev_total;
            unsigned long long diff_idle = (idle >= prev_idle) ? (idle - prev_idle) : 0;
            if (diff_total > diff_idle) {
                cpu_pct = (uint16_t)(((diff_total - diff_idle) * 1000ULL) / diff_total);
            }
        }
        prev_idle = idle;
        prev_total = total;

        uint16_t mem_used = 0, mem_total = 0;
        get_mem_stats(&mem_used, &mem_total);

        TelemetryPacket pkt;
        memset(&pkt, 0, sizeof(pkt));
        pkt.magic = TELEMETRY_MAGIC;
        pkt.fps_x10 = (uint16_t)(get_measured_fps() * 10.0f);
        pkt.cpu_usage_pct = cpu_pct;
        pkt.cpu_temp_x10 = read_thermal(g_cpu_temp_path);
        pkt.gpu_usage_pct = get_gpu_usage();
        pkt.gpu_temp_x10 = read_thermal(g_gpu_temp_path);
        if (pkt.gpu_temp_x10 == 0) pkt.gpu_temp_x10 = pkt.cpu_temp_x10;
        pkt.battery_temp_x10 = read_thermal(g_bat_temp_path);
        pkt.mem_used_mb = mem_used;
        pkt.mem_total_mb = mem_total;

        send(client_fd, &pkt, sizeof(pkt), MSG_NOSIGNAL);
    }
    return NULL;
}

// -------------------------------------------------------------
// Touch Injection Subsystem
// -------------------------------------------------------------

int auto_detect_touchscreen(TouchDevice *dev) {
    char path[64];
    for (int i = 0; i < 32; i++) {
        snprintf(path, sizeof(path), "/dev/input/event%d", i);
        int fd = open(path, O_RDWR);
        if (fd < 0) continue;

        unsigned long ev_bits = 0;
        if (ioctl(fd, EVIOCGBIT(0, sizeof(ev_bits)), &ev_bits) < 0) {
            close(fd);
            continue;
        }

        if (ev_bits & (1UL << EV_ABS)) {
            unsigned long abs_bits[(ABS_MAX / (8 * sizeof(unsigned long))) + 1] = {0};
            ioctl(fd, EVIOCGBIT(EV_ABS, sizeof(abs_bits)), abs_bits);

            #define TEST_BIT(b, arr) ((arr[(b) / (8 * sizeof(unsigned long))] >> ((b) % (8 * sizeof(unsigned long)))) & 1UL)

            bool has_mt_x = TEST_BIT(ABS_MT_POSITION_X, abs_bits);
            bool has_mt_slot = TEST_BIT(ABS_MT_SLOT, abs_bits);

            if (has_mt_x && has_mt_slot) {
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
                return 0;
            }
        }
        close(fd);
    }
    return -1;
}

void init_touch_device(TouchDevice *dev) {
    dev->tracking_id_counter = 1;
    dev->active_slot_count = 0;
    for (int i = 0; i < MAX_SLOTS; i++) {
        dev->active_slots[i] = -1;
    }
}

static inline void add_event(struct input_event *batch, int *count, uint16_t type, uint16_t code, int32_t val) {
    struct input_event *ev = &batch[*count];
    memset(ev, 0, sizeof(struct input_event));
    ev->type = type;
    ev->code = code;
    ev->value = val;
    (*count)++;
}

void flush_events(int fd, struct input_event *batch, int *count) {
    if (*count <= 0) return;
    ssize_t bytes = (*count) * sizeof(struct input_event);
    ssize_t written = write(fd, batch, bytes);
    if (written < 0) {
        perror("[-] write to event dev failed");
    }
    *count = 0;
}

void process_packet(TouchDevice *dev, const TouchPacket *pkt, struct input_event *batch, int *count) {
    if (pkt->action == ACTION_CONFIG_TELEMETRY) {
        g_telemetry_interval_ms = pkt->x;
        return;
    }

    uint8_t slot = pkt->slot;
    if (slot >= MAX_SLOTS) return;

    switch (pkt->action) {
        case ACTION_DOWN: {
            int prev_active = dev->active_slot_count;
            if (dev->active_slots[slot] < 0) {
                dev->active_slots[slot] = dev->tracking_id_counter++;
                if (dev->tracking_id_counter > 65535) dev->tracking_id_counter = 1;
                dev->active_slot_count++;
            }

            uint16_t x = (dev->max_x > 0 && pkt->x > dev->max_x) ? dev->max_x : pkt->x;
            uint16_t y = (dev->max_y > 0 && pkt->y > dev->max_y) ? dev->max_y : pkt->y;

            add_event(batch, count, EV_ABS, ABS_MT_SLOT, slot);
            add_event(batch, count, EV_ABS, ABS_MT_TRACKING_ID, dev->active_slots[slot]);
            add_event(batch, count, EV_ABS, ABS_MT_POSITION_X, x);
            add_event(batch, count, EV_ABS, ABS_MT_POSITION_Y, y);
            add_event(batch, count, EV_ABS, ABS_MT_TOUCH_MAJOR, 12);

            if (prev_active == 0) {
                add_event(batch, count, EV_KEY, BTN_TOUCH, 1);
                add_event(batch, count, EV_KEY, BTN_TOOL_FINGER, 1);
            }
            break;
        }

        case ACTION_MOVE: {
            if (dev->active_slots[slot] >= 0) {
                uint16_t x = (dev->max_x > 0 && pkt->x > dev->max_x) ? dev->max_x : pkt->x;
                uint16_t y = (dev->max_y > 0 && pkt->y > dev->max_y) ? dev->max_y : pkt->y;
                add_event(batch, count, EV_ABS, ABS_MT_SLOT, slot);
                add_event(batch, count, EV_ABS, ABS_MT_POSITION_X, x);
                add_event(batch, count, EV_ABS, ABS_MT_POSITION_Y, y);
            }
            break;
        }

        case ACTION_UP: {
            if (dev->active_slots[slot] >= 0) {
                dev->active_slots[slot] = -1;
                dev->active_slot_count--;

                add_event(batch, count, EV_ABS, ABS_MT_SLOT, slot);
                add_event(batch, count, EV_ABS, ABS_MT_TRACKING_ID, -1);

                if (dev->active_slot_count == 0) {
                    add_event(batch, count, EV_KEY, BTN_TOUCH, 0);
                    add_event(batch, count, EV_KEY, BTN_TOOL_FINGER, 0);
                }
            }
            break;
        }

        case ACTION_RESET: {
            for (int s = 0; s < MAX_SLOTS; s++) {
                if (dev->active_slots[s] >= 0) {
                    dev->active_slots[s] = -1;
                    add_event(batch, count, EV_ABS, ABS_MT_SLOT, s);
                    add_event(batch, count, EV_ABS, ABS_MT_TRACKING_ID, -1);
                }
            }
            dev->active_slot_count = 0;
            add_event(batch, count, EV_KEY, BTN_TOUCH, 0);
            break;
        }

        case ACTION_SYNC:
        default:
            break;
    }

    if (pkt->flags & FLAG_AUTO_SYNC || pkt->action == ACTION_SYNC || pkt->action == ACTION_RESET) {
        add_event(batch, count, EV_SYN, SYN_REPORT, 0);
        flush_events(dev->fd, batch, count);
    } else if (*count >= (MAX_BATCH_EVENTS - 6)) {
        flush_events(dev->fd, batch, count);
    }
}

int main(int argc, char **argv) {
    setvbuf(stdout, NULL, _IONBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);
    TouchDevice dev;
    memset(&dev, 0, sizeof(dev));
    init_touch_device(&dev);

    int port = DEFAULT_PORT;
    const char *custom_dev = NULL;

    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "-d") == 0 && i + 1 < argc) {
            custom_dev = argv[++i];
        } else if (strcmp(argv[i], "-p") == 0 && i + 1 < argc) {
            port = atoi(argv[++i]);
        }
    }

    if (custom_dev) {
        dev.fd = open(custom_dev, O_RDWR);
        if (dev.fd < 0) {
            fprintf(stderr, "[-] Could not open %s: %s\n", custom_dev, strerror(errno));
            return 1;
        }
        strncpy(dev.path, custom_dev, sizeof(dev.path) - 1);
        ioctl(dev.fd, EVIOCGNAME(sizeof(dev.name)), dev.name);
        struct input_absinfo abs_x, abs_y;
        ioctl(dev.fd, EVIOCGABS(ABS_MT_POSITION_X), &abs_x);
        ioctl(dev.fd, EVIOCGABS(ABS_MT_POSITION_Y), &abs_y);
        dev.min_x = abs_x.minimum;
        dev.max_x = abs_x.maximum;
        dev.min_y = abs_y.minimum;
        dev.max_y = abs_y.maximum;
    } else {
        if (auto_detect_touchscreen(&dev) < 0) {
            fprintf(stderr, "[-] No multi-touch screen detected. Run with -d /dev/input/eventX\n");
            return 1;
        }
    }

    printf("[+] Touch Mapper Daemon Started\n");
    printf("[+] Device: %s (%s)\n", dev.path, dev.name);
    printf("[+] Digitizer Resolution: X [%d..%d], Y [%d..%d]\n", dev.min_x, dev.max_x, dev.min_y, dev.max_y);

    int server_fd = socket(AF_INET, SOCK_STREAM, 0);
    if (server_fd < 0) {
        perror("[-] socket creation failed");
        close(dev.fd);
        return 1;
    }

    int opt = 1;
    setsockopt(server_fd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

    struct sockaddr_in address;
    memset(&address, 0, sizeof(address));
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = htonl(INADDR_LOOPBACK); // 127.0.0.1
    address.sin_port = htons(port);

    if (bind(server_fd, (struct sockaddr *)&address, sizeof(address)) < 0) {
        perror("[-] bind failed");
        close(server_fd);
        close(dev.fd);
        return 1;
    }

    if (listen(server_fd, 8) < 0) {
        perror("[-] listen failed");
        close(server_fd);
        close(dev.fd);
        return 1;
    }

    signal(SIGHUP, SIG_IGN);
    signal(SIGPIPE, SIG_IGN);
    signal(SIGINT, sig_handler);
    signal(SIGTERM, sig_handler);

    // Start background hardware telemetry thread
    pthread_t tele_th;
    pthread_create(&tele_th, NULL, telemetry_worker, NULL);

    printf("[+] Listening on 127.0.0.1:%d (Ready for ADB forward)\n", port);

    struct input_event batch[MAX_BATCH_EVENTS];
    int batch_count = 0;

    while (g_running) {
        printf("[*] Waiting for Windows client connection...\n");
        int client_fd = accept(server_fd, NULL, NULL);
        if (client_fd < 0) {
            if (!g_running) break;
            continue;
        }

        int nodelay = 1;
        setsockopt(client_fd, IPPROTO_TCP, TCP_NODELAY, &nodelay, sizeof(nodelay));

        printf("[+] Windows Client Connected! Streaming kernel touch events...\n");
        g_active_client_fd = client_fd;

        TouchPacket pkt_buffer[16];
        while (g_running) {
            ssize_t bytes_read = recv(client_fd, pkt_buffer, sizeof(pkt_buffer), 0);
            if (bytes_read <= 0) {
                printf("[-] Client disconnected or error.\n");
                break;
            }

            size_t num_packets = bytes_read / sizeof(TouchPacket);
            for (size_t i = 0; i < num_packets; i++) {
                process_packet(&dev, &pkt_buffer[i], batch, &batch_count);
            }
        }

        g_active_client_fd = -1;

        // Reset all touch points upon disconnect
        TouchPacket reset_pkt = {
            .action = ACTION_RESET,
            .slot = 0,
            .x = 0,
            .y = 0,
            .flags = FLAG_AUTO_SYNC
        };
        process_packet(&dev, &reset_pkt, batch, &batch_count);

        close(client_fd);
    }

    close(server_fd);
    close(dev.fd);
    printf("[+] Clean shutdown complete.\n");
    return 0;
}
