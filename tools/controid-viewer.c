#define _POSIX_C_SOURCE 200809L

#include <SDL2/SDL.h>
#include <errno.h>
#include <libavcodec/avcodec.h>
#include <libavutil/frame.h>
#include <libswscale/swscale.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "../assets/controid_icon_64.h"

enum {
    REMOTE_TOUCH = 0x01,
    REMOTE_SCROLL = 0x02,
    REMOTE_KEY = 0x03,
    REMOTE_TEXT = 0x04,
};

enum {
    ACTION_DOWN = 0,
    ACTION_UP = 1,
    ACTION_MOVE = 2,
};

enum {
    ANDROID_KEYCODE_HOME = 3,
    ANDROID_KEYCODE_BACK = 4,
    ANDROID_KEYCODE_POWER = 26,
    ANDROID_KEYCODE_APP_SWITCH = 187,
};

enum {
    ANDROID_META_SHIFT = 0x01u,
    ANDROID_META_ALT = 0x02u,
    ANDROID_META_CTRL = 0x1000u,
};

#define READ_BUF_SIZE 65536
#define MAX_INIT_WIN_HEIGHT 900

typedef struct {
    enum AVCodecID codec_id;
    int wake_read_fd;
    uint32_t wakeup_event;
    pthread_mutex_t mu;
    AVFrame *pending_frame;
    bool eof;
    bool decode_error;
} SharedDecodeState;

static pthread_mutex_t g_out_mu = PTHREAD_MUTEX_INITIALIZER;

static inline void put_u16_be(uint8_t *dst, uint16_t v) {
    dst[0] = (uint8_t)((v >> 8) & 0xFFu);
    dst[1] = (uint8_t)(v & 0xFFu);
}

static inline void put_u32_be(uint8_t *dst, uint32_t v) {
    dst[0] = (uint8_t)((v >> 24) & 0xFFu);
    dst[1] = (uint8_t)((v >> 16) & 0xFFu);
    dst[2] = (uint8_t)((v >> 8) & 0xFFu);
    dst[3] = (uint8_t)(v & 0xFFu);
}

static inline int16_t round_clamp_i16(float v) {
    float r = v >= 0.0f ? v + 0.5f : v - 0.5f;
    if (r >= 32767.0f) return 32767;
    if (r <= -32768.0f) return -32768;
    return (int16_t)r;
}

static void send_packet(const uint8_t *payload, uint16_t len) {
    uint8_t hdr[2];
    put_u16_be(hdr, len);

    pthread_mutex_lock(&g_out_mu);
    if (fwrite(hdr, 1, sizeof(hdr), stdout) == sizeof(hdr) &&
        fwrite(payload, 1, len, stdout) == len) {
        fflush(stdout);
    }
    pthread_mutex_unlock(&g_out_mu);
}

static void send_touch(uint8_t action, uint8_t pointer_id, uint8_t buttons, uint16_t x_norm, uint16_t y_norm) {
    uint8_t pkt[8];
    pkt[0] = REMOTE_TOUCH;
    pkt[1] = action;
    pkt[2] = pointer_id;
    pkt[3] = buttons;
    put_u16_be(pkt + 4, x_norm);
    put_u16_be(pkt + 6, y_norm);
    send_packet(pkt, sizeof(pkt));
}

static void send_scroll(uint16_t x_norm, uint16_t y_norm, int16_t h_scroll, int16_t v_scroll) {
    uint8_t pkt[9];
    pkt[0] = REMOTE_SCROLL;
    put_u16_be(pkt + 1, x_norm);
    put_u16_be(pkt + 3, y_norm);
    put_u16_be(pkt + 5, (uint16_t)h_scroll);
    put_u16_be(pkt + 7, (uint16_t)v_scroll);
    send_packet(pkt, sizeof(pkt));
}

