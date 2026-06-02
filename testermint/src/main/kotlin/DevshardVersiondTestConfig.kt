package com.productscience

import com.github.kittinunf.fuel.Fuel
import org.tinylog.kotlin.Logger
import java.nio.file.Files
import java.nio.file.Path

/**
 * Shared devshardd/versiond naming for Testermint override tests.
 *
 * Resolution order for [devshardTestVersion] (must match `make devshardd-build`):
 * 1. `DEVSHARD_VERSION` env (explicit override for CI or one-off runs)
 * 2. `build/devshard-version` (written by `make devshardd-build`)
 * 3. `make -C <repo> print-devshard-version` (root Makefile `DEVSHARD_VERSION`, default `dev`)
 */
const val DEVSHARD_VERSION_ENV = "DEVSHARD_VERSION"

const val DEVSHARD_VERSION_STAMP = "build/devshard-version"

const val DEVSHARD_OVERRIDE_BINARY_PATH = "/opt/overrides/devshardd"

private val VERSIOND_ENV_LOG_KEYS =
    listOf(
        "VERSIOND_FORCE",
        "VERSIOND_BINARY_NAME",
        "VERSIOND_SERVICE_NAME",
        "VERSIOND_ORACLE_URL",
    )

private val resolvedDevshardTestVersion: String by lazy { resolveDevshardTestVersion() }

/** Version name used for VERSIOND_FORCE and /devshard/<version>/ routes. */
fun devshardTestVersion(): String = resolvedDevshardTestVersion

private fun resolveDevshardTestVersion(): String {
    val jvmEnv = System.getenv(DEVSHARD_VERSION_ENV)
    Logger.info(
        "[devshard-version] JVM {}={}",
        DEVSHARD_VERSION_ENV,
        jvmEnv?.takeIf { it.isNotBlank() } ?: "<unset>",
    )

    val envVersion = jvmEnv?.takeIf { it.isNotBlank() }
    if (envVersion != null) {
        Logger.info("[devshard-version] resolved from env {}={}", DEVSHARD_VERSION_ENV, envVersion)
        return envVersion
    }

    val stampPath = Path.of(getRepoRoot(), DEVSHARD_VERSION_STAMP)
    val stampExists = Files.isRegularFile(stampPath)
    Logger.info(
        "[devshard-version] stamp path={} exists={}",
        stampPath.toAbsolutePath(),
        stampExists,
    )
    val stampVersion = readDevshardVersionStamp()
    if (stampVersion != null) {
        Logger.info("[devshard-version] resolved from stamp {}={}", DEVSHARD_VERSION_STAMP, stampVersion)
        return stampVersion
    }

    val makeVersion = makefileDevshardVersion()
    if (makeVersion != null) {
        Logger.info("[devshard-version] resolved from make print-devshard-version={}", makeVersion)
        return makeVersion
    }

    Logger.warn("[devshard-version] fallback to default version=dev")
    return "dev"
}

private fun readDevshardVersionStamp(): String? = runCatching {
    val stamp = Path.of(getRepoRoot(), DEVSHARD_VERSION_STAMP)
    if (!Files.isRegularFile(stamp)) {
        return@runCatching null
    }
    Files.readString(stamp).trim().takeIf { it.isNotBlank() }
}.getOrNull()

private fun makefileDevshardVersion(): String? = runCatching {
    val proc =
        ProcessBuilder(
            "make",
            "-s",
            "--no-print-directory",
            "-C",
            getRepoRoot(),
            "print-devshard-version",
        )
            .redirectErrorStream(true)
            .start()
    val out = proc.inputStream.bufferedReader().use { it.readText().trim() }
    if (proc.waitFor() == 0 && out.isNotBlank()) out else null
}.getOrNull()

/** Maps version name to VERSIOND_OVERRIDE env suffix (dots -> underscores). */
fun versiondOverrideEnvKey(version: String): String =
    "VERSIOND_OVERRIDE_${version.replace('.', '_')}"

