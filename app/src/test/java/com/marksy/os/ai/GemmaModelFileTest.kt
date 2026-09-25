package com.marksy.os.ai

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

class GemmaModelFileTest {
    private val tmp = TemporaryFolder().apply { create() }

    private fun tarEntry(name: String, data: ByteArray): ByteArray {
        val header = ByteArray(512)
        name.toByteArray().copyInto(header)
        "%011o".format(data.size).toByteArray().copyInto(header, 124)
        header[156] = '0'.code.toByte()
        val padded = ByteArray((data.size + 511) / 512 * 512)
        data.copyInto(padded)
        return header + padded
    }

    // Kaggle ships the model as a .tar.gz holding both a .tflite and the .task MediaPipe needs.
    @Test fun extractsOnlyTheTaskFromAKaggleTarGzAndCopiesABareTask() {
        val task = ByteArray(1500) { (it % 251).toByte() }
        val tar = tarEntry("gemma3-1B-it-int4.tflite", ByteArray(700) { 7 }) + tarEntry("gemma3-1B-it-int4.task", task) + ByteArray(1024)
        val gz = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(tar) } }.toByteArray()

        val fromArchive = File(tmp.root, "a.task")
        GemmaModelFile.extractTask(gz.inputStream(), fromArchive) {}
        assertArrayEquals(task, fromArchive.readBytes())

        val bare = File(tmp.root, "b.task")
        GemmaModelFile.extractTask(task.inputStream(), bare) {}
        assertArrayEquals(task, bare.readBytes())

        val none = File(tmp.root, "c.task")
        val noTask = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(tarEntry("x.tflite", ByteArray(10)) + ByteArray(1024)) } }
        runCatching { GemmaModelFile.extractTask(noTask.toByteArray().inputStream(), none) {} }
        assertFalse(none.exists())
    }
}
