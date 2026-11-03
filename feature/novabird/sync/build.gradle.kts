plugins {
    id(ThunderbirdPlugins.Library.androidCompose)
}

android {
    namespace = "org.novabird.feature.sync"
    resourcePrefix = "novabird_sync_"
}

dependencies {
    implementation(projects.core.android.account)
    implementation(projects.core.logging.api)
    implementation(projects.core.ui.setting.api)
    implementation(projects.core.ui.theme.api)
    implementation(projects.backend.api)
    implementation(projects.legacy.core)
    implementation(projects.legacy.message)
    implementation(projects.legacy.ui.base)

    implementation(libs.androidx.activity.compose)
    implementation(libs.jetbrains.compose.material3)
    implementation(libs.androidx.work.runtime)

    testImplementation(projects.core.logging.testing)
    testImplementation(projects.backend.imap)
    testImplementation(projects.backend.testing)
    testImplementation(projects.legacy.logging)
    testImplementation(projects.mail.testing)
    testImplementation(libs.robolectric)
}

codeCoverage {
    branchCoverage = 0
    lineCoverage = 0
}
