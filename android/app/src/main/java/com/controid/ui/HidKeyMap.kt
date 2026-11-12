package com.controid.ui

internal data class KeyMapping(
    val hidUsage: Int,
    val linuxKeycode: Int,
    val shift: Boolean = false
)

internal object HidKeyMap {
    const val MOD_CTRL_HID = 0xE0
    const val MOD_SHIFT_HID = 0xE1
    const val MOD_ALT_HID = 0xE2
    const val MOD_SUPER_HID = 0xE3

    const val MOD_CTRL_LINUX = 29
    const val MOD_SHIFT_LINUX = 42
    const val MOD_ALT_LINUX = 56
    const val MOD_SUPER_LINUX = 125

    const val KEY_ESC = 1
    const val KEY_BACKSPACE = 14
    const val KEY_TAB = 15
    const val KEY_ENTER = 28
    const val KEY_UP = 103
    const val KEY_LEFT = 105
    const val KEY_RIGHT = 106
    const val KEY_DOWN = 108

    const val HID_ESC = 0x29
    const val HID_BACKSPACE = 0x2A
    const val HID_TAB = 0x2B
    const val HID_ENTER = 0x28
    const val HID_RIGHT = 0x4F
    const val HID_LEFT = 0x50
    const val HID_DOWN = 0x51
    const val HID_UP = 0x52

    const val CONSUMER_NEXT = 0x00B5
    const val CONSUMER_PREV = 0x00B6
    const val CONSUMER_PLAY_PAUSE = 0x00CD
    const val CONSUMER_MUTE = 0x00E2
    const val CONSUMER_VOL_UP = 0x00E9
    const val CONSUMER_VOL_DOWN = 0x00EA

    private val linuxLetterCodes = intArrayOf(
        30, 48, 46, 32, 18, 33, 34, 35, 23, 36, 37, 38, 50,
        49, 24, 25, 16, 19, 31, 20, 22, 47, 17, 45, 21, 44
    )

    private val linuxDigitCodes = intArrayOf(
        11, 2, 3, 4, 5, 6, 7, 8, 9, 10
    )

    private val shiftedDigits = charArrayOf(
        ')', '!', '@', '#', '$', '%', '^', '&', '*', '('
    )

    fun mapChar(ch: Char): KeyMapping? {
        if (ch in 'a'..'z') {
            val idx = ch - 'a'
            return KeyMapping(0x04 + idx, linuxLetterCodes[idx], shift = false)
        }
        if (ch in 'A'..'Z') {
            val idx = ch - 'A'
            return KeyMapping(0x04 + idx, linuxLetterCodes[idx], shift = true)
        }
        if (ch in '1'..'9') {
            val idx = ch - '0'
            return KeyMapping(0x1E + (idx - 1), linuxDigitCodes[idx], shift = false)
        }
        if (ch == '0') {
            return KeyMapping(0x27, linuxDigitCodes[0], shift = false)
        }
        val shiftedDigitIdx = shiftedDigits.indexOf(ch)
        if (shiftedDigitIdx >= 0) {
            val hid = if (shiftedDigitIdx == 0) 0x27 else 0x1E + (shiftedDigitIdx - 1)
            return KeyMapping(hid, linuxDigitCodes[shiftedDigitIdx], shift = true)
        }

        return when (ch) {
            '\n' -> KeyMapping(HID_ENTER, KEY_ENTER)
            '\t' -> KeyMapping(HID_TAB, KEY_TAB)
            ' ' -> KeyMapping(0x2C, 57)
            '-' -> KeyMapping(0x2D, 12)
            '_' -> KeyMapping(0x2D, 12, shift = true)
            '=' -> KeyMapping(0x2E, 13)
            '+' -> KeyMapping(0x2E, 13, shift = true)
            '[' -> KeyMapping(0x2F, 26)
            '{' -> KeyMapping(0x2F, 26, shift = true)
            ']' -> KeyMapping(0x30, 27)
            '}' -> KeyMapping(0x30, 27, shift = true)
            '\\' -> KeyMapping(0x31, 43)
            '|' -> KeyMapping(0x31, 43, shift = true)
            ';' -> KeyMapping(0x33, 39)
            ':' -> KeyMapping(0x33, 39, shift = true)
            '\'' -> KeyMapping(0x34, 40)
            '"' -> KeyMapping(0x34, 40, shift = true)
            '`' -> KeyMapping(0x35, 41)
            '~' -> KeyMapping(0x35, 41, shift = true)
            ',' -> KeyMapping(0x36, 51)
            '<' -> KeyMapping(0x36, 51, shift = true)
            '.' -> KeyMapping(0x37, 52)
            '>' -> KeyMapping(0x37, 52, shift = true)
            '/' -> KeyMapping(0x38, 53)
            '?' -> KeyMapping(0x38, 53, shift = true)
            else -> null
        }
    }
}
