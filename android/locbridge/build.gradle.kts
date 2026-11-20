plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.noreal.locbridge"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.noreal.locbridge"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

// Xposed 经典 API 的编译期占位：用本机 JDK 把 src/stub/java 编译成 jar，
// 只在编译期可见，运行期由 LSPosed / LSPatch 的框架实现接管。
val stubClasses = layout.buildDirectory.dir("stub/classes")
val stubSources = fileTree("src/stub/java") { include("**/*.java") }

val compileStub by tasks.registering {
    val compiler = javaToolchains.compilerFor { languageVersion.set(JavaLanguageVersion.of(17)) }
    inputs.files(stubSources)
    outputs.dir(stubClasses)
    doLast {
        val out = stubClasses.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        val sources = stubSources.files.map { it.absolutePath }
        project.exec {
            commandLine(
                listOf(
                    compiler.get().executablePath.asFile.absolutePath,
                    "-d",
                    out.absolutePath
                ) + sources
            )
        }
    }
}

val stubJar by tasks.registering(Jar::class) {
    dependsOn(compileStub)
    from(stubClasses)
    archiveFileName.set("xposed-api-stub.jar")
    destinationDirectory.set(layout.buildDirectory.dir("stub"))
}

dependencies {
    compileOnly(files(stubJar))
}

tasks.named("preBuild") {
    dependsOn(stubJar)
}
