plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
}
allprojects {
    group = "io.github.jichuo1.meguviz"
    version = "1.2.0"
}
val beginHostVerification by tasks.registering(Exec::class) {
    commandLine("python", "scripts/verification.py", "start-host")
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
                providers.exec { commandLine("python", rootProject.file("scripts/verification.py"), "stamp", "--module", project.name, "--task", name) }.result.get().assertNormalExitValue()
            }
        }
        testLogging { events("failed", "skipped") }
    }
    dependencyLocking { lockAllConfigurations() }
    tasks.matching { it.name == "lintReportDebug" || it.name == "publishReleasePublicationToCandidateRepository" }.configureEach {
        outputs.upToDateWhen { false }
        doFirst {
            if (gradle.taskGraph.hasTask(beginHostVerification.get())) {
                providers.exec { commandLine("python", rootProject.file("scripts/verification.py"), "stamp", "--module", project.name, "--task", name) }.result.get().assertNormalExitValue()
            }
        }
    }
}
val candidatePublications = listOf("android-runtime", "android-window", "android-lifecycle")
    .map { ":$it:publishReleasePublicationToCandidateRepository" }
val dumpApi by tasks.registering(Exec::class) {
    dependsOn(candidatePublications, beginHostVerification)
    commandLine("python", "scripts/api_dump.py")
}
val verifyConsumer by tasks.registering(Exec::class) {
    dependsOn(candidatePublications, beginHostVerification)
    doFirst { providers.exec { commandLine("python", "scripts/verification.py", "consumer-start") }.result.get().assertNormalExitValue() }
    commandLine("cmd", "/c", "gradlew.bat", "-p", "samples", "recordConsumption", ":plain:assembleDebug", ":embedded:assembleDebug",
        ":plain:assembleDebugAndroidTest", ":embedded:assembleDebugAndroidTest", "--no-daemon", "--console=plain")
    doLast { providers.exec { commandLine("python", "scripts/verification.py", "consumer-end") }.result.get().assertNormalExitValue() }
}
tasks.register<Exec>("verifyGateNegatives") {
    dependsOn(beginHostVerification)
    commandLine("python", "scripts/verification.py", "negatives")
}
tasks.register<Exec>("verifyHost") {
    dependsOn(beginHostVerification, dumpApi, verifyConsumer, "verifyGateNegatives")
    dependsOn(listOf("android-runtime", "android-window", "android-lifecycle", "android-resources-loader").flatMap {
        listOf(":$it:testDebugUnitTest", ":$it:lintDebug")
    })
    commandLine("python", "scripts/verification.py", "host")
}
tasks.register<Exec>("verifyDevice") {
    commandLine("python", "scripts/verification.py", "device")
}
tasks.register<Exec>("verifyReleaseCandidate") {
    commandLine("python", "scripts/verification.py", "release", "--profile",
        providers.gradleProperty("meguviz.profile").orElse("full_replacement").get())
}
