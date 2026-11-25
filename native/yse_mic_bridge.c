/* UDP wake (как housed) + TCP фраза после YSEGO. Режим --daemon: один процесс, сокеты живут между кусками tinycap. */
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <math.h>
#include <netinet/in.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>

#define CHUNK 320
#define CONF "/product/etc/yse-host.conf"
#define PHRASE_ON "/data/opt/ear/phrase.on"
#define PHRASE_DONE "/data/opt/ear/phrase.done"

static char host_ip[64] = "192.168.209.50";
static int base_port = 18805;
static float mic_prev;
static float aec_prev;
static uint32_t mic_seq;
static int16_t mic_pkt[CHUNK];
static int mic_fill;

static void load_conf(void) {
    FILE *f = fopen(CONF, "r");
    char line[128];
    if (!f) {
        return;
    }
    while (fgets(line, sizeof line, f)) {
        char val[64];
        if (sscanf(line, " host=%63s", val) == 1 || sscanf(line, "host=%63s", val) == 1) {
            snprintf(host_ip, sizeof host_ip, "%s", val);
        }
        if (sscanf(line, " base=%d", &base_port) == 1 || sscanf(line, "base=%d", &base_port) == 1) {
        }
    }
    fclose(f);
}

static int wav_header(const char *path, off_t *pcm_off) {
    FILE *f = fopen(path, "rb");
    unsigned char h[12];
    if (!f) {
        return -1;
    }
    if (fread(h, 1, 12, f) != 12) {
        fclose(f);
        return -1;
    }
    if (memcmp(h, "RIFF", 4) != 0) {
        fclose(f);
        *pcm_off = 0;
        return 0;
    }
    for (;;) {
        unsigned char id[4];
        unsigned int sz;
        if (fread(id, 1, 4, f) != 4) {
            break;
        }
        if (fread(&sz, 1, 4, f) != 4) {
            break;
        }
        if (memcmp(id, "data", 4) == 0) {
            *pcm_off = ftello(f);
            fclose(f);
            return 0;
        }
        fseeko(f, sz, SEEK_CUR);
    }
    fclose(f);
    *pcm_off = 44;
    return 0;
}

static void push_udp(int fd, int16_t sample) {
    unsigned char buf[4 + CHUNK * 2];
    float x = (float)sample;
    float y = x - 0.97f * mic_prev;
    int s;
    mic_prev = x;
    s = (int)(y >= 0.0f ? y + 0.5f : y - 0.5f);
    if (s > 32767) {
        s = 32767;
    } else if (s < -32768) {
        s = -32768;
    }
    mic_pkt[mic_fill++] = (int16_t)s;
    if (mic_fill < CHUNK) {
        return;
    }
    memcpy(buf, &mic_seq, 4);
    mic_seq++;
    memcpy(buf + 4, mic_pkt, sizeof mic_pkt);
    mic_fill = 0;
    send(fd, buf, sizeof buf, MSG_DONTWAIT);
}

static void push_tcp(int fd, int16_t sample) {
    float cy = (float)sample - 0.97f * aec_prev;
    int16_t s;
    aec_prev = (float)sample;
    s = (int16_t)cy;
    send(fd, &s, 2, MSG_NOSIGNAL);
}

static int udp_open(void) {
    struct sockaddr_in addr;
    int fd = socket(AF_INET, SOCK_DGRAM, 0);
    if (fd < 0) {
        return -1;
    }
    memset(&addr, 0, sizeof addr);
    addr.sin_family = AF_INET;
    addr.sin_port = htons((uint16_t)base_port);
    if (inet_pton(AF_INET, host_ip, &addr.sin_addr) != 1) {
        close(fd);
        return -1;
    }
    if (connect(fd, (struct sockaddr *)&addr, sizeof addr) != 0) {
        close(fd);
        return -1;
    }
    return fd;
}

static int phrase_connect(void) {
    struct sockaddr_in addr;
    int fd;
    const char *hdr = "YSE1 lag=0 chime=0\n";
    fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) {
        return -1;
    }
    memset(&addr, 0, sizeof addr);
    addr.sin_family = AF_INET;
    addr.sin_port = htons((uint16_t)(base_port + 5));
    if (inet_pton(AF_INET, host_ip, &addr.sin_addr) != 1) {
        close(fd);
        return -1;
    }
    if (connect(fd, (struct sockaddr *)&addr, sizeof addr) != 0) {
        close(fd);
        return -1;
    }
    send(fd, hdr, strlen(hdr), 0);
    unlink(PHRASE_ON);
    return fd;
}

static int phrase_poll_stop(int fd) {
    char buf[32];
    ssize_t n;
    if (fd < 0) {
        return 0;
    }
    n = recv(fd, buf, sizeof buf, MSG_DONTWAIT);
    if (n <= 0) {
        return 0;
    }
    buf[n < (ssize_t)sizeof buf ? n : sizeof buf - 1] = 0;
    if (strstr(buf, "stop") != NULL) {
        return 1;
    }
    return 0;
}

static void phrase_close(int *fd) {
    if (fd == NULL || *fd < 0) {
        return;
    }
    close(*fd);
    *fd = -1;
    creat(PHRASE_DONE, 0644);
}

static void ensure_phrase(int *phrase_fd) {
    if (*phrase_fd >= 0) {
        return;
    }
    if (access(PHRASE_ON, F_OK) != 0) {
        return;
    }
    *phrase_fd = phrase_connect();
}