static void send_key(uint8_t action, uint16_t keycode, uint32_t metastate, uint16_t repeat) {
    uint8_t pkt[10];
    pkt[0] = REMOTE_KEY;
    pkt[1] = action;
    put_u16_be(pkt + 2, keycode);
    put_u32_be(pkt + 4, metastate);
    put_u16_be(pkt + 8, repeat);
    send_packet(pkt, sizeof(pkt));
}

static void send_text(const char *utf8) {
    size_t slen = strlen(utf8);
    if (slen == 0 || slen > 1024) {
        return;
    }
    uint8_t pkt[1025];
    pkt[0] = REMOTE_TEXT;
    memcpy(pkt + 1, utf8, slen);
    send_packet(pkt, (uint16_t)(1 + slen));
}

static void push_wakeup(uint32_t wakeup_event) {
    SDL_Event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = wakeup_event;
    SDL_PushEvent(&ev);
}

static void publish_frame(SharedDecodeState *shared, AVFrame *dec_frame) {
    AVFrame *cloned = av_frame_clone(dec_frame);
    if (!cloned) {
        return;
    }
    pthread_mutex_lock(&shared->mu);
    AVFrame *old = shared->pending_frame;
    shared->pending_frame = cloned;
    pthread_mutex_unlock(&shared->mu);
    if (old) {
        av_frame_free(&old);
    }
    push_wakeup(shared->wakeup_event);
}

static void drain_decoder(AVCodecContext *ctx, AVFrame *dec_frame, SharedDecodeState *shared) {
    for (;;) {
        int ret = avcodec_receive_frame(ctx, dec_frame);
        if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) {
            break;
        }
        if (ret < 0) {
            break;
        }
        publish_frame(shared, dec_frame);
        av_frame_unref(dec_frame);
    }
}

static void send_and_drain(AVCodecContext *ctx, AVPacket *pkt, AVFrame *dec_frame, SharedDecodeState *shared) {
    int ret = avcodec_send_packet(ctx, pkt);
    if (ret == AVERROR(EAGAIN)) {
        drain_decoder(ctx, dec_frame, shared);
        ret = avcodec_send_packet(ctx, pkt);
    }
    if (ret >= 0) {
        drain_decoder(ctx, dec_frame, shared);
    }
}

