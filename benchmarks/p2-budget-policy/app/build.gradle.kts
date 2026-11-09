import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.security.MessageDigest

plugins { id("com.android.application") }
android {
    namespace = "io.github.jichuo1.meguviz.p2budgetpolicy"
    compileSdk = 37
    defaultConfig {
        applicationId = "io.github.jichuo1.meguviz.p2budgetpolicy"
        minSdk = 27; targetSdk = 37; versionCode = 1; versionName = "0.1-probe"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    testBuildType = "release"
    buildTypes { release { isMinifyEnabled = false; isDebuggable = false; signingConfig = signingConfigs.getByName("debug") } }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/probe-assets").get().asFile)
}
dependencies {
    androidTestImplementation(files(rootProject.file("inputs/android-runtime.aar")))
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    // Local AAR file dependencies omit their POM; use the Kotlin version the family is built with.
    androidTestImplementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.21")
}
fun sha(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
fun checkPrepared() {
    val python = providers.environmentVariable("P2_BENCH_PYTHON").orNull ?: error("P2_BENCH_PYTHON required")
    providers.exec { commandLine(python, rootProject.file("prepare.py"), "check") }.result.get().assertNormalExitValue()
}
val prepareProbeIdentity by tasks.registering {
    outputs.upToDateWhen { false }
    doLast {
        checkPrepared()
        check(configurations.getByName("releaseRuntimeClasspath").files.isEmpty()) { "Main app must have no runtime dependencies" }
        val runtime = rootProject.file("inputs/android-runtime.aar")
        check(configurations.getByName("releaseAndroidTestRuntimeClasspath").files.any { it.canonicalFile == runtime.canonicalFile })
        val dependencies = configurations.getByName("releaseAndroidTestRuntimeClasspath").resolvedConfiguration.resolvedArtifacts.map {
            mapOf("group" to it.moduleVersion.id.group, "name" to it.name, "version" to it.moduleVersion.id.version,
                  "sha256" to sha(it.file), "bytes" to it.file.length())
        }.sortedBy { "${it["group"]}:${it["name"]}:${it["version"]}" }
        @Suppress("UNCHECKED_CAST")
        val identity = (JsonSlurper().parse(rootProject.file("inputs/manifest.json")) as Map<String, Any>).toMutableMap()
        identity["test_dependencies"] = dependencies
        val target = layout.buildDirectory.file("generated/probe-assets/identity.json").get().asFile
        target.parentFile.mkdirs(); target.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(identity)))
    }
}
tasks.matching { it.name == "mergeReleaseAssets" }.configureEach { dependsOn(prepareProbeIdentity) }
tasks.register("recordProbeBuild") {
    dependsOn("assembleRelease", "assembleReleaseAndroidTest")
    doLast {
        checkPrepared()
        val app = layout.buildDirectory.file("outputs/apk/release/app-release.apk").get().asFile
        val tests = layout.buildDirectory.file("outputs/apk/androidTest/release/app-release-androidTest.apk").get().asFile
        check(app.isFile && tests.isFile && app.length() > 0 && tests.length() > 0)
        val identity = JsonSlurper().parse(layout.buildDirectory.file("generated/probe-assets/identity.json").get().asFile)
        val result = mapOf("status" to "NOT_RUN", "scope" to "Built instrumentation fixture only; no performance claim",
            "app_apk" to mapOf("path" to app.absolutePath, "sha256" to sha(app)),
            "test_apk" to mapOf("path" to tests.absolutePath, "sha256" to sha(tests)), "identity" to identity)
        rootProject.layout.buildDirectory.file("probe-build-receipt.json").get().asFile.apply {
            parentFile.mkdirs(); writeText(JsonOutput.prettyPrint(JsonOutput.toJson(result)))
        }
    }
}
