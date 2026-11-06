plugins { id("com.android.application") }
android {
    namespace = "io.github.jichuo1.meguviz.samples.embedded"
    compileSdk = 37
    defaultConfig {
        applicationId = "io.github.jichuo1.meguviz.samples.embedded"
        minSdk = 27; targetSdk = 29; versionCode = 1; versionName = "0.1-development"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildTypes { release { isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt")) } }
    // Deliberate legacy-host compatibility fixture, never distributed through Google Play.
    lint { disable += "ExpiredTargetSdkVersion" }
    sourceSets["androidTest"].java.srcDir("../plain/src/androidTest/java")
    sourceSets["debug"].java.srcDir("../plain/src/debug/java")
}
dependencies {
    implementation("io.github.jichuo1.meguviz:meguviz-android-window:1.2.0")
    debugImplementation("io.github.jichuo1.meguviz:meguviz-android-lifecycle:1.2.0")
    debugImplementation("androidx.fragment:fragment:1.9.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
