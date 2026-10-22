# Modern Terminal for Eclipse

[English README](README.md)

![Markdown Viewer 옆에 Modern Terminal을 세 칸으로 분할: Claude Code, htop, 셸](docs/images/overview.png)

| 한글을 출력하는 Claude Code | 위 Claude Code, 아래 htop |
|---|---|
| ![Modern Terminal에서 실행한 Claude Code, 한글 표 정렬 정상](docs/images/claude-code.png) | ![한 뷰 안에 Claude Code와 htop을 분할](docs/images/split.png) |

Eclipse용 터미널 뷰.
한글 등 넓은 문자, 전체 화면 프로그램, 최신 키 입력을 제대로 처리: Claude Code, Codex CLI, vim, less, htop이 Windows Terminal과 같이 동작.
Eclipse Terminal을 fork한 것으로, 별도 뷰 **Modern Terminal**로 기존 터미널 옆에 설치됨.

## 개선·추가 내용

| | Eclipse Terminal | Modern Terminal |
|---|---|---|
| 한글·중국어·일본어 | 1칸으로 계산: 줄이 깨지고 커서가 밀림 | 다른 터미널과 같이 2칸 (East Asian Width) |
| 이모지·결합 문자 | 쪼개지거나 겹침 | 하나의 글자 묶음(grapheme cluster)으로 표시 (ZWJ 이모지, 국기, 피부색, 악센트) |
| 전체 화면 프로그램 (vim, less, htop, Claude Code) | 스크롤 기록 위에 그려 잔상이 남음 | 대체 화면 버퍼: 종료하면 셸 화면이 그대로 돌아옴 |
| 마우스 | 프로그램에 전달 안 됨 | 요청한 프로그램에 전달 (클릭, 휠, 드래그, SGR 방식) |
| DEC 모드, 브래킷 붙여넣기, 커서 숨김, 흐린 글자 | 일부만 | 지원 |
| Shift+Enter | 바로 실행 | Claude Code·Codex 등에서 줄바꿈 (웹과 같은 습관) |
| 명령 입력창 | 한 줄 | 여러 줄 (Shift+Enter), 기록 유지 |
| 탭 | 뷰마다 한 줄 | 탭을 가장자리로 끌어 화면 분할, 재시작 후 배치 복원 |
| Ctrl+클릭 | URL과 단순 파일 경로 | 괄호·따옴표·백틱 안 경로, Windows 절대 경로, WSL 경로(`/mnt/c/...`, `/home/...`), 한글 경로도 열기 |
| 파일 끌어 놓기 | 지원 안 함 | 경로 입력, 셸에 맞게 따옴표 처리 (bash, cmd, PowerShell) |
| 색 | 글자색을 시스템에서 가져옴: 다크 테마에서 거의 검정 | Tango 기반, Eclipse 테마에 맞춤, Tango Dark·Light·Classic 프리셋 |
| 기본값 | 블록 커서, 1000줄 | 세로 막대 커서, 선택 시 복사, 10000줄 |
| SSH | JSch 0.1.55 | Apache MINA SSHD, OpenSSH 방식 known_hosts, 세션 저장 |
| SFTP | 없음 | Files 뷰: 탐색, 전송, 원격 파일을 Eclipse에서 편집, 셸 폴더 따라가기 |

- 로컬 셸 (cmd, PowerShell, Git Bash, WSL 배포판은 시작 시 자동 감지)과 SSH 지원

## 요구 사항

- Eclipse 2026-09 (4.41)에서 확인, 2026-06 (4.40)도 동작 예상, Java 21 이상
- Eclipse Terminal이 들어 있는 Eclipse IDE 패키지 (PTY 라이브러리 `org.eclipse.cdt.core.native` 사용)
- Windows 10/11, Linux에서 확인 (macOS는 미확인)
- SSH와 Files 뷰: Apache MINA SSHD 2.19 이상 2.x (`org.apache.sshd.osgi`, `org.apache.sshd.sftp`)
  - EGit이 들어 있는 Eclipse IDE 패키지에는 모두 포함 (JGit이 SSH에 사용), 예: IDE for Java Developers, IDE for Enterprise Java, Eclipse 2026-09는 2.19.0
  - 그 밖의 패키지: 설치할 때 켜져 있는 업데이트 사이트(예: Eclipse 릴리스 사이트 `https://download.eclipse.org/releases/2026-09`)에서 받을 수 있어야 함

## 설치

