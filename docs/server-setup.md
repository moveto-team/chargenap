# 개발 서버 이전 (Vultr)

2026-11-13 작성. 지금까지 개발한 PC(Windows 11 + WSL2, 메모리 8GB)에서 Vultr 서버로 개발 환경을 옮기고, 서버에서 Claude Code로 작업을 이어 가는 절차다. 남은 작업은 `docs/next-steps.md`에 있다.

## 왜 옮기나

남은 핵심 작업은 **Pleos Connect Emulator에서 실제 차량 값을 읽고 시트·공조를 제어해 보는 실행 검증**이다(`docs/progress.md` "에뮬레이터 검증"). 지금 PC에서는 다음 때문에 막혔다.

- WSL 안에서 `/dev/kvm`을 쓸 수 없어 에뮬레이터를 Windows 쪽에서 따로 돌려야 했다.
- 메모리 8GB를 Gradle·에뮬레이터·영상 렌더가 나눠 써서 한 번에 하나만 돌릴 수 있었다(Gradle 콜드 빌드 20~40분).
- 에뮬레이터의 차량 HAL 서비스가 부팅 중 계속 죽어 `car_service`가 준비되지 않았다. 사양 문제인지 이미지 문제인지는 아직 모른다. 서버에서 가장 먼저 확인할 일이다.

## 1. 서버 고르기

| 항목 | 권장 | 이유 |
| --- | --- | --- |
| 지역 | Seoul | SSH·화면 전송 지연 |
| OS | Ubuntu 24.04 LTS x64 | 스크립트가 이 배포판 기준(22.04도 동작하도록 작성) |
| CPU | 8코어 이상 | 에뮬레이터 4코어 + Gradle |
| 메모리 | 32GB 이상 | 에뮬레이터 4~8GB, Gradle 데몬 8GB, 영상 렌더(Chromium 4개) |
| 디스크 | NVMe 200GB 이상 | Android SDK·에뮬레이터 이미지 약 30GB(압축 4GB 포함), AVD 데이터 6GB, Gradle 캐시, 영상 프로젝트 |
| **KVM** | **필수** | 에뮬레이터 가속. 없으면 AAOS 부팅이 사실상 불가능하다 |

**KVM 확인이 가장 중요하다.** Vultr의 일반 가상 서버(Cloud Compute)가 중첩 가상화(`/dev/kvm`)를 주는지는 Vultr 공식 문서에서 확인하지 못했다. 다음 순서로 정한다.

1. 시간 단위 과금이므로 원하는 사양의 Cloud Compute를 먼저 만들고, 접속해서 바로 확인한다.
   ```bash
   ls -l /dev/kvm; grep -c -E 'vmx|svm' /proc/cpuinfo
   ```
   `/dev/kvm`이 있고 두 번째 숫자가 0보다 크면 그대로 쓴다.
2. 없으면 그 인스턴스를 지우고 **Bare Metal**(물리 서버라 KVM이 확실하다)로 다시 만들거나, Vultr 지원팀에 중첩 가상화를 문의한다.
3. KVM 없이도 빌드·단위 테스트·영상 작업은 모두 된다. 에뮬레이터 검증만 따로 KVM 있는 곳에서 하는 선택지도 있다.

서버를 만들 때 **SSH 공개키를 등록**한다(비밀번호 로그인은 `setup-root.sh`가 끈다). 키가 없다면 로컬 PC에서 `ssh-keygen -t ed25519` 로 만든다.

## 2. 무엇을 어떻게 옮기나

