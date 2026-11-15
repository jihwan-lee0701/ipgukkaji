import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 카카오 로컬 API REST 키 (local.properties: kakao.rest.api.key). 저장소에 넣지 않는다.
val kakaoKey: String = run {
    val props = Properties()
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { props.load(it) }
    (props.getProperty("kakao.rest.api.key") ?: "").replace("\"", "")
}

android {
    namespace = "io.ipgukkaji.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.ipgukkaji.app"
        // Pleos Connect 요구사항: API 28 이상 (개발 환경 설정 가이드)
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        // 카카오 로컬 API REST 키: 저장소에 넣지 않고 local.properties 의 kakao.rest.api.key 에서 읽는다.
        // 키가 없으면 빈 문자열 → 앱은 "주변 주차장 찾기" 기능을 숨긴다.
        buildConfigField("String", "KAKAO_REST_API_KEY", "\"$kakaoKey\"")
    }

    // AAOS 표준 CarUxRestrictionsManager 사용 (주행 규제 개발 가이드)
    useLibrary("android.car")

    buildTypes {
        release {
            isMinifyEnabled = false
            // TODO(제출 전): 공모전 시연용 release APK는 별도 키스토어로 서명한다.
            //   키스토어 파일과 비밀번호는 저장소에 올리지 않는다.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true // 개발용 도구를 디버그 빌드에만 노출
    }
    lint {
        abortOnError = false
    }
}

dependencies {
    // NaviHelper SDK v3.0.5 (ai.pleos.playground:NaviHelper:3.0.5)
    implementation("ai.pleos.playground:NaviHelper:3.0.5")

    testImplementation("junit:junit:4.13.2")
    // 단위 테스트에서 assets/entrances.json 을 실제 org.json 구현으로 파싱하기 위함 (Android 스텁 대체)
    testImplementation("org.json:json:20231013")
}