/** Env vars for versiond compose: force local override binary as [version]. */
fun versiondOverrideEnv(version: String = devshardTestVersion()): Map<String, String> =
    mapOf(
        "VERSIOND_BINARY_NAME" to "devshardd",
        versiondOverrideEnvKey(version) to DEVSHARD_OVERRIDE_BINARY_PATH,
        "VERSIOND_FORCE" to version,
        "VERSIOND_SERVICE_NAME" to "versiond",
    )

fun devshardVersionedRoutePrefix(version: String = devshardTestVersion()): String =
    "/devshard/$version"

/**
 * Logs VERSIOND_* values from the host env passed to `docker compose`.
 * Call immediately before compose `up` when the versiond overlay is enabled.
 */
fun logVersiondComposeEnvironment(pairName: String, composeEnv: Map<String, String>, context: String) {
    val version = devshardTestVersion()
    val overrideKey = versiondOverrideEnvKey(version)
    Logger.info("[{}] versiond compose environment ({})", pairName, context)
    Logger.info("[{}]   devshardTestVersion={}", pairName, version)
    Logger.info("[{}]   devshardVersionedRoutePrefix={}", pairName, devshardVersionedRoutePrefix(version))
    VERSIOND_ENV_LOG_KEYS.forEach { key ->
        Logger.info("[{}]   {}={}", pairName, key, composeEnv[key]?.ifBlank { "<empty>" } ?: "<unset>")
    }
    Logger.info(
        "[{}]   {}={}",
        pairName,
        overrideKey,
        composeEnv[overrideKey]?.ifBlank { "<empty>" } ?: "<unset>",
    )
    val hostBinary = Path.of(getRepoRoot(), "build", "devshardd")
    Logger.info(
        "[{}]   hostBinary path={} exists={} size={}",
        pairName,
        hostBinary.toAbsolutePath(),
        Files.isRegularFile(hostBinary),
        if (Files.isRegularFile(hostBinary)) Files.size(hostBinary) else -1,
    )
    warnIfComposeOverrideKeyNotDeclared(version, pairName)
}

/**
 * [local-test-net/docker-compose.versiond.yml] only declares `VERSIOND_OVERRIDE_dev` for
 * compose substitution. Other version names need an explicit service env entry.
 */
fun warnIfComposeOverrideKeyNotDeclared(version: String, pairName: String) {
    if (version == "dev") {
        return
    }
    Logger.warn(
        "[{}] versiond compose file declares VERSIOND_OVERRIDE_dev only; " +
            "override tests using version '{}' need VERSIOND_OVERRIDE_{} in docker-compose.versiond.yml",
        pairName,
        version,
        version.replace('.', '_'),
    )
}