- Eclipse Marketplace: 실행 중인 Eclipse에 버튼을 끌어 놓기, 또는 Help → Eclipse Marketplace에서 "Modern Terminal" 검색 ([목록](https://marketplace.eclipse.org/content/modern-terminal))

  [![Drag to your running Eclipse workspace to install Modern Terminal](https://marketplace.eclipse.org/modules/custom/eclipsefdn/eclipsefdn_marketplace/images/btn-install.svg)](https://marketplace.eclipse.org/marketplace-client-intro?mpc_install=7574373 "Drag to your running Eclipse workspace to install Modern Terminal")

- 업데이트 사이트: Help → Install New Software → Work with에 `https://insjang.github.io/eclipse-modern-terminal/updates/` 입력 → Modern Terminal 체크 → 설치 → 재시작
  - 코드 서명이 없어 신뢰 여부를 물음: Trust Selected
  - 이후 Help → Check for Updates로 업데이트
- 열기: Window → Show View → Other... → Modern Terminal, 또는 Ctrl+Alt+Shift+M
  - Ctrl+Alt+M: 선택한 리소스 위치에서 터미널 열기
  - 두 터미널을 함께 설치할 수 있도록 Eclipse Terminal의 단축키(Ctrl+Alt+Shift+T, Ctrl+Alt+T)와 다르게 지정
- 제거: Help → About Eclipse IDE → Installation Details → Installed Software → Modern Terminal → Uninstall

## 설정

- Preferences → Modern Terminal (하위에 Local Terminal)
- 색, 커서, 선택 시 복사, 버퍼 줄 수, 셸 목록은 Eclipse Terminal 설정과 별개
- Load Presets: Modern Terminal Defaults (Restore Defaults 값), Tango Dark, Tango Light, Classic Terminal (Eclipse Terminal 색), Eclipse Light, Eclipse Dark

## SSH

- Ctrl+Alt+Shift+M (또는 뷰 툴바의 Open Modern Terminal...) → Choose terminal: SSH Terminal
  - 호스트, 사용자, 포트, 키 파일(선택), 타임아웃, keepalive 입력
- Saved Sessions (PuTTY 방식): 이름 입력 후 Save, 골라서 Load, 더블클릭하면 바로 접속, Delete는 세션과 저장된 비밀번호 삭제
- 인증: OpenSSH와 같은 순서로 시도
  - 키: 키 파일 칸 먼저, 다음 `~/.ssh/id_ed25519`, `id_ecdsa`, `id_rsa`, `~/.ssh/config`의 `IdentityFile`, 필요하면 암호(passphrase) 입력 창
  - 비밀번호, keyboard-interactive (일회용 코드 등)
- 비밀번호는 "Save password"를 체크했을 때만 저장, 저장 위치는 Eclipse Secure Storage뿐
- 호스트 키: OpenSSH와 같이 `~/.ssh/known_hosts`로 확인
  - 처음 보는 호스트: 지문(fingerprint)을 보여 주고, 수락하면 키 추가
  - 키가 바뀐 호스트: 연결 거부, 바뀐 것이 맞으면 `known_hosts`에서 이전 줄 삭제

## Files 뷰 (SFTP)

- Window → Show View → Other... → Modern Terminal → Modern Terminal Files
- Modern Terminal에서 선택한 SSH 탭의 파일을 같은 연결로 표시: 다시 로그인하지 않음
- 탐색, Upload... (바탕 화면에서 파일·폴더 끌어 놓기도 가능), Download..., New Folder..., Rename..., Delete
- 파일을 더블클릭하면 Eclipse 편집기로 열림, 저장하면 업로드
  - 연 뒤에 서버에서 파일이 바뀌었으면 덮어쓰기 전에 확인
  - 바이너리로 보이는 파일은 열기 전에 확인: 텍스트 편집기로 저장하면 손상될 수 있음
  - 원격 파일을 외부 프로그램으로 열지 않음
- 업로드는 임시 이름으로 쓴 뒤 원래 이름으로 바꿈: 취소하거나 연결이 끊겨도 원래 파일 유지
  - `posix-rename@openssh.com` 확장 필요 (OpenSSH는 지원), 없으면 그 자리에 씀
- 로컬 탭(cmd, PowerShell, Git Bash, WSL)을 고르면 로컬 폴더를 표시, WSL 탭은 해당 배포판의 파일
  - 탐색, 열기, New Folder..., Rename..., Delete 지원, Upload·Download는 SSH 전용
  - 삭제는 링크·junction 안으로 들어가지 않음

### 셸 폴더 따라가기 (OSC 7)

- 원격 셸이 현재 폴더를 알려 주면 `cd` 할 때마다 Files 뷰가 따라감
- 뷰 툴바의 Follow Terminal Folder로 켜고 끔
- 원격 셸에 아무것도 자동으로 넣지 않음: 서버의 셸 시작 파일에 아래 중 하나를 추가

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

- `vte.sh`가 이미 OSC 7을 보내는 시스템(Fedora 등)은 설정 불필요

## 빌드

```bash
ECLIPSE=/path/to/eclipse ./build.sh            # build/*.jar 생성, 단위 테스트 실행
ECLIPSE=/path/to/eclipse ./build.sh install    # $ECLIPSE/dropins 에 복사 (Eclipse 종료 상태에서)
ECLIPSE=/path/to/eclipse ./build.sh site       # build/site 에 p2 업데이트 사이트 생성
```

- JDK 21 이상, bash (WSL 가능)

## 출처와 라이선스

- [eclipse-platform/eclipse.platform](https://github.com/eclipse-platform/eclipse.platform)의 `terminal/` (ea81a95)을 fork
  - upstream에 제안한 PR 2894, 2899~2905, 2973, 2979의 변경과 이후 추가 기능 포함
- 패키지명은 `org.eclipse.terminal`에서 `io.github.insjang.terminal`로 변경
- Eclipse Public License 2.0, [LICENSE](LICENSE)와 [NOTICE](NOTICE) 참고

AI(Claude Code)의 도움을 받아 개발.