| 대상 | 방법 | 비고 |
| --- | --- | --- |
| 소스, 문서, 스크립트, 서브에이전트(`.claude/agents/`), `CLAUDE.md`, APK | **git** (`git clone`) | 서버로 옮기기 전에 로컬의 새 커밋을 push 해야 한다 |
| 릴리스 키스토어, `keystore.properties` | **번들** (`export-bundle.sh`) | 커밋 금지. 잃으면 같은 서명으로 APK를 다시 낼 수 없다 |
| `submission/` (접수양식·메일 초안·영상) | 번들 | 개인정보 포함, 커밋 금지 |
| 시연 영상 프로젝트 `~/workspace/chargenap-film` | 번들 (`node_modules`·`out/` 제외) | git 저장소가 아니다 |
| Claude Code 자동 메모리, 사용자 규칙(`~/.claude/rules`) | 번들 | 서버의 새 경로에 맞게 자동으로 옮긴다 |
| JDK 17, Gradle, Android SDK, 에뮬레이터 이미지, Node.js, Claude Code | 서버에서 새로 설치 (`setup-dev.sh`) | |
| Claude Code 플러그인 | 서버에서 새로 설치 (`install-claude-plugins.sh`) | 이 프로젝트에 쓰는 것만 |
| Claude Code 로그인, `gh` 로그인 | 서버에서 새로 로그인 | 토큰 파일은 복사하지 않는다 |
| claude.ai 커넥터(ElevenLabs 등) | 옮길 것 없음 | 같은 계정으로 로그인하면 따라온다 |
| Windows 전용 도구 (Word COM, SAPI 한국어 TTS, `scripts/windows/`) | 옮기지 않음 | 서버에는 없다. 접수양식 PDF 변환이 다시 필요하면 로컬 PC에서 하거나 `libreoffice --headless --convert-to pdf` |

## 3. 순서

### 3-1. 로컬 PC (지금의 WSL)

```bash
cd ~/workspace/chargenap
git push origin main                         # 서버가 clone 할 수 있게
scripts/server/export-bundle.sh              # → ~/chargenap-bundle.tar.gz (약 370MB, 권한 600)
```

### 3-2. 서버, root로 한 번

```bash
ssh root@<서버IP>
curl -fsSL https://raw.githubusercontent.com/moveto-team/chargenap/main/scripts/server/setup-root.sh -o setup-root.sh
bash setup-root.sh dev
```

패키지·한글 글꼴·시간대, 개발 계정 `dev`(root의 SSH 키 복사, 비밀번호 없는 sudo — 원하지 않으면 `DEV_SUDO_NOPASSWD=0`), KVM 권한, 스왑 8GB, 방화벽(SSH만), SSH 비밀번호 로그인 끄기를 한다. 끝에 KVM 사용 가능 여부를 출력한다.

### 3-3. 번들 옮기기 (로컬 PC에서)

```bash
scp ~/chargenap-bundle.tar.gz dev@<서버IP>:~/
```

### 3-4. 서버, dev 계정으로

```bash
ssh dev@<서버IP>
git clone https://github.com/moveto-team/chargenap.git ~/workspace/chargenap
cd ~/workspace/chargenap
scripts/server/setup-dev.sh --with-emulator        # 툴체인, Gradle 설정, Node, Claude Code, 에뮬레이터(약 20GB)
source ~/.bashrc
scripts/server/import-bundle.sh ~/chargenap-bundle.tar.gz
rm ~/chargenap-bundle.tar.gz                        # 로컬 PC의 번들도 지운다
gh auth login                                       # push 할 때 필요 (HTTPS, 브라우저 코드 방식)
```

- `--with-emulator`는 kvm 그룹이 반영된 셸에서만 설치한다. `setup-root.sh` 직후 처음 접속한 셸이면 그대로 된다.
- ccam으로 여러 Claude 계정을 쓰려면 `--with-ccam`을 더하고, 계정을 `ccam add`로 만든 뒤 그 계정 설정 폴더를 `CLAUDE_DIR=...`로 주어 `import-bundle.sh`를 실행해야 메모리가 그 계정에 들어간다.
- Gradle 메모리는 `~/.gradle/gradle.properties`에 서버 사양 기준으로 써 둔다. 저장소의 `gradle.properties`(5GB PC 기준)는 그대로 두고, 사용자 홈 설정이 우선한다.

### 3-5. Claude Code 로그인과 플러그인

