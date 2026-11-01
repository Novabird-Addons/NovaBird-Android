package org.novabird.feature.vectors

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import java.io.File
import java.nio.LongBuffer
import java.util.EnumSet

/**
 * Where ONNX Runtime computes on the phone. NNAPI hands supported parts to the vendor driver (GPU/NPU),
 * WebGPU runs on the GPU via Vulkan.
 */
enum class EmbedDevice { CPU, XNNPACK, NNAPI, NNAPI_NO_CPU, WEBGPU }

/**
 * Vectors with ONNX Runtime like NovaBird Helper `Engine.cs`: fp16 model, mean pooling, length 1.
 * [fixedLength] pads every input to one shape (needed by NNAPI, which can't take dynamic shapes).
 */
class OnnxEmbedder(
    modelFile: File,
    val device: EmbedDevice,
    threads: Int,
    private val fixedLength: Int? = null,
    private val dims: Int = EmbeddingModel.JINA_DE.dims,
) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        val options = OrtSession.SessionOptions().apply {
            // fp16 model: higher optimization levels fail (SimplifiedLayerNormFusion), as in the Helper
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            setIntraOpNumThreads(threads)
            fixedLength?.let {
                setSymbolicDimensionValue("batch", 1)
                setSymbolicDimensionValue("sequence", it.toLong())
            }
            when (device) {
                EmbedDevice.CPU -> Unit
                EmbedDevice.XNNPACK -> addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
                EmbedDevice.NNAPI -> addNnapi(EnumSet.of(NNAPIFlags.USE_FP16))
                EmbedDevice.NNAPI_NO_CPU -> addNnapi(EnumSet.of(NNAPIFlags.USE_FP16, NNAPIFlags.CPU_DISABLED))
                EmbedDevice.WEBGPU -> addWebGPU(emptyMap())
            }
        }
        session = options.use { env.createSession(modelFile.path, it) }
    }

    fun embed(ids: LongArray): FloatArray {
        val len = fixedLength ?: ids.size
        require(ids.size <= len) { "input longer than fixed length" }
        val idBuf = LongBuffer.allocate(len)
        val maskBuf = LongBuffer.allocate(len)
        for (i in ids.indices) {
            idBuf.put(i, ids[i])
            maskBuf.put(i, 1)
        }
        val shape = longArrayOf(1, len.toLong())
        OnnxTensor.createTensor(env, idBuf, shape).use { idT ->
            OnnxTensor.createTensor(env, maskBuf, shape).use { maskT ->
                session.run(mapOf("input_ids" to idT, "attention_mask" to maskT)).use { result ->
                    val out = (result[0] as OnnxTensor).floatBuffer
                    val hidden = FloatArray(ids.size * dims)
                    out.get(hidden)
                    return meanPoolNormalized(hidden, 0, ids.size, dims)
                }
            }
        }
    }

    override fun close() = session.close()
}
