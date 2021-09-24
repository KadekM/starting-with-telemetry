package server

import zio.*
import io.opentelemetry.exporter.jaeger.JaegerGrpcSpanExporter
import io.opentelemetry.api.trace.*
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.*
import io.opentelemetry.sdk.trace.`export`.SimpleSpanProcessor
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.semconv.resource.attributes.ResourceAttributes

def serviceTracer(serviceName: String): TaskLayer[Has[Tracer]] =
  val serviceNameResource =
    Resource.create(
      Attributes.of(ResourceAttributes.SERVICE_NAME, serviceName)
    )
  (for {
    spanExporter <- ZManaged.fromAutoCloseable(Task(
      JaegerGrpcSpanExporter
        .builder()
        .setEndpoint("http://127.0.0.1:14250")
        .build()
    ))
    spanProcessor <- ZManaged.fromAutoCloseable(UIO(SimpleSpanProcessor.create(spanExporter)))
    tracerProvider <- ZManaged.fromAutoCloseable(UIO(
      SdkTracerProvider
        .builder()
        .addSpanProcessor(spanProcessor)
        .setResource(serviceNameResource)
        .build()
    ))
    openTelemetry <- UIO(
      OpenTelemetrySdk
        .builder()
        .setTracerProvider(tracerProvider)
        .build()
    ).toManaged_
    tracer <- UIO(
      openTelemetry.getTracer("zio.telemetry.opentelemetry")
    ).toManaged_
  } yield tracer).toLayer
