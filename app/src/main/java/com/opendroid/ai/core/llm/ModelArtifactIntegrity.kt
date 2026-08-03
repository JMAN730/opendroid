package com.opendroid.ai.core.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Publisher metadata for an artifact that the app may download itself.
 *
 * A partial record is intentionally not downloadable. In particular, an exact byte size is
 * useful display metadata but is never a substitute for a publisher SHA-256 value.
 */
@Serializable
data class ManagedModelArtifactMetadata(
    val downloadUrl: String? = null,
    val sourceRevision: String? = null,
    val expectedSize: Long? = null,
    val sha256: String? = null
) {
    val isComplete: Boolean
        get() {
            val revision = sourceRevision
            return revision != null &&
                SOURCE_REVISION_PATTERN.matches(revision) &&
                downloadUrl.isPinnedHttpsUrl(revision) &&
                expectedSize != null && expectedSize > 0L &&
                sha256 != null && SHA256_PATTERN.matches(sha256)
        }

    private fun String?.isPinnedHttpsUrl(revision: String): Boolean =
        !isNullOrBlank() && runCatching {
            val uri = java.net.URI(requireNotNull(this))
            uri.scheme.equals("https", ignoreCase = true) &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null &&
                uri.path.split('/').let { pathSegments ->
                    val resolveIndex = pathSegments.indexOf("resolve")
                    resolveIndex >= 0 && pathSegments.getOrNull(resolveIndex + 1) == revision
                }
        }.getOrDefault(false)

    private companion object {
        val SHA256_PATTERN = Regex("^[a-f0-9]{64}$")
        val SOURCE_REVISION_PATTERN = Regex("^[a-f0-9]{40}$")
    }
}

@Serializable
enum class ModelArtifactInstallSource {
    MANAGED_DOWNLOAD,
    LOCAL_IMPORT
}

/**
 * The persisted identity of an installed model file.
 *
 * The manifest is an install record, not a trust root: managed-download metadata is checked
 * against the immutable registry before it is used. Local imports retain only a fingerprint so
 * later accidental modification is detected; they do not claim publisher provenance.
 */
@Serializable
data class ModelArtifactManifest(
    @SerialName("schema_version")
    val schemaVersion: Int = SCHEMA_VERSION,
    @SerialName("model_id")
    val modelId: String,
    @SerialName("filename")
    val filename: String,
    @SerialName("install_source")
    val installSource: ModelArtifactInstallSource,
    @SerialName("byte_size")
    val byteSize: Long,
    @SerialName("sha256")
    val sha256: String,
    @SerialName("download_url")
    val downloadUrl: String? = null,
    @SerialName("source_revision")
    val sourceRevision: String? = null
) {
    fun isWellFormed(): Boolean =
        schemaVersion == SCHEMA_VERSION &&
            modelId.isNotBlank() &&
            filename.isNotBlank() &&
            byteSize > 0L &&
            SHA256_PATTERN.matches(sha256) &&
            when (installSource) {
                ModelArtifactInstallSource.MANAGED_DOWNLOAD ->
                    ManagedModelArtifactMetadata(
                        downloadUrl = downloadUrl,
                        sourceRevision = sourceRevision,
                        expectedSize = byteSize,
                        sha256 = sha256
                    ).isComplete

                ModelArtifactInstallSource.LOCAL_IMPORT ->
                    downloadUrl == null && sourceRevision == null
            }

    fun toJson(): String = JSON.encodeToString(this)

    companion object {
        const val SCHEMA_VERSION = 1
        private val SHA256_PATTERN = Regex("^[a-f0-9]{64}$")

        fun forManagedDownload(spec: OnDeviceModelSpec): ModelArtifactManifest? {
            val artifact = spec.managedArtifact ?: return null
            if (!artifact.isComplete) return null

            return ModelArtifactManifest(
                modelId = spec.id,
                filename = spec.modelFilename,
                installSource = ModelArtifactInstallSource.MANAGED_DOWNLOAD,
                byteSize = artifact.expectedSize!!,
                sha256 = artifact.sha256!!,
                downloadUrl = artifact.downloadUrl,
                sourceRevision = artifact.sourceRevision
            )
        }

        fun fromJson(json: String): ModelArtifactManifest? = runCatching {
            JSON.decodeFromString<ModelArtifactManifest>(json)
                .takeIf(ModelArtifactManifest::isWellFormed)
        }.getOrNull()

        private val JSON = Json {
            encodeDefaults = true
            explicitNulls = false
            ignoreUnknownKeys = false
        }
    }
}

