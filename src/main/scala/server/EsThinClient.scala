package server

import io.opentelemetry.api.trace.Tracer
import org.apache.http.HttpHost
import org.elasticsearch.action.admin.cluster.health.*
import org.elasticsearch.client.*
import zio.*
import zio.blocking.*
import zio.telemetry.opentelemetry.*
import zio.telemetry.opentelemetry.Tracing
import zio.telemetry.opentelemetry.TracingSyntax.*

trait EsThinClient:
  def clusterHealth
      : ZIO[Blocking & Tracing, Throwable, ClusterHealthResponse]

object EsThinClient:
  type EsThinClientService = Has[EsThinClient]

  def liveLocalhost: ZLayer[Any, Throwable, EsThinClientService] =
    val makeClient = ZIO.effect(
      new RestHighLevelClient(
        RestClient.builder(new HttpHost("localhost", 9200, "http"))
      )
    )
    ZManaged
      .fromAutoCloseable(makeClient)
      .map { restClient =>
        new EsThinClient:
          override def clusterHealth: ZIO[
            Blocking & Tracing,
            Throwable,
            ClusterHealthResponse
          ] =
            effectBlockingIO(
              restClient
                .cluster()
                .health(
                  new ClusterHealthRequest(),
                  RequestOptions.DEFAULT
                )
            )
              .span("health request")
      }
      .toLayer
