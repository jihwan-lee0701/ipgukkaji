# 입구까지 (Pleos Connect 차량용 앱)

차량 내비에 설정한 병원 목적지를 불러와, 방문 목적에 맞는 **방문객용 주차장 입구**를 출입 조건·출처·확인일과 함께 보여주고, 사용자가 고른 입구로 **차량 내비 경로를 바꾸는** Pleos Connect 앱입니다.

> ⚠️ **현재 상태 (2026-11-14 기준, 정직한 보고)**
>
> | 단계 | 상태 |
> |---|---|
> | ① 빌드 | **성공** — `assembleDebug`, `assembleRelease`, `lintDebug`(경고 2: 앱 아이콘 없음, 데이터 추출 규칙) / 단위 테스트 **49개 통과**(실제 데이터 파일·변경 기록·확인일 검사 포함) (2026-11-14, Windows 11, JDK 21, Gradle 8.7, AGP 8.5.2, compileSdk 34) |
> | ② 에뮬레이터 실행 | **성공** — Pleos Connect v2.1.0 에뮬레이터(API 34, 2560×1440, 160dpi)에서 debug·release APK 설치·실행 |
> | ③ 실제 NaviHelper SDK 연동(실제 콜백 수신) | **확인** — 에뮬레이터 내비에서 실제 `onDestinationInfo`·`onRouteCancelled`·`onRouteStarted` 수신, `requestRoute`로 내비 경로 변경 확인(아래 표·`docs/emulator/`) |
> | ④ 실차 검증 | 해당 없음(공모전 2차 PT는 에뮬레이터 시연) |
>
> 단위 테스트(`app/src/test`)는 가짜 내비(FakeNavi)로 상태 전이를 검증한다. 실제 SDK 연동은 별도로 에뮬레이터에서 확인했다.
> 에뮬레이터 검증은 **가상 테스트 시설**(입구까지테스트병원)로 했다. 분당서울대학교병원 1곳·입구 2개(정문 방면 지하주차장, 장례식장 방면 주차장)를 공식 안내 + OpenStreetMap 좌표 + 카카오맵 로드뷰(2026-08 촬영)로 확인해 입력했다(상태: 원격 확인, 현장·경로 미검증). 이 실제 데이터로는 아직 에뮬레이터 시연을 하지 않았다.
> 앱에 들어 있는 입구 데이터는 모두 **테스트용 가상 데이터**이며 화면에 "테스트 데이터" 배너가 표시됩니다.

## 사용 SDK / 권한

| 항목 | 값 | 근거 |
|---|---|---|
| SDK | `ai.pleos.playground:NaviHelper:3.0.5` | NaviHelper "Importing SDK" |
| 저장소 | `https://nexus-playground.pleos.ai/repository/maven-releases/` | 〃 |
| 사용 API | `NaviHelper(context)`, `initialize(onConnected, onFailed)`, `addListener`, `getDestinationInfo`, `requestRoute(RouteInfo)`, `removeListener`, `release` | API Reference |
| 사용 콜백 | `onDestinationInfo`, `onDestinationChanged`, `onRouteStarted`, `onRoutingCompleted`, `onRouteCancelled`, `onError` | 〃 |
| 권한 | `pleos.car.permission.NAVI_ROUTE`, `pleos.car.permission.NAVI_ROUTE_SEARCH` | "Declaring permissions" |
| 주행 규제 | AAOS `CarUxRestrictionsManager` (`useLibrary("android.car")`), Activity `distractionOptimized=true` | 주행 규제 개발 가이드, 지역별 주행 규제 정책 |

`APP_MAPS` 카테고리는 선언하지 않습니다(길 안내는 차량 내비가 담당).

## 구조

```
app/src/main/java/io/ipgukkaji/app/
  domain/   SDK·Android 비의존 로직 (JVM 테스트 대상)
    Model.kt              시설·입구·목적지 데이터
    FacilityMatcher.kt    목적지 ↔ 시설 매칭 (POI ID / 이름+거리 / 오연결 방지 / 자기 변경 감지)
    EntranceRanker.kt     입구 정렬 (진입 불가 맨 아래, 방문 목적 우선)
    EntranceController.kt 상태 관리: 목적지 조회 → 매칭 → (사용자 확인) 경로 요청 → 결과 이벤트
    Ports.kt              NaviGateway / Scheduler 경계
  ui/AppSession.kt          NaviHelper 연결을 Activity 재생성과 분리(프로세스 범위)
  navi/PleosNaviGateway.kt  NaviHelper SDK 어댑터
  ux/DrivingRestrictionMonitor.kt  CarUxRestrictions 감시
  data/EntranceCatalogLoader.kt    assets/entrances.json 파서
  ui/MainActivity.kt        화면
app/src/main/assets/entrances.json  테스트용 가상 입구 데이터
```

## 실행 방법

### 1. 개발 환경 (Pleos 개발 환경 설정 가이드 기준)
1. 최신 Android Studio 설치
2. SDK Manager → SDK Update Sites에 추가
   - Name: `Pleos Connect System Image`
   - Windows/Linux: `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml`
   - Mac: `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-arm64-sys-img.xml`
3. SDK Platforms → Android API 34 → Pleos Connect system image 설치(Show Package Details)
4. AVD 생성: Form Factor **Automotive**, 디바이스 **Pleos Connect v2.1.0**, 시스템 이미지 **Pleos Connect v2.1.0 APIs (API 34)**, Boot **Cold**, RAM **4GB**

### 2. 빌드
`local.properties`에 `sdk.dir`(Android SDK 경로)를 적거나 Android Studio로 `app-android/`를 연다.