/** Persists manifests through a same-directory atomic replace. */
class ModelArtifactManifestStore(
    private val mover: AtomicFileMover = NioAtomicFileMover
) {
    fun read(file: File): ModelArtifactManifest? {
        if (!file.isFile || !file.canRead()) return null
        return runCatching { ModelArtifactManifest.fromJson(file.readText()) }.getOrNull()
    }

    @Throws(IOException::class)
    fun writeAtomically(file: File, manifest: ModelArtifactManifest) {
        require(manifest.isWellFormed()) { "Refusing to persist malformed model manifest" }
        val directory = file.parentFile ?: throw IOException("Model manifest has no parent directory")
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Could not create model manifest directory")
        }

        val staging = File.createTempFile(".manifest-", ".installing", directory)
        try {
            writeStaged(staging, manifest)
            mover.replace(staging, file)
        } finally {
            staging.delete()
        }
    }

    @Throws(IOException::class)
    internal fun writeStaged(file: File, manifest: ModelArtifactManifest) {
        require(manifest.isWellFormed()) { "Refusing to persist malformed model manifest" }
        FileOutputStream(file, false).use { output ->
            output.write(manifest.toJson().toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
    }
}

/** A testable boundary for hashing files without retaining model content in memory. */
fun interface FileHashVerifier {
    @Throws(IOException::class)
    fun sha256(file: File): String
}

object StreamingSha256FileHashVerifier : FileHashVerifier {
    override fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }
}

/**
 * Caches a verified digest only while the file has the same canonical path, byte count, and
 * modification time. A file that changes while it is being read is never cached.
 */
class CachingFileHashVerifier(
    private val delegate: FileHashVerifier = StreamingSha256FileHashVerifier
) : FileHashVerifier {
    private data class FileSignature(
        val canonicalPath: String,
        val byteSize: Long,
        val lastModified: Long
    )

    private val cache = ConcurrentHashMap<FileSignature, String>()

    override fun sha256(file: File): String {
        val before = signatureOf(file)
        cache[before]?.let { return it }

        val hash = delegate.sha256(file)
        if (signatureOf(file) != before) {
            throw IOException("Model file changed while it was being verified")
        }

        cache.keys.removeIf { it.canonicalPath == before.canonicalPath && it != before }
        cache.putIfAbsent(before, hash)
        return cache.getValue(before)
    }

    private fun signatureOf(file: File): FileSignature = FileSignature(
        canonicalPath = file.canonicalPath,
        byteSize = file.length(),
        lastModified = file.lastModified()
    )
}

enum class ArtifactVerificationFailure {
    MISSING_FILE,
    UNREADABLE_FILE,
    MANIFEST_INVALID,
    MANIFEST_MISMATCH,
    METADATA_UNAVAILABLE,
    SIZE_MISMATCH,
    HASH_MISMATCH,
    HASH_UNAVAILABLE,
    FORMAT_INVALID
}

sealed interface ArtifactVerificationResult {
    data object Valid : ArtifactVerificationResult
    data class Invalid(val failure: ArtifactVerificationFailure) : ArtifactVerificationResult
}

/**
 * Verifies catalog installs against registry-owned metadata and local imports against their
 * persisted fingerprint. Full hashes are deferred until a native load is about to occur.
 */
