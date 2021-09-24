package server

import io.opentelemetry.api.trace.*
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.*
import zhttp.http.{Request as ZioRequest, Response as ZioResponse, *}
import zhttp.service.Server
import zio.*
import zio.clock.Clock
import zio.telemetry.opentelemetry.*
import server.*
import sttp.*
import sttp.client3.SttpBackend
import sttp.client3.asynchttpclient.zio.AsyncHttpClientZioBackend
import sttp.capabilities
import sttp.client3.*
import zio.telemetry.opentelemetry.TracingSyntax.*

import scala.jdk.CollectionConverters.*
import scala.collection.mutable

type RequestBackend = SttpBackend[
  Task,
  capabilities.zio.ZioStreams & capabilities.WebSockets
]

object FooServerApp extends App:
  val tracer = serviceTracer("foo")

  val httpClient = AsyncHttpClientZioBackend.managed().toLayer

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] =
    val api = FooServer.api
    val zioTracer =
      (tracer ++ ZLayer.requires[Clock])
        >>> zio.telemetry.opentelemetry.Tracing.live

    Server
      .start(8000, api)
      .provideCustomLayer(zioTracer ++ httpClient)
      .exitCode

object FooServer:
  val propagator: TextMapPropagator =
    W3CTraceContextPropagator.getInstance()
  val setter: TextMapSetter[mutable.Map[String, String]] =
    (carrier, key, value) => carrier.update(key, value)
  val errorMapper: PartialFunction[Throwable, StatusCode] = {
    case _ => StatusCode.UNSET
  }

  val api = Http.collectM[ZioRequest] {
    case req @ Method.GET -> Root / "foo" =>
      val response = for
        _    <- zio.console.putStrLn("foo received message ")
        resp <- sendRequestToBar
      yield ZioResponse.text("sent")

      val span = s"${req.method.toString()} ${req.url.asString}"

      Tracing
        .root(span, SpanKind.SERVER, errorMapper)(response)
  }

  private def sendRequestToBar =
    for
      client <- ZIO.service[RequestBackend]

      carrier <- UIO(mutable.Map[String, String]().empty)
      _       <- Tracing.inject(propagator, carrier, setter)

      request = basicRequest
        .headers(carrier.toMap)
        .post(uri"http://localhost:9000/bar")

      _ <- client.send(request)
    yield "ok"
