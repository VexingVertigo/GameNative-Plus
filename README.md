# GameNative+

[![Release](https://img.shields.io/github/v/release/VexingVertigo/GameNative-Plus?color=blue&style=flat-square)](https://github.com/VexingVertigo/GameNative-Plus/releases)
[![License: GPL-3.0](https://img.shields.io/badge/License-GPL%203.0-green.svg?style=flat-square)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android-orange?style=flat-square)](https://developer.android.com/)

**GameNative+** is an independent fork of [GameNative](https://github.com/utkarshdalal/GameNative) optimized for sideloaded PC game collections, container isolation, and automated community compatibility configuration on Android.

---

## Key Features

- **Side-by-Side Coexistence (`app.gamenative.plus`):** Completely decoupled package namespace prevents Google Play or official builds from overwriting your custom binaries, custom prefixes, or saved states.

- **Automated Steam App ID & DLC Generation:** Sideloaded game folders without metadata are automatically normalized (e.g., PascalCase names like `TheBindingOfIsaacRebirth` resolve to `The Binding Of Isaac Rebirth`), searched against the Steam Store API, and auto-provisioned with `steam_appid.txt` and `steam_settings/DLC.txt` to prevent emulator black screens[cite: 10].

- **Automated Community Config Ingestion:** Pulls down verified device-specific Wine/Box64 profiles, Turnip driver combinations, and Proton compatibility fixes automatically for sideloaded games by resolving their official store identity[cite: 10].

- **Container Isolation:** Game-specific runtime environments live in isolated containers, ensuring experimental tweaks or bad prefixes never corrupt underlying game installations or sibling setups.

- **Tailored GameNative+ Branding:** Updated interface assets, launcher icons, and adaptive icon layers.

---

## Directory & Artwork Setup

Game folders should be placed in your local `CustomGames/` directory:

    CustomGames/
    └── YourGameTitle/
        ├── YourGame.exe
        ├── steam_appid.txt          <-- Auto-generated on scan if missing (or manual override)
        ├── steam_settings/
        │   └── DLC.txt              <-- Auto-generated from Steam store data if missing
        ├── coverh.jpg               <-- Horizontal hero / banner (optional)
        └── coverv.jpg               <-- Vertical capsule / poster (optional)

- **Steam Metadata & Configs:** GameNative+ automatically searches Steam, resolves the canonical game title, writes root/subfolder `steam_appid.txt`, and fetches official library artwork[cite: 10]. If you want to force a specific App ID, drop a manual `steam_appid.txt` into the root directory.

- **Custom Artwork:** GameNative+ automatically loads `coverh.jpg` (or `.png`) for horizontal banners and `coverv.jpg` (or `.png`) for vertical library box art, falling back to official Steam CDN art when an App ID is resolved.

---

## Known Limitations

- **App Screen Metadata ("Developer: Unknown" / "Release Date: Unknown"):** Dynamic runtime querying via Compose was bypassed to preserve app stability. Because GameNative relies on a Room database and pre-cached ViewModel pipeline, game metadata fields will display as "Unknown" for sideloaded directories.
  * **Status:** Purely cosmetic. Container creation, hardware preset fetching, and gameplay execution function normally.

---

## Edge Cases & Troubleshooting

### Sideloaded Game Collision / False Positives
- **The "Spacewar (App ID 480)" Issue:** Generic cracks and Steam emulators (e.g., Goldberg) frequently ship with a dummy `steam_appid.txt` set to `480`. This causes GameNative+ to fetch configs for *Spacewar* rather than your actual game.
  * **The Fix:** Delete or edit the dummy `steam_appid.txt` so it matches the real game's Steam Store ID, or remove it entirely and let GameNative+ auto-resolve the folder name.

- **Resetting Bad Configurations:** If an incompatible community preset prevents a game from booting, open the game view, tap **Settings**, and select **Reset to Defaults** to purge the container environment without affecting your local game files.

---

## Test Environment

- **Device:** Samsung Galaxy S26 Ultra (Snapdragon 8 Elite, One UI)
- **Verified Titles:**
  - *Celeste* (verified automatic community preset matching, container deployment, and controller pass-through)
  - *The Binding of Isaac: Rebirth* (verified automatic title resolution from PascalCase folder, auto-generation of App ID `250900`, `DLC.txt` population, and community config retrieval)[cite: 10]

---

## Community Feedback & Reports

Compatibility reports for additional titles are welcome. If submitting an [Issue](https://github.com/VexingVertigo/GameNative-Plus/issues), please include:
1. **Device Model & SoC:** (e.g., Galaxy S26 Ultra / Snapdragon 8 Elite)
2. **Game Title Tested:**
3. **Detection Behavior:** Whether automatic Steam Store lookup worked out-of-the-box or required a manual `steam_appid.txt` entry.

---

## License & Acknowledgments

GameNative+ is distributed under the **GNU General Public License v3.0**. 

Based on the original [GameNative](https://github.com/utkarshdalal/GameNative) project by Utkarsh Dalal. All upstream components, submodules, and associated third-party libraries retain their respective licenses.
