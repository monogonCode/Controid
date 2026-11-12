#define FUSE_USE_VERSION 31
#define _POSIX_C_SOURCE 200809L

#include <errno.h>
#include <fuse3/fuse.h>
#include <limits.h>
#include <pthread.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/statvfs.h>
#include <sys/un.h>
#include <unistd.h>

enum {
    FS_OP_STATFS = 0x01,
    FS_OP_GETATTR = 0x02,
    FS_OP_READDIR = 0x03,
    FS_OP_READ = 0x04,
    FS_OP_WRITE = 0x05,
    FS_OP_CREATE = 0x06,
    FS_OP_TRUNCATE = 0x07,
    FS_OP_MKDIR = 0x08,
    FS_OP_UNLINK = 0x09,
    FS_OP_RMDIR = 0x0A,
    FS_OP_RENAME = 0x0B,
};

#define FS_CHUNK_MAX 131072u
#define RPC_MAX_FRAME (16u * 1024u * 1024u)

typedef struct {
    uint8_t *data;
    size_t len;
    size_t cap;
} ByteBuf;

typedef struct {
    const uint8_t *p;
    size_t rem;
} Cursor;

typedef struct {
    pthread_mutex_t mu;
    int fd;
    char sock_path[PATH_MAX];
    uint8_t *rx_buf;
    size_t rx_cap;
    uid_t uid;
    gid_t gid;
} RpcClient;

static RpcClient g_rpc = {
    .mu = PTHREAD_MUTEX_INITIALIZER,
    .fd = -1,
};

static inline void put_u16_be(uint8_t *d, uint16_t v) {
    d[0] = (uint8_t)((v >> 8) & 0xFFu);
    d[1] = (uint8_t)(v & 0xFFu);
}

static inline void put_u32_be(uint8_t *d, uint32_t v) {
    d[0] = (uint8_t)((v >> 24) & 0xFFu);
    d[1] = (uint8_t)((v >> 16) & 0xFFu);
    d[2] = (uint8_t)((v >> 8) & 0xFFu);
    d[3] = (uint8_t)(v & 0xFFu);
}

static inline void put_u64_be(uint8_t *d, uint64_t v) {
    d[0] = (uint8_t)((v >> 56) & 0xFFu);
    d[1] = (uint8_t)((v >> 48) & 0xFFu);
    d[2] = (uint8_t)((v >> 40) & 0xFFu);
    d[3] = (uint8_t)((v >> 32) & 0xFFu);
    d[4] = (uint8_t)((v >> 24) & 0xFFu);
    d[5] = (uint8_t)((v >> 16) & 0xFFu);
    d[6] = (uint8_t)((v >> 8) & 0xFFu);
    d[7] = (uint8_t)(v & 0xFFu);
}

static inline uint16_t get_u16_be(const uint8_t *s) {
    return (uint16_t)(((uint16_t)s[0] << 8) | (uint16_t)s[1]);
}

static inline uint32_t get_u32_be(const uint8_t *s) {
    return ((uint32_t)s[0] << 24) | ((uint32_t)s[1] << 16) |
           ((uint32_t)s[2] << 8) | (uint32_t)s[3];
}

static inline uint64_t get_u64_be(const uint8_t *s) {
    return ((uint64_t)s[0] << 56) | ((uint64_t)s[1] << 48) |
           ((uint64_t)s[2] << 40) | ((uint64_t)s[3] << 32) |
           ((uint64_t)s[4] << 24) | ((uint64_t)s[5] << 16) |
           ((uint64_t)s[6] << 8) | (uint64_t)s[7];
}

static int bb_reserve(ByteBuf *b, size_t extra) {
    if (extra > SIZE_MAX - b->len) return -ENOMEM;
    size_t need = b->len + extra;
    if (need <= b->cap) return 0;
    size_t new_cap = b->cap ? b->cap : 256;
    while (new_cap < need) {
        if (new_cap > SIZE_MAX / 2) {
            new_cap = need;
            break;
        }
        new_cap *= 2;
    }
    uint8_t *p = realloc(b->data, new_cap);
    if (!p) return -ENOMEM;
    b->data = p;
    b->cap = new_cap;
    return 0;
}

static void bb_free(ByteBuf *b) {
    free(b->data);
    b->data = NULL;
    b->len = 0;
    b->cap = 0;
}

