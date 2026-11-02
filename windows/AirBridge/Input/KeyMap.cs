namespace AirBridge.Input
{
    /// <summary>
    /// PS/2 scan code set 1 (+E0 extended flag) ↔ USB HID usage page 0x07, plus the few VK-based special cases
    /// that low-level hooks report inconsistently (Pause/NumLock share 0x45, PrintScreen/SysRq, L/R modifiers).
    /// </summary>
    public static class KeyMap
    {
        public const int VK_BACK = 0x08, VK_TAB = 0x09, VK_RETURN = 0x0D, VK_SHIFT = 0x10, VK_CONTROL = 0x11, VK_MENU = 0x12,
            VK_PAUSE = 0x13, VK_CAPITAL = 0x14, VK_ESCAPE = 0x1B, VK_SPACE = 0x20, VK_LEFT = 0x25, VK_UP = 0x26,
            VK_RIGHT = 0x27, VK_DOWN = 0x28, VK_SNAPSHOT = 0x2C, VK_CANCEL = 0x03, VK_NUMLOCK = 0x90, VK_SCROLL = 0x91,
            VK_LWIN = 0x5B, VK_RWIN = 0x5C, VK_APPS = 0x5D, VK_LSHIFT = 0xA0, VK_RSHIFT = 0xA1, VK_LCONTROL = 0xA2,
            VK_RCONTROL = 0xA3, VK_LMENU = 0xA4, VK_RMENU = 0xA5, VK_BROWSER_BACK = 0xA6, VK_VOLUME_MUTE = 0xAD,
            VK_VOLUME_DOWN = 0xAE, VK_VOLUME_UP = 0xAF, VK_MEDIA_NEXT_TRACK = 0xB0, VK_MEDIA_PREV_TRACK = 0xB1,
            VK_MEDIA_STOP = 0xB2, VK_MEDIA_PLAY_PAUSE = 0xB3,
            VK_C = 0x43, VK_V = 0x56, VK_S = 0x53, VK_D = 0x44, VK_N = 0x4E, VK_A = 0x41;

        public const int HID_LCTRL = 0xE0, HID_LSHIFT = 0xE1, HID_LALT = 0xE2, HID_LGUI = 0xE3,
            HID_RCTRL = 0xE4, HID_RSHIFT = 0xE5, HID_RALT = 0xE6, HID_RGUI = 0xE7;

        // index = scan code (0..0x7F); value = HID usage (0 = none)
        private static readonly byte[] Plain = new byte[256];
        private static readonly byte[] Extended = new byte[256];
        // index = HID usage; value = scan | 0x100 if extended (0 = none)
        private static readonly ushort[] HidToScan = new ushort[256];

        static KeyMap()
        {
            void P(int sc, int hid) => Plain[sc] = (byte)hid;
            void E(int sc, int hid) => Extended[sc] = (byte)hid;

            P(0x01, 0x29); // Esc
            for (int i = 0; i < 10; i++) P(0x02 + i, 0x1E + i); // 1..9, 0
            P(0x0C, 0x2D); P(0x0D, 0x2E); P(0x0E, 0x2A); P(0x0F, 0x2B);
            int[] qwerty = { 0x14, 0x1A, 0x08, 0x15, 0x17, 0x1C, 0x18, 0x0C, 0x12, 0x13 }; // Q..P
            for (int i = 0; i < qwerty.Length; i++) P(0x10 + i, qwerty[i]);
            P(0x1A, 0x2F); P(0x1B, 0x30); P(0x1C, 0x28); P(0x1D, HID_LCTRL);
            int[] asdf = { 0x04, 0x16, 0x07, 0x09, 0x0A, 0x0B, 0x0D, 0x0E, 0x0F }; // A..L
            for (int i = 0; i < asdf.Length; i++) P(0x1E + i, asdf[i]);
            P(0x27, 0x33); P(0x28, 0x34); P(0x29, 0x35); P(0x2A, HID_LSHIFT); P(0x2B, 0x31);
            int[] zxcv = { 0x1D, 0x1B, 0x06, 0x19, 0x05, 0x11, 0x10 }; // Z..M
            for (int i = 0; i < zxcv.Length; i++) P(0x2C + i, zxcv[i]);
            P(0x33, 0x36); P(0x34, 0x37); P(0x35, 0x38); P(0x36, HID_RSHIFT); P(0x37, 0x55);
            P(0x38, HID_LALT); P(0x39, 0x2C); P(0x3A, 0x39);
            for (int i = 0; i < 10; i++) P(0x3B + i, 0x3A + i); // F1..F10
            P(0x45, 0x53); P(0x46, 0x47);
            P(0x47, 0x5F); P(0x48, 0x60); P(0x49, 0x61); P(0x4A, 0x56);
            P(0x4B, 0x5C); P(0x4C, 0x5D); P(0x4D, 0x5E); P(0x4E, 0x57);
            P(0x4F, 0x59); P(0x50, 0x5A); P(0x51, 0x5B); P(0x52, 0x62); P(0x53, 0x63);
            P(0x54, 0x46); // SysRq (Alt+PrintScreen)
            P(0x56, 0x64); // ISO \|
            P(0x57, 0x44); P(0x58, 0x45); // F11, F12
            P(0x59, 0x67); // Keypad =
            for (int i = 0; i < 11; i++) P(0x64 + i, 0x68 + i); // F13..F23
            P(0x76, 0x73); // F24
            P(0x70, 0x88); // Katakana/Hiragana
            P(0x73, 0x87); // Ro (International1)
            P(0x79, 0x8A); // Henkan
            P(0x7B, 0x8B); // Muhenkan
            P(0x7D, 0x89); // Yen (International3)
            P(0x7E, 0x85); // Keypad , (Brazil)
            P(0x72, 0x90); P(0xF2, 0x90); // Lang1 (Hangul)
            P(0x71, 0x91); P(0xF1, 0x91); // Lang2 (Hanja)

            E(0x1C, 0x58); // Keypad Enter
            E(0x1D, HID_RCTRL);
            E(0x35, 0x54); // Keypad /
            E(0x36, HID_RSHIFT); // hooks may flag RShift as extended
            E(0x37, 0x46); // PrintScreen
            E(0x38, HID_RALT);
            E(0x45, 0x53); // NumLock as reported by LL hooks
            E(0x46, 0x48); // Ctrl+Break
            E(0x47, 0x4A); E(0x48, 0x52); E(0x49, 0x4B);
            E(0x4B, 0x50); E(0x4D, 0x4F);
            E(0x4F, 0x4D); E(0x50, 0x51); E(0x51, 0x4E);
            E(0x52, 0x49); E(0x53, 0x4C);
            E(0x5B, HID_LGUI); E(0x5C, HID_RGUI); E(0x5D, 0x65); E(0x5E, 0x66);

            // Inverse: first plain, then extended, so extended overrides only where intended.
            for (int sc = 0; sc < 256; sc++) if (Plain[sc] != 0 && HidToScan[Plain[sc]] == 0) HidToScan[Plain[sc]] = (ushort)sc;
            for (int sc = 0; sc < 256; sc++) if (Extended[sc] != 0 && HidToScan[Extended[sc]] == 0) HidToScan[Extended[sc]] = (ushort)(sc | 0x100);
            HidToScan[HID_RSHIFT] = 0x36;
            HidToScan[0x46] = 0x137; // PrintScreen = E0 37
            HidToScan[0x32] = 0x2B;  // Non-US # shares the \ position
            HidToScan[0x90] = 0x72; HidToScan[0x91] = 0x71;
        }

        /// <summary>True for the fake shifts some keyboards emit around gray navigation keys (E0 2A / E0 AA).</summary>
        public static bool IsFakeShift(uint scan, bool extended) => extended && (scan & 0xFF) == 0x2A;

        /// <summary>True for the fake LCtrl that AltGr generates (scan 0x21D in LL hooks).</summary>
        public static bool IsAltGrCtrl(uint vk, uint scan) => vk == VK_LCONTROL && (scan & 0x200) != 0;

        /// <summary>Scan code (+ extended flag) and VK from a KBDLLHOOKSTRUCT → HID usage, or 0.</summary>
        public static int ToHid(uint vk, uint scan, bool extended)
        {
            switch ((int)vk)
            {
                case VK_PAUSE: case VK_CANCEL: return 0x48;
                case VK_NUMLOCK: return 0x53;
                case VK_SNAPSHOT: return 0x46;
                case VK_LSHIFT: return HID_LSHIFT;
                case VK_RSHIFT: return HID_RSHIFT;
                case VK_LCONTROL: return HID_LCTRL;
                case VK_RCONTROL: return HID_RCTRL;
                case VK_LMENU: return HID_LALT;
                case VK_RMENU: return HID_RALT;
                case VK_LWIN: return HID_LGUI;
                case VK_RWIN: return HID_RGUI;
            }
            int sc = (int)(scan & 0xFF);
            int hid = extended ? Extended[sc] : Plain[sc];
            if (hid == 0 && sc == 0 && vk != 0)
            {
                // Some virtual/remapped keyboards report scan 0; derive it from the VK.
                uint ex = NativeMethods.MapVirtualKey(vk, 4 /* MAPVK_VK_TO_VSC_EX */);
                if (ex != 0)
                {
                    bool e = (ex & 0xFF00) == 0xE000;
                    hid = e ? Extended[ex & 0xFF] : Plain[ex & 0xFF];
                }
            }
            return hid;
        }

        /// <summary>
        /// HID usage → how to inject it. Returns (scan, extended, vk): when vk != 0 inject by VK (keys whose scan codes
        /// don't round-trip through SendInput), otherwise inject by scan code. (0,false,0) = unknown.
        /// </summary>
        public static (ushort scan, bool extended, ushort vk) FromHid(int hid)
        {
            if (hid <= 0 || hid > 255) return (0, false, 0);
            switch (hid)
            {
                case 0x48: return (0x45, false, VK_PAUSE);
                case 0x53: return (0x45, true, VK_NUMLOCK);
                case 0x46: return (0x37, true, VK_SNAPSHOT);
            }
            ushort v = HidToScan[hid];
            if (v == 0) return (0, false, 0);
            return ((ushort)(v & 0xFF), (v & 0x100) != 0, 0);
        }

        public static bool IsModifierHid(int hid) => hid >= 0xE0 && hid <= 0xE7;

        /// <summary>Consumer keys are sent as actions, not as keys (§8.3).</summary>
        public static string? ConsumerAction(uint vk) => (int)vk switch
        {
            VK_VOLUME_MUTE => "vol_mute",
            VK_VOLUME_DOWN => "vol_down",
            VK_VOLUME_UP => "vol_up",
            VK_MEDIA_PLAY_PAUSE => "media_play_pause",
            VK_MEDIA_NEXT_TRACK => "media_next",
            VK_MEDIA_PREV_TRACK => "media_prev",
            VK_BROWSER_BACK => "back",
            _ => null,
        };

        /// <summary>VK codes for which no HID mapping exists but which must not leak to the local machine.</summary>
        public static bool IsSwallowOnlyVk(uint vk) => (vk >= 0xA6 && vk <= 0xB7) || vk == 0x5F /* VK_SLEEP */;

        /// <summary>Extended-key flag for VK-based injection.</summary>
        public static bool IsExtendedVk(int vk) => vk switch
        {
            VK_LWIN or VK_RWIN or VK_APPS or VK_RCONTROL or VK_RMENU or VK_NUMLOCK or VK_SNAPSHOT or VK_CANCEL => true,
            >= 0x21 and <= 0x28 => true, // PgUp, PgDn, End, Home, arrows
            0x2D or 0x2E => true,         // Insert, Delete
            0x6F => true,                 // Divide
            >= 0xA6 and <= 0xB7 => true,  // browser / volume / media / launch
            _ => false,
        };
    }
}
