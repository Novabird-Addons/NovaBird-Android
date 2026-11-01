package org.novabird.feature.vectors

import kotlin.math.sqrt

/** Mean pooling over the first [tokens] rows of [hidden] (row-major, [dims] wide), then normalized to length 1. */
fun meanPoolNormalized(hidden: FloatArray, offset: Int, tokens: Int, dims: Int): FloatArray {
    val v = FloatArray(dims)
    for (j in 0 until tokens) {
        val base = offset + j * dims
        for (d in 0 until dims) v[d] += hidden[base + d]
    }
    var sum = 0.0
    for (x in v) sum += x.toDouble() * x
    val norm = sqrt(sum)
    if (norm > 0) for (d in 0 until dims) v[d] = (v[d] / norm).toFloat()
    return v
}

/** Cosine of two normalized vectors (dot product). */
fun cosine(a: FloatArray, b: FloatArray): Double {
    var s = 0.0
    for (i in a.indices) s += a[i].toDouble() * b[i]
    return s
}