static int bb_put_bytes(ByteBuf *b, const void *src, size_t len) {
    if (len == 0) return 0;
    int rc = bb_reserve(b, len);
    if (rc < 0) return rc;
    memcpy(b->data + b->len, src, len);
    b->len += len;
    return 0;
}

static int bb_put_u32_be(ByteBuf *b, uint32_t v) {
    uint8_t tmp[4];
    put_u32_be(tmp, v);
    return bb_put_bytes(b, tmp, sizeof(tmp));
}

static int bb_put_u64_be(ByteBuf *b, uint64_t v) {
    uint8_t tmp[8];
    put_u64_be(tmp, v);
    return bb_put_bytes(b, tmp, sizeof(tmp));
}

static int bb_put_str(ByteBuf *b, const char *s) {
    size_t slen = strlen(s);
    if (slen > UINT16_MAX) return -ENAMETOOLONG;
    uint8_t tmp[2];
    put_u16_be(tmp, (uint16_t)slen);
    int rc = bb_put_bytes(b, tmp, sizeof(tmp));
    if (rc < 0) return rc;
    return bb_put_bytes(b, s, slen);
}

static bool cur_u32_be(Cursor *c, uint32_t *out) {
    if (c->rem < 4) return false;
    *out = get_u32_be(c->p);
    c->p += 4;
    c->rem -= 4;
    return true;
}

static bool cur_u64_be(Cursor *c, uint64_t *out) {
    if (c->rem < 8) return false;
    *out = get_u64_be(c->p);
    c->p += 8;
    c->rem -= 8;
    return true;
}

static bool cur_i64_be(Cursor *c, int64_t *out) {
    uint64_t u = 0;
    if (!cur_u64_be(c, &u)) return false;
    *out = (int64_t)u;
    return true;
}

static bool cur_str(Cursor *c, const char **out_ptr, uint16_t *out_len) {
    if (c->rem < 2) return false;
    uint16_t slen = get_u16_be(c->p);
    c->p += 2;
    c->rem -= 2;
    if (c->rem < slen) return false;
    *out_ptr = (const char *)c->p;
    *out_len = slen;
    c->p += slen;
    c->rem -= slen;
    return true;
}

