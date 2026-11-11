plugins {
    id("java")
    kotlin("jvm") version "2.2.20"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

val localIde: File = providers.gradleProperty("idePath").map { file(it) }.orNull
    ?: layout.projectDirectory.dir(".ide/idea").asFile

dependencies {
    intellijPlatform {
        if (localIde.isDirectory) {
            local(localIde)
        } else {
            intellijIdeaCommunity(providers.gradleProperty("platformVersion"))
        }
        bundledPlugin("Git4Idea")
    }
}

kotlin {
    jvmToolchain(17)
}

intellijPlatform {
    buildSearchableOptions = false
    instrumentCode.set(false)

    pluginConfiguration {
        ideaVersion {
            sinceBuild = "233"
            untilBuild = provider { null }
        }
    }
}
