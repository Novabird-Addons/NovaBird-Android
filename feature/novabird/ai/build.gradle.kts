plugins {
    id(ThunderbirdPlugins.Library.androidCompose)
}

android {
    namespace = "org.novabird.feature.ai"
    resourcePrefix = "novabird_ai_"
}

dependencies {
    implementation(projects.feature.novabird.search.api)
    implementation(projects.feature.novabird.vectors)
    implementation(projects.core.logging.api)
    implementation(projects.core.ui.theme.api)
    implementation(projects.legacy.ui.base)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.lifecycle.process)

    testImplementation(projects.core.logging.testing)
    testImplementation(libs.robolectric)
}

codeCoverage {
    branchCoverage = 0
    lineCoverage = 0
}
