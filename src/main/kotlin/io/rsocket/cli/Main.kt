/*
 * Copyright 2015 Netflix, Inc.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package io.rsocket.cli

import com.baulsupp.oksocial.output.ConsoleHandler
import com.baulsupp.oksocial.output.OutputHandler
import com.baulsupp.oksocial.output.UsageException
import com.google.common.io.Files
import io.rsocket.AbstractRSocket
import io.rsocket.Closeable
import io.rsocket.ConnectionSetupPayload
import io.rsocket.Payload
import io.rsocket.RSocket
import io.rsocket.RSocketFactory
import io.rsocket.resume.PeriodicResumeStrategy
import io.rsocket.transport.ClientTransport
import io.rsocket.transport.TransportHeaderAware
import io.rsocket.util.DefaultPayload
import io.rsocket.util.EmptyPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.reactivestreams.Publisher
import org.slf4j.LoggerFactory
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Duration.ofSeconds
import java.util.concurrent.TimeUnit
import java.util.function.Supplier
import kotlin.system.exitProcess

/**
 * Simple command line tool to make a RSocket connection and send/receive elements.
 *
 * Currently limited in features, only supports a text/line based approach.
 */
@Command(description = ["RSocket CLI command"],
  name = "rsocket-cli", mixinStandardHelpOptions = true, version = ["dev"])
class Main : Runnable {
  @Option(names = ["-H", "--header"], description = ["Custom header to pass to server"])
  var headers: List<String>? = null

  @Option(names = ["-T", "--transport-header"],
    description = ["Custom header to pass to the transport"])
  var transportHeader: List<String>? = null

  @Option(names = ["--stream"], description = ["Request Stream"])
  var stream: Boolean = false

  @Option(names = ["--request"], description = ["Request Response"])
  var requestResponse: Boolean = false

  @Option(names = ["--fnf"], description = ["Fire and Forget"])
  var fireAndForget: Boolean = false

  @Option(names = ["--channel"], description = ["Channel"])
  var channel: Boolean = false

  @Option(names = ["--metadataPush"], description = ["Metadata Push"])
  var metadataPush: Boolean = false

  @Option(names = ["--server"], description = ["Start server instead of client"])
  var serverMode: Boolean = false

  @Option(names = ["-i", "--input"], description = ["String input, '-' (STDIN) or @path/to/file"])
  var input: List<String>? = null

  @Option(names = ["-m", "--metadata"],
    description = ["Metadata input string input or @path/to/file"])
  var metadata: String? = null

  @Option(names = ["--metadataFormat"], description = ["Metadata Format"])
  var metadataFormat = "json"

  @Option(names = ["--dataFormat"], description = ["Data Format"])
  var dataFormat = "binary"

  @Option(names = ["--setup"], description = ["String input or @path/to/file for setup metadata"])
  var setup: String? = null

  @Option(names = ["--debug"], description = ["Debug Output"])
  var debug: Boolean = false

  @Option(names = ["--ops"], description = ["Operation Count"])
  var operations = 1

  @Option(names = ["--timeout"], description = ["Timeout in seconds"])
  var timeout: Long? = null

  @Option(names = ["--keepalive"], description = ["Keepalive period"])
  var keepalive: String? = null

  @Option(names = ["--requestn", "-r"], description = ["Request N credits"])
  var requestN = Integer.MAX_VALUE

  @Option(names = ["--resume"], description = ["resume enabled"])
  var resume: Boolean = false

  @Option(names = ["--completionScript"], description = ["Generate completion script"])
  var completionScript: Boolean = false

  @Parameters(arity = "0..1", paramLabel = "target", description = ["Endpoint URL"],
    completionCandidates = UrlCandidates::class)
  var target: String? = null

  lateinit var client: RSocket

  lateinit var outputHandler: OutputHandler<Any>

  lateinit var inputPublisher: InputPublisher

  lateinit var server: Closeable

  override fun run() {
    runBlocking {
      exec()
    }
  }

