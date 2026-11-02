plugins {
    id(ThunderbirdPlugins.Library.kmpCompose)
}

kotlin {
    android {
        namespace = "org.novabird.feature.search.internal"
    }
    sourceSets {
        commonMain.dependencies {
            implementation(projects.feature.novabird.search.api)
            implementation(projects.feature.novabird.vectors)
            implementation(projects.core.logging.api)
            implementation(projects.core.ui.contract)
            implementation(libs.koin.compose.viewmodel)
            implementation(libs.koin.core.viewmodel)

            implementation(novabirdLibs.androidx.sqlite)
            implementation(novabirdLibs.androidx.sqlite.bundled)
            implementation(novabirdLibs.quickjs.kt)
        }
        androidMain.dependencies {
            implementation(libs.androidx.work.runtime)
            implementation(libs.koin.android)
        }
        commonTest.dependencies {
            implementation(projects.core.logging.testing)
        }
    }
}

compose.resources {
    publicResClass = false
    packageOfResClass = "org.novabird.feature.search.internal.resources"
}

codeCoverage {
    branchCoverage = 0
    lineCoverage = 0
}

// Golden cases of NovaBird-Search expect Europe/Berlin (QuickJS reads TZ on Linux/macOS; Windows uses the system zone)
tasks.withType<Test>().configureEach {
    environment("TZ", "Europe/Berlin")
}
