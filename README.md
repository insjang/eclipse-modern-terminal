# Modern Terminal for Eclipse

A terminal view for Eclipse that gets wide characters, full-screen programs and modern key handling right, so that Claude Code, Codex CLI, vim, less and htop work as they do in Windows Terminal.
It is a fork of the Eclipse Terminal and installs beside it as its own view, **Modern Terminal**.

[한국어 README](README.ko.md)

![Modern Terminal split three ways beside the Markdown Viewer: Claude Code, htop and a shell](docs/images/overview-en.png)

| Claude Code with Korean text | Claude Code above, htop below |
|---|---|
| ![Claude Code running in Modern Terminal, Korean table aligned](docs/images/claude-code.png) | ![Claude Code and htop split in one view](docs/images/split.png) |

## What it fixes and adds

| | Eclipse Terminal | Modern Terminal |
|---|---|---|
| Korean, Chinese, Japanese | counted as one column: lines break and the cursor drifts | two columns, as in every other terminal (East Asian Width) |
| Emoji, combining marks | split or doubled | one grapheme cluster in its cells (ZWJ sequences, flags, skin tones, accents) |
| Full-screen programs (vim, less, htop, Claude Code) | draw over the scrollback, leave garbage behind | alternate screen buffer: the shell comes back as it was |
| Mouse | ignored by programs | reported to programs that ask (click, wheel, drag; SGR mode) |
| DEC modes, bracketed paste, hidden cursor, faint text | partly | supported |
| Shift+Enter | runs the line | a new line in Claude Code, Codex and the like, as on the web |
| Paste with a line break | runs it at once | asks first where the shell has not turned on bracketed paste, a single line ending in a line break too (a preference); bracketed paste cannot be ended early by the text |
| Command input field | one line | several lines (Shift+Enter), history kept |
| Tabs | one row per view | drag a tab to an edge to split the view; the layout comes back after a restart |
| Ctrl+click | URLs and plain file paths | also paths in brackets, quotes or backticks, absolute Windows paths, WSL paths (`/mnt/c/...`, `/home/...`), Hangul paths |
| Hyperlinks (OSC 8) | ignored | Ctrl+click opens them: `ls --hyperlink`, gcc, and others; http, https and local file links only, up to 2048 characters and 1000 different links in the buffer at once; a link whose text says something else shows where it goes first (see Ctrl+click) |
| Drop files | not supported | types their paths, quoted for the shell (bash, cmd, PowerShell) |
| Colors | text color from the system: near black on the dark theme | Tango based, matched to the Eclipse theme; Tango Dark/Light and Classic presets |
| Shell integration (OSC 133) | none | Ctrl+Up/Down between prompts, failed commands marked, copy the last command's output |
| Find | none | Ctrl+Shift+F: the scrollback, match case, regular expressions, up and down |
| OSC 52 | ignored | programs copy to the local clipboard, also over SSH (vim, tmux); reading it is never allowed |
| Bell, OSC 9, OSC 777 | ignored | the tab is marked; a desktop notification while Eclipse is in the background; never a sound |
| Defaults | block cursor, 1000 lines | bar cursor, copy on select, 10000 lines |
| SSH | JSch 0.1.55 | Apache MINA SSHD, OpenSSH-style known_hosts, saved sessions |
| SFTP | none | Files view: browse, transfer, edit remote files in Eclipse, follows the shell's folder; local tabs show local folders |

Local shells (cmd, PowerShell, Git Bash, WSL distributions, detected on start) and SSH.

## Requirements

- Eclipse 2026-09 (4.41), tested; 2026-06 (4.40) should work. Java 21 or later
- An Eclipse IDE package that has the Eclipse Terminal (for its PTY library, `org.eclipse.cdt.core.native`)
- Windows 10/11 and Linux; macOS should work but is not tested
- For SSH and the Files view: Apache MINA SSHD 2.19 or later in 2.x (`org.apache.sshd.osgi`, `org.apache.sshd.sftp`)
  - Every Eclipse IDE package with EGit has it (JGit uses it for SSH), e.g. the IDE for Java Developers and for Enterprise Java; Eclipse 2026-09 ships 2.19.0
  - Other packages: the install needs it from an enabled update site, such as the Eclipse release site `https://download.eclipse.org/releases/2026-09`