```bash
tmux new -s chargenap          # SSH가 끊겨도 세션이 살아 있게 한다. 다시 붙기: tmux attach -t chargenap
cd ~/workspace/chargenap
claude                         # 처음 실행하면 로그인 주소가 나온다. 로컬 브라우저에서 열고 받은 코드를 붙여 넣는다
                               # 폴더 신뢰 확인에 동의한다
# 다른 창에서
scripts/server/install-claude-plugins.sh
```

### 3-6. 확인 (서버 첫 Claude 세션에 맡겨도 된다)

```bash
./gradlew testDebugUnitTest                 # 418개 통과
scripts/package-release.sh Moveto           # 서명 확인(apksigner)까지
scripts/server/emu.sh up                    # 창 없이 cold boot, 부팅까지 대기
scripts/server/emu.sh status                # 'car_service not ready' 수가 늘지 않는지
scripts/server/emu.sh install
scripts/server/emu.sh shot /tmp/home.png
```

`./gradlew testDebugUnitTest`는 화면 스냅샷을 `docs/screenshots/`에 다시 쓴다. 글꼴 렌더링이 PC와 달라 PNG가 바뀌어 보일 수 있으니, 화면 코드를 바꾸지 않았다면 커밋 전에 `git checkout docs/screenshots`로 되돌린다.

## 4. 서버에서 일하는 법

- **Claude Code**: `tmux` 안에서 실행한다. 로컬 PC는 SSH 터미널만 있으면 된다. VS Code를 쓰면 Remote-SSH로 같은 폴더를 연다.
- **에뮬레이터 화면**: Claude Code는 `scripts/server/emu.sh shot <png>`로 캡처해 직접 본다. 사람이 보고 만지려면 `scripts/server/emu.sh tunnel`이 알려 주는 대로 SSH 터널을 열고 로컬 PC에서 scrcpy를 실행한다(서버 포트를 인터넷에 열지 않는다).
- **Virtual Console**: 서버 안에서는 `scripts/vconsole.sh`가 바로 동작한다(`emu.sh up`이 8088 포워딩을 해 둔다). 브라우저 UI는 위 터널로 로컬 `http://127.0.0.1:8088`.
- **영상 렌더**: `~/workspace/chargenap-film`. 서버에는 화면이 없으므로 `DISPLAY` 문제 없이 Chromium이 headless로 돈다. 메모리가 넉넉해 Gradle과 동시에 돌려도 된다.

## 5. 보안

- 방화벽은 SSH만 연다. Virtual Console(8088)·adb(5037/5555)는 SSH 터널로만 쓴다.
- 키스토어와 `keystore.properties`는 권한 600, `~/.chargenap/`에 둔다. 번들은 옮긴 뒤 양쪽에서 지운다.
- `dev` 계정은 비밀번호 없이 sudo를 쓴다(Claude Code가 패키지를 설치할 수 있게). 서버를 다른 사람과 같이 쓰면 `DEV_SUDO_NOPASSWD=0`으로 다시 설정한다.
- 서버 IP, 계정 이름 등은 공개 저장소에 적지 않는다.

## 6. 스크립트

| 파일 | 어디서 | 하는 일 |
| --- | --- | --- |
| `scripts/server/export-bundle.sh` | 로컬 PC | git에 없는 것(서명 키, 제출 자료, 영상 프로젝트, Claude 메모리·규칙)을 묶는다 |
| `scripts/server/setup-root.sh` | 서버 root | OS 패키지, 계정, KVM, 스왑, 방화벽, SSH |
| `scripts/server/setup-dev.sh` | 서버 dev | 툴체인, Gradle 설정, Node, Claude Code, (선택) 에뮬레이터·ccam |
| `scripts/server/import-bundle.sh` | 서버 dev | 번들을 제자리에 풀고 경로를 고친다 |
| `scripts/server/install-claude-plugins.sh` | 서버 dev | 이 프로젝트에서 쓰는 Claude Code 플러그인 |
| `scripts/server/emu.sh` | 서버 dev | 에뮬레이터 up/status/install/shot/log/down/tunnel |