class ModelArtifactVerifier(
    private val hashVerifier: FileHashVerifier = StreamingSha256FileHashVerifier,
    private val manifestStore: ModelArtifactManifestStore = ModelArtifactManifestStore()
) {
    fun verifyForStartup(
        file: File,
        manifestFile: File,
        spec: OnDeviceModelSpec
    ): ArtifactVerificationResult = verify(file, manifestFile, spec, verifyHash = false)

    fun verifyBeforeNativeLoad(
        file: File,
        manifestFile: File,
        spec: OnDeviceModelSpec
    ): ArtifactVerificationResult = verify(file, manifestFile, spec, verifyHash = true)

    fun verifyManagedPayload(file: File, spec: OnDeviceModelSpec): ArtifactVerificationResult {
        val artifact = spec.managedArtifact
            ?: return ArtifactVerificationResult.Invalid(ArtifactVerificationFailure.METADATA_UNAVAILABLE)
        if (!artifact.isComplete) {
            return ArtifactVerificationResult.Invalid(ArtifactVerificationFailure.METADATA_UNAVAILABLE)
        }

        val fileCheck = basicFileCheck(file)
        if (fileCheck != null) return fileCheck
        if (file.length() != artifact.expectedSize) {
            return ArtifactVerificationResult.Invalid(ArtifactVerificationFailure.SIZE_MISMATCH)
        }

        return compareHash(file, artifact.sha256!!)
    }

    @Throws(IOException::class)
    fun createLocalImportManifest(spec: OnDeviceModelSpec, file: File): ModelArtifactManifest {
        when (basicFileCheck(file)) {
            null -> Unit
            is ArtifactVerificationResult.Invalid -> throw IOException("Local model file is unavailable")
        }
        return ModelArtifactManifest(
            modelId = spec.id,
            filename = file.name,
            installSource = ModelArtifactInstallSource.LOCAL_IMPORT,
            byteSize = file.length(),
            sha256 = hashVerifier.sha256(file)
        )
    }

    /**
     * Supports a safe one-time repair of pre-manifest catalog installs: the full registry-owned
     * hash must match before a new managed-download manifest is written.
     */
    fun manifestForVerifiedLegacyCatalogFile(
        file: File,
        spec: OnDeviceModelSpec
    ): ModelArtifactManifest? =
        if (verifyManagedPayload(file, spec) == ArtifactVerificationResult.Valid) {
            ModelArtifactManifest.forManagedDownload(spec)
        } else {
            null
        }

    private fun verify(
        file: File,
        manifestFile: File,
        spec: OnDeviceModelSpec,
        verifyHash: Boolean
    ): ArtifactVerificationResult {
        val fileCheck = basicFileCheck(file)
        if (fileCheck != null) return fileCheck

        val manifest = manifestStore.read(manifestFile)
            ?: return ArtifactVerificationResult.Invalid(ArtifactVerificationFailure.MANIFEST_INVALID)
        if (manifest.modelId != spec.id || manifest.filename != file.name) {
            return ArtifactVerificationResult.Invalid(ArtifactVerificationFailure.MANIFEST_MISMATCH)
        }
        if (manifest.byteSize != file.length()) {
            return ArtifactVerificationResult.Invalid(ArtifactVerificationFailure.SIZE_MISMATCH)
        }

        return when (manifest.installSource) {
            ModelArtifactInstallSource.MANAGED_DOWNLOAD -> verifyManagedManifest(
                file = file,
                spec = spec,
                manifest = manifest,
                verifyHash = verifyHash
            )

            ModelArtifactInstallSource.LOCAL_IMPORT -> verifyLocalManifest(
                file = file,
                manifest = manifest,
                verifyHash = verifyHash
            )
        }
    }

    private fun verifyManagedManifest(
        file: File,
        spec: OnDeviceModelSpec,
        manifest: ModelArtifactManifest,
        verifyHash: Boolean
    ): ArtifactVerificationResult {
        val expected = ModelArtifactManifest.forManagedDownload(spec)
            ?: return ArtifactVerificationResult.Invalid(ArtifactVerificationFailure.METADATA_UNAVAILABLE)
        if (manifest != expected) {
            return ArtifactVerificationResult.Invalid(ArtifactVerificationFailure.MANIFEST_MISMATCH)
        }
        if (file.length() != expected.byteSize) {
            return ArtifactVerificationResult.Invalid(ArtifactVerificationFailure.SIZE_MISMATCH)
        }
        return if (verifyHash) compareHash(file, expected.sha256) else ArtifactVerificationResult.Valid
    }

    private fun verifyLocalManifest(
        file: File,
        manifest: ModelArtifactManifest,
        verifyHash: Boolean
    ): ArtifactVerificationResult {
        if (manifest.downloadUrl != null || manifest.sourceRevision != null) {
            return ArtifactVerificationResult.Invalid(ArtifactVerificationFailure.MANIFEST_MISMATCH)
        }
        return if (verifyHash) compareHash(file, manifest.sha256) else ArtifactVerificationResult.Valid
    }

    /** Size-then-hash check against an expected fingerprint, using the injected hash verifier. */
    fun verifyFingerprint(
        file: File,
        expectedByteSize: Long,
        expectedSha256: String
    ): ArtifactVerificationResult {
        if (file.length() != expectedByteSize) {
            return ArtifactVerificationResult.Invalid(ArtifactVerificationFailure.SIZE_MISMATCH)
        }
        return compareHash(file, expectedSha256)
    }

    private fun basicFileCheck(file: File): ArtifactVerificationResult.Invalid? = when {
        !file.exists() || !file.isFile ->
            ArtifactVerificationResult.Invalid(ArtifactVerificationFailure.MISSING_FILE)

        !file.canRead() -> ArtifactVerificationResult.Invalid(ArtifactVerificationFailure.UNREADABLE_FILE)
        else -> null
    }

    private fun compareHash(file: File, expectedHash: String): ArtifactVerificationResult {
        val actual = try {
            hashVerifier.sha256(file)
        } catch (_: Exception) {
            return ArtifactVerificationResult.Invalid(ArtifactVerificationFailure.HASH_UNAVAILABLE)
        }
        return if (actual == expectedHash) {
            ArtifactVerificationResult.Valid
        } else {
            ArtifactVerificationResult.Invalid(ArtifactVerificationFailure.HASH_MISMATCH)
        }
    }
}

