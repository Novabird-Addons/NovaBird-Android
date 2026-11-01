package org.novabird.feature.ai

import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import org.novabird.feature.ai.helper.HelperKeyVault
import org.novabird.feature.ai.local.LocalEmbedder
import org.novabird.feature.ai.local.ModelDownloads
import org.novabird.feature.ai.local.ModelFiles
import org.novabird.feature.ai.settings.AiSettingsRepository
import org.novabird.feature.ai.settings.SharedPreferencesAiSettingsRepository
import org.novabird.feature.ai.ui.AiSettingsViewModel
import org.novabird.feature.search.NovaBirdVectorBackend

/** Semantic search backends (NovaBird Helper, model on the phone) and their settings screen. */
val novaBirdAiModule = module {
    single<AiSettingsRepository> { SharedPreferencesAiSettingsRepository(androidContext()) }
    single { HelperKeyVault(androidContext()) }
    single { ModelFiles(androidContext()) }
    single { ModelDownloads(androidContext()) }
    single { LocalEmbedder(files = get(), settings = get(), logger = get()) }
    single { AiVectorBackend(settings = get(), keyVault = get(), local = get(), logger = get()) }
    single<NovaBirdVectorBackend> { get<AiVectorBackend>() }
    viewModel {
        AiSettingsViewModel(
            repository = get(),
            keyVault = get(),
            backend = get(),
            files = get(),
            downloads = get(),
            local = get(),
            vectorIndexer = get(),
            indexTrigger = get(),
        )
    }
}