  private suspend fun exec(): Void? {
    configureLogging(debug)

    if (!this::outputHandler.isInitialized) {
      outputHandler = ConsoleHandler.instance()
    }

    if (!this::inputPublisher.isInitialized) {
      inputPublisher = LineInputPublishers(outputHandler)
    }

    val uri = sanitizeUri(target ?: throw UsageException("no target specified"))

    return if (serverMode) {
      server = buildServer(uri)

      server.onClose().awaitFirstOrNull()
    } else {
      if (!this::client.isInitialized) {
        client = buildClient(uri)
      }

      val run = run(client)

      if (timeout != null) {
        withTimeout(TimeUnit.SECONDS.toMillis(timeout!!)) {
          run.then().awaitFirstOrNull()
        }
      } else {
        run.then().awaitFirstOrNull()
      }
    }
  }

  suspend fun buildServer(uri: String): Closeable {
    val factory = RSocketFactory.receive()

    if (resume) {
      factory.resume()
    }

    val transport = factory
      .acceptor(this::createServerRequestHandler)
      .transport(UriTransportRegistry.serverForUri(uri))

    return transport.start().awaitFirst()
  }

  suspend fun buildClient(uri: String): RSocket {
    val clientRSocketFactory = RSocketFactory.connect()

    if (resume) {
      clientRSocketFactory.resume()
        .resumeSessionDuration(ofSeconds(30))
        .resumeStrategy { PeriodicResumeStrategy(ofSeconds(3)) }
    }

    if (keepalive != null) {
      clientRSocketFactory.keepAliveTickPeriod(parseShortDuration(keepalive!!))
    }
    clientRSocketFactory.errorConsumer { t ->
      runBlocking {
        outputHandler.showError("client error", t)
      }
    }
    clientRSocketFactory.metadataMimeType(standardMimeType(metadataFormat))
    clientRSocketFactory.dataMimeType(standardMimeType(dataFormat))
    if (setup != null) {
      clientRSocketFactory.setupPayload(parseSetupPayload())
    }

    val clientTransport = clientTransport(uri)

    if (transportHeader != null && clientTransport is TransportHeaderAware) {
      clientTransport.setTransportHeaders { headerMap(transportHeader) }
    }

    clientRSocketFactory.acceptor(this::createClientRequestHandler)

    return clientRSocketFactory.transport(clientTransport).start().awaitFirst()
  }

  private fun clientTransport(uri: String): ClientTransport? {
    return UriTransportRegistry.clientForUri(uri)
  }

  private fun createClientRequestHandler(socket: RSocket): RSocket = createResponder()

  private fun standardMimeType(dataFormat: String?): String = when (dataFormat) {
    null -> "application/json"
    "json" -> "application/json"
    "cbor" -> "application/cbor"
    "binary" -> "application/binary"
    "text" -> "text/plain"
    else -> dataFormat
  }

  private fun parseSetupPayload(): Payload = when {
    setup == null -> EmptyPayload.INSTANCE
    setup!!.startsWith("@") -> DefaultPayload.create(
      Files.asCharSource(expectedFile(setup!!.substring(1)), StandardCharsets.UTF_8).read())
    else -> DefaultPayload.create(setup!!)
  }

  private fun createServerRequestHandler(
    setupPayload: ConnectionSetupPayload,
    socket: RSocket
  ): Mono<RSocket> {
    LoggerFactory.getLogger(Main::class.java).debug("setup payload $setupPayload")

    // TODO chain
    runAllOperations(socket).subscribe()
    return Mono.just(createResponder())
  }

  private fun sanitizeUri(uri: String): String {
    var validationUri = URI(uri)
    if (validationUri.scheme == "ws" || validationUri.scheme == "wss") {
      if (validationUri.path.isEmpty()) {
        return "$uri/"
      }
    }
    return uri
  }

  fun createResponder(): AbstractRSocket {
    return object : AbstractRSocket() {
      override fun fireAndForget(payload: Payload): Mono<Void> = GlobalScope.mono(
        Dispatchers.Default) {
        outputHandler.showOutput(payload.dataUtf8)
      }.then()

      override fun requestResponse(payload: Payload): Mono<Payload> = handleIncomingPayload(
        payload).single()

      override fun requestStream(payload: Payload): Flux<Payload> = handleIncomingPayload(payload)

      override fun requestChannel(payloads: Publisher<Payload>): Flux<Payload> {
        // TODO chain
        Flux.from(payloads).takeN(requestN)
          .onNext { outputHandler.showOutput(it.dataUtf8) }
          .onError { outputHandler.showError("channel error", it) }.subscribe()
        return inputPublisherX()
      }

      override fun metadataPush(payload: Payload): Mono<Void> = GlobalScope.mono(
        Dispatchers.Default) {
        outputHandler.showOutput(payload.metadataUtf8)
      }.then()
    }
  }