```bash
./gradlew testDebugUnitTest   # 단위 테스트 (49개)
./gradlew assembleDebug       # 디버그 APK
./gradlew assembleRelease     # release APK
```

- **Windows 사용자 폴더 이름에 한글이 있으면** 단위 테스트가 `ClassNotFoundException`으로 실패할 수 있다. 빌드 산출물만 ASCII 경로로 보내면 해결된다:
  `./gradlew -PbuildRoot=C:/devtools/build testDebugUnitTest assembleRelease` (`gradle.properties`의 `android.overridePathCheck=true`도 같은 이유)
- **release APK 서명**: 지금은 Android 디버그 키로 서명되어 있다(`Signer: CN=Android Debug`). 공모전 제출용은 팀 키스토어로 서명하도록 `app/build.gradle.kts`의 `signingConfig`를 바꾼다. 키스토어·비밀번호는 저장소에 올리지 않는다.
- `apk/팀명_입구까지_release.apk`: 2026-11-14 빌드본(디버그 키 서명). 제출 규칙에 맞게 `팀명`을 실제 팀명으로 바꾼다.

### 3. 에뮬레이터에서 확인할 것 (2차 PT 전 필수)
1. Pleos 에뮬레이터 내비에서 목적지를 설정한다.
2. 앱 실행 → `adb logcat -s Ipgukkaji/Navi Ipgukkaji Ipgukkaji/UxR` 로 `onDestinationInfo` 의 이름·좌표·`poiId`·표시 좌표를 기록한다.
3. 기록한 좌표에 맞춰 `entrances.json` 의 테스트 시설 `center`·입구 `routeTarget` 을 옮긴다(여전히 `TEST` 로 표시).
4. 입구 선택 → `requestRoute` 후 `onRouteStarted`/`onRoutingCompleted`/`onError` 로그를 확인한다.
5. 아래 가정을 하나씩 확인하고 결과를 이 표에 적는다.

| # | 확인할 가정 | 결과 (2026-11-14, Pleos Connect v2.1.0 에뮬레이터) |
|---|---|---|
| A1 | 에뮬레이터 내비가 getDestinationInfo/requestRoute 에 응답하고 이벤트를 보낸다 | **확인**. 실제 POI(예: 검색한 병원)에 대해 name·좌표·poiId 수신. `displayLatitude/Longitude`는 null, `poiProvider`는 빈 값 |
| A2 | 권한은 매니페스트 선언만으로 충분 | **확인**. `NAVI_ROUTE`, `NAVI_ROUTE_SEARCH` 설치 시 `granted=true` |
| A3 | `poiId=""` + 좌표만으로 경로 요청 가능 | **확인**. 내비가 경로 미리보기 → 5초 카운트다운 후 안내 시작 |
| A4 | 목적지 미설정 시 동작 | **확인**. 빈 이름·0 좌표로 응답 → "목적지 없음". 내비 앱이 준비되기 전에는 응답이 50초 이상 늦을 수 있음(5초 타임아웃으로 처리) |
| A5 | requestRoute 후 onDestinationChanged 재수신 | **오지 않음**. 경로가 바뀌어도 `onDestinationChanged`가 오지 않았다 → `onRouteStarted`/완료 후 목적지를 직접 재조회하도록 수정. 재조회 결과를 "앱이 바꾼 입구"로 인식, 경로 요청은 사용자 선택 1회뿐(반복 없음) |
| A6 | 새 경로 요청 직후 기존 경로 onRouteCancelled 가 먼저 오는지 | **확인**. 요청 0.5초 뒤 기존 경로 `onRouteCancelled` + 빈 목적지가 먼저 오고, 약 7초 뒤 `onRouteStarted`(거리·시간 포함) |
| A7 | 주행 상태에서 CarUxRestrictions 가 DO 필요로 바뀐다 | **확인**. Virtual Console(`adb forward tcp:8088 tcp:8088`)에서 기어 D → `DO=true, active=80`(NO_SETUP·NO_VIDEO), 앱은 한 줄 상태만 표시. P → 해제 |
| 추가 | `onRoutingCompleted` | **이 내비는 보내지 않았다**. 대신 문서에 없는 `onRouteEnded`를 `onRouteStarted` 직후 보냄 → 앱은 `onRouteStarted`를 "안내 시작"이라는 실제 결과로 표시하고 `onRouteEnded`는 기록만 함 |
| 추가 | Activity 재생성 | 시스템 테마 오버레이 변경(`changes=0x10000000`)으로 Activity가 재생성되며 SDK가 해제됨 → `AppSession`으로 연결을 프로세스 범위에 유지하도록 수정 |

**에뮬레이터에서 가상 시설로 시험하는 방법(디버그 빌드 전용)**: 목적지 없음/미등록 화면의 `[개발용] 테스트 시설을 현재 위치 근처로 옮기고 목적지로 설정` 버튼 → 내비 현재 위치(`getCurrentLocationInfo`) 약 1km 북쪽으로 TEST 시설을 옮기고 `requestRoute`로 목적지 설정. release 빌드에는 이 버튼이 없다. 근거 화면·로그: `../docs/emulator/`

## 테스트 환경 기록 (제출 시 채움)
- Pleos Connect 에뮬레이터: **Pleos Connect v2.1.0 APIs (API 34, x86_64)**, 빌드 `RELEASE.release_connect_v2.1.0`, Android Emulator 37.2.13, WHPX 가속, AVD RAM 4GB, Cold boot
- 화면 규격: **2560×1440, 160dpi** (시스템 이미지 devices.xml 및 `wm size` 확인). 앱은 홈 화면 오른쪽 영역(약 1680×1260px)에 표시됨
- APK 위치: `apk/[팀명]_입구까지_release.apk` 또는 GitHub Release 링크
- 최종 commit: `[hash]`