## Install

- Eclipse Marketplace: drag the button onto a running Eclipse, or search for "Modern Terminal" in Help > Eclipse Marketplace ([listing](https://marketplace.eclipse.org/content/modern-terminal))

  [![Drag to your running Eclipse workspace to install Modern Terminal](https://marketplace.eclipse.org/modules/custom/eclipsefdn/eclipsefdn_marketplace/images/btn-install.svg)](https://marketplace.eclipse.org/marketplace-client-intro?mpc_install=7574373 "Drag to your running Eclipse workspace to install Modern Terminal")

- Update site: Help > Install New Software > Work with `https://insjang.github.io/eclipse-modern-terminal/updates/` > check Modern Terminal > install > restart
  - The bundles are not signed, so Eclipse asks whether to trust them: Trust Selected
  - Later versions come with Help > Check for Updates
- Open it: Window > Show View > Other... > Modern Terminal, or Ctrl+Alt+Shift+M
  - Ctrl+Alt+M opens a terminal at the selected resource
  - Ctrl+Alt+Enter in an editor (or Run Selected Text in Modern Terminal in its context menu) runs the selected text in the terminal shown in Modern Terminal: pasted, then Enter
  - The keys differ from the Eclipse Terminal's (Ctrl+Alt+Shift+T, Ctrl+Alt+T) so that both can be installed
- Uninstall: Help > About Eclipse IDE > Installation Details > Installed Software > Modern Terminal > Uninstall

## Preferences

Preferences > Modern Terminal (and Local Terminal under it).
Colors, cursor, copy on select, buffer lines and the shell list are separate from the Eclipse Terminal's.
Load Presets offers Modern Terminal Defaults (what Restore Defaults gives), Tango Dark, Tango Light, Classic Terminal (the Eclipse Terminal's colors), Eclipse Light and Eclipse Dark.

## Find

Ctrl+Shift+F in a terminal opens a find bar above it (with the selected text, if any).

- Typing finds the last match; Enter goes to the one above, Shift+Enter to the one below, round at the ends; the match is selected and scrolled into view
- Aa: match case; .*: a Java regular expression
- A line that ran on to the next one is searched as one; wide characters (Hangul, CJK, emoji) are matched by their cells
- Esc closes it and the focus goes back to the terminal; Ctrl+Shift+F again puts the focus back in the bar
- New output scrolls to the end as before: turn on Scroll Lock in the view toolbar to read a match while a program is still writing

## Ctrl+click

What the terminal shows comes from programs and servers, so Ctrl+click (on a path, a URL or an OSC 8 link) never runs anything:

- A file opens in an Eclipse editor (the text editor when Eclipse has none for it), never in a program outside Eclipse or an OLE editor
- On Windows only a PDF or an image (pdf, png, jpg, jpeg, gif, bmp) may open in the program the system has for it, and only after a question that shows the full path
  - It is judged by the file a symlink or junction leads to, and that file is what is opened; the name is read as Windows reads it (trailing dots and spaces dropped), and alternate data streams (`a.exe:x.pdf`) and network (UNC) paths are refused
  - On Linux and macOS files always open inside Eclipse (xdg-open picks the program by the content, not the name)
- A web link opens in the browser; an OSC 8 link whose text is not its address shows the address first, with the host in ASCII (punycode) and unusual characters escaped
- An OSC 8 file link opens only a file on this computer; one to another host is ignored

## Shell integration (OSC 133)

A shell that marks its prompts (OSC 133, as for VS Code and Windows Terminal) gets:

- Ctrl+Up and Ctrl+Down: the prompt before or after the one at the top of the view; while the screen has prompt marks these keys stay in the terminal, without marks (and on the alternate screen of vim, tmux and the like) they go to the program as before
- A red bar at the left of the prompt of a command that failed (exit status not 0)
- Copy Output of Last Command in the context menu: selects the output of the last command and copies it
- The marks scroll with their lines into the scrollback and go when the lines do

Nothing is injected into the shell: add one of these to its startup file (after the OSC 7 lines, if you have them, so that it runs first and sees the exit status).

bash (`~/.bashrc`):

```bash
# Modern Terminal: mark prompts, commands and their exit status (OSC 133)
__mt_osc133() {
	printf '\e]133;D;%s\a\e]133;A\a' "$?"
}
PROMPT_COMMAND="__mt_osc133${PROMPT_COMMAND:+;$PROMPT_COMMAND}"
PS1="$PS1"'\[\e]133;B\a\]'
PS0='\e]133;C\a'
```

zsh (`~/.zshrc`):

```zsh
# Modern Terminal: mark prompts, commands and their exit status (OSC 133)
__mt_osc133_precmd() {
	printf '\e]133;D;%s\a\e]133;A\a' "$?"
}
__mt_osc133_preexec() {
	printf '\e]133;C\a'
}
autoload -Uz add-zsh-hook
precmd_functions=(__mt_osc133_precmd $precmd_functions) # first, to see the exit status
add-zsh-hook preexec __mt_osc133_preexec
PS1="$PS1"$'%{\e]133;B\a%}'
```

Shells set up by the shell integration scripts of WezTerm or Windows Terminal already send OSC 133 and need nothing.

## Clipboard from programs (OSC 52)

vim (with an OSC 52 plugin), tmux (`set -g set-clipboard on`) and other programs copy to the clipboard of this computer with `ESC ] 52 ; c ; <base64> BEL`, also from a server over SSH.

- Up to about 768 KB of text; longer is ignored
- Programs can only write: a request to read the clipboard (`?`) is never answered
- Off with Preferences > Modern Terminal > Programs may copy text to the clipboard

## Notifications

A bell (BEL), `ESC ] 9 ; text BEL` (iTerm2) or `ESC ] 777 ; notify ; title ; text BEL` (rxvt, VTE) from the program in a tab, for example Claude Code waiting for input with its notification channel set to `terminal_bell`:

- The tab gets a marker when it is not the one shown; showing it clears the marker. If the Modern Terminal view itself is hidden, its view tab turns bold
- While the Eclipse window is not active: a desktop notification (a tray balloon, a toast on Windows), at most one per tab every 5 seconds; clicking it brings the tab up. Off with Preferences > Modern Terminal > Desktop notification
- No sound, and no setting for one
- `ESC ] 9 ; <number> ; ...` are ConEmu commands (9;4 is progress) and are ignored; control, bidi, zero-width and separator characters are dropped from the text and from the tab title shown with it, both cut to 256 characters


- Ctrl+Alt+Shift+M (or Open Modern Terminal... in the view toolbar) > Choose terminal: SSH Terminal
  - Host, user, port, an optional key file, timeout and keepalive
- `~/.ssh/config` is read as OpenSSH reads it: the host can be a `Host` alias, and its `HostName`, `User`, `Port`, `IdentityFile`, `ProxyJump` and `IdentityAgent` are used
  - The user may be left empty: `User` of the config, else the local login name
  - Port 22 in the field leaves `Port` of the config in force, as `ssh alias` without `-p`; any other port wins
  - `ProxyJump` (bastion hosts, also chained): the key of every host on the way is checked against `known_hosts`, as for a direct connection
- Saved Sessions (as in PuTTY): type a name and Save, select one and Load, or double-click to connect; Delete removes it and its saved password
- Start folder: the shell and the Files view start there after login (empty: the home); SSH has no request for the folder, so the command `sh -c 'cd "$1"; exec "$SHELL" -l' sh '<folder>'` starts the login shell there (any login shell, csh too; a missing folder leaves an error and the home)
- Local forwards: ports on this computer passed through the server, as `ssh -L`; `8080:localhost:80 5433:db:5432` makes `localhost:8080` here reach port 80 on the server and `localhost:5433` reach `db:5432` from it. They listen on 127.0.0.1 only, are listed in the terminal when the connection opens (a port in use is reported, the rest still open) and close with the terminal
- Edit Session... in the context menu of an SSH terminal: the settings of its saved session, saved for the next connection (the open terminal stays as it is)
- Authentication, as OpenSSH tries it:
  - An ssh-agent first: `IdentityAgent` of the config, else `SSH_AUTH_SOCK`, else on Windows the Windows OpenSSH agent (`\\.\pipe\openssh-ssh-agent`); Pageant through `IdentityAgent` with its named pipe (`pageant --openssh-config` writes that line; the older window messages only are not supported); `IdentityAgent none` turns it off. The agent is never forwarded to the server
  - Keys: the key file field, then `~/.ssh/id_ed25519`, `id_ecdsa`, `id_rsa` and `IdentityFile` entries of `~/.ssh/config`; a passphrase is asked when needed
  - Password and keyboard-interactive (e.g. one-time codes)
- Passwords are kept only when "Save password" is checked, and then only in the Eclipse Secure Storage
- Host keys are checked against `~/.ssh/known_hosts` like OpenSSH:
  - Unknown host: the fingerprint is shown, and the key is added if you accept
  - Changed key: the connection is refused; remove the old line from `known_hosts` if the change is expected

## Files view (SFTP)

Window > Show View > Other... > Modern Terminal > Modern Terminal Files.

- Shows the files of the SSH tab selected in Modern Terminal, on the same connection: no second login
- Browse, Upload... (or drop files and folders from the desktop), Download..., New Folder..., Rename..., Delete
- Double-click a file to open it in the Eclipse editor; saving uploads it
  - If the file has changed on the server since it was opened, you are asked before it is overwritten
  - Files that look binary are asked about first, since a text editor can damage them
  - Remote files are never opened in an external program
- Uploads are written to a temporary name and then renamed over the file, so a cancel or a dropped connection keeps the original
  - This needs the `posix-rename@openssh.com` extension (OpenSSH has it); without it the file is written in place
- For a local tab (cmd, PowerShell, Git Bash, WSL) the view shows the local folders instead; a WSL tab shows the distribution's files
  - Browse, Open, New Folder..., Rename..., Delete; Upload and Download are SSH only
  - Delete never goes into links or junctions

### Follow the shell's folder (OSC 7)

When the remote shell reports its current folder, the Files view follows the terminal as you `cd`.
Nothing is injected into the remote shell: add one of these to the server's shell startup file.

bash (`~/.bashrc`):

```bash
# Modern Terminal: report the current folder (OSC 7) at every prompt
__mt_osc7() {
	local LC_ALL=C s=$PWD out= c i
	for ((i = 0; i < ${#s}; i++)); do
		c=${s:i:1}
		case $c in
		[-/._~A-Za-z0-9]) out+=$c ;;
		*) printf -v c '%%%02X' "'$c"; out+=$c ;;
		esac
	done
	printf '\e]7;file://%s%s\e\\' "$HOSTNAME" "$out"
}
PROMPT_COMMAND="__mt_osc7${PROMPT_COMMAND:+;$PROMPT_COMMAND}"
```

zsh (`~/.zshrc`):

```zsh
# Modern Terminal: report the current folder (OSC 7) at every prompt
__mt_osc7() {
	emulate -L zsh
	setopt extended_glob
	local LC_ALL=C
	printf '\e]7;file://%s%s\e\\' "$HOST" "${PWD//(#m)[^-\/._~A-Za-z0-9]/%${(l:2::0:)$(([##16]#MATCH))}}"
}
autoload -Uz add-zsh-hook
add-zsh-hook precmd __mt_osc7
```

Systems whose `vte.sh` already sends OSC 7 (Fedora, for example) need nothing.

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
