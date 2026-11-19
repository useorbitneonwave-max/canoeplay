# CanoePlay

A retro game player for Android TV / Google TV / Fire TV. Plays your own ROMs for
SNES, NES, Game Boy / Color / Advance, N64, Sega Genesis, Master System, Game Gear and DS,
using the open-source EmulatorJS (libretro) cores.

## Getting games onto the TV
- **Over Wi-Fi:** open CanoePlay, select **Add games**, then on your phone or computer go to
  the address it shows (e.g. `http://192.168.1.40:8080/upload`) and pick your files.
- **USB stick:** put your games in a folder called `ROMs` on the stick and plug it in.

## Controls
- Library: D-pad / stick to move, **A** or **OK** to play, **B** / **Back** to go back.
- In game: buttons are matched by position (bottom = B, right = A, left = Y, top = X on SNES).
- **Back** on the remote or the **Home/Guide** button on the controller opens the pause menu
  (resume, quick save, quick load, restart, quit).
- In-game battery saves are kept automatically.

## Building
Every push to `main` builds the APK with GitHub Actions and publishes it as the latest release:
`https://github.com/<user>/canoeplay/releases/latest/download/CanoePlay.apk`

Add a `SIGNING_KEY_B64` repository secret so every build is signed with the same key;
then updates install over the old version and your saves are kept.

Only play games you own.
