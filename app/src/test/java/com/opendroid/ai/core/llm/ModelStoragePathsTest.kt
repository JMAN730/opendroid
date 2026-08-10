package com.opendroid.ai.core.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ModelStoragePathsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun gemmaE2BSpec() = OnDeviceModelSpec(
        id = "gemma-4-e2b-it-litert",
        displayName = "Gemma 4 E2B-it (LiteRT)",
        family = "Gemma 4",
        sizeLabel = "2B",
        backend = OnDeviceBackend.LITERT_LM,
        modelPath = "litert-community/gemma-4-E2B-it-litert-lm",
        modelFilename = "gemma-4-E2B-it.litertlm",
        expectedSize = 2588147712L
    )

    private fun qwenSpec() = OnDeviceModelSpec(
        id = "qwen25-05b-instruct-litert",
        displayName = "Qwen 2.5 0.5B",
        family = "Qwen",
        sizeLabel = "0.5B",
        backend = OnDeviceBackend.LITERT_LM,
        modelFilename = "Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"
    )

    @Test
    fun `folderName maps known Gemma ids`() {
        assertEquals("Gemma4-E2B", ModelStoragePaths.folderName("gemma-4-e2b-it-litert"))
        assertEquals("Gemma4-E4B", ModelStoragePaths.folderName("gemma-4-e4b-it-litert"))
        assertEquals("Gemma3n-E2B", ModelStoragePaths.folderName("gemma-3n-e2b-it-litert"))
        assertEquals("Gemma3n-E4B", ModelStoragePaths.folderName("gemma-3n-e4b-it-litert"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `modelDir rejects traversal in an untrusted model id`() {
        ModelStoragePaths.modelDir(tempFolder.root, "../../outside")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `targetFile rejects a registry filename with a path separator`() {
        val unsafeSpec = gemmaE2BSpec().copy(modelFilename = "../outside.litertlm")
        ModelStoragePaths.targetFile(tempFolder.root, unsafeSpec)
    }

    @Test
    fun `targetFile uses registry modelFilename for litertlm`() {
        val dir = tempFolder.newFolder("Gemma4-E2B")
        val target = ModelStoragePaths.targetFile(dir, gemmaE2BSpec())
        assertEquals("gemma-4-E2B-it.litertlm", target.name)
        assertEquals(dir, target.parentFile)
    }

    @Test
    fun `targetFile uses registry modelFilename for task`() {
        val dir = tempFolder.newFolder("Qwen")
        val target = ModelStoragePaths.targetFile(dir, qwenSpec())
        assertEquals(
            "Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            target.name
        )
    }

    @Test
    fun `resolveExistingFile prefers litertlm over legacy model_task`() {
        val dir = tempFolder.newFolder("Gemma4-E2B")
        val litertlm = File(dir, "gemma-4-E2B-it.litertlm")
        litertlm.writeBytes(ByteArray(64) { 1 })
        File(dir, ModelStoragePaths.LEGACY_TASK_FILENAME).writeBytes(ByteArray(32) { 2 })

        val resolved = ModelStoragePaths.resolveExistingFile(dir, gemmaE2BSpec())
        assertNotNull(resolved)
        assertEquals(litertlm.absolutePath, resolved!!.absolutePath)
    }

    @Test
    fun `resolveExistingFile falls back to legacy model_task`() {
        val dir = tempFolder.newFolder("Gemma4-E2B-legacy")
        val legacy = File(dir, ModelStoragePaths.LEGACY_TASK_FILENAME)
        legacy.writeBytes(ByteArray(48) { 3 })

        val resolved = ModelStoragePaths.resolveExistingFile(dir, gemmaE2BSpec())
        assertNotNull(resolved)
        assertEquals(legacy.absolutePath, resolved!!.absolutePath)
    }

    @Test
    fun `resolveExistingFile returns null when nothing present`() {
        val dir = tempFolder.newFolder("empty")
        assertNull(ModelStoragePaths.resolveExistingFile(dir, gemmaE2BSpec()))
    }

    @Test
    fun `manifestFile is colocated with the model artifact`() {
        val dir = tempFolder.newFolder("size-check")
        assertEquals(File(dir, "manifest.json"), ModelStoragePaths.manifestFile(dir))
    }

    @Test
    fun `folderName preserves custom model ids`() {
        assertEquals(
            "custom-my-model-abcd1234",
            ModelStoragePaths.folderName("custom-my-model-abcd1234")
        )
    }

    @Test
    fun `sanitizeImportFilename accepts litertlm and task`() {
        assertEquals(
            "gemma-4-E2B-it.litertlm",
            ModelStoragePaths.sanitizeImportFilename("gemma-4-E2B-it.litertlm")
        )
        assertEquals(
            "Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            ModelStoragePaths.sanitizeImportFilename("Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task")
        )
    }

    @Test
    fun `sanitizeImportFilename rejects gguf and other extensions`() {
        assertNull(ModelStoragePaths.sanitizeImportFilename("model.gguf"))
        assertNull(ModelStoragePaths.sanitizeImportFilename("weights.bin"))
        assertTrue(ModelStoragePaths.isLikelyUnsupportedGguf("llama-7b-q4.gguf"))
    }

    @Test
    fun `sanitizeImportFilename strips path and unsafe characters`() {
        assertEquals(
            "my_model.litertlm",
            ModelStoragePaths.sanitizeImportFilename("Download/my model.litertlm")
        )
    }

    @Test
    fun `ImportLocalModelResult Failure carries reason for UI`() {
        val failure: ImportLocalModelResult = ImportLocalModelResult.Failure("Could not open the selected file.")
        assertTrue(failure is ImportLocalModelResult.Failure)
        assertEquals("Could not open the selected file.", (failure as ImportLocalModelResult.Failure).reason)

        val success: ImportLocalModelResult = ImportLocalModelResult.Success
        assertTrue(success is ImportLocalModelResult.Success)
    }
}
