# 인수인계: Claude 계정을 새로 만든 뒤 이어서 작업하기

2026-11-10에 작성했습니다. 기준 커밋은 `main` = `7715393`, PR #3 = `d6574dc`입니다.
이 시점의 기록이니, 이후에 바뀐 것은 `git log`와 PR 목록을 보세요.
저장소가 공개(public) 상태라 비밀번호, 키, 개인 정보는 넣지 않았습니다.

## 한눈에 보기

- 코드, 브랜치, PR, 자동 빌드 APK는 모두 GitHub에 있어서 Claude 계정을 초기화해도 남습니다.
- 계정과 함께 사라지는 것은 Claude 세션 대화 기록, 클라우드 환경 설정, Claude와 GitHub의 연결입니다.
  대화에서만 오간 요청과 기기 테스트 결과는 5장에 옮겨 두었습니다.
- **최신 작업은 아직 main에 없습니다.** 열려 있는 [PR #3](https://github.com/cicada2132/FileExplorer/pull/3)
  (브랜치 `ccr-32d5b6ab-xg3r7k`, 커밋 8개)에 있습니다. 충돌 없이 병합할 수 있고, 2026-11-10에 테스트 66개가 모두 통과했습니다.
- 새 계정에서 가장 먼저 할 일은 마지막 빌드(`d6574dc`)를 기기에 설치해 AVI·MPG 재생을 확인하는 것입니다(4-1).
- 서명 키는 저장소에 없습니다. 키 백업 zip은 직접 보관해야 합니다(1장).

## 1. 계정 초기화 전에 할 일

1. **서명 키 백업 확인.** `FileExplorer-upload-key.zip`(`upload-keystore.jks` + `keystore.properties`)이 PC나 클라우드 드라이브처럼
   Claude 밖에 있는지 확인하세요. 저장소에는 없고(`.gitignore`), Claude 대화에 올려 두었다면 계정과 함께 사라집니다.
   이 키가 없으면 Play에 같은 앱의 업데이트를 올릴 수 없습니다. Play 앱 서명을 쓰고 있다면 Play Console에서 업로드 키 재설정을
   요청할 수는 있지만 시간이 걸립니다.
2. **PR #3 병합 (권장).** GitHub에서 PR #3을 열고 *Merge pull request*를 누릅니다.
   - 병합하면 새 계정의 Claude가 main에서 바로 최신 코드로 시작하고, README의 `apk-main` 다운로드 링크도 생깁니다.
   - PR 제목이 첫 커밋(USB 허용)만 말하고 있습니다. 병합할 때 제목을 "USB 허용 수정, APK 자동 빌드, VLC 플레이어"처럼 바꾸면
     나중에 기록을 보기 쉽습니다.
   - 병합하지 않고 초기화해도 PR #3과 브랜치는 그대로 남습니다. 그때는 새 세션에 PR #3 브랜치에서 작업하라고 알려 주세요(3-4).
3. **이 문서를 main에 넣기 (선택).** 이 문서는 브랜치 `ccr-ed9a23b8-5vfq2i`에 있습니다. PR을 만들어 main에 병합하면
   새 세션이 바로 찾습니다. main 기준으로 문서만 더했기 때문에 PR #3과 어느 쪽을 먼저 병합해도 충돌하지 않습니다.
4. 1.1.0 릴리스 빌드 때 생긴 `mapping.txt`를 가지고 있다면 키와 함께 보관하세요(Play 크래시 보고 해독용).
5. 커밋 메시지 끝의 `Claude-Session:` 링크는 초기화 후에는 열리지 않습니다. 필요한 내용은 커밋 메시지와 이 문서에 있습니다.

## 2. 지금 상태 (2026-11-10)

### 2-1. 브랜치와 PR

| 브랜치 | 상태 | 내용 |
|---|---|---|
| `main` (`7715393`) | 기본 브랜치 | 1.1.0 갤러리, USB 탐색(PR #2), 내장 플레이어 첫 버전(Android `MediaPlayer`, PR #1). 테스트 61개 |
| `ccr-32d5b6ab-xg3r7k` (`d6574dc`) | **PR #3 열림**, 충돌 없음 | main 전체 + 아래 커밋 8개. 테스트 66개 |
| `ccr-ed9a23b8-5vfq2i` | PR #2로 병합된 뒤 main에서 다시 시작 | 이 문서 |
| `ccr-58d363d4-ahy7ur` (`dd9d977`) | 쓰지 않음 | 플레이어와 USB를 따로 합쳐 본 브랜치. PR #1 병합으로 대체됐으니 지워도 됩니다 |

PR #3의 커밋(오래된 순):

| 커밋 | 내용 |
|---|---|
| `a249374` | USB: 파일 선택 화면에서 허용한 접근이 유지되게 하고, 허용한 뒤 실제로 읽히는지 확인 |
| `45ec212` | GitHub Actions: 푸시할 때마다 디버그 APK를 빌드해 Releases의 `apk-<브랜치>`로 올림 |
| `000d268` | USB: 단계별 접근 안내, 허용 실패 대비, 설정 › 저장소 › USB 연결 진단 |
| `ea8bae6` | 플레이어: libVLC 3.7.7 추가(AVI·WMV·FLV 등), 크래시 수정, 진단 정보에 크래시 기록, CPU별 APK 분리 |
| `fa58e9a` | ‘새 라이브러리 금지’ 규칙 삭제, 그 규칙 때문에 못 했던 것(내장 자막 트랙, 오디오 싱크, 디코딩 선택, androidx ExifInterface) |
| `4d61512` | 사진 뷰어 안에서 동영상 재생(사진·동영상 함께 넘겨 보기) |
| `dddd8ab` | VLC만 사용(Android 플레이어 엔진 제거, 끌 수 없음), 자막 파일 직접 열기, 자동 자막 개선 |
| `d6574dc` | 동영상·음악을 다른 앱으로 넘기지 않음, VLC 코덱 설정 안정화, 설정 › 버전에 빌드 커밋 표시 |

### 2-2. 확인한 것

- `d6574dc`에서 `./gradlew testDebugUnitTest`: 66개 통과, 건너뜀 0, 실패 0 (2026-11-10, 클라우드 세션).
- GitHub Actions 빌드 7번 모두 성공. 최신 APK(2026-11-07):
  [FileExplorer-ccr-32d5b6ab-xg3r7k.apk](https://github.com/cicada2132/FileExplorer/releases/download/apk-ccr-32d5b6ab-xg3r7k/FileExplorer-ccr-32d5b6ab-xg3r7k.apk)
  (64비트 ARM, 46MB). 오래된 32비트 기기나 에뮬레이터는 같은
  [릴리스](https://github.com/cicada2132/FileExplorer/releases/tag/apk-ccr-32d5b6ab-xg3r7k)의 `-universal.apk`(96MB).
- 버전: `versionCode 2`, `versionName "1.1.0"` (main과 PR #3이 같음).
- GitHub 이슈 없음. Claude 예약 작업 없음.
- README의 `apk-main` 링크는 아직 열리지 않습니다. APK 빌드 워크플로(`.github/workflows/apk.yml`)가 PR #3에만 있어서,
  PR #3을 병합해야 main 빌드가 처음 만들어집니다.
- 저장소는 공개(public)입니다. 10/27 세션에서 비공개 전환을 권했지만 그대로입니다. 비공개로 바꾸면 README의 APK 링크는
  GitHub에 로그인한 본인만 받을 수 있습니다.

### 2-3. main과 PR #3의 규칙 차이

main의 `CLAUDE.md`는 PR #3 이전 규칙입니다. PR #3을 병합하면 오른쪽(현재 소유자 결정)으로 정리됩니다.

| | main | PR #3 |
|---|---|---|
| 라이브러리 | 새 라이브러리 금지 | 허용. 정확한 버전으로 고정하고, 네트워크를 쓰지 않는 것만, 설정 › 라이선스에 추가 |
| 동영상·음악 재생 | Android `MediaPlayer` | VLC(libVLC)만. 끌 수 없고, Android 플레이어나 다른 앱으로 넘기지 않음 |
| EXIF | 프레임워크 `android.media.ExifInterface` | `androidx.exifinterface` 1.4.2 |
| 테스트 | 61개 | 66개 |

## 3. 새 계정에서 다시 연결하기

### 3-1. GitHub 연결

1. 새 계정으로 claude.ai에 로그인하고 <https://claude.ai/connect-github>에서 GitHub 계정 `cicada2132`를 연결합니다.
2. 같은 페이지에서 Claude GitHub App이 `cicada2132/FileExplorer`에 설치돼 있는지 보고, 없으면 설치합니다.
   App 설치는 GitHub 쪽 설정이라 Claude 계정을 초기화해도 남아 있을 수 있습니다.
3. claude.ai/code에서 새 세션을 만들 때 저장소 목록에 `cicada2132/FileExplorer`가 보이면 연결된 것입니다.

### 3-2. 클라우드 환경

이전 계정의 환경: 이름 ‘기본값’, 개인용, **네트워크 전체 허용(unrestricted)**, 환경 변수 없음, 설정 스크립트 없음, 저장된 키 없음.

- 새 환경도 네트워크 접근을 가장 넓은 단계로 두면 그대로 동작합니다. 세션 화면 제목 표시줄의 클라우드 환경 메뉴 → 편집(Edit) →
  Network access에서 바꿉니다. 안내: <https://code.claude.com/docs/en/cloud-environments#network-access>
- 설정 스크립트는 필요 없습니다. 저장소의 `.claude/hooks/session-start.sh`(SessionStart 훅, `.claude/settings.json`에 등록)가
  세션마다 Android SDK(`/opt/android-sdk`, platform 36, build-tools 36.0.0), `local.properties`, `7z`, Gradle 의존성을 준비합니다.
  새 컨테이너의 첫 세션은 이 준비에 몇 분 걸립니다.
- 네트워크를 제한(Limited)으로 쓰려면 *Allow package managers*를 켠 채 허용 도메인(Allowed domains)에 아래를 넣으세요.
  2026-11-10에 리디렉션까지 확인한 주소이지만, 제한 모드에서 실제로 빌드해 보지는 않았습니다.
  - `dl.google.com`: Android SDK 도구·패키지, Google Maven
  - `repo.maven.apache.org`, `repo1.maven.org`: Maven Central(라이브러리, Robolectric의 `android-all` jar)
  - `plugins.gradle.org`: Gradle 플러그인
  - `services.gradle.org`, `github.com`, `release-assets.githubusercontent.com`: Gradle 8.14.3 배포판
    (services.gradle.org가 GitHub 릴리스로 리디렉션합니다)
  - `7z`(p7zip-full)는 apt로 설치하므로 package managers 허용이 필요합니다.
- 비밀 값은 필요 없습니다. 디버그 빌드와 테스트는 키 없이 됩니다. 릴리스 서명은 6-2를 보세요.

### 3-3. 세션 사용 방식

- 세션은 시작할 때 고른 브랜치(보통 main)에서 새 작업 브랜치(`claude/…` 또는 `ccr-…`)를 만들고 거기에 푸시합니다.
  main에 넣으려면 PR을 만들어 병합하세요. Claude에게 "PR 만들어 줘"라고 하면 만들어 줍니다.
- 푸시하면 GitHub Actions가 그 브랜치의 디버그 APK를 Releases의 `apk-<브랜치 이름>`에 올립니다(약 4분).
  워크플로가 들어 있는 브랜치에서만 동작합니다(PR #3 병합 전에는 PR #3 브랜치와 거기서 갈라진 브랜치만).
- Maven Central이 가끔 HTTP 429를 돌려줍니다. 코드 문제가 아니니 다시 실행하면 됩니다(`--max-workers=2`가 도움이 됨).

### 3-4. 새 세션의 첫 메시지

PR #3을 병합했다면:

```
이 저장소는 오프라인 Android 파일 관리자 앱이야. CLAUDE.md와 docs/HANDOFF.md를 읽고 현재 상태를 파악해 줘.
그다음 ./gradlew testDebugUnitTest로 테스트 66개가 모두 통과하는지 확인하고 결과를 알려 줘.
```

PR #3을 병합하지 않았다면:

```
이 저장소는 오프라인 Android 파일 관리자 앱이야. 최신 작업은 main이 아니라 PR #3 브랜치 ccr-32d5b6ab-xg3r7k에 있어.
그 브랜치를 기준으로 작업하고, 그 브랜치의 CLAUDE.md를 따라 줘(main의 CLAUDE.md는 예전 규칙이야).
인수인계 문서는 브랜치 ccr-ed9a23b8-5vfq2i의 docs/HANDOFF.md에 있으니 먼저 읽어 줘.
그다음 테스트 66개가 모두 통과하는지 확인하고 결과를 알려 줘.
```

이 문서를 main에 병합했다면 마지막 메시지의 문서 위치 안내는 빼도 됩니다.

## 4. 다음에 할 일

### 4-1. 기기 확인 (가장 먼저)

`d6574dc` 빌드는 소유자 기기에서 아직 확인하지 않았습니다. 테스트는 PC(Robolectric)에서 돌기 때문에
VLC 재생과 실제 USB 연결은 기기에서만 확인할 수 있습니다.

1. 2-2의 최신 APK를 설치합니다. "앱이 설치되지 않음"이 나오면 서명이 달라서이니, 기존 앱을 지우고 설치합니다(앱 설정이 지워짐).
2. 설정 › 버전에 `d6574dc`가 보이는지 확인합니다. 보이면 이 빌드입니다.
3. AVI·MPG 파일을 열어 앱 안(VLC)에서 재생되는지, 앱이 꺼지지 않는지 봅니다.
4. 동영상을 눌렀을 때 Android 기본 플레이어나 다른 앱으로 넘어가지 않는지 봅니다.
5. 안 되면 오류 화면 아래의 회색 글씨나 설정 › 저장소 › 진단 정보를 복사해 새 세션에 붙여 넣습니다.
6. 그 밖에 `docs/player.md`(PR #3 버전)의 ‘기기에서 확인할 것’ 목록: 회전, PIP, 제스처, 내장 자막, 오디오 싱크, 디코딩 전환 등.

새 세션에 보낼 메시지 예:

```
d6574dc 빌드를 기기에서 확인했어. AVI: (재생됨/안 됨/앱 꺼짐), MPG: (…), Android 플레이어로 넘어감: (예/아니오)
오류 화면 문구 또는 진단 정보: (설정 › 저장소 › 진단 정보에서 복사한 내용)
```

### 4-2. 정리

- PR #3 병합 후 README의 `apk-main` 링크가 열리는지 확인합니다.
- 쓰지 않는 브랜치 `ccr-58d363d4-ahy7ur`와 병합이 끝난 `ccr-…` 브랜치를 지워도 됩니다(선택).

### 4-3. 기능 후보

- `docs/player.md`에서 ‘없음’으로 남긴 것: 백그라운드 재생과 알림·잠금 화면 컨트롤(포그라운드 서비스 `mediaPlayback` 권한과
  Play Console 선언이 필요해서 보류), 이퀄라이저, 챕터, 스냅샷, 시간을 입력해서 이동, 자막 색·배경·글꼴
- README의 USB 제한: 파일 선택 화면으로 연 USB는 수정 날짜를 바꿀 수 없음, 그 USB의 사진은 갤러리에 안 나옴,
  Android가 마운트하지 못하는 형식(일부 기기의 NTFS)

## 5. 작업 기록 (세션 대화에서 옮겨 온 것)

계정을 초기화하면 대화 기록은 사라집니다. 커밋 메시지에 없는 소유자 요청과 기기 보고를 중심으로 남깁니다.

| 날짜(UTC) | 소유자 요청·보고 | 결과 |
|---|---|---|
| 10/27 | 빌드해 본 적 없는 1.1.0 갤러리 코드를 빌드·테스트·수정 | 컴파일 오류와 탭 색 수정, CLAUDE.md와 SessionStart 훅 추가(`1359050`, `7c45803`) |
| 10/31 | 내장 동영상·음악 플레이어(MX Player식 화면), USB 저장소 탐색 | PR #1, PR #2 병합. 그때는 ‘새 라이브러리 금지’라 Android `MediaPlayer`로 만듦 |
| 11/1 | 기기에서 USB 접근을 허용해도 계속 허용을 요청하고 열리지 않음. CX 파일 탐색기 같은 앱의 권한 방식을 참고해 달라고 함 | `a249374`, `000d268` → 소유자 확인: **고쳐짐** |
| 11/1 | 동영상이 재생되지 않거나 바로 꺼지고 AVI는 아예 안 됨 → 코덱 추가 요청. 방식은 libVLC를 고름. ‘새 라이브러리 금지’ 규칙을 없애고 그 규칙 때문에 못 한 작업을 모두 진행 | `ea8bae6`, `fa58e9a` |
| 11/7 | MPG·AVI 지원, 사진과 동영상을 구분하지 않고 넘겨 보기. 기기 보고: AVI·MPG를 열면 **앱이 꺼짐** | `4d61512` (뷰어 안 재생, VLC 표면 처리 수정) |
| 11/7 | 자막 수동 선택, 자동 자막 재점검, Android 기본 플레이어 완전 배제, 설정의 VLC 항목 제거(VLC를 끌 수 없게) | `dddd8ab` |
| 11/7 | AVI·MPG 코덱 문제와 ‘Android 플레이어로 넘어가는’ 문제가 여전함 → 가장 대중적인 코덱 설정으로 안정화 요청 | `d6574dc` → **기기 결과 아직 없음** (4-1) |
| 11/10 | Claude 계정 초기화 예정, 인수인계 문서 요청 | 이 문서 |

## 6. 참고

### 6-1. 지켜야 할 규칙 (자세한 것은 CLAUDE.md)

- 오프라인 앱: `INTERNET` 권한을 넣지 않습니다.
- `applicationId`(`com.offlinefiles.explorer`), 서명 설정, 기존 파일 관리 동작은 요청 없이 바꾸지 않습니다.
- 문자열은 `values/strings.xml`(영어)과 `values-ko/strings.xml`(한국어)을 함께 고칩니다.
- README의 기능 목록과 테스트 수를 코드와 맞춥니다.
- 화면을 바꾸면 테스트가 다시 만든 `app/screenshots/*.png`를 열어 확인하고 함께 커밋합니다.
- minSdk 26보다 새 API는 `Build.VERSION.SDK_INT`로 확인합니다.
- 키 파일(`upload-keystore.jks`, `keystore.properties`)은 절대 커밋하지 않습니다. 저장소가 공개 상태입니다.

### 6-2. Play 릴리스

- 키 백업 zip의 두 파일을 프로젝트 루트에 두고 `./gradlew assembleRelease bundleRelease`를 실행합니다.
  자세한 절차는 README ‘빌드’, CLAUDE.md ‘Signing’, `docs/release-1.1.0-checklist.md`에 있습니다.
- 업로드 인증서 SHA-256: `7bc573b031b3a3092fa372ae6cba5988bd924d73a2930a3ab47c7a538d0cb6f2`
- 클라우드 세션에서 서명하려면 키 zip을 대화에 첨부해야 합니다(커밋 금지). PC의 Android Studio에서 빌드해도 됩니다.
- 다음 릴리스는 `app/build.gradle.kts`의 `versionCode`를 올려야 합니다(지금 2 = 1.1.0).
  1.1.0이 Play에 실제로 올라갔는지는 저장소 기록으로는 알 수 없으니 Play Console에서 확인하세요.
- libVLC 때문에 앱이 커졌습니다(자동 빌드한 arm64 디버그 APK가 약 46MB). Play용 AAB는 기기별로 자동으로 나뉩니다.
- `MANAGE_EXTERNAL_STORAGE`, `REQUEST_INSTALL_PACKAGES`는 Play 정책상 권한 선언이 필요합니다(README ‘Play 스토어 업로드’).
- 릴리스마다 `app/build/outputs/mapping/release/mapping.txt`를 보관합니다.
- libVLC는 LGPL 2.1+입니다. 수정하지 않고 동적 라이브러리로 쓰며, 설정 › 오픈소스 라이선스에 고지가 있습니다.

### 6-3. 문서와 설정 파일

| 파일 | 내용 |
|---|---|
| `CLAUDE.md` | Claude용 개발 안내(명령, 구조, 규칙, 서명). 세션이 자동으로 읽음 |
| `README.md` | 기능, 빌드, 테스트(한국어) |
| `docs/player.md` | 플레이어 설계, VLC 기능 비교, 기기 확인 목록(PR #3 버전이 최신) |
| `docs/release-1.1.0-checklist.md` | 1.1.0 빌드·검증 절차 |
| `.github/workflows/apk.yml` | APK 자동 빌드(PR #3에서 추가) |
| `.claude/hooks/session-start.sh` | 클라우드 세션 준비(SDK, 7z, Gradle 의존성) |
