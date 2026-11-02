plugins {
    id(ThunderbirdPlugins.Library.kmpCompose)
}

kotlin {
    android {
        namespace = "org.novabird.feature.protection"
    }
    sourceSets {
        commonMain.dependencies {
            api(projects.feature.novabird.search.api)
            implementation(novabirdLibs.quickjs.kt)
            implementation(libs.koin.compose.viewmodel)
            implementation(libs.koin.core.viewmodel)
            implementation(novabirdLibs.androidx.sqlite)
            implementation(novabirdLibs.androidx.sqlite.bundled)
        }
        androidMain.dependencies {
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.work.runtime)
            implementation(libs.koin.android)
        }
    }
}

compose.resources {
    publicResClass = false
    packageOfResClass = "org.novabird.feature.protection.resources"
}

codeCoverage {
    branchCoverage = 0
    lineCoverage = 0
}