  private fun handleIncomingPayload(payload: Payload): Flux<Payload> = GlobalScope.mono(
    Dispatchers.Default) {
    outputHandler.showOutput(payload.dataUtf8)
  }.thenMany(inputPublisherX())

  fun getInputFromSource(source: String?, nullHandler: Supplier<String>): String =
    when (source) {
      null -> nullHandler.get()
      else -> stringValue(source)
    }

  fun buildMetadata(): ByteArray? = when {
    this.metadata != null -> {
      if (this.headers != null) {
        throw UsageException("Can't specify headers and metadata")
      }

      getInputFromSource(this.metadata, Supplier { ""; }).toByteArray(StandardCharsets.UTF_8)
    }
    this.headers != null -> encodeMetadataMap(headerMap(headers), standardMimeType(metadataFormat))
    else -> ByteArray(0)
  }

  private fun inputPublisherX(): Flux<Payload> {
    return inputPublisher.inputPublisher(input ?: listOf("-"), buildMetadata())
  }

  private fun singleInputPayload(): Payload {
    return inputPublisher.singleInputPayload(input ?: listOf("-"), buildMetadata())
  }

  suspend fun run(client: RSocket): Flux<Void> = try {
    runAllOperations(client)
  } catch (e: Exception) {
    outputHandler.showError("error", e)
    Flux.empty()
  }

  private fun runAllOperations(client: RSocket): Flux<Void> =
    Flux.range(0, operations).flatMap { runSingleOperation(client) }

  private fun runSingleOperation(client: RSocket): Flux<Void> = try {
    when {
      fireAndForget -> client.fireAndForget(singleInputPayload()).thenMany(Flux.empty())
      metadataPush -> client.metadataPush(singleInputPayload()).thenMany(Flux.empty())
      requestResponse -> client.requestResponse(singleInputPayload()).flux()
      stream -> client.requestStream(singleInputPayload())
      channel -> client.requestChannel(inputPublisherX())
      else -> Flux.never()
    }
      .takeN(requestN)
      .map { it.dataUtf8 }
      .onNext { outputHandler.showOutput(it) }
      .onError { outputHandler.showError("error from server", it) }
      .onErrorResume { Flux.empty() }
      .then().flux()
  } catch (ex: Exception) {
    GlobalScope.mono(Dispatchers.Default) {
      outputHandler.showError("error before query", ex)
    }.then().flux()
  }

  companion object {
    const val NAME = "reactivesocket-cli"

    @JvmStatic
    fun main(vararg args: String) {
      exec(Main(), args.toList())
    }

    private fun exec(runnable: Main, args: List<String>) {
      val cmd = CommandLine(runnable)
      try {
        val parseResult = cmd.parseArgs(*args.toTypedArray())

        if (cmd.isUsageHelpRequested) {
          cmd.usage(cmd.out)
          exitProcess(parseResult.commandSpec().exitCodeOnUsageHelp())
        } else if (cmd.isVersionHelpRequested) {
          cmd.printVersionHelp(cmd.out)
          exitProcess(parseResult.commandSpec().exitCodeOnVersionHelp())
        }

        if (runnable.completionScript) {
          print(picocli.AutoComplete.bash("rsocket-cli", cmd))
        } else {
          runnable.run()
        }

        exitProcess(0)
      } catch (pe: CommandLine.ParameterException) {
        cmd.err.println(pe.message)
        if (!CommandLine.UnmatchedArgumentException.printSuggestions(pe, cmd.err)) {
          cmd.usage(cmd.err)
        }
        exitProcess(cmd.commandSpec.exitCodeOnInvalidInput())
      } catch (ue: UsageException) {
        cmd.err.println(ue.message)
        cmd.usage(cmd.err)
        exitProcess(cmd.commandSpec.exitCodeOnInvalidInput())
      } catch (ex: Exception) {
        ex.printStackTrace(cmd.err)
        exitProcess(cmd.commandSpec.exitCodeOnExecutionException())
      }
    }
  }
}
