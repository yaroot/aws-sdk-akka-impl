package com.example

import java.util.{List => JList, Map => JMap}
import java.util.Optional
import java.util.concurrent.CancellationException

import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.duration._
import scala.collection.JavaConverters._
import scala.util.{Failure, Success}

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{RawHeader, `User-Agent`}
import akka.http.scaladsl.model.{headers => AkkaHeaders}
import akka.stream.scaladsl._
import akka.util.ByteString
import org.reactivestreams.{Publisher, Subscriber, Subscription}
import software.amazon.awssdk.http.{HttpResponse => _, _}
import software.amazon.awssdk.http.async._
import software.amazon.awssdk.utils.AttributeMap

class AkkaHttpSdkAsyncHttpService extends SdkAsyncHttpService {
  override def createAsyncHttpClientFactory(): SdkAsyncHttpClientFactory = {
    new AkkaHttpSdkClientFactory
  }
}

class AkkaHttpSdkClientFactory extends SdkAsyncHttpClientFactory {
  override def createHttpClientWithDefaults(serviceDefaults: AttributeMap): SdkAsyncHttpClient = {
    implicit val system = ActorSystem("test")
    new AkkaHttpSdkClient(serviceDefaults)
  }
}

class AkkaHttpSdkClient(
  configAttributes: AttributeMap
)(
  implicit
  val actorSystem: ActorSystem
) extends SdkAsyncHttpClient {
  import AkkaHttp._

  implicit private val mat = ActorMaterializer()
  implicit private val ec = actorSystem.dispatcher

  override def prepareRequest(
    request: SdkHttpRequest,
    context: SdkRequestContext,
    requestProvider: SdkHttpRequestProvider,
    handler: SdkHttpResponseHandler[_]): AbortableRunnable = {

    val url = request.getEndpoint.toString + request.getResourcePath
    val params = flattenMapList(request.getParameters)
    val headers = flattenMapList(request.getHeaders)
      .map {
        case (AkkaHeaders.`Content-Type`.name, _) => Option.empty[HttpHeader]
        case (AkkaHeaders.`Content-Length`.name, _) => None
        case (AkkaHeaders.Host.name, host) => Some(AkkaHeaders.Host(host))
        case (AkkaHeaders.`User-Agent`.name, ua) => Some(fromUserAgent(ua))
        case (k, v) => Some(RawHeader(k, v))
      }
      .flatMap(_.toVector)

    val entity = {
      requestProvider.contentLength() match {
        case 0L =>
          HttpEntity.Empty
        case contentLength =>
          val contentType: ContentType = request.getFirstHeaderValue(AkkaHeaders.`Content-Type`.lowercaseName).asScala
            .map(ContentType.parse)
            .fold[ContentType](ContentTypes.NoContentType) {
            case Right(t) => t
            case Left(errs) =>
              throw new IllegalStateException(errs.map(_.formatPretty).mkString(", "))
          }
          val bufSource = Source.fromPublisher(requestProvider).map(ByteString.apply)
          HttpEntity(contentType, contentLength, bufSource)
      }
    }

    val req = HttpRequest(
      method = request.getHttpMethod.asAkka,
      uri = Uri(url).withQuery(Uri.Query(params: _*)),
      headers = headers,
      entity = entity
    )


    new AbortableRunnable {
      private val success = Promise[HttpResponse]
      @volatile private var request: Future[HttpResponse] = _

      override def abort(): Unit = {
        val ok = success.tryFailure(new CancellationException)
        if (!ok && (request ne null)) {
          request.foreach(_.entity.dataBytes.runWith(Sink.ignore))
        }
      }

      override def run(): Unit = {
        request = Http().singleRequest(req)
        request.onComplete(t => success.tryComplete(t))
        success.future.onComplete {
          case Success(resp) =>
            handler.headersReceived(fromAkkaResponse(resp))
            if(resp.entity.contentLengthOption.contains(0L)) {
              handler.complete()
            } else {
              val contentPublisher = resp.entity.dataBytes
                .mapConcat(_.asByteBuffers)
                .runWith(Sink.asPublisher(false))
              handler.onStream(wrapPublisher(contentPublisher, handler))
            }
          case Failure(exp) =>
            handler.exceptionOccurred(exp)
        }
      }
    }
  }

  override def close(): Unit = {
    actorSystem.terminate()
    Await.result(actorSystem.whenTerminated, 10.seconds)
  }

  override def getConfigurationValue[T](key: SdkHttpConfigurationOption[T]): Optional[T] = {
    if (configAttributes.containsKey(key)) Optional.of(configAttributes.get(key))
    else Optional.empty()
  }
}

object AkkaHttp {

  implicit class methodAs(private val sdkMethod: SdkHttpMethod) extends AnyVal {
    def asAkka: HttpMethod = HttpMethods.getForKey(sdkMethod.name()).getOrElse(throw new IllegalStateException("impossible"))
  }

  implicit class javaOptionalAs[A](private val opt: Optional[A]) extends AnyVal {
    def asScala: Option[A] = if(opt.isPresent) Some(opt.get()) else None
  }

  def flattenMapList(ml: JMap[String, JList[String]]): Vector[(String, String)] = {
    for {
      (k, vs) <- ml.asScala.toVector
      v <- vs.asScala
    } yield (k, v)
  }

  def fromAkkaResponse(resp: HttpResponse): SdkHttpFullResponse = {
    val hs = resp.headers.map { h => h.name() -> h.value() }
      .groupBy(_._1)
      .mapValues(_.map(_._2).asJava)
      .asJava

    SdkHttpFullResponse.builder()
      .statusCode(resp.status.intValue())
      .statusText(resp.status.reason())
      .headers(hs)
      .build()
  }

  def wrapPublisher[T](origin: Publisher[T], handler: SdkHttpResponseHandler[_]): Publisher[T] =
    (s: Subscriber[_ >: T]) => { origin.subscribe(wrapSubscriber(s, handler)) }

  def wrapSubscriber[T](origin: Subscriber[T], handler: SdkHttpResponseHandler[_]): Subscriber[T] =
    new Subscriber[T] {
      override def onError(t: Throwable): Unit = {
        origin.onError(t)
        handler.exceptionOccurred(t)
      }

      override def onComplete(): Unit = {
        origin.onComplete()
        handler.complete()
      }

      override def onNext(t: T): Unit = origin.onNext(t)

      override def onSubscribe(s: Subscription): Unit = origin.onSubscribe(s)
    }

  // workaround; aws user-agent is not RFC compliant
  def fromUserAgent(str: String): `User-Agent` = {
    val prods = str.split(" ").toVector
      .map { pair =>
        pair.split("[/]", 2) match {
          case Array(prod) => AkkaHeaders.ProductVersion(prod)
          case Array(prod, ver) => AkkaHeaders.ProductVersion(prod, ver)
        }
      }
    AkkaHeaders.`User-Agent`(prods)
  }
}
