package com.controid.dock

enum class DockCategoryFilter(val label: String) {
    ALL("Todo"),
    PINNED("Fijadas"),
    RUNNING("Abiertas"),
    FREQUENT("Frecuentes"),
    INTERNET("Internet"),
    DEV("Código"),
    MEDIA("Media"),
    SYSTEM("Sistema");

    fun matches(app: DockAppItem, pinnedSet: Set<String>, frequentSet: Set<String>): Boolean = when (this) {
        ALL -> true
        PINNED -> app.id in pinnedSet
        RUNNING -> app.runningCount > 0
        FREQUENT -> app.id in frequentSet || app.launchCount > 0
        INTERNET -> app.categories.any {
            it.equals("Network", true) ||
                it.equals("WebBrowser", true) ||
                it.equals("InstantMessaging", true) ||
                it.equals("Email", true)
        }
        DEV -> app.categories.any {
            it.equals("Development", true) ||
                it.equals("IDE", true) ||
                it.equals("TerminalEmulator", true) ||
                it.equals("TextEditor", true)
        }
        MEDIA -> app.categories.any {
            it.equals("AudioVideo", true) ||
                it.equals("Audio", true) ||
                it.equals("Video", true) ||
                it.equals("Graphics", true) ||
                it.equals("Game", true)
        }
        SYSTEM -> app.categories.any {
            it.equals("System", true) ||
                it.equals("Settings", true) ||
                it.equals("Utility", true) ||
                it.equals("FileManager", true)
        }
    }
}

data class DockAppItem(
    val id: String,
    val name: String,
    val genericName: String? = null,
    val categories: List<String> = emptyList(),
    val iconHash: String? = null,
    val hasIcon: Boolean = false,
    val runningCount: Int = 0,
    val focused: Boolean = false,
    val terminal: Boolean = false,
    val launchCount: Int = 0,
    val lastLaunched: Long = 0L
)

data class DockWorkspace(
    val id: String,
    val name: String,
    val active: Boolean,
    val windows: Int
)

data class DockMediaState(
    val title: String,
    val artist: String,
    val playing: Boolean,
    val volume: Int? = null,
    val muted: Boolean? = null
)

typealias DockMediaStatus = DockMediaState

data class DockSnapshotState(
    val desktopEnv: String = "",
    val sessionType: String = "",
    val supportsWindowFocus: Boolean = false,
    val supportsWorkspaces: Boolean = false,
    val activeWorkspace: String? = null,
    val workspaces: List<DockWorkspace> = emptyList(),
    val pinnedIds: List<String> = emptyList(),
    val frequentIds: List<String> = emptyList(),
    val apps: List<DockAppItem> = emptyList(),
    val media: DockMediaState? = null,
    val loading: Boolean = false,
    val syncError: String? = null,
    val syncedAtMs: Long = 0L
)

data class DockLaunchFeedback(
    val desktopId: String,
    val ok: Boolean,
    val action: String,
    val error: String? = null,
    val timestampMs: Long = System.currentTimeMillis()
)