static void write_status(const char *text) {
    int fd = open("/data/opt/ear/mic.status", O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd < 0) {
        return;
    }
    write(fd, text, strlen(text));
    close(fd);
}

static void stream_wav_fd(const char *path, int udp, int *phrase_fd, pid_t cap) {
    off_t pcm_off = 0;
    FILE *f = NULL;
    int16_t frame[16];
    int acc;
    int part;
    int waits;

    if (udp < 0) {
        return;
    }
    for (waits = 0; waits < 400 && f == NULL; waits++) {
        if (wav_header(path, &pcm_off) == 0) {
            f = fopen(path, "rb");
            if (f) {
                fseeko(f, pcm_off, SEEK_SET);
            }
        }
        if (f == NULL) {
            usleep(50000);
        }
    }
    if (f == NULL) {
        return;
    }
    write_status("udp ch14 pdm\n");
    acc = 0;
    part = 0;
    for (;;) {
        int st;
        if (fread(frame, sizeof(int16_t), 16, f) != 16) {
            clearerr(f);
            if (cap > 0 && waitpid(cap, &st, WNOHANG) == 0) {
                usleep(2000);
                continue;
            }
            break;
        }
        acc += frame[14];
        part++;
        if (part < 3) {
            continue;
        }
        {
            int16_t avg = (int16_t)(acc / 3);
            acc = 0;
            part = 0;
            push_udp(udp, avg);
            ensure_phrase(phrase_fd);
            if (*phrase_fd >= 0) {
                push_tcp(*phrase_fd, avg);
                if (phrase_poll_stop(*phrase_fd)) {
                    phrase_close(phrase_fd);
                }
            }
        }
    }
    fclose(f);
}

static void stream_wav(const char *path) {
    int udp = udp_open();
    int phrase = -1;
    stream_wav_fd(path, udp, &phrase, -1);
    phrase_close(&phrase);
    if (udp >= 0) {
        close(udp);
    }
}

static int run_tinycap(const char *wav) {
    pid_t pid;
    int st;
    const char *cap = "/product/bin/yse-ear-capture.sh";
    const char *tiny = "/system/bin/tinycap2";
    if (access(PHRASE_ON, F_OK) == 0) {
        pid = fork();
        if (pid == 0) {
            execl(cap, cap, wav, "-D", "0", "-d", "1", "-r", "48000", "-c", "16", "-p", "960", "-b", "16", "-t", "12",
                  (char *)NULL);
            execl(tiny, tiny, wav, "-D", "0", "-d", "1", "-r", "48000", "-c", "16", "-p", "960", "-b", "16", "-t", "12",
                  (char *)NULL);
            _exit(127);
        }
    } else {
        pid = fork();
        if (pid == 0) {
            execl(cap, cap, wav, "-D", "0", "-d", "1", "-r", "48000", "-c", "16", "-p", "960", "-b", "16", "-t", "1",
                  (char *)NULL);
            execl(tiny, tiny, wav, "-D", "0", "-d", "1", "-r", "48000", "-c", "16", "-p", "960", "-b", "16", "-t", "1",
                  (char *)NULL);
            _exit(127);
        }
    }
    if (pid < 0) {
        return -1;
    }
    while (waitpid(pid, &st, 0) < 0 && errno == EINTR) {
    }
    return WIFEXITED(st) ? WEXITSTATUS(st) : -1;
}

static int lock_self(void) {
    int fd = open("/data/opt/ear/bridge.lock", O_CREAT | O_RDWR, 0644);
    if (fd < 0) {
        return -1;
    }
    if (flock(fd, LOCK_EX | LOCK_NB) != 0) {
        _exit(0);
    }
    return fd;
}

static pid_t start_cap(const char *wav) {
    pid_t pid = fork();
    char sec[] = "3600";
    if (pid != 0) {
        usleep(300000);
        system("tinymix2 -D 0 set 94 PDMIN");
        return pid;
    }
    execl("/product/bin/yse-ear-capture.sh", "yse-ear-capture.sh", wav, "-D", "0", "-d", "1", "-r", "48000", "-c",
          "16", "-p", "960", "-b", "16", "-t", sec, (char *)NULL);
    execl("/system/bin/tinycap2", "tinycap2", wav, "-D", "0", "-d", "1", "-r", "48000", "-c", "16", "-p", "960", "-b",
          "16", "-t", sec, (char *)NULL);
    _exit(127);
}

static void daemon_loop(const char *wav) {
    int udp;
    int phrase = -1;
    int held;
    mkdir("/data/opt/ear", 0777);
    chmod("/data/opt/ear", 0777);
    held = lock_self();
    (void)held;
    udp = udp_open();
    for (;;) {
        int st;
        pid_t cap;
        unlink(wav);
        cap = start_cap(wav);
        if (cap < 0) {
            sleep(1);
            continue;
        }
        stream_wav_fd(wav, udp, &phrase, cap);
        kill(cap, SIGKILL);
        while (waitpid(cap, &st, 0) < 0 && errno == EINTR) {
        }
        sleep(1);
    }
}

int main(int argc, char **argv) {
    const char *wav = "/data/opt/ear/loop.wav";
    load_conf();
    if (argc >= 2 && strcmp(argv[1], "--daemon") == 0) {
        if (argc >= 3) {
            wav = argv[2];
        }
        daemon_loop(wav);
        return 0;
    }
    if (argc < 2) {
        return 1;
    }
    stream_wav(argv[1]);
    return 0;
}