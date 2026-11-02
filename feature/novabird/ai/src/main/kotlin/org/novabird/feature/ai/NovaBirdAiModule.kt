package org.novabird.feature.ai

import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import org.novabird.feature.ai.assist.AiAssistant
import org.novabird.feature.ai.assist.DigestCache
import org.novabird.feature.ai.helper.HelperAbsence
import org.novabird.feature.ai.helper.HelperKeyVault
import org.novabird.feature.ai.local.LocalEmbedder
import org.novabird.feature.ai.local.ModelDownloads
import org.novabird.feature.ai.local.ModelFiles
import org.novabird.feature.ai.settings.AiSettingsRepository
import org.novabird.feature.ai.settings.SharedPreferencesAiSettingsRepository
import org.novabird.feature.ai.speech.SpeechPlayer
import org.novabird.feature.ai.ui.AiMailViewModel
import org.novabird.feature.ai.ui.AiNewsViewModel
import org.novabird.feature.ai.ui.AiSettingsViewModel
import org.novabird.feature.search.NovaBirdVectorBackend

/**
 * Semantic search backends (NovaBird Helper, model on the phone), AI summary, "Was gibt's Neues" and reading aloud, and
 * their screens. The app binds an [AiMessageOpener].
 */
val novaBirdAiModule = module {
    single<AiSettingsRepository> { SharedPreferencesAiSettingsRepository(androidContext()) }
    single { HelperKeyVault(androidContext()) }
    single { ModelFiles(androidContext()) }
    single { ModelDownloads(androidContext()) }
    single { LocalEmbedder(files = get(), settings = get(), logger = get()) }
    single { HelperAbsence(androidContext()) }
    single {
        AiVectorBackend(settings = get(), keyVault = get(), local = get(), absence = get(), logger = get())
    }
    single<NovaBirdVectorBackend> { get<AiVectorBackend>() }
    single { DigestCache(androidContext()) }
    single { AiAssistant(backend = get(), settings = get(), mailTexts = get(), cache = get()) }
    single { SpeechPlayer(context = androidContext(), backend = get(), settings = get(), logger = get()) }
    viewModel { params -> AiMailViewModel(location = params.get(), assistant = get(), speech = get()) }
    viewModel { AiNewsViewModel(assistant = get(), settings = get(), speech = get()) }
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
