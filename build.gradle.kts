plugins {
    id("java")
    id("org.jetbrains.intellij.platform")
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity(providers.gradleProperty("platformVersion").get())
        instrumentationTools()
        pluginVerifier()
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks {
    patchPluginXml {
        sinceBuild.set("241")
        untilBuild.set("262.*")
    }

    buildSearchableOptions {
        enabled = false
    }

    jar {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
}

intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        ideaVersion {
            sinceBuild = "241"
            untilBuild = "262.*"
        }
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}
