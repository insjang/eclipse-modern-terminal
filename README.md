# Modern Terminal for Eclipse

A terminal view for Eclipse that gets wide characters, full-screen programs and modern key handling right, so that Claude Code, Codex CLI, vim, less and htop work as they do in Windows Terminal.
It is a fork of the Eclipse Terminal and installs beside it as its own view, **Modern Terminal**.

[한국어 README](README.ko.md)

| Claude Code in Modern Terminal | Split tabs |
|---|---|
| ![Claude Code running in Modern Terminal, Korean text aligned](docs/images/claude-code.png) | ![Two terminals side by side in one view](docs/images/split.png) |

## What it fixes and adds

| | Eclipse Terminal | Modern Terminal |
|---|---|---|
| Korean, Chinese, Japanese | counted as one column: lines break and the cursor drifts | two columns, as in every other terminal (East Asian Width) |
| Emoji, combining marks | split or doubled | one grapheme cluster in its cells (ZWJ sequences, flags, skin tones, accents) |
| Full-screen programs (vim, less, htop, Claude Code) | draw over the scrollback, leave garbage behind | alternate screen buffer: the shell comes back as it was |
| Mouse | ignored by programs | reported to programs that ask (click, wheel, drag; SGR mode) |
| DEC modes, bracketed paste, hidden cursor, faint text | partly | supported |
| Shift+Enter | runs the line | a new line in Claude Code, Codex and the like, as on the web |
| Command input field | one line | several lines (Shift+Enter), history kept |
| Tabs | one row per view | drag a tab to an edge to split the view; the layout comes back after a restart |
| Ctrl+click | URLs and plain file paths | also paths in brackets, quotes or backticks, absolute Windows paths, WSL paths (`/mnt/c/...`, `/home/...`), Hangul paths |
| Drop files | not supported | types their paths, quoted for the shell (bash, cmd, PowerShell) |
| Colors | text color from the system: near black on the dark theme | Tango based, matched to the Eclipse theme; Tango Dark/Light and Classic presets |
| Defaults | block cursor, 1000 lines | bar cursor, copy on select, 10000 lines |

Local shells only for now (cmd, PowerShell, Git Bash, WSL distributions, detected on start).
SSH and SFTP are planned for 1.1.

## Requirements

- Eclipse 2026-09 (4.41), tested; 2026-06 (4.40) should work. Java 21 or later
- An Eclipse IDE package that has the Eclipse Terminal (for its PTY library, `org.eclipse.cdt.core.native`)
- Windows 10/11 and Linux; macOS should work but is not tested

## Install

- Update site: Help > Install New Software > Work with `https://insjang.github.io/eclipse-modern-terminal/updates/` > check Modern Terminal > install > restart
  - The bundles are not signed, so Eclipse asks whether to trust them: Trust Selected
  - Later versions come with Help > Check for Updates
- Open it: Window > Show View > Other... > Modern Terminal, or Ctrl+Alt+Shift+M
  - Ctrl+Alt+M opens a terminal at the selected resource
  - The keys differ from the Eclipse Terminal's (Ctrl+Alt+Shift+T, Ctrl+Alt+T) so that both can be installed
- Uninstall: Help > About Eclipse IDE > Installation Details > Installed Software > Modern Terminal > Uninstall

## Preferences

Preferences > Modern Terminal (and Local Terminal under it).
Colors, cursor, copy on select, buffer lines and the shell list are separate from the Eclipse Terminal's.
Load Presets offers Modern Terminal Defaults (what Restore Defaults gives), Tango Dark, Tango Light, Classic Terminal (the Eclipse Terminal's colors), Eclipse Light and Eclipse Dark.

## Build

```bash
ECLIPSE=/path/to/eclipse ./build.sh            # build/*.jar, and runs the unit tests
ECLIPSE=/path/to/eclipse ./build.sh install    # also copies the jars into $ECLIPSE/dropins (close Eclipse first)
ECLIPSE=/path/to/eclipse ./build.sh site       # also builds a p2 update site in build/site
```

JDK 21 or later and bash (WSL works).

## Source and license

Forked from [eclipse-platform/eclipse.platform](https://github.com/eclipse-platform/eclipse.platform) `terminal/` at ea81a95, with the changes proposed upstream in pull requests 2894, 2899 to 2905, 2973 and 2979, and more since.
Packages are renamed from `org.eclipse.terminal` to `io.github.insjang.terminal`.
Eclipse Public License 2.0; see [LICENSE](LICENSE) and [NOTICE](NOTICE).

Developed with the help of AI (Claude Code).
