이 폴더는 오프라인 Android 파일 관리자 앱(Kotlin + Jetpack Compose, 패키지 `com.offlinefiles.explorer`) 프로젝트야.
1.1.0에서 갤러리 기능(홈 갤러리 카드, 사진/앨범 탭, 전체 화면 뷰어, 상세 정보, 파일 목록에서 사진 열기)을 추가했어.
그런데 이 코드는 Android SDK가 없는 환경에서 작성돼서 **한 번도 Gradle로 빌드되지 않았어.**
순수 Kotlin 부분(`core/gallery/Media.kt`와 `GalleryCoreTest`)만 kotlinc로 컴파일하고 테스트했어.
Android·Compose 코드는 컴파일 오류가 있을 수 있어. 아래 순서대로 빌드, 테스트, 수정, 서명까지 끝내 줘.

## 0. 준비
- git 저장소가 아니면 `git init` 후 현재 상태를 "1.1.0 gallery (unbuilt)"로 커밋해서 이후 수정 내역이 diff로 남게 해 줘.
- 프로젝트 루트에 `upload-keystore.jks`와 `keystore.properties`가 있는지 확인해. 없으면 키 백업 zip 위치를 나에게 물어봐. 두 파일은 `.gitignore`에 있으니 절대 커밋하지 마.
- JDK 17 이상이 필요해. 없으면 Android Studio에 들어 있는 JDK를 `JAVA_HOME`으로 써.
- Android SDK가 필요해(compileSdk 36). `ANDROID_HOME`이 없으면 `local.properties`에 `sdk.dir=`를 만들어. `platforms;android-36`, build-tools가 없으면 sdkmanager로 설치해.

## 1. 디버그 빌드: `./gradlew assembleDebug`
컴파일 오류가 나면 **기능과 화면은 그대로 두고** 오류만 최소한으로 고쳐 줘. 오류가 날 가능성이 큰 곳은 아래와 같아.
- `ui/gallery/GalleryScreen.kt`: `PrimaryTabRow`/`Tab`, `LazyVerticalGrid`의 `items(span=…)`, `awaitFirstDown(pass=…)`, 아이콘 이름
- `ui/gallery/MediaViewer.kt`: `HorizontalPager`(`beyondViewportPageCount`, `pageSpacing`, `key`), `WindowInsets.statusBarsIgnoringVisibility`/`navigationBarsIgnoringVisibility`(ExperimentalLayoutApi), `calculateCentroid(useCurrent=)`, `Surface(onClick=)`, `Painter`를 상속한 `DrawablePainter`, `AnimatedVisibility`를 `BoxScope` 확장 함수 안에서 쓴 부분
- `ui/gallery/MediaDecode.kt`: `ImageDecoder`(API 28+), `MediaMetadataRetriever.getScaledFrameAtTime`, `ExifInterface` 상수
- `data/MediaLibrary.kt`, `ui/components/Thumbnails.kt`: `ContentResolver.loadThumbnail`, `MediaStore.getVersion/getGeneration`
- `ui/gallery/MediaInfoSheet.kt`, `ui/home/HomeScreen.kt`의 갤러리 카드
Compose 버전에 맞지 않는 API는 그 버전에서 같은 동작을 하는 API로 바꿔 줘.

## 2. 테스트: `./gradlew testDebugUnitTest`
- 모두 37개야. 기존 27개와 새로 추가한 `GalleryCoreTest` 6개, `GalleryFlowTest` 4개야.
- 실패하면 원인을 찾아 **코드**를 고쳐 줘. 테스트가 틀렸을 때만 테스트를 고치고, 왜 고쳤는지 알려 줘.
- `app/screenshots/40_…`부터 `48_…`까지가 갤러리 스크린샷이야. 직접 열어서 썸네일이 보이는지, 레이아웃이 깨지지 않았는지, 한글과 영어 문구가 맞는지 확인해 줘. 기존 00~30번 스크린샷도 이상해지지 않았는지 봐 줘.

## 3. 린트: `./gradlew lintRelease`
새 코드에서 나온 `NewApi`(minSdk 26) 같은 오류급 항목만 고쳐 줘.

## 4. 릴리스: `./gradlew assembleRelease bundleRelease`
- `apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk` 결과의 서명 SHA-256이 아래 값과 같아야 해(기존 1.0.0과 같은 업로드 키).
  `7bc573b031b3a3092fa372ae6cba5988bd924d73a2930a3ab47c7a538d0cb6f2`
- AAB도 `keytool -printcert -jarfile app/build/outputs/bundle/release/app-release.aab`로 같은 키인지 확인해 줘.
- versionCode가 2, versionName이 1.1.0인지 확인해 줘.

## 5. 기기 확인 (`adb devices`에 기기가 있을 때만)
- debug APK를 설치해. 이미 설치된 앱과 서명이 달라 설치가 실패하면 **삭제하기 전에 나에게 물어봐**(앱 데이터가 지워져).
- 다음을 확인해 줘.
  - 홈의 갤러리 카드: 사진·동영상 수, 최근 썸네일
  - 사진 탭: 날짜 머리글, 두 손가락으로 격자 크기 변경
  - 앨범 탭: 카메라 앨범이 맨 앞, 동영상 앨범
  - 뷰어: 좌우로 넘기기, 두 손가락 확대, 두 번 탭 확대, 탭하면 상단·하단 바와 상태 표시줄 숨기기
  - 상세 정보, 삭제 후 다음 사진으로 이동, 공유, 폴더에서 보기
  - 파일 목록에서 사진을 누르면 내장 뷰어로 열리는지
  - 동영상을 누르면 외부 플레이어로 열리는지
- 확인하는 동안 `adb logcat -b crash`에 크래시가 없는지 봐 줘.

## 지켜야 할 것
- 새 라이브러리를 추가하지 마. `INTERNET` 권한도 추가하지 마(오프라인 앱).
- `applicationId`, 서명 설정, 기존 파일 관리 기능은 바꾸지 마.
- 문자열을 고치거나 추가하면 `values/strings.xml`(영어)과 `values-ko/strings.xml`(한국어)을 함께 맞춰 줘.
- README의 테스트 개수와 기능 설명이 실제와 다르면 고쳐 줘.

## 끝나면 알려 줄 것
1. 고친 내용: 파일:줄, 원인, 수정 내용
2. 테스트 결과(통과 수/37)와 스크린샷 확인 결과
3. 산출물 경로: APK, AAB
4. 서명 SHA-256 일치 여부와 버전
5. 기기에서 확인한 결과, 또는 기기가 없어 건너뛴 사실
6. 남은 문제나 내가 결정해야 할 것
