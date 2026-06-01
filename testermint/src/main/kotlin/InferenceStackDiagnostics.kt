package com.productscience

import com.github.dockerjava.api.model.Container
import com.github.dockerjava.core.DockerClientBuilder
import org.tinylog.kotlin.Logger

private val inferenceStackServices = listOf("node", "api", "postgres", "proxy", "mock-server", "versiond")

/**
 * Logs docker compose output and returns the process exit code.
 * Used for join/genesis stack bring-up so CI artifacts include compose failures.
 */
fun DockerGroup.runComposeLogged(context: String, vararg composeArgs: String): Int {
    val fullArgs = composeArgs.toList()
    Logger.info(
        "[{}] docker compose ({}) cmd: {}",
        pairName,
        context,
        fullArgs.joinToString(" "),
    )
    Logger.info(
        "[{}] compose context ({}): ACCOUNT_PUBKEY={}, coldAccountPubkey.len={}, KEYRING_BACKEND={}",
        pairName,
        context,
        if (coldAccountPubkey.isNullOrBlank()) "unset" else "set",
        coldAccountPubkey?.length ?: 0,
        if (isGenesis) "test" else "file",
    )
    val process = dockerProcess(*fullArgs.toTypedArray())
        .redirectErrorStream(true)
        .start()
    process.inputStream.bufferedReader().lines().forEach { line ->
        Logger.info("[{}] compose> {}", pairName, line)
    }
    val exitCode = process.waitFor()
    if (exitCode != 0) {
        Logger.error("[{}] docker compose ({}) exited with code {}", pairName, context, exitCode)
    }
    return exitCode
}

/** `docker compose ps -a` for this pair's project (running and exited services). */
fun DockerGroup.logComposeProjectState(context: String) {
  val args = listOf("ps", "-a")
  runComposeLogged("compose-ps-$context", *args.toTypedArray())
}

/** Lists inference-related containers for one pair, including stopped/exited. */
fun logInferenceStackContainers(pairName: String, context: String) {
    val dockerClient = DockerClientBuilder.getInstance().build()
    val all = dockerClient.listContainersCmd().withShowAll(true).exec()
    Logger.info("[{}] inference stack container scan ({})", pairName, context)
    inferenceStackServices.forEach { service ->
        val expected = listOf("$pairName-$service", "/$pairName-$service")
        val container = all.find { c -> c.names.any { it in expected } }
        if (container == null) {
            Logger.warn("[{}]   {}-{}: not found (never created or removed)", pairName, pairName, service)
        } else {
            Logger.info(
                "[{}]   {}: state={} status={} image={}",
                pairName,
                container.names.first(),
                container.state,
                container.status,
                container.image,
            )
            if (container.state != "running") {
                tailDockerLogs(container.names.first().trimStart('/'), lines = 100, context = context)
            }
        }
    }
}

/** Explains why [getLocalInferencePairs] cannot attach dapi log streams (pair discovery). */
fun logClusterPairMismatch(
    config: ApplicationConfig,
    nodes: List<Container>,
    apis: List<Container>,
    context: String,
) {
    Logger.error(
        "Cluster pair mismatch ({}): {} node container(s), {} api container(s) (image={})",
        context,
        nodes.size,
        apis.size,
        config.apiImageName,
    )
    nodes.forEach { n ->
        Logger.error("  node: name={} state={} status={}", n.names.first(), n.state, n.status)
    }
    apis.forEach { a ->
        Logger.error("  api:  name={} state={} status={}", a.names.first(), a.state, a.status)
    }
    val nodePairNames = nodes.mapNotNull { nameExtractor.find(it.names.first())?.groupValues?.get(1) }.toSet()
    val apiPairNames = apis.mapNotNull { container ->
        container.names.first().removePrefix("/").removeSuffix("-api").takeIf { it.isNotBlank() }
    }.toSet()
    val nodesWithoutApi = nodePairNames - apiPairNames
    val apisWithoutNode = apiPairNames - nodePairNames
    if (nodesWithoutApi.isNotEmpty()) {
        Logger.error("  pairs with node but no running api: {}", nodesWithoutApi)
        nodesWithoutApi.forEach { logInferenceStackContainers(it, context) }
    }
    if (apisWithoutNode.isNotEmpty()) {
        Logger.error("  pairs with api but no running node: {}", apisWithoutNode)
    }
    listOf("genesis", "join1", "join2").forEach { pair ->
        if (pair == "genesis" || pair.startsWith("join")) {
            logInferenceStackContainers(pair, "mismatch-$context")
        }
    }
}

fun tailDockerLogs(containerName: String, lines: Int = 80, context: String = "") {
    val label = if (context.isBlank()) containerName else "$containerName ($context)"
    val process = ProcessBuilder("docker", "logs", "--tail", lines.toString(), containerName)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText()
    val exit = process.waitFor()
    if (exit != 0) {
        Logger.warn("Could not read docker logs for {} (exit={}): {}", label, exit, output.trim())
        return
    }
    if (output.isBlank()) {
        Logger.info("docker logs for {}: (empty)", label)
        return
    }
    Logger.info("docker logs for {} (last {} lines):", label, lines)
    output.lineSequence().take(200).forEach { line ->
        Logger.info("  | {}", line)
    }
}
