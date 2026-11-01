plugins {
    id(ThunderbirdPlugins.Library.kmp)
}

kotlin {
    android {
        namespace = "org.novabird.feature.vectors"
    }
    sourceSets {
        androidMain.dependencies {
            implementation(novabirdLibs.onnxruntime.android)
        }
    }
}

codeCoverage {
    branchCoverage = 0
    lineCoverage = 0
}

// Tokenizer test reads tokenizer.json of the downloaded model (override with -Pnovabird.model.dir)
val modelDir = providers.gradleProperty("novabird.model.dir")
    .orElse(layout.projectDirectory.dir("../../../../tools/models/jina-de").asFile.absolutePath)
tasks.withType<Test>().configureEach {
    systemProperty("novabird.model.dir", modelDir.get())
}
