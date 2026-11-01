plugins {
    id(ThunderbirdPlugins.Library.kmpCompose)
}

kotlin {
    android {
        namespace = "net.thunderbird.feature.novabird.search.internal"
    }
    sourceSets {
        commonMain.dependencies {
            implementation(projects.feature.novabird.search.api)
            implementation(projects.core.logging.api)
            implementation(libs.koin.core.viewmodel)

            implementation(novabirdLibs.androidx.sqlite)
            implementation(novabirdLibs.androidx.sqlite.bundled)
            implementation(novabirdLibs.quickjs.kt)
        }
        commonTest.dependencies {
            implementation(projects.core.logging.testing)
        }
    }
}

compose.resources {
    publicResClass = false
    packageOfResClass = "net.thunderbird.feature.novabird.search.internal.resources"
}

codeCoverage {
    branchCoverage = 0
    lineCoverage = 0
}

// Golden cases of NovaBird-Search expect Europe/Berlin (QuickJS reads TZ on Linux/macOS; Windows uses the system zone)
tasks.withType<Test>().configureEach {
    environment("TZ", "Europe/Berlin")
}
