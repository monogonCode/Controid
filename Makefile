SHELL := /bin/bash
CC ?= gcc
CARGO := cargo --manifest-path daemon/Cargo.toml
TOOLS_OUT_DIR := daemon/target/tools

.PHONY: all check test clippy fmt build-daemon build-tools build-android install-icons install-gui install-systemd install-autostart clean

all: check test

check:
	$(CARGO) check --workspace --all-targets

test:
	$(CARGO) test --workspace

clippy:
	$(CARGO) clippy --workspace --all-targets -- -D warnings

fmt:
	$(CARGO) fmt --all

build-daemon:
	$(CARGO) build --release --workspace

build-tools:
	mkdir -p $(TOOLS_OUT_DIR)
	$(CC) -O2 -Wall -Wextra tools/controid-viewer.c -o $(TOOLS_OUT_DIR)/controid-viewer $$(pkg-config --cflags --libs sdl2 libavcodec libswscale libavutil) -lpthread -lm
	$(CC) -O2 -Wall -Wextra -D_FILE_OFFSET_BITS=64 tools/controid-mount.c -o $(TOOLS_OUT_DIR)/controid-mount $$(pkg-config --cflags --libs fuse3) -lpthread

build-android:
	cd android && ./gradlew assembleDebug

install-icons:
	@for size in 32x32 48x48 64x64 128x128 256x256 512x512; do \
		install -Dm644 assets/icons/$$size/controid.png $(HOME)/.local/share/icons/hicolor/$$size/apps/controid.png; \
	done
	@gtk-update-icon-cache -f -t $(HOME)/.local/share/icons/hicolor >/dev/null 2>&1 || true

install-gui: install-icons
	install -Dm755 gui/controid_gui.py $(HOME)/.local/bin/controid-gui
	install -Dm644 systemd/controid.desktop $(HOME)/.local/share/applications/controid.desktop
	sed -i "s|^Exec=controid-gui|Exec=$(HOME)/.local/bin/controid-gui|" $(HOME)/.local/share/applications/controid.desktop

install-systemd:
	install -Dm644 systemd/controid.service $(HOME)/.config/systemd/user/controid.service
	@if command -v systemctl >/dev/null 2>&1 && systemctl --user show-environment >/dev/null 2>&1; then \
		systemctl --user daemon-reload; \
	else \
		echo "systemd --user no disponible; usa 'make install-autostart' para arranque XDG."; \
	fi

install-autostart:
	install -Dm644 systemd/controidd-autostart.desktop $(HOME)/.config/autostart/controidd.desktop
	sed -i "s|^Exec=controidd|Exec=$(HOME)/.local/bin/controidd|" $(HOME)/.config/autostart/controidd.desktop

clean:
	$(CARGO) clean
	rm -rf $(TOOLS_OUT_DIR) android/build android/app/build
