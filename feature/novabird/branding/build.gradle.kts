plugins {
    id(ThunderbirdPlugins.Library.kmpCompose)
}

kotlin {
    android {
        namespace = "org.novabird.feature.branding"
    }
}

compose.resources {
    publicResClass = false
    packageOfResClass = "org.novabird.feature.branding.resources"
}

codeCoverage {
    branchCoverage = 0
    lineCoverage = 0
}
