package org.novabird.feature.vectors

/**
 * Embedding model as in NovaBird Helper `BuiltinModels.cs` and NovaBird-Search `semantic/models.js`
 * (same file, same revision, same vectors). Keep all three in sync.
 */
data class EmbeddingModel(
    val id: String,
    val repo: String,
    val revision: String,
    val dims: Int,
    val maxTokens: Int,
    val files: List<ModelFile>,
) {
    val onnxPath: String get() = files.first { it.path.endsWith(".onnx") }.path
    val totalBytes: Long get() = files.sumOf { it.size }

    fun fileUrl(path: String) = "https://huggingface.co/$repo/resolve/$revision/$path"

    companion object {
        val JINA_DE = EmbeddingModel(
            id = "jina-de",
            repo = "jinaai/jina-embeddings-v2-base-de",
            revision = "3f9eede875721714945b6a99a3198299243cf2be",
            dims = 768,
            maxTokens = 512,
            files = listOf(
                ModelFile(
                    "config.json",
                    1177,
                    sha256 = "8d4c498741937d1467af5be739dc0fbcc4d033eb1fb93ece4f1c9c6bbdd37ec5",
                ),
                ModelFile(
                    "tokenizer.json",
                    2633554,
                    sha256 = "376c53a06d6911569afc19f584002a11dbcd6b871afb6d96b8600eb3c4e7e7a7",
                ),
                ModelFile(
                    "onnx/model_fp16.onnx",
                    320852338,
                    sha256 = "e039ce538df36cb7ecee6bb88aacf0699227277be769a8e9adb5da719d23c571",
                ),
            ),
        )
    }
}

data class ModelFile(val path: String, val size: Long, val sha256: String)
