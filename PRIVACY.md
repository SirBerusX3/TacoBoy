# TacoBoy Privacy Policy

**Effective 14 September 2026.**

This policy covers the TacoBoy app for Android as it is built from this repository. The
offline achievement features in section 2 (the unlock queue and saved game data) first
appear in the release after version 0.2.1. In everything else, 0.2.1 behaves as described.

## In short

- **TacoBoy has no servers.** The developer runs no server, website backend or database for
  the app, and receives no data from it.
- **No accounts, analytics, advertising, tracking or telemetry.** TacoBoy contains none, and
  sells or shares no data.
- **Everything TacoBoy keeps stays on your phone**, in storage other apps cannot read.
- **It connects to two outside services, and only when you use the features that need them**:
  RetroAchievements, if you log in, and libretro's box art server, when you download box art.
  Both are described in section 3.

## 1. Permissions

TacoBoy requests two Android permissions:

- **Internet**, for the two services in section 3.
- **Vibrate**, for haptic feedback on the on-screen controls.

Android's own support library adds a third, which only protects TacoBoy's internal messages
from other apps and gives access to nothing.

It does not ask for access to your files, contacts, location, camera or microphone. ROMs are
opened through Android's folder picker, which gives TacoBoy access only to the folder you
choose. ROMs are read from that folder and never copied into the app.

## 2. What TacoBoy stores on your device

All of it is kept in TacoBoy's private app storage. It is **excluded from Android's cloud
backup**, so it is not uploaded to Google. Android's device-to-device transfer, used when you
move to a new phone, may still copy it across.

| Data | What it is | How long it is kept |
|---|---|---|
| Settings | Your choices in Settings, controller bindings and presets, on-screen layout | Until you change or reset them, clear the app's data, or uninstall |
| Library | The ROM folders you picked, file names of your games, custom titles, hidden games, when each game was last played and how many times, and for a multi-disc game which disc you last switched to | Until you clear the app's data or uninstall. Picking a different folder replaces the list of games, but not play history |
| Game saves | In-game (SRAM) saves and save states | Until you clear the app's data or uninstall. Saving again replaces a game's save, or that slot's state |
| BIOS files | BIOS files you import, copied into the app | Until you delete them in the BIOS screen, clear the app's data, or uninstall |
| Box art | Cover images downloaded for your games | Until you clear the app's data or uninstall. Resetting a game's box art replaces it |
| RetroAchievements login | Your username, your Web API key, and the live-tracking session token. **Your password is never stored**: it is sent once to log in and then discarded | Until you log out in Settings, clear the app's data, or uninstall |
| Saved achievement data | For games you have played or checked while logged in to live tracking: the game's achievement list, which ones you have earned, and the conditions used to detect them. It lets achievements work offline | Until the next time that game is played or checked online (it is then replaced), you clear the app's data, or you uninstall. Logging out does not delete it |
| Unsent achievement unlocks | Achievements earned while RetroAchievements could not be reached: your username, the achievement's ID, the game's hash, when you earned it, and the emulator core that was running. No password or token | Until RetroAchievements accepts or refuses them, you clear the app's data, or you uninstall. Logging out keeps them, so they are sent when you log in again |
| Achievement images | Badge and game icons shown in achievement lists | In the app's cache, which Android may clear at any time |
| Disc playlist | For a multi-disc game, a copy of its .m3u playlist listing its disc filenames, which the emulator core reads | In the app's cache: replaced each time a multi-disc game starts, and Android may clear it at any time |
| Crash history | Details of the last 20 crashes: the time, the technical error, and where known the game's file name and location on your phone, its system and the emulator core | Until you tap Clear Crash History in Settings, clear the app's data, or uninstall |
| App log | Recent technical messages, at most 500 lines, which can include game names | In memory only, and gone when the app closes. The same messages also go to Android's system log, which other apps cannot read |

You can remove all of it at once through Android's settings (TacoBoy > Storage > Clear data)
or by uninstalling the app.

**Nothing on this list is sent anywhere automatically.** Two features let *you* take some of it
out of the app:

- **Copy Diagnostics** (Settings > About) copies your TacoBoy version, Android version, phone
  make, model and processor type, memory page size, PS1 core and on-screen pad setting to the
  clipboard, only when you tap it. It goes wherever you paste it.
- **Export Logs** (Settings > Advanced) saves the app log and crash history to a file in a
  place you choose. It can contain game file names and technical details. It goes nowhere
  else.

## 3. Outside services

TacoBoy connects to these services, over HTTPS, and nothing else. As with any internet
connection, each service can see your IP address.

### RetroAchievements (retroachievements.org)

Only if you log in to RetroAchievements in Settings. TacoBoy sends:

- **When you log in:** your username and your Web API key, or, for live tracking, your
  username and password once in exchange for a session token.
- **When you check a game or start one while logged in:** a hash of the game, a short
  fingerprint calculated from the ROM on your phone (the ROM itself is never uploaded), and
  your username with your API key or session token, to identify the game and fetch its
  achievements and your progress in it.
- **When you earn an achievement with live tracking on:** your username and session token,
  the achievement's ID, the game's hash and, if it is sent late, how long ago you earned it.
- **With every request to its API:** TacoBoy's version, your Android version and, for requests
  about a game you are playing or earned an achievement in, the emulator core and its version,
  as RetroAchievements requires of emulators.

RetroAchievements also sends back achievement images, which TacoBoy downloads from its
servers. RetroAchievements is an independent service that TacoBoy does not operate. What it
does with this data, including how long it keeps it and where its servers are, is governed by
its own [Legal & Terms](https://retroachievements.org/terms).

### libretro thumbnail server (thumbnails.libretro.com)

Only when you download box art in the library, or reset a game's box art. For each game, TacoBoy
requests the image by the game's system and file name, so the server receives those names. The
request also carries Android's standard identification of the connection, which includes your
phone model and Android version. The server is run by the libretro project, not by TacoBoy;
its handling of requests, including where they are processed, is governed by libretro's own
[privacy policy](https://docs.libretro.com/support/privacy-policy/).

### Links you tap

Settings > About links to TacoBoy's source code and to the projects TacoBoy is built from, all
on GitHub. Tapping one opens it in your browser, under that browser's and GitHub's own policies.
TacoBoy itself sends nothing.

## 4. Server locations

TacoBoy has no servers, so **none of your data is held by TacoBoy anywhere except on your own
device.** The two outside services in section 3 are run independently by their own operators,
whose policies, linked there, cover where they process data.

## 5. GDPR and your rights

TacoBoy retains no telemetry, and the developer collects, receives and processes no personal
data from the app. Data TacoBoy stores exists only on your device and is under your control:
section 2 explains how to delete each part of it.

Data you choose to send to RetroAchievements is held by RetroAchievements. To access, correct or
delete it, use RetroAchievements' own account settings and contact routes in its
[Legal & Terms](https://retroachievements.org/terms).

## 6. Changes to this policy

Changes are made in this file, and every version is kept in the repository's history, so any
earlier version can be read and compared. The effective date above changes with each revision.

## 7. Contact

Questions about this policy or TacoBoy's privacy can be asked on the repository's
[Issues page](https://github.com/SirBerusX3/TacoBoy/issues). Issues are public, so **never post
your password, API key or diagnostics you would rather keep private** there.