static void *decode_thread_fn(void *arg) {
    SharedDecodeState *shared = (SharedDecodeState *)arg;
    const AVCodec *codec = avcodec_find_decoder(shared->codec_id);
    if (!codec) {
        fprintf(stderr, "controid-viewer: decoder not found\n");
        goto fail;
    }

    AVCodecParserContext *parser = av_parser_init((int)shared->codec_id);
    AVCodecContext *ctx = avcodec_alloc_context3(codec);
    AVPacket *pkt = av_packet_alloc();
    AVFrame *dec_frame = av_frame_alloc();
    uint8_t *inbuf = calloc(1, READ_BUF_SIZE + AV_INPUT_BUFFER_PADDING_SIZE);

    if (!parser || !ctx || !pkt || !dec_frame || !inbuf) {
        fprintf(stderr, "controid-viewer: failed to allocate decoder state\n");
        free(inbuf);
        av_frame_free(&dec_frame);
        av_packet_free(&pkt);
        avcodec_free_context(&ctx);
        if (parser) av_parser_close(parser);
        goto fail;
    }

    ctx->flags |= AV_CODEC_FLAG_LOW_DELAY;
    ctx->flags2 |= AV_CODEC_FLAG2_FAST;
    ctx->thread_count = 1;

    if (avcodec_open2(ctx, codec, NULL) < 0) {
        fprintf(stderr, "controid-viewer: failed to open codec\n");
        free(inbuf);
        av_frame_free(&dec_frame);
        av_packet_free(&pkt);
        avcodec_free_context(&ctx);
        av_parser_close(parser);
        goto fail;
    }

    struct pollfd pfds[2] = {
        { .fd = STDIN_FILENO, .events = POLLIN },
        { .fd = shared->wake_read_fd, .events = POLLIN },
    };

    for (;;) {
        int pr = poll(pfds, 2, -1);
        if (pr < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (pfds[1].revents & (POLLIN | POLLHUP | POLLERR)) {
            break;
        }
        if (!(pfds[0].revents & (POLLIN | POLLHUP | POLLERR))) {
            continue;
        }

        ssize_t n = read(STDIN_FILENO, inbuf, READ_BUF_SIZE);
        if (n < 0) {
            if (errno == EINTR || errno == EAGAIN) continue;
            break;
        }
        if (n == 0) {
            uint8_t *out_data = NULL;
            int out_size = 0;
            av_parser_parse2(parser, ctx, &out_data, &out_size, NULL, 0,
                             AV_NOPTS_VALUE, AV_NOPTS_VALUE, 0);
            if (out_size > 0) {
                pkt->data = out_data;
                pkt->size = out_size;
                send_and_drain(ctx, pkt, dec_frame, shared);
                pkt->data = NULL;
                pkt->size = 0;
            }
            send_and_drain(ctx, NULL, dec_frame, shared);
            break;
        }

        memset(inbuf + n, 0, AV_INPUT_BUFFER_PADDING_SIZE);
        const uint8_t *data = inbuf;
        int data_size = (int)n;

        while (data_size > 0) {
            uint8_t *out_data = NULL;
            int out_size = 0;
            int parsed = av_parser_parse2(
                parser, ctx, &out_data, &out_size,
                data, data_size, AV_NOPTS_VALUE, AV_NOPTS_VALUE, 0);
            if (parsed < 0) {
                break;
            }
            data += parsed;
            data_size -= parsed;

            if (out_size > 0) {
                pkt->data = out_data;
                pkt->size = out_size;
                send_and_drain(ctx, pkt, dec_frame, shared);
                pkt->data = NULL;
                pkt->size = 0;
            }
        }
    }

    free(inbuf);
    av_frame_free(&dec_frame);
    av_packet_free(&pkt);
    avcodec_free_context(&ctx);
    av_parser_close(parser);

    pthread_mutex_lock(&shared->mu);
    shared->eof = true;
    pthread_mutex_unlock(&shared->mu);
    push_wakeup(shared->wakeup_event);
    return NULL;

fail:
    pthread_mutex_lock(&shared->mu);
    shared->decode_error = true;
    shared->eof = true;
    pthread_mutex_unlock(&shared->mu);
    push_wakeup(shared->wakeup_event);
    return NULL;
}

static SDL_Rect compute_dst_rect(int out_w, int out_h, int video_w, int video_h) {
    SDL_Rect dst = { 0, 0, out_w, out_h };
    if (video_w <= 0 || video_h <= 0 || out_w <= 0 || out_h <= 0) {
        return dst;
    }
    int64_t lhs = (int64_t)out_w * (int64_t)video_h;
    int64_t rhs = (int64_t)video_w * (int64_t)out_h;
    if (lhs > rhs) {
        dst.h = out_h;
        dst.w = (int)(((int64_t)out_h * (int64_t)video_w) / video_h);
        if (dst.w < 1) dst.w = 1;
        dst.x = (out_w - dst.w) / 2;
        dst.y = 0;
    } else {
        dst.w = out_w;
        dst.h = (int)(((int64_t)out_w * (int64_t)video_h) / video_w);
        if (dst.h < 1) dst.h = 1;
        dst.x = 0;
        dst.y = (out_h - dst.h) / 2;
    }
    return dst;
}

static void resize_window_for_video(SDL_Window *win, int video_w, int video_h) {
    if (video_w <= 0 || video_h <= 0) return;

    int max_w = 1600;
    int max_h = MAX_INIT_WIN_HEIGHT;
    int disp = SDL_GetWindowDisplayIndex(win);
    SDL_Rect usable;
    if (disp >= 0 && SDL_GetDisplayUsableBounds(disp, &usable) == 0) {
        int bw = (usable.w * 85) / 100;
        int bh = (usable.h * 85) / 100;
        if (bw > 100) max_w = bw;
        if (bh > 100 && bh < max_h) max_h = bh;
    }

    int target_w = video_w;
    int target_h = video_h;
    if (target_h > max_h) {
        target_w = (int)(((int64_t)target_w * max_h) / target_h);
        target_h = max_h;
    }
    if (target_w > max_w) {
        target_h = (int)(((int64_t)target_h * max_w) / target_w);
        target_w = max_w;
    }
    if (target_w < 160) target_w = 160;
    if (target_h < 160) target_h = 160;
    SDL_SetWindowSize(win, target_w, target_h);
}

static void render_frame(SDL_Renderer *ren, SDL_Texture *tex, int video_w, int video_h) {
    if (!tex) {
        SDL_SetRenderDrawColor(ren, 0, 0, 0, 255);
        SDL_RenderClear(ren);
        SDL_RenderPresent(ren);
        return;
    }
    int out_w = 0, out_h = 0;
    SDL_GetRendererOutputSize(ren, &out_w, &out_h);
    SDL_Rect dst = compute_dst_rect(out_w, out_h, video_w, video_h);

    SDL_SetRenderDrawColor(ren, 0, 0, 0, 255);
    SDL_RenderClear(ren);
    SDL_RenderCopy(ren, tex, NULL, &dst);
    SDL_RenderPresent(ren);
}

static bool map_mouse_to_norm(
    SDL_Window *win,
    SDL_Renderer *ren,
    int video_w,
    int video_h,
    int mx,
    int my,
    uint16_t *out_x,
    uint16_t *out_y
) {
    if (video_w <= 0 || video_h <= 0) return false;

    int win_w = 0, win_h = 0, out_w = 0, out_h = 0;
    SDL_GetWindowSize(win, &win_w, &win_h);
    SDL_GetRendererOutputSize(ren, &out_w, &out_h);
    if (win_w <= 0 || win_h <= 0 || out_w <= 0 || out_h <= 0) return false;

    SDL_Rect dst = compute_dst_rect(out_w, out_h, video_w, video_h);
    if (dst.w <= 0 || dst.h <= 0) return false;

    double rx = (double)mx * ((double)out_w / (double)win_w);
    double ry = (double)my * ((double)out_h / (double)win_h);
    double nx = (rx - (double)dst.x) / (double)dst.w;
    double ny = (ry - (double)dst.y) / (double)dst.h;

    if (nx < 0.0) nx = 0.0;
    if (nx > 1.0) nx = 1.0;
    if (ny < 0.0) ny = 0.0;
    if (ny > 1.0) ny = 1.0;

    *out_x = (uint16_t)(nx * 65535.0 + 0.5);
    *out_y = (uint16_t)(ny * 65535.0 + 0.5);
    return true;
}

static uint32_t sdl_mod_to_android(Uint16 mod) {
    uint32_t meta = 0;
    if (mod & KMOD_SHIFT) meta |= ANDROID_META_SHIFT;
    if (mod & KMOD_ALT) meta |= ANDROID_META_ALT;
    if (mod & KMOD_CTRL) meta |= ANDROID_META_CTRL;
    return meta;
}

static uint16_t map_special_scancode(SDL_Scancode sc) {
    switch (sc) {
    case SDL_SCANCODE_RETURN:
    case SDL_SCANCODE_KP_ENTER:
        return 66;
    case SDL_SCANCODE_BACKSPACE:
        return 67;
    case SDL_SCANCODE_ESCAPE:
        return 111;
    case SDL_SCANCODE_TAB:
        return 61;
    case SDL_SCANCODE_DELETE:
        return 112;
    case SDL_SCANCODE_HOME:
        return 122;
    case SDL_SCANCODE_END:
        return 123;
    case SDL_SCANCODE_PAGEUP:
        return 92;
    case SDL_SCANCODE_PAGEDOWN:
        return 93;
    case SDL_SCANCODE_UP:
        return 19;
    case SDL_SCANCODE_DOWN:
        return 20;
    case SDL_SCANCODE_LEFT:
        return 21;
    case SDL_SCANCODE_RIGHT:
        return 22;
    default:
        return 0;
    }
}

static uint16_t map_printable_key(SDL_Scancode sc, SDL_Keycode sym) {
    if (sym >= SDLK_a && sym <= SDLK_z) {
        return (uint16_t)(29 + (sym - SDLK_a));
    }
    if (sym >= SDLK_0 && sym <= SDLK_9) {
        return (uint16_t)(7 + (sym - SDLK_0));
    }
    if (sym == SDLK_SPACE || sc == SDL_SCANCODE_SPACE) {
        return 62;
    }
    if (sc >= SDL_SCANCODE_A && sc <= SDL_SCANCODE_Z) {
        return (uint16_t)(29 + (sc - SDL_SCANCODE_A));
    }
    if (sc == SDL_SCANCODE_0) {
        return 7;
    }
    if (sc >= SDL_SCANCODE_1 && sc <= SDL_SCANCODE_9) {
        return (uint16_t)(8 + (sc - SDL_SCANCODE_1));
    }
    return 0;
}

static uint16_t map_alt_shortcut(SDL_Scancode sc, SDL_Keycode sym) {
    if (sym == SDLK_h || sc == SDL_SCANCODE_H) return ANDROID_KEYCODE_HOME;
    if (sym == SDLK_b || sc == SDL_SCANCODE_B) return ANDROID_KEYCODE_BACK;
    if (sym == SDLK_s || sc == SDL_SCANCODE_S) return ANDROID_KEYCODE_APP_SWITCH;
    if (sym == SDLK_p || sc == SDL_SCANCODE_P) return ANDROID_KEYCODE_POWER;
    return 0;
}

static void print_usage(const char *prog) {
    printf("Usage: %s [--codec=h264|h265] [--title=<title>] [-h|--help]\n"
           "Reads raw Annex-B video from stdin and writes binary RemoteInputEvent packets to stdout.\n"
           "Shortcuts: Alt+H (Home), Alt+B (Back), Alt+S (Recents), Alt+P (Power)\n",
           prog);
}

int main(int argc, char **argv) {
    enum AVCodecID codec_id = AV_CODEC_ID_H264;
    const char *title = "Controid Screen";

    for (int i = 1; i < argc; ++i) {
        const char *arg = argv[i];
        if (strcmp(arg, "-h") == 0 || strcmp(arg, "--help") == 0) {
            print_usage(argv[0]);
            return 0;
        } else if (strncmp(arg, "--codec=", 8) == 0) {
            const char *val = arg + 8;
            if (strcmp(val, "h264") == 0) {
                codec_id = AV_CODEC_ID_H264;
            } else if (strcmp(val, "h265") == 0 || strcmp(val, "hevc") == 0) {
                codec_id = AV_CODEC_ID_HEVC;
            } else {
                fprintf(stderr, "controid-viewer: unsupported codec '%s'\n", val);
                return 1;
            }
        } else if (strncmp(arg, "--title=", 8) == 0) {
            title = arg + 8;
        } else {
            fprintf(stderr, "controid-viewer: unknown option '%s'\n", arg);
            return 1;
        }
    }

    signal(SIGPIPE, SIG_IGN);

    int wake_pipe[2] = { -1, -1 };
    if (pipe(wake_pipe) != 0) {
        perror("pipe");
        return 1;
    }

    SDL_SetHint(SDL_HINT_VIDEODRIVER, "wayland,x11");
    SDL_SetHint(SDL_HINT_RENDER_SCALE_QUALITY, "linear");
    SDL_SetHint("SDL_APP_ID", "controid-viewer");
    SDL_SetHint("SDL_APP_NAME", "Controid Screen");

    if (SDL_Init(SDL_INIT_VIDEO) != 0) {
        fprintf(stderr, "controid-viewer: SDL_Init failed: %s\n", SDL_GetError());
        close(wake_pipe[0]);
        close(wake_pipe[1]);
        return 1;
    }

    uint32_t wakeup_event = SDL_RegisterEvents(1);
    if (wakeup_event == (uint32_t)-1) {
        fprintf(stderr, "controid-viewer: SDL_RegisterEvents failed\n");
        SDL_Quit();
        close(wake_pipe[0]);
        close(wake_pipe[1]);
        return 1;
    }

    SDL_Window *win = SDL_CreateWindow(
        title,
        SDL_WINDOWPOS_CENTERED,
        SDL_WINDOWPOS_CENTERED,
        480,
        854,
        SDL_WINDOW_RESIZABLE | SDL_WINDOW_ALLOW_HIGHDPI);
    if (!win) {
        fprintf(stderr, "controid-viewer: SDL_CreateWindow failed: %s\n", SDL_GetError());
        SDL_Quit();
        close(wake_pipe[0]);
        close(wake_pipe[1]);
        return 1;
    }

    SDL_Surface *icon_surf = SDL_CreateRGBSurfaceWithFormatFrom(
        (void *)CONTROID_ICON_64_RGBA,
        64,
        64,
        32,
        64 * 4,
        SDL_PIXELFORMAT_RGBA32);
    if (icon_surf) {
        SDL_SetWindowIcon(win, icon_surf);
        SDL_FreeSurface(icon_surf);
    }

    SDL_Renderer *ren = SDL_CreateRenderer(
        win, -1, SDL_RENDERER_ACCELERATED | SDL_RENDERER_PRESENTVSYNC);
    if (!ren) {
        ren = SDL_CreateRenderer(win, -1, SDL_RENDERER_SOFTWARE);
    }
    if (!ren) {
        fprintf(stderr, "controid-viewer: SDL_CreateRenderer failed: %s\n", SDL_GetError());
        SDL_DestroyWindow(win);
        SDL_Quit();
        close(wake_pipe[0]);
        close(wake_pipe[1]);
        return 1;
    }

    SDL_StartTextInput();
    render_frame(ren, NULL, 0, 0);

    SharedDecodeState shared = {
        .codec_id = codec_id,
        .wake_read_fd = wake_pipe[0],
        .wakeup_event = wakeup_event,
        .mu = PTHREAD_MUTEX_INITIALIZER,
        .pending_frame = NULL,
        .eof = false,
        .decode_error = false,
    };

    pthread_t dec_thread;
    if (pthread_create(&dec_thread, NULL, decode_thread_fn, &shared) != 0) {
        fprintf(stderr, "controid-viewer: pthread_create failed\n");
        SDL_DestroyRenderer(ren);
        SDL_DestroyWindow(win);
        SDL_Quit();
        close(wake_pipe[0]);
        close(wake_pipe[1]);
        return 1;
    }

    SDL_Texture *tex = NULL;
    struct SwsContext *sws_ctx = NULL;
    AVFrame *sws_frame = NULL;
    int video_w = 0;
    int video_h = 0;
    bool left_down = false;
    uint16_t down_keycodes[SDL_NUM_SCANCODES];
    bool down_shortcut[SDL_NUM_SCANCODES];
    memset(down_keycodes, 0, sizeof(down_keycodes));
    memset(down_shortcut, 0, sizeof(down_shortcut));

    bool running = true;
    SDL_Event ev;
    while (running && SDL_WaitEvent(&ev)) {
        if (ev.type == SDL_QUIT) {
            running = false;
        } else if (ev.type == wakeup_event) {
            pthread_mutex_lock(&shared.mu);
            AVFrame *frame = shared.pending_frame;
            shared.pending_frame = NULL;
            bool eof = shared.eof;
            pthread_mutex_unlock(&shared.mu);

            if (frame) {
                if (frame->width > 0 && frame->height > 0) {
                    if (frame->width != video_w || frame->height != video_h) {
                        if (tex) SDL_DestroyTexture(tex);
                        tex = SDL_CreateTexture(
                            ren,
                            SDL_PIXELFORMAT_IYUV,
                            SDL_TEXTUREACCESS_STREAMING,
                            frame->width,
                            frame->height);
                        video_w = frame->width;
                        video_h = frame->height;
                        resize_window_for_video(win, video_w, video_h);

                        av_frame_free(&sws_frame);
                    }
                    if (tex) {
                        if (frame->format == AV_PIX_FMT_YUV420P &&
                            frame->linesize[0] > 0 && frame->linesize[1] > 0 && frame->linesize[2] > 0) {
                            SDL_UpdateYUVTexture(
                                tex, NULL,
                                frame->data[0], frame->linesize[0],
                                frame->data[1], frame->linesize[1],
                                frame->data[2], frame->linesize[2]);
                        } else {
                            sws_ctx = sws_getCachedContext(
                                sws_ctx,
                                frame->width, frame->height, (enum AVPixelFormat)frame->format,
                                frame->width, frame->height, AV_PIX_FMT_YUV420P,
                                SWS_BILINEAR, NULL, NULL, NULL);
                            if (sws_ctx) {
                                if (!sws_frame) {
                                    sws_frame = av_frame_alloc();
                                    if (sws_frame) {
                                        sws_frame->format = AV_PIX_FMT_YUV420P;
                                        sws_frame->width = frame->width;
                                        sws_frame->height = frame->height;
                                        if (av_frame_get_buffer(sws_frame, 32) < 0) {
                                            av_frame_free(&sws_frame);
                                        }
                                    }
                                }
                                if (sws_frame) {
                                    sws_scale(
                                        sws_ctx,
                                        (const uint8_t * const *)frame->data,
                                        frame->linesize,
                                        0,
                                        frame->height,
                                        sws_frame->data,
                                        sws_frame->linesize);
                                    SDL_UpdateYUVTexture(
                                        tex, NULL,
                                        sws_frame->data[0], sws_frame->linesize[0],
                                        sws_frame->data[1], sws_frame->linesize[1],
                                        sws_frame->data[2], sws_frame->linesize[2]);
                                }
                            }
                        }
                        render_frame(ren, tex, video_w, video_h);
                    }
                }
                av_frame_free(&frame);
            }

            if (eof) {
                running = false;
            }
        } else if (ev.type == SDL_WINDOWEVENT) {
            if (ev.window.event == SDL_WINDOWEVENT_EXPOSED ||
                ev.window.event == SDL_WINDOWEVENT_RESIZED ||
                ev.window.event == SDL_WINDOWEVENT_SIZE_CHANGED) {
                render_frame(ren, tex, video_w, video_h);
            }
        } else if (ev.type == SDL_MOUSEBUTTONDOWN) {
            if (ev.button.button == SDL_BUTTON_LEFT) {
                uint16_t nx = 0, ny = 0;
                if (map_mouse_to_norm(win, ren, video_w, video_h, ev.button.x, ev.button.y, &nx, &ny)) {
                    left_down = true;
                    send_touch(ACTION_DOWN, 0, 1, nx, ny);
                }
            } else if (ev.button.button == SDL_BUTTON_RIGHT) {
                send_key(ACTION_DOWN, ANDROID_KEYCODE_BACK, 0, 0);
            } else if (ev.button.button == SDL_BUTTON_MIDDLE) {
                send_key(ACTION_DOWN, ANDROID_KEYCODE_HOME, 0, 0);
            }
        } else if (ev.type == SDL_MOUSEBUTTONUP) {
            if (ev.button.button == SDL_BUTTON_LEFT) {
                if (left_down) {
                    left_down = false;
                    uint16_t nx = 0, ny = 0;
                    if (map_mouse_to_norm(win, ren, video_w, video_h, ev.button.x, ev.button.y, &nx, &ny)) {
                        send_touch(ACTION_UP, 0, 0, nx, ny);
                    }
                }
            } else if (ev.button.button == SDL_BUTTON_RIGHT) {
                send_key(ACTION_UP, ANDROID_KEYCODE_BACK, 0, 0);
            } else if (ev.button.button == SDL_BUTTON_MIDDLE) {
                send_key(ACTION_UP, ANDROID_KEYCODE_HOME, 0, 0);
            }
        } else if (ev.type == SDL_MOUSEMOTION) {
            if (left_down && (ev.motion.state & SDL_BUTTON_LMASK)) {
                uint16_t nx = 0, ny = 0;
                if (map_mouse_to_norm(win, ren, video_w, video_h, ev.motion.x, ev.motion.y, &nx, &ny)) {
                    send_touch(ACTION_MOVE, 0, 1, nx, ny);
                }
            }
        } else if (ev.type == SDL_MOUSEWHEEL) {
            uint16_t nx = 0, ny = 0;
            if (map_mouse_to_norm(win, ren, video_w, video_h, ev.wheel.mouseX, ev.wheel.mouseY, &nx, &ny)) {
                int16_t h = round_clamp_i16(ev.wheel.preciseX * 100.0f);
                int16_t v = round_clamp_i16(ev.wheel.preciseY * 100.0f);
                if (h != 0 || v != 0) {
                    send_scroll(nx, ny, h, v);
                }
            }
        } else if (ev.type == SDL_KEYDOWN) {
            SDL_Scancode sc = ev.key.keysym.scancode;
            SDL_Keycode sym = ev.key.keysym.sym;
            Uint16 mod = ev.key.keysym.mod;
            bool has_alt = (mod & KMOD_ALT) != 0;
            bool has_ctrl = (mod & KMOD_CTRL) != 0;

            if (has_alt && !has_ctrl) {
                uint16_t shortcut = map_alt_shortcut(sc, sym);
                if (shortcut != 0) {
                    if (!ev.key.repeat) {
                        if ((size_t)sc < SDL_NUM_SCANCODES) {
                            down_keycodes[sc] = shortcut;
                            down_shortcut[sc] = true;
                        }
                        send_key(ACTION_DOWN, shortcut, 0, 0);
                    }
                    continue;
                }
            }

            uint16_t keycode = map_special_scancode(sc);
            bool is_special = (keycode != 0);
            if (!is_special && (has_ctrl || has_alt)) {
                keycode = map_printable_key(sc, sym);
            }

            if (keycode != 0) {
                if ((size_t)sc < SDL_NUM_SCANCODES) {
                    down_keycodes[sc] = keycode;
                    down_shortcut[sc] = false;
                }
                send_key(
                    ACTION_DOWN,
                    keycode,
                    sdl_mod_to_android(mod),
                    ev.key.repeat ? 1 : 0);
            }
        } else if (ev.type == SDL_KEYUP) {
            SDL_Scancode sc = ev.key.keysym.scancode;
            if ((size_t)sc < SDL_NUM_SCANCODES && down_keycodes[sc] != 0) {
                uint16_t keycode = down_keycodes[sc];
                uint32_t meta = down_shortcut[sc] ? 0 : sdl_mod_to_android(ev.key.keysym.mod);
                down_keycodes[sc] = 0;
                down_shortcut[sc] = false;
                send_key(ACTION_UP, keycode, meta, 0);
            }
        } else if (ev.type == SDL_TEXTINPUT) {
            if ((SDL_GetModState() & (KMOD_CTRL | KMOD_ALT)) == 0) {
                send_text(ev.text.text);
            }
        }
    }

    uint8_t stop_byte = 1;
    if (write(wake_pipe[1], &stop_byte, 1) < 0) {
        /* ignore */
    }
    close(wake_pipe[1]);
    pthread_join(dec_thread, NULL);
    close(wake_pipe[0]);

    pthread_mutex_lock(&shared.mu);
    av_frame_free(&shared.pending_frame);
    pthread_mutex_unlock(&shared.mu);
    pthread_mutex_destroy(&shared.mu);

    av_frame_free(&sws_frame);
    if (sws_ctx) sws_freeContext(sws_ctx);
    if (tex) SDL_DestroyTexture(tex);
    SDL_StopTextInput();
    SDL_DestroyRenderer(ren);
    SDL_DestroyWindow(win);
    SDL_Quit();

    return shared.decode_error ? 1 : 0;
}
