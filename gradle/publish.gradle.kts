import java.io.File
import org.gradle.api.tasks.bundling.Jar
import org.gradle.plugins.signing.SigningExtension

apply(plugin = "maven-publish")
apply(plugin = "signing")

val pythonExecutable = providers.gradleProperty("pythonExecutable").getOrElse(
    if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3")
val centralPreparation = providers.gradleProperty("meguviz.centralPrepare").orElse("false")
val centralEnabled = when (centralPreparation.get()) {
    "true" -> true
    "false" -> false
    else -> throw GradleException("meguviz.centralPrepare must be true or false")
}
if (centralEnabled) {
    val requestedVersion = providers.gradleProperty("meguviz.releaseVersion").orNull
        ?: throw GradleException("BLOCKED: set an immutable meguviz.releaseVersion for Central preparation")
    val allowedVersion = Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9][A-Za-z0-9.-]*)?")
    if (!allowedVersion.matches(requestedVersion) || requestedVersion.contains("..") ||
        requestedVersion.endsWith("-SNAPSHOT", ignoreCase = true)) {
        throw GradleException("BLOCKED: Central preparation requires a valid immutable non-SNAPSHOT version")
    }
    project.version = requestedVersion
}
val releaseAar = layout.buildDirectory.file("outputs/aar/${project.name}-release.aar")
val apiReferenceDir = layout.buildDirectory.dir("reports/jvm-api-reference")
val generateJvmApiReference = tasks.register<Exec>("generateJvmApiReference") {
    dependsOn("bundleReleaseAar")
    inputs.file(releaseAar)
    inputs.file(rootProject.file("scripts/jvm_api_reference.py"))
    inputs.property("module", project.name)
    inputs.property("publicationVersion", providers.provider { project.version.toString() })
    outputs.dir(apiReferenceDir)
    doFirst {
        val javap = File(System.getProperty("java.home"),
            if (System.getProperty("os.name").startsWith("Windows")) "bin/javap.exe" else "bin/javap")
        commandLine(pythonExecutable, rootProject.file("scripts/jvm_api_reference.py"),
            "--aar", releaseAar.get().asFile, "--module", project.name,
            "--version", project.version.toString(), "--output", apiReferenceDir.get().asFile,
            "--javap", javap)
    }
}
val jvmApiReferenceJar = tasks.register<Jar>("jvmApiReferenceJar") {
    dependsOn(generateJvmApiReference)
    archiveClassifier.set("javadoc")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from(apiReferenceDir) {
        include("index.html", "api-manifest.json")
    }
}

configure<PublishingExtension> {
    repositories { maven { name = "Candidate"; url = uri(rootProject.layout.buildDirectory.dir("repo")) } }
    publications {
        register<MavenPublication>("release") {
            artifactId = "meguviz-${project.name}"
            pom {
                name.set(artifactId)
                description.set("MVX-1 scoped Android lifecycle, resources and scheduling.")
                url.set("https://github.com/jichuo1/MeguvizAndroid")
                licenses { license { name.set("Apache License 2.0"); url.set("https://www.apache.org/licenses/LICENSE-2.0") } }
                developers { developer { id.set("jichuo1"); name.set("jichuo1") } }
                scm {
                    url.set("https://github.com/jichuo1/MeguvizAndroid")
                    connection.set("scm:git:https://github.com/jichuo1/MeguvizAndroid.git")
                    developerConnection.set("scm:git:ssh://git@github.com/jichuo1/MeguvizAndroid.git")
                }
            }
            artifact(jvmApiReferenceJar)
            afterEvaluate { from(components["release"]) }
        }
    }
}

// This task only writes the Candidate repository. It never uploads to Central.
// Signing is wired only when explicitly requested, keeping normal host builds key-free.
tasks.register("prepareCentralLocal") {
    group = "publishing"
    description = "Prepare a signed local candidate; Central upload is separate and unauthorized."
    if (centralEnabled) dependsOn("publishReleasePublicationToCandidateRepository")
    doFirst {
        check(centralEnabled) { "BLOCKED: request -Pmeguviz.centralPrepare=true to prepare signed local artifacts" }
    }
}
if (centralEnabled) {
    extensions.configure<SigningExtension> {
        isRequired = true
        sign(project.extensions.getByType<PublishingExtension>().publications.getByName("release"))
    }
    gradle.taskGraph.whenReady {
        if (project.version.toString().endsWith("-SNAPSHOT", ignoreCase = true)) {
            throw GradleException("BLOCKED: Central preparation requires an immutable non-SNAPSHOT version")
        }
    }
}