static int write_exact(int fd, const uint8_t *buf, size_t len) {
    size_t sent = 0;
    while (sent < len) {
        ssize_t n = write(fd, buf + sent, len - sent);
        if (n < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (n == 0) return -1;
        sent += (size_t)n;
    }
    return 0;
}

static int read_exact(int fd, uint8_t *buf, size_t len) {
    size_t got = 0;
    while (got < len) {
        ssize_t n = read(fd, buf + got, len - got);
        if (n < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (n == 0) return -1;
        got += (size_t)n;
    }
    return 0;
}

static int rpc_connect_locked(void) {
    if (g_rpc.fd >= 0) {
        close(g_rpc.fd);
        g_rpc.fd = -1;
    }
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    size_t plen = strlen(g_rpc.sock_path);
    if (plen == 0 || plen >= sizeof(addr.sun_path)) {
        return -ENAMETOOLONG;
    }
    memcpy(addr.sun_path, g_rpc.sock_path, plen + 1);

    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) return -ENOTCONN;
    if (connect(fd, (struct sockaddr *)&addr, sizeof(addr)) != 0) {
        close(fd);
        return -ENOTCONN;
    }
    g_rpc.fd = fd;
    return 0;
}

static int rpc_exchange_locked(const uint8_t *frame, size_t frame_len, uint32_t *out_resp_len) {
    if (write_exact(g_rpc.fd, frame, frame_len) != 0) {
        return -1;
    }
    uint8_t hdr[4];
    if (read_exact(g_rpc.fd, hdr, sizeof(hdr)) != 0) {
        return -1;
    }
    uint32_t resp_len = get_u32_be(hdr);
    if (resp_len < 4 || resp_len > RPC_MAX_FRAME) {
        return -1;
    }
    if (resp_len > g_rpc.rx_cap) {
        uint8_t *p = realloc(g_rpc.rx_buf, resp_len);
        if (!p) return -1;
        g_rpc.rx_buf = p;
        g_rpc.rx_cap = resp_len;
    }
    if (read_exact(g_rpc.fd, g_rpc.rx_buf, resp_len) != 0) {
        return -1;
    }
    *out_resp_len = resp_len;
    return 0;
}

static int rpc_call(
    uint8_t op,
    const ByteBuf *body,
    uint8_t **out_resp,
    size_t *out_resp_len
) {
    if (out_resp) *out_resp = NULL;
    if (out_resp_len) *out_resp_len = 0;

    size_t body_len = body ? body->len : 0;
    if (body_len > UINT32_MAX - 1 || body_len + 5 > RPC_MAX_FRAME) {
        return -EMSGSIZE;
    }

    size_t frame_len = 5 + body_len;
    uint8_t stack_frame[512];
    uint8_t *frame = stack_frame;
    if (frame_len > sizeof(stack_frame)) {
        frame = malloc(frame_len);
        if (!frame) return -ENOMEM;
    }

    put_u32_be(frame, (uint32_t)(1 + body_len));
    frame[4] = op;
    if (body_len > 0) {
        memcpy(frame + 5, body->data, body_len);
    }

    pthread_mutex_lock(&g_rpc.mu);

    if (g_rpc.fd < 0 && rpc_connect_locked() < 0) {
        pthread_mutex_unlock(&g_rpc.mu);
        if (frame != stack_frame) free(frame);
        return -ENOTCONN;
    }

    uint32_t resp_len = 0;
    if (rpc_exchange_locked(frame, frame_len, &resp_len) != 0) {
        if (rpc_connect_locked() < 0 || rpc_exchange_locked(frame, frame_len, &resp_len) != 0) {
            if (g_rpc.fd >= 0) {
                close(g_rpc.fd);
                g_rpc.fd = -1;
            }
            pthread_mutex_unlock(&g_rpc.mu);
            if (frame != stack_frame) free(frame);
            return -ENOTCONN;
        }
    }

    if (frame != stack_frame) free(frame);

    int32_t rpc_errno = (int32_t)get_u32_be(g_rpc.rx_buf);
    if (rpc_errno != 0) {
        pthread_mutex_unlock(&g_rpc.mu);
        if (rpc_errno == INT32_MIN) return -EIO;
        return rpc_errno < 0 ? rpc_errno : -rpc_errno;
    }

    size_t payload_len = resp_len - 4;
    if (out_resp && out_resp_len && payload_len > 0) {
        uint8_t *copy = malloc(payload_len);
        if (!copy) {
            pthread_mutex_unlock(&g_rpc.mu);
            return -ENOMEM;
        }
        memcpy(copy, g_rpc.rx_buf + 4, payload_len);
        *out_resp = copy;
        *out_resp_len = payload_len;
    }

    pthread_mutex_unlock(&g_rpc.mu);
    return 0;
}

static bool parse_attr(Cursor *c, struct stat *st) {
    uint32_t mode = 0;
    uint64_t size = 0;
    int64_t atime = 0;
    int64_t mtime = 0;
    if (!cur_u32_be(c, &mode) || !cur_u64_be(c, &size) ||
        !cur_i64_be(c, &atime) || !cur_i64_be(c, &mtime)) {
        return false;
    }
    memset(st, 0, sizeof(*st));
    st->st_mode = (mode_t)mode;
    st->st_nlink = S_ISDIR(st->st_mode) ? 2 : 1;
    st->st_uid = g_rpc.uid;
    st->st_gid = g_rpc.gid;
    st->st_size = (off_t)size;
    st->st_blksize = 4096;
    st->st_blocks = (blkcnt_t)((size + 511u) / 512u);
    st->st_atime = (time_t)atime;
    st->st_mtime = (time_t)mtime;
    st->st_ctime = (time_t)mtime;
    return true;
}

static void *dc_init(struct fuse_conn_info *conn, struct fuse_config *cfg) {
    (void)cfg;
    conn->max_write = FS_CHUNK_MAX;
    return NULL;
}

static int dc_statfs(const char *path, struct statvfs *stbuf) {
    ByteBuf req = {0};
    int rc = bb_put_str(&req, (path && *path) ? path : "/");
    if (rc < 0) {
        bb_free(&req);
        return rc;
    }
    uint8_t *resp = NULL;
    size_t rlen = 0;
    rc = rpc_call(FS_OP_STATFS, &req, &resp, &rlen);
    bb_free(&req);
    if (rc < 0) return rc;

    Cursor c = { .p = resp, .rem = rlen };
    uint64_t bsize = 0, blocks = 0, bfree = 0, bavail = 0;
    if (!cur_u64_be(&c, &bsize) || !cur_u64_be(&c, &blocks) ||
        !cur_u64_be(&c, &bfree) || !cur_u64_be(&c, &bavail)) {
        free(resp);
        return -EIO;
    }
    free(resp);

    memset(stbuf, 0, sizeof(*stbuf));
    stbuf->f_bsize = (unsigned long)bsize;
    stbuf->f_frsize = (unsigned long)bsize;
    stbuf->f_blocks = (fsblkcnt_t)blocks;
    stbuf->f_bfree = (fsblkcnt_t)bfree;
    stbuf->f_bavail = (fsblkcnt_t)bavail;
    stbuf->f_namemax = 255;
    return 0;
}

static int dc_getattr(const char *path, struct stat *stbuf, struct fuse_file_info *fi) {
    (void)fi;
    ByteBuf req = {0};
    int rc = bb_put_str(&req, path);
    if (rc < 0) {
        bb_free(&req);
        return rc;
    }

    uint8_t *resp = NULL;
    size_t rlen = 0;
    rc = rpc_call(FS_OP_GETATTR, &req, &resp, &rlen);
    bb_free(&req);
    if (rc < 0) return rc;

    Cursor c = { .p = resp, .rem = rlen };
    bool ok = parse_attr(&c, stbuf);
    free(resp);
    return ok ? 0 : -EIO;
}

static int dc_readdir(
    const char *path,
    void *buf,
    fuse_fill_dir_t filler,
    off_t offset,
    struct fuse_file_info *fi,
    enum fuse_readdir_flags flags
) {
    (void)offset;
    (void)fi;
    (void)flags;

    ByteBuf req = {0};
    int rc = bb_put_str(&req, path);
    if (rc < 0) {
        bb_free(&req);
        return rc;
    }

    uint8_t *resp = NULL;
    size_t rlen = 0;
    rc = rpc_call(FS_OP_READDIR, &req, &resp, &rlen);
    bb_free(&req);
    if (rc < 0) return rc;

    Cursor c = { .p = resp, .rem = rlen };
    uint32_t count = 0;
    if (!cur_u32_be(&c, &count)) {
        free(resp);
        return -EIO;
    }

    filler(buf, ".", NULL, 0, FUSE_FILL_DIR_DEFAULTS);
    filler(buf, "..", NULL, 0, FUSE_FILL_DIR_DEFAULTS);

    char name_buf[4096];
    for (uint32_t i = 0; i < count; ++i) {
        const char *name_ptr = NULL;
        uint16_t name_len = 0;
        struct stat st;
        if (!cur_str(&c, &name_ptr, &name_len) || !parse_attr(&c, &st)) {
            free(resp);
            return -EIO;
        }
        if (name_len == 0 || name_len >= sizeof(name_buf)) {
            continue;
        }
        memcpy(name_buf, name_ptr, name_len);
        name_buf[name_len] = '\0';
        if (strcmp(name_buf, ".") == 0 || strcmp(name_buf, "..") == 0) {
            continue;
        }
        if (filler(buf, name_buf, &st, 0, FUSE_FILL_DIR_PLUS) != 0) {
            break;
        }
    }

    free(resp);
    return 0;
}

static int dc_open(const char *path, struct fuse_file_info *fi) {
    struct stat st;
    int rc = dc_getattr(path, &st, NULL);
    if (rc < 0) return rc;
    if (S_ISDIR(st.st_mode)) return -EISDIR;
    fi->keep_cache = 0;
    return 0;
}

static int dc_read(const char *path, char *buf, size_t size, off_t offset, struct fuse_file_info *fi) {
    (void)fi;
    if (offset < 0) return -EINVAL;

    size_t total = 0;
    ByteBuf req = {0};
    while (total < size) {
        size_t rem = size - total;
        uint32_t chunk = rem > FS_CHUNK_MAX ? FS_CHUNK_MAX : (uint32_t)rem;

        req.len = 0;
        int rc = bb_put_str(&req, path);
        if (rc == 0) rc = bb_put_u64_be(&req, (uint64_t)offset + (uint64_t)total);
        if (rc == 0) rc = bb_put_u32_be(&req, chunk);
        if (rc < 0) {
            bb_free(&req);
            return total > 0 ? (int)total : rc;
        }

        uint8_t *resp = NULL;
        size_t rlen = 0;
        rc = rpc_call(FS_OP_READ, &req, &resp, &rlen);
        if (rc < 0) {
            bb_free(&req);
            return total > 0 ? (int)total : rc;
        }

        Cursor c = { .p = resp, .rem = rlen };
        uint32_t actual_len = 0;
        if (!cur_u32_be(&c, &actual_len) || actual_len > chunk || c.rem != actual_len) {
            free(resp);
            bb_free(&req);
            return total > 0 ? (int)total : -EIO;
        }
        if (actual_len > 0) {
            memcpy(buf + total, c.p, actual_len);
        }
        free(resp);

        total += actual_len;
        if (actual_len < chunk) {
            break;
        }
    }

    bb_free(&req);
    return (int)total;
}

static int dc_write(
    const char *path,
    const char *buf,
    size_t size,
    off_t offset,
    struct fuse_file_info *fi
) {
    (void)fi;
    if (offset < 0) return -EINVAL;

    size_t total = 0;
    ByteBuf req = {0};
    while (total < size) {
        size_t rem = size - total;
        uint32_t chunk = rem > FS_CHUNK_MAX ? FS_CHUNK_MAX : (uint32_t)rem;

        req.len = 0;
        int rc = bb_put_str(&req, path);
        if (rc == 0) rc = bb_put_u64_be(&req, (uint64_t)offset + (uint64_t)total);
        if (rc == 0) rc = bb_put_u32_be(&req, chunk);
        if (rc == 0) rc = bb_put_bytes(&req, buf + total, chunk);
        if (rc < 0) {
            bb_free(&req);
            return total > 0 ? (int)total : rc;
        }

        uint8_t *resp = NULL;
        size_t rlen = 0;
        rc = rpc_call(FS_OP_WRITE, &req, &resp, &rlen);
        if (rc < 0) {
            bb_free(&req);
            return total > 0 ? (int)total : rc;
        }

        Cursor c = { .p = resp, .rem = rlen };
        uint32_t written = 0;
        bool ok = cur_u32_be(&c, &written) && written <= chunk;
        free(resp);
        if (!ok) {
            bb_free(&req);
            return total > 0 ? (int)total : -EIO;
        }

        total += written;
        if (written < chunk) {
            break;
        }
    }

    bb_free(&req);
    return (int)total;
}

static int dc_create(const char *path, mode_t mode, struct fuse_file_info *fi) {
    ByteBuf req = {0};
    int rc = bb_put_str(&req, path);
    if (rc == 0) rc = bb_put_u32_be(&req, (uint32_t)mode);
    if (rc == 0) rc = rpc_call(FS_OP_CREATE, &req, NULL, NULL);
    bb_free(&req);
    if (rc == 0) {
        fi->keep_cache = 0;
    }
    return rc;
}

static int dc_truncate(const char *path, off_t size, struct fuse_file_info *fi) {
    (void)fi;
    if (size < 0) return -EINVAL;
    ByteBuf req = {0};
    int rc = bb_put_str(&req, path);
    if (rc == 0) rc = bb_put_u64_be(&req, (uint64_t)size);
    if (rc == 0) rc = rpc_call(FS_OP_TRUNCATE, &req, NULL, NULL);
    bb_free(&req);
    return rc;
}

static int dc_mkdir(const char *path, mode_t mode) {
    ByteBuf req = {0};
    int rc = bb_put_str(&req, path);
    if (rc == 0) rc = bb_put_u32_be(&req, (uint32_t)mode);
    if (rc == 0) rc = rpc_call(FS_OP_MKDIR, &req, NULL, NULL);
    bb_free(&req);
    return rc;
}

static int dc_unlink(const char *path) {
    ByteBuf req = {0};
    int rc = bb_put_str(&req, path);
    if (rc == 0) rc = rpc_call(FS_OP_UNLINK, &req, NULL, NULL);
    bb_free(&req);
    return rc;
}

static int dc_rmdir(const char *path) {
    ByteBuf req = {0};
    int rc = bb_put_str(&req, path);
    if (rc == 0) rc = rpc_call(FS_OP_RMDIR, &req, NULL, NULL);
    bb_free(&req);
    return rc;
}

static int dc_rename(const char *from, const char *to, unsigned int flags) {
    ByteBuf req = {0};
    int rc = bb_put_str(&req, from);
    if (rc == 0) rc = bb_put_str(&req, to);
    if (rc == 0) rc = bb_put_u32_be(&req, (uint32_t)flags);
    if (rc == 0) rc = rpc_call(FS_OP_RENAME, &req, NULL, NULL);
    bb_free(&req);
    return rc;
}

static int dc_utimens(const char *path, const struct timespec tv[2], struct fuse_file_info *fi) {
    (void)path;
    (void)tv;
    (void)fi;
    return 0;
}

static const struct fuse_operations controid_ops = {
    .init = dc_init,
    .statfs = dc_statfs,
    .getattr = dc_getattr,
    .readdir = dc_readdir,
    .open = dc_open,
    .read = dc_read,
    .write = dc_write,
    .create = dc_create,
    .truncate = dc_truncate,
    .mkdir = dc_mkdir,
    .unlink = dc_unlink,
    .rmdir = dc_rmdir,
    .rename = dc_rename,
    .utimens = dc_utimens,
};

static void resolve_default_socket(char *dst, size_t cap) {
    const char *env_sock = getenv("CONTROID_FILES_SOCK");
    if (env_sock && env_sock[0] != '\0') {
        snprintf(dst, cap, "%s", env_sock);
        return;
    }
    const char *runtime_dir = getenv("XDG_RUNTIME_DIR");
    if (runtime_dir && runtime_dir[0] != '\0') {
        snprintf(dst, cap, "%s/controid/files.sock", runtime_dir);
    } else {
        snprintf(dst, cap, "/tmp/controid-%lu/files.sock", (unsigned long)getuid());
    }
}

static void print_usage(const char *prog) {
    printf("Usage: %s [--socket=<path>] [FUSE options] <mountpoint>\n"
           "Mount Android storage exposed via Controid Unix RPC socket.\n"
           "Socket lookup: --socket=<path> | CONTROID_FILES_SOCK | $XDG_RUNTIME_DIR/controid/files.sock\n",
           prog);
}

int main(int argc, char **argv) {
    signal(SIGPIPE, SIG_IGN);
    g_rpc.uid = getuid();
    g_rpc.gid = getgid();
    resolve_default_socket(g_rpc.sock_path, sizeof(g_rpc.sock_path));

    char **fuse_argv = calloc((size_t)argc + 1, sizeof(char *));
    if (!fuse_argv) return 1;

    int fuse_argc = 0;
    fuse_argv[fuse_argc++] = argv[0];

    for (int i = 1; i < argc; ++i) {
        const char *arg = argv[i];
        if (strcmp(arg, "-h") == 0 || strcmp(arg, "--help") == 0) {
            print_usage(argv[0]);
            free(fuse_argv);
            return 0;
        } else if (strncmp(arg, "--socket=", 9) == 0) {
            snprintf(g_rpc.sock_path, sizeof(g_rpc.sock_path), "%s", arg + 9);
        } else if (strcmp(arg, "--socket") == 0) {
            if (i + 1 >= argc) {
                fprintf(stderr, "controid-mount: --socket requires an argument\n");
                free(fuse_argv);
                return 1;
            }
            snprintf(g_rpc.sock_path, sizeof(g_rpc.sock_path), "%s", argv[++i]);
        } else {
            fuse_argv[fuse_argc++] = argv[i];
        }
    }
    fuse_argv[fuse_argc] = NULL;

    int ret = fuse_main(fuse_argc, fuse_argv, &controid_ops, NULL);
    free(fuse_argv);

    pthread_mutex_lock(&g_rpc.mu);
    if (g_rpc.fd >= 0) {
        close(g_rpc.fd);
        g_rpc.fd = -1;
    }
    free(g_rpc.rx_buf);
    g_rpc.rx_buf = NULL;
    g_rpc.rx_cap = 0;
    pthread_mutex_unlock(&g_rpc.mu);
    return ret;
}
