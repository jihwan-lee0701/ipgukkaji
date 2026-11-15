plugins {
    id("com.android.application") version "8.5.2" apply false
    // NaviHelper 3.0.5가 kotlin-stdlib 1.9.25에 의존하므로 같은 계열로 맞춘다.
    id("org.jetbrains.kotlin.android") version "1.9.25" apply false
}

// 선택: Windows 사용자 경로에 한글 등 비ASCII 문자가 있으면 단위 테스트 워커가 클래스를 못 찾는 경우가 있다.
// 그럴 때 ./gradlew -PbuildRoot=C:/devtools/build ... 로 빌드 산출물만 ASCII 경로에 둔다.
providers.gradleProperty("buildRoot").orNull?.let { root ->
    allprojects {
        layout.buildDirectory.set(file("$root/${rootProject.name}/${project.name}"))
    }
}
