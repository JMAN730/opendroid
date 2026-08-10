package com.opendroid.ai.core.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class ModelArtifactIntegrityTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val helloSha256 =
        "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
    private val testRevision = "0123456789abcdef0123456789abcdef01234567"

    private fun managedSpec(
        sha256: String? = helloSha256,
        expectedSize: Long? = 5L
    ) = OnDeviceModelSpec(
        id = "test-model",
        displayName = "Test model",
        family = "Test",
        sizeLabel = "Test",
        backend = OnDeviceBackend.LITERT_LM,
        modelFilename = "model.task",
        managedArtifact = ManagedModelArtifactMetadata(
            downloadUrl = "https://models.example.test/test-model/resolve/$testRevision/model.task",
            sourceRevision = testRevision,
            expectedSize = expectedSize,
            sha256 = sha256
        )
    )

    @Test
    fun `managed downloads require complete publisher metadata`() {
        val missingHash = managedSpec(sha256 = null)

        assertFalse(missingHash.isManagedDownloadAvailable)
        assertFalse(missingHash.managedArtifact!!.isComplete)
    }

    @Test
    fun `incomplete metadata cannot authorize a managed artifact`() {
        val spec = managedSpec(sha256 = null)
        val payload = File(tempFolder.root, spec.modelFilename)
        payload.writeText("hello")

        assertNull(ModelArtifactManifest.forManagedDownload(spec))
        assertEquals(
            ArtifactVerificationResult.Invalid(ArtifactVerificationFailure.METADATA_UNAVAILABLE),
            ModelArtifactVerifier().verifyManagedPayload(payload, spec)
        )
    }

    @Test
    fun `Gemma managed downloads are available with their recorded SHA pins`() {
        listOf(
            "gemma-3n-e2b-it-litert",
            "gemma-3n-e4b-it-litert",
            "gemma-4-e2b-it-litert",
            "gemma-4-e4b-it-litert"
        ).forEach { id ->
            val gemma = requireNotNull(OnDeviceModelRegistry.findById(id))
            val artifact = requireNotNull(gemma.managedArtifact)

            assertTrue(gemma.isManagedDownloadAvailable)
            assertTrue(artifact.isComplete)
            assertNotNull(ModelArtifactManifest.forManagedDownload(gemma))
            assertTrue(requireNotNull(artifact.downloadUrl).contains("/${artifact.sourceRevision}/"))
        }
    }

    @Test
    fun `Qwen managed download is pinned to an immutable publisher revision`() {
        val qwen = requireNotNull(
            OnDeviceModelRegistry.findById("qwen-2.5-0.5b-it-litert")
        )
        val artifact = requireNotNull(qwen.managedArtifact)

        assertTrue(qwen.isManagedDownloadAvailable)
        assertTrue(artifact.isComplete)
        assertEquals("6c237a59eedeb06a821b21f0a59b03d346ac8bc3", artifact.sourceRevision)
        assertTrue(requireNotNull(artifact.downloadUrl).contains("/${artifact.sourceRevision}/"))
    }

    @Test
    fun `Gemma 4 entries pin the exact published artifact sizes`() {
        val sizes = mapOf(
            "gemma-4-e2b-it-litert" to 2588147712L,
            "gemma-4-e4b-it-litert" to 3659530240L
        )

        sizes.forEach { (id, expectedSize) ->
            val spec = requireNotNull(OnDeviceModelRegistry.findById(id))

            assertEquals(expectedSize, spec.expectedSize)
            assertEquals(expectedSize, requireNotNull(spec.managedArtifact).expectedSize)
        }
    }

    @Test
    fun `no catalog LiteRT entry advertises a size without a publisher hash`() {
        OnDeviceModelRegistry.liteRTOnly.forEach { spec ->
            if (spec.expectedSize > 0L) {
                assertTrue(
                    "${spec.id} carries a size but no complete publisher record",
                    spec.isManagedDownloadAvailable
                )
            }
        }
    }

    @Test
    fun `manifest round trip preserves trusted managed metadata`() {
        val spec = managedSpec()
        val manifest = ModelArtifactManifest.forManagedDownload(spec)!!
        val manifestFile = File(tempFolder.root, "manifest.json")
        val store = ModelArtifactManifestStore()

        store.writeAtomically(manifestFile, manifest)

        assertEquals(manifest, store.read(manifestFile))
    }

    @Test
    fun `startup verification rejects a truncated managed artifact`() {
        val spec = managedSpec()
        val modelFile = File(tempFolder.root, spec.modelFilename)
        modelFile.writeText("hell")
        val manifestFile = File(tempFolder.root, "manifest.json")
        ModelArtifactManifestStore().writeAtomically(
            manifestFile,
            ModelArtifactManifest.forManagedDownload(spec)!!
        )

        val result = ModelArtifactVerifier().verifyForStartup(modelFile, manifestFile, spec)

        assertEquals(
            ArtifactVerificationResult.Invalid(ArtifactVerificationFailure.SIZE_MISMATCH),
            result
        )
    }

    @Test
    fun `native-load verification rejects same-size hash corruption`() {
        val spec = managedSpec()
        val modelFile = File(tempFolder.root, spec.modelFilename)
        modelFile.writeText("world")
        val manifestFile = File(tempFolder.root, "manifest.json")
        ModelArtifactManifestStore().writeAtomically(
            manifestFile,
            ModelArtifactManifest.forManagedDownload(spec)!!
        )

        val result = ModelArtifactVerifier().verifyBeforeNativeLoad(modelFile, manifestFile, spec)

        assertEquals(
            ArtifactVerificationResult.Invalid(ArtifactVerificationFailure.HASH_MISMATCH),
            result
        )
    }

    @Test
    fun `managed manifest cannot replace registry-owned source metadata`() {
        val spec = managedSpec()
        val modelFile = File(tempFolder.root, spec.modelFilename)
        modelFile.writeText("hello")
        val manifestFile = File(tempFolder.root, "manifest.json")
        val substitutedManifest = ModelArtifactManifest.forManagedDownload(spec)!!.copy(
            downloadUrl = "https://mirror.example.test/test-model/resolve/$testRevision/model.task"
        )
        ModelArtifactManifestStore().writeAtomically(manifestFile, substitutedManifest)

        assertEquals(
            ArtifactVerificationResult.Invalid(ArtifactVerificationFailure.MANIFEST_MISMATCH),
            ModelArtifactVerifier().verifyBeforeNativeLoad(modelFile, manifestFile, spec)
        )
    }

    @Test
    fun `local import manifest detects later content changes`() {
        val spec = OnDeviceModelSpec(
            id = "local-model",
            displayName = "Local model",
            family = "Test",
            sizeLabel = "Test",
            backend = OnDeviceBackend.LITERT_LM,
            modelFilename = "local.task"
        )
        val modelFile = File(tempFolder.root, spec.modelFilename)
        modelFile.writeText("local")
        val manifestFile = File(tempFolder.root, "manifest.json")
        val verifier = ModelArtifactVerifier()
        val manifest = verifier.createLocalImportManifest(spec, modelFile)
        ModelArtifactManifestStore().writeAtomically(manifestFile, manifest)

        assertEquals(
            ArtifactVerificationResult.Valid,
            verifier.verifyBeforeNativeLoad(modelFile, manifestFile, spec)
        )

        modelFile.writeText("alter")

        assertEquals(
            ArtifactVerificationResult.Invalid(ArtifactVerificationFailure.HASH_MISMATCH),
            verifier.verifyBeforeNativeLoad(modelFile, manifestFile, spec)
        )
    }

    @Test
    fun `managed install preserves an existing artifact when verification fails`() {
        val spec = managedSpec()
        val source = File(tempFolder.root, "download.tmp")
        source.writeText("world")
        val target = File(tempFolder.root, spec.modelFilename)
        target.writeText("older")
        val manifestFile = File(tempFolder.root, "manifest.json")

        val result = ModelArtifactInstaller().installManagedDownload(
            source = source,
            target = target,
            manifestFile = manifestFile,
            spec = spec,
            verifyFormat = {}
        )

        assertTrue(result is ArtifactVerificationResult.Invalid)
        assertEquals("older", target.readText())
        assertFalse(manifestFile.exists())
        assertFalse(tempFolder.root.listFiles().orEmpty().any { it.name.endsWith(".installing") })
    }

    @Test
    fun `managed install preserves an existing artifact when LiteRT validation fails`() {
        val spec = managedSpec()
        val source = File(tempFolder.root, "download.tmp")
        source.writeText("hello")
        val target = File(tempFolder.root, spec.modelFilename)
        target.writeText("older")
        val manifestFile = File(tempFolder.root, "manifest.json")

        val result = ModelArtifactInstaller().installManagedDownload(
            source = source,
            target = target,
            manifestFile = manifestFile,
            spec = spec,
            verifyFormat = { throw IllegalArgumentException("not a LiteRT model") }
        )

        assertEquals(
            ArtifactVerificationResult.Invalid(ArtifactVerificationFailure.FORMAT_INVALID),
            result
        )
        assertEquals("older", target.readText())
        assertFalse(manifestFile.exists())
        assertFalse(tempFolder.root.listFiles().orEmpty().any { it.name.endsWith(".installing") })
    }

    @Test
    fun `managed install commits exact artifact and its manifest together`() {
        val spec = managedSpec()
        val source = File(tempFolder.root, "download.tmp")
        source.writeText("hello")
        val target = File(tempFolder.root, spec.modelFilename)
        target.writeText("older")
        val manifestFile = File(tempFolder.root, "manifest.json")

        val result = ModelArtifactInstaller().installManagedDownload(
            source = source,
            target = target,
            manifestFile = manifestFile,
            spec = spec,
            verifyFormat = {}
        )

        assertEquals(ArtifactVerificationResult.Valid, result)
        assertEquals("hello", target.readText())
        assertNotNull(ModelArtifactManifestStore().read(manifestFile))
        assertEquals(
            ArtifactVerificationResult.Valid,
            ModelArtifactVerifier().verifyBeforeNativeLoad(target, manifestFile, spec)
        )
    }

    @Test
    fun `local import failure committing the manifest leaves state reinstallable, not mismatched`() {
        val spec = OnDeviceModelSpec(
            id = "local-model",
            displayName = "Local model",
            family = "Test",
            sizeLabel = "Test",
            backend = OnDeviceBackend.LITERT_LM,
            modelFilename = "local.task"
        )
        val target = File(tempFolder.root, spec.modelFilename)
        target.writeText("original")
        val manifestFile = File(tempFolder.root, "manifest.json")
        val verifier = ModelArtifactVerifier()
        val originalManifest = verifier.createLocalImportManifest(spec, target)
        ModelArtifactManifestStore().writeAtomically(manifestFile, originalManifest)

        // A local import has no re-download source to self-repair from, unlike a managed
        // download: this is the path where a mismatched artifact/manifest pair is unrecoverable
        // without the user finding and re-importing the original file.
        val source = File(tempFolder.root, "import.tmp")
        source.writeText("replacement")

        var calls = 0
        val failsOnSecondMove = AtomicFileMover { from, to ->
            calls += 1
            if (calls == 2) throw IOException("simulated failure committing the manifest")
            NioAtomicFileMover.replace(from, to)
        }
        val installer = ModelArtifactInstaller(mover = failsOnSecondMove)

        val result = installer.installLocalImport(
            source = source,
            target = target,
            manifestFile = manifestFile,
            spec = spec,
            verifyFormat = {}
        )

        assertEquals(
            ArtifactVerificationResult.Invalid(ArtifactVerificationFailure.UNREADABLE_FILE),
            result
        )
        // The artifact move (call 1) already committed before the manifest move (call 2) failed.
        assertEquals("replacement", target.readText())
        // The old manifest was deleted before either move was attempted, and the new one never
        // landed: no manifest survives, which must read as "unverified, reinstall" rather than
        // silently validating against the stale manifest or crashing.
        assertFalse(manifestFile.exists())
        assertNull(ModelArtifactManifestStore().read(manifestFile))
        assertEquals(
            ArtifactVerificationResult.Invalid(ArtifactVerificationFailure.MANIFEST_INVALID),
            verifier.verifyBeforeNativeLoad(target, manifestFile, spec)
        )
        assertFalse(tempFolder.root.listFiles().orEmpty().any { it.name.endsWith(".installing") })
    }

    @Test
    fun `local import reports LiteRT runtime incompatibility separately from an invalid file`() {
        val spec = OnDeviceModelSpec(
            id = "local-model",
            displayName = "Local model",
            family = "Test",
            sizeLabel = "Test",
            backend = OnDeviceBackend.LITERT_LM,
            modelFilename = "local.task"
        )
        val source = File(tempFolder.root, "import.task").apply { writeText("model") }
        val target = File(tempFolder.root, spec.modelFilename)
        val manifestFile = File(tempFolder.root, "manifest.json")

        val result = ModelArtifactInstaller().installLocalImport(
            source = source,
            target = target,
            manifestFile = manifestFile,
            spec = spec,
            verifyFormat = {
                throw LiteRtRuntimeIncompatibilityException(IllegalStateException("GPU unavailable"))
            }
        )

        assertEquals(
            ArtifactVerificationResult.Invalid(ArtifactVerificationFailure.LITERT_RUNTIME_INCOMPATIBLE),
            result
        )
        assertFalse(target.exists())
        assertFalse(manifestFile.exists())
    }

    @Test
    fun `hash cache is keyed by stable file metadata`() {
        val file = File(tempFolder.root, "cached.task")
        file.writeText("hello")
        var calls = 0
        val cache = CachingFileHashVerifier(
            FileHashVerifier {
                calls += 1
                helloSha256
            }
        )

        assertEquals(helloSha256, cache.sha256(file))
        assertEquals(helloSha256, cache.sha256(file))

        assertEquals(1, calls)
    }
}
