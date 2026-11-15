# 입구까지

차량 내비의 목적지를 이어받아 방문 목적·이용 조건·비용에 맞는 시설 입구를 고르고, 고른 입구로 차량 내비 경로를 바로 바꾸는 Pleos Connect 차량용 앱 (대형병원 우선, 공항·쇼핑몰 확장)

## APK

- 위치: [`apk/입구까지_입구까지_release.apk`](apk/입구까지_입구까지_release.apk)
- release 빌드 (versionName 0.1.0). 서명은 디버그 키
- release 빌드에서는 테스트용 가상 시설 제외, 실제 데이터(분당서울대학교병원 입구 2곳)만 포함
- 카카오 REST API 키가 없는 빌드이므로 "주변 주차장 찾기" 기능은 숨겨짐

## 실행 방법

1. Pleos Connect 에뮬레이터 실행 (아래 테스트 환경 참고)
2. APK 설치: `adb install apk/입구까지_입구까지_release.apk` (권한은 설치 시 자동 허용)
3. 차량 내비에서 "분당서울대학교병원"을 목적지로 설정 (정차 상태)
4. '입구까지' 실행 → 내비 목적지와 등록 입구 2곳 표시
5. "외래 진료·검사" 선택 → "정문 방면 지하주차장 입구" → "이 입구로 안내" → 확인
6. 차량 내비 경로가 선택한 입구로 바뀌고 안내 시작
7. 주행(D단) 전환 시 앱이 한 줄 안내 화면으로 전환
   - 주행 전환: `adb forward tcp:8088 tcp:8088` → 브라우저 `http://127.0.0.1:8088/` (Virtual Console) → Drive / Park

## 테스트 환경 및 필요한 설정

- Pleos Connect 에뮬레이터: **Pleos Connect v2.1.0** (시스템 이미지 `system-images;android-34;pleos_car_emulator;x86_64`, API 34)
  - SDK Update Site: `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml`
  - AVD: 기기 Pleos Connect v2.1.0, RAM 4GB, Cold boot
  - 빌드 `RELEASE.release_connect_v2.1.0`, Android Emulator 37.2.13
- 화면 규격: **2560×1440, 160dpi**. 앱은 홈 화면 오른쪽 영역(약 1680×1260px)에 표시
- 사용 SDK: NaviHelper SDK v3.0.5 (`ai.pleos.playground:NaviHelper:3.0.5`)
- 권한: `pleos.car.permission.NAVI_ROUTE`, `pleos.car.permission.NAVI_ROUTE_SEARCH`, `android.permission.INTERNET`

## 소스에서 빌드

- 소스: [`app-android/`](app-android/) (Kotlin, minSdk 28, compileSdk 34, JDK 17, Gradle 8.7 래퍼 포함)
- `app-android/local.properties` 새로 작성: `sdk.dir=<Android SDK 경로>` (카카오 기능 사용 시 `kakao.rest.api.key=<REST 키>` 추가, 저장소에는 넣지 않음)
- 빌드·테스트: `./gradlew testDebugUnitTest assembleRelease` (단위 테스트 49개)
- 상세 개발 기록: [`app-android/README.md`](app-android/README.md) (2026-11-14 기준 기록 포함)
