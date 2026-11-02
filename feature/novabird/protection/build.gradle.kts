plugins {
    id(ThunderbirdPlugins.Library.kmpCompose)
}

kotlin {
    android {
        namespace = "org.novabird.feature.protection"
    }
    sourceSets {
        commonMain.dependencies {
            implementation(novabirdLibs.quickjs.kt)
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