/** Read versiond container env and recent logs (for JUnit / CI artifacts). */
fun LocalInferencePair.logVersiondDiagnostics(expectedVersion: String, logTail: Int = 120) {
    val pairLabel = name.trimStart('/')
    Logger.info("[{}] versiond runtime diagnostics (expectedVersion={})", pairLabel, expectedVersion)
  try {
        val envLines =
            execInVersiond(
                listOf("sh", "-c", "env | grep -E '^VERSIOND_' | sort"),
                null,
            ).joinToString("\n")
        if (envLines.isBlank()) {
            Logger.warn("[{}]   versiond container: no VERSIOND_* env vars visible", pairLabel)
        } else {
            envLines.lineSequence().forEach { line ->
                Logger.info("[{}]   versiond env {}", pairLabel, line)
            }
        }
    } catch (e: Exception) {
        Logger.warn("[{}]   could not read versiond env: {}", pairLabel, e.message)
    }

    try {
        val overrideMount =
            execInVersiond(
                listOf(
                    "sh",
                    "-c",
                    "ls -la '$DEVSHARD_OVERRIDE_BINARY_PATH' 2>&1 || echo MISSING",
                ),
                null,
            ).joinToString(" ").trim()
        Logger.info("[{}]   override mount {}: {}", pairLabel, DEVSHARD_OVERRIDE_BINARY_PATH, overrideMount)
    } catch (e: Exception) {
        Logger.warn("[{}]   override mount check failed: {}", pairLabel, e.message)
    }

    val binExists = versiondBinaryExists(expectedVersion, "devshardd")
    Logger.info(
        "[{}]   installed binary {} exists={}",
        pairLabel,
        versiondBinaryPath(expectedVersion, "devshardd"),
        binExists,
    )

    val health = queryVersionedHealth(expectedVersion)
    Logger.info("[{}]   GET /devshard/{}/healthz => {}", pairLabel, expectedVersion, health ?: "<unavailable>")

    try {
        val logs = readVersiondLogs(tail = logTail)
        val interesting =
            logs.lineSequence()
                .filter { line ->
                    line.contains("versiond", ignoreCase = true) ||
                        line.contains("VERSIOND", ignoreCase = true) ||
                        line.contains(expectedVersion, ignoreCase = true) ||
                        line.contains("override", ignoreCase = true) ||
                        line.contains("force", ignoreCase = true) ||
                        line.contains("reconcile", ignoreCase = true) ||
                        line.contains("not found", ignoreCase = true) ||
                        line.contains("error", ignoreCase = true)
                }
                .take(40)
                .joinToString("\n")
        if (interesting.isNotBlank()) {
            Logger.info("[{}]   versiond log highlights:\n{}", pairLabel, interesting)
        } else {
            Logger.info("[{}]   versiond logs (last {} lines): no matching highlights", pairLabel, logTail)
        }
    } catch (e: Exception) {
        Logger.warn("[{}]   could not read versiond logs: {}", pairLabel, e.message)
    }
}

fun LocalInferencePair.queryVersionedHealth(versionName: String): String? =
    runCatching {
        val url = "${api.getPublicUrl()}/devshard/$versionName/healthz"
        val (_, response, result) = Fuel.get(url).timeoutRead(10_000).responseString()
        "${response.statusCode}:${result.get().trim().take(200)}"
    }.getOrNull()

/**
 * Waits until versiond has installed the forced override and the proxy health route responds.
 */
fun LocalInferencePair.waitForVersiondOverrideReady(
    expectedVersion: String = devshardTestVersion(),
    timeoutSeconds: Int = 120,
) {
    logSection("Waiting for versiond override version=$expectedVersion")
    warnIfComposeOverrideKeyNotDeclared(expectedVersion, name.trimStart('/'))
    logVersiondDiagnostics(expectedVersion)
    val deadline = System.currentTimeMillis() + timeoutSeconds * 1000L
    var lastLogMs = 0L
    while (System.currentTimeMillis() < deadline) {
        val binReady = versiondBinaryExists(expectedVersion, "devshardd")
        val healthOk =
            runCatching {
                queryVersionedHealth(expectedVersion)?.startsWith("200:ok") == true
            }.getOrDefault(false)
        if (binReady && healthOk) {
            Logger.info(
                "[{}] versiond override ready (version={}, binary+healthz ok)",
                name.trimStart('/'),
                expectedVersion,
            )
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastLogMs >= 30_000) {
            Logger.info(
                "[{}] still waiting for versiond override (binary={}, healthz={})",
                name.trimStart('/'),
                binReady,
                healthOk,
            )
            logVersiondDiagnostics(expectedVersion, logTail = 80)
            lastLogMs = now
        }
        Thread.sleep(2_000)
    }
    logVersiondDiagnostics(expectedVersion, logTail = 400)
    error(
        "versiond override not ready for version '$expectedVersion' within ${timeoutSeconds}s " +
            "(see [devshard-version] and versiond diagnostics above)",
    )
}

internal fun DockerGroup.logVersiondComposeEnvironmentIfNeeded(context: String) {
    if (!composeFiles.any { it.endsWith("docker-compose.versiond.yml") }) {
        return
    }
    logVersiondComposeEnvironment(pairName, getCommonEnvMapForLogging(), context)
}