/** Same-directory replace; failure is safer than a non-atomic fallback. */
fun interface AtomicFileMover {
    @Throws(IOException::class)
    fun replace(source: File, target: File)
}

object NioAtomicFileMover : AtomicFileMover {
    override fun replace(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (error: AtomicMoveNotSupportedException) {
            throw IOException("Atomic model installation is not supported on this storage", error)
        }
    }
}

/**
 * Stages a verified artifact beside its final destination before either final file is replaced.
 * A failed verification or format check can never overwrite a working installed model.
 */
class ModelArtifactInstaller(
    private val verifier: ModelArtifactVerifier = ModelArtifactVerifier(),
    private val manifestStore: ModelArtifactManifestStore = ModelArtifactManifestStore(),
    private val mover: AtomicFileMover = NioAtomicFileMover
) {
    fun installManagedDownload(
        source: File,
        target: File,
        manifestFile: File,
        spec: OnDeviceModelSpec,
        verifyFormat: (File) -> Unit
    ): ArtifactVerificationResult {
        val manifest = ModelArtifactManifest.forManagedDownload(spec)
            ?: return ArtifactVerificationResult.Invalid(
                ArtifactVerificationFailure.METADATA_UNAVAILABLE
            )
        val sourceResult = verifier.verifyManagedPayload(source, spec)
        if (sourceResult is ArtifactVerificationResult.Invalid) {
            return sourceResult
        }

        return stageAndCommit(source, target, manifestFile, manifest, verifyFormat) { staged ->
            verifier.verifyManagedPayload(staged, spec)
        }
    }

    fun installLocalImport(
        source: File,
        target: File,
        manifestFile: File,
        spec: OnDeviceModelSpec,
        verifyFormat: (File) -> Unit
    ): ArtifactVerificationResult {
        val manifest = try {
            verifier.createLocalImportManifest(spec, source)
        } catch (_: IOException) {
            return ArtifactVerificationResult.Invalid(ArtifactVerificationFailure.HASH_UNAVAILABLE)
        }

        return stageAndCommit(source, target, manifestFile, manifest, verifyFormat) { staged ->
            verifier.verifyFingerprint(staged, manifest.byteSize, manifest.sha256)
        }
    }

    private fun stageAndCommit(
        source: File,
        target: File,
        manifestFile: File,
        manifest: ModelArtifactManifest,
        verifyFormat: (File) -> Unit,
        verifyStagedPayload: (File) -> ArtifactVerificationResult
    ): ArtifactVerificationResult {
        val targetDirectory = target.parentFile
            ?: return ArtifactVerificationResult.Invalid(ArtifactVerificationFailure.UNREADABLE_FILE)
        val manifestDirectory = manifestFile.parentFile
            ?: return ArtifactVerificationResult.Invalid(ArtifactVerificationFailure.UNREADABLE_FILE)
        if (targetDirectory.canonicalFile != manifestDirectory.canonicalFile) {
            return ArtifactVerificationResult.Invalid(ArtifactVerificationFailure.MANIFEST_MISMATCH)
        }
        if (!targetDirectory.exists() && !targetDirectory.mkdirs()) {
            return ArtifactVerificationResult.Invalid(ArtifactVerificationFailure.UNREADABLE_FILE)
        }

        val stagedArtifact = File.createTempFile(".artifact-", ".installing", targetDirectory)
        val stagedManifest = File.createTempFile(".manifest-", ".installing", targetDirectory)
        try {
            source.inputStream().use { input ->
                FileOutputStream(stagedArtifact, false).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }

            val stagedResult = verifyStagedPayload(stagedArtifact)
            if (stagedResult is ArtifactVerificationResult.Invalid) {
                return stagedResult
            }

            try {
                verifyFormat(stagedArtifact)
            } catch (_: Exception) {
                return ArtifactVerificationResult.Invalid(ArtifactVerificationFailure.FORMAT_INVALID)
            } catch (_: LinkageError) {
                return ArtifactVerificationResult.Invalid(ArtifactVerificationFailure.FORMAT_INVALID)
            }

            manifestStore.writeStaged(stagedManifest, manifest)

            // The artifact and manifest are committed with two separate atomic moves, and the
            // pair can never be made atomic together. Rather than pick an order that leaves a
            // window where a stale manifest describes a new artifact (or vice versa) — a silent
            // mismatch that verification would wrongly reject *or*, worse, wrongly accept — we
            // delete the old manifest first. Every interruption point below then leaves either no
            // manifest at all, or a manifest that matches the artifact on disk. A missing manifest
            // reads as "unverified, reinstall" on the verification path, which is unambiguous and
            // already handled: see ModelArtifactVerifier.verify(), which treats a null read from
            // ModelArtifactManifestStore.read() as MANIFEST_INVALID rather than crashing or
            // treating the artifact as valid.
            if (manifestFile.exists() && !manifestFile.delete()) {
                return ArtifactVerificationResult.Invalid(ArtifactVerificationFailure.UNREADABLE_FILE)
            }
            mover.replace(stagedArtifact, target)
            mover.replace(stagedManifest, manifestFile)
            return ArtifactVerificationResult.Valid
        } catch (_: IOException) {
            return ArtifactVerificationResult.Invalid(ArtifactVerificationFailure.UNREADABLE_FILE)
        } finally {
            stagedArtifact.delete()
            stagedManifest.delete()
        }
    }
}
