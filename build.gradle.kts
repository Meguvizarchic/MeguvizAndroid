plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    // Classpath anchor only: AGP's built-in Kotlin compiles with the KGP version found here instead of its
    // bundled default (2.2.10). Modules do not apply this plugin.
    alias(libs.plugins.kotlin.android) apply false
}
allprojects {
    group = "io.github.jichuo1.meguviz"
    version = "1.2.0"
}
// Host verification used to hard-code `python` and `cmd /c gradlew.bat`, which made verifyHost
// unrunnable on macOS/Linux. Keep the published behaviour unchanged on Windows and let other hosts
// opt into a specific interpreter with -PpythonExecutable=... when `python3` is not on PATH.
val isWindows = System.getProperty("os.name").startsWith("Windows")
val pythonExecutable = providers.gradleProperty("pythonExecutable").getOrElse(if (isWindows) "python" else "python3")
val gradleLauncher = if (isWindows) listOf("cmd", "/c", "gradlew.bat") else listOf("./gradlew")
val beginHostVerification by tasks.registering(Exec::class) {
    commandLine(pythonExecutable, "scripts/verification.py", "start-host")
}
subprojects {
    tasks.configureEach { mustRunAfter(beginHostVerification) }
    tasks.withType<Test>().configureEach {
        failOnNoDiscoveredTests = true
        outputs.upToDateWhen { false }
        maxParallelForks = 1
        systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
        jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED",
            "--add-opens=java.base/java.util=ALL-UNNAMED", "--add-opens=java.base/java.io=ALL-UNNAMED",
            "--add-opens=java.base/java.net=ALL-UNNAMED", "--add-opens=java.base/java.security=ALL-UNNAMED",
            "--add-opens=java.base/java.text=ALL-UNNAMED", "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
            "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED", "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED")
        doFirst {
            if (name == "testDebugUnitTest" && gradle.taskGraph.hasTask(beginHostVerification.get())) {
                providers.exec { commandLine(pythonExecutable, rootProject.file("scripts/verification.py"), "stamp", "--module", project.name, "--task", name) }.result.get().assertNormalExitValue()
            }
        }
        testLogging { events("failed", "skipped") }
    }
    dependencyLocking { lockAllConfigurations() }
    tasks.matching { it.name == "lintReportDebug" || it.name == "publishReleasePublicationToCandidateRepository" }.configureEach {
        outputs.upToDateWhen { false }
        doFirst {
            if (gradle.taskGraph.hasTask(beginHostVerification.get())) {
                providers.exec { commandLine(pythonExecutable, rootProject.file("scripts/verification.py"), "stamp", "--module", project.name, "--task", name) }.result.get().assertNormalExitValue()
            }
        }
    }
}
// android-resources-loader is not consumed by the pinned samples, but its candidate AAR feeds the public-API dump and the
// published-release comparison like the other three.
val candidatePublications = listOf("android-runtime", "android-window", "android-lifecycle", "android-resources-loader")
    .map { ":$it:publishReleasePublicationToCandidateRepository" }
val dumpApi by tasks.registering(Exec::class) {
    dependsOn(candidatePublications, beginHostVerification)
    commandLine(pythonExecutable, "scripts/api_dump.py")
}
val verifyConsumer by tasks.registering(Exec::class) {
    dependsOn(candidatePublications, beginHostVerification)
    doFirst { providers.exec { commandLine(pythonExecutable, "scripts/verification.py", "consumer-start") }.result.get().assertNormalExitValue() }
    commandLine(*(gradleLauncher + listOf("-p", "samples", "recordConsumption", ":plain:assembleDebug", ":embedded:assembleDebug",
        ":plain:assembleDebugAndroidTest", ":embedded:assembleDebugAndroidTest", "--no-daemon", "--console=plain")).toTypedArray())
    doLast { providers.exec { commandLine(pythonExecutable, "scripts/verification.py", "consumer-end") }.result.get().assertNormalExitValue() }
}
tasks.register<Exec>("verifyGateNegatives") {
    dependsOn(beginHostVerification)
    commandLine(pythonExecutable, "scripts/verification.py", "negatives")
}
tasks.register<Exec>("verifyHost") {
    dependsOn(beginHostVerification, dumpApi, verifyConsumer, "verifyGateNegatives")
    dependsOn(listOf("android-runtime", "android-window", "android-lifecycle", "android-resources-loader").flatMap {
        listOf(":$it:testDebugUnitTest", ":$it:lintDebug")
    })
    commandLine(pythonExecutable, "scripts/verification.py", "host")
}
tasks.register<Exec>("verifyDevice") {
    commandLine(pythonExecutable, "scripts/verification.py", "device")
}
tasks.register<Exec>("verifyReleaseCandidate") {
    commandLine(pythonExecutable, "scripts/verification.py", "release", "--profile",
        providers.gradleProperty("meguviz.profile").orElse("full_replacement").get())
}
