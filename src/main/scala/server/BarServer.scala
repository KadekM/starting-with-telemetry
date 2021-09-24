package server

import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.{
  TextMapGetter,
  TextMapPropagator
}
import zhttp.http.{Request as ZioRequest, Response as ZioResponse, *}
import zhttp.service.Server
import zio.*
import zio.telemetry.opentelemetry.*
import server.*
import sttp.*
import org.apache.http.HttpHost
import org.elasticsearch.client.*
import zio.telemetry.opentelemetry.*
import zio.telemetry.opentelemetry.Tracing
import zio.telemetry.opentelemetry.TracingSyntax.*
import zio.clock.Clock

import java.lang
import scala.jdk.CollectionConverters.*

object BarServerApp extends App:
  val tracer   = serviceTracer("bar")
  val esClient = EsThinClient.liveLocalhost

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] =
    val zioTracer = (tracer ++ ZLayer
      .requires[Clock]) >>> zio.telemetry.opentelemetry.Tracing.live

    Server
      .start(9000, BarServer.api)
      .provideCustomLayer(zioTracer ++ esClient)
      .exitCode

object BarServer:
  val propagator: TextMapPropagator =
    W3CTraceContextPropagator.getInstance()
  val getter: TextMapGetter[List[Header]] =
    new TextMapGetter[List[Header]]:
      def keys(
          carrier: List[Header]
      ): lang.Iterable[String] =
        carrier.map(_.name.toString).asJava

      def get(carrier: List[Header], key: String): String =
        carrier
          .find(_.name.toString == key)
          .map(_.value.toString)
          .orNull

  val api = Http.collectM[ZioRequest] {
    case req @ Method.POST -> Root / "bar" =>
      val headers = req.headers
        .map(x => x.name.toString -> x.value.toString)
        .toMap
        .asJava
      val response = for
        esClient <- ZIO.service[EsThinClient]
        _        <- zio.console.putStrLn("bar received message ")
        _ <- ZIO
          .foreachPar_(1 to 3)(_ => esClient.clusterHealth)
          .span("make es requests")
      yield ZioResponse.text("bar response")

      val span = s"${req.method.toString()} ${req.url.asString}"

      response
        .spanFrom(
          propagator,
          req.headers,
          getter,
          span,
          SpanKind.SERVER
        )
  }
