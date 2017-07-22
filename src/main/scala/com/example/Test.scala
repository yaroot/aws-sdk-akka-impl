package com.example

import java.nio.ByteBuffer
import java.util.concurrent.CompletionStage
import java.util.function.BiFunction

import scala.compat.java8.FutureConverters
import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.duration._

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl._
import akka.util.ByteString
import org.reactivestreams.{Publisher, Subscriber}
import software.amazon.awssdk.async.{AsyncRequestProvider, AsyncResponseHandler}
import software.amazon.awssdk.client.builder.ClientAsyncHttpConfiguration
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.model._


object Test extends App {
  implicit class RichCompletionStage[A](private val stage: CompletionStage[A]) extends AnyVal {
    def asScala: Future[A] = {
      FutureConverters.toScala(stage)
    }
  }

  implicit val system = ActorSystem("aaa")
  implicit val mat = ActorMaterializer()
  implicit val ec = system.dispatcher

  val http = ClientAsyncHttpConfiguration
    .builder()
    .httpClientFactory(new AkkaHttpSdkClientFactory())
    .build()

  val s3 = S3AsyncClient.builder()
    .asyncHttpConfiguration(http)
    .region(Region.CN_NORTH_1)
    .build()

  val bucket = "bucket"

  val promise = Promise[Source[ByteString, Any]]

  val getobject = GetObjectRequest.builder().bucket(bucket).key("from_file").build()
  val xx = s3.getObject(getobject, new AsyncResponseHandler[GetObjectResponse, NotUsed] {
    override def responseReceived(response: GetObjectResponse): Unit = {
      if(response.contentLength() == 0) {
        promise.tryFailure(new RuntimeException("file not found"))
      }
    }

    override def exceptionOccurred(throwable: Throwable): Unit = promise.tryFailure(throwable)

    override def onStream(publisher: Publisher[ByteBuffer]): Unit = {
      val source = Source.fromPublisher(publisher).map(ByteString.apply)
      promise.trySuccess(source)
    }

    override def complete(): NotUsed = {
      promise.tryFailure(new IllegalStateException("shouldn't be called"))
      NotUsed
    }
  })

  val contentF = promise.future.flatMap { src =>
    src
      .fold(ByteString.empty)(_ ++ _)
      .runWith(Sink.last)
  }

  val filecontent = Await.result(contentF, 10.seconds)

  val putReq = PutObjectRequest.builder()
    .bucket(bucket)
    .key("to_file")
    .build()

  val asyncProvider = new AsyncRequestProvider {
    override def contentLength(): Long = filecontent.size
    override def subscribe(s: Subscriber[_ >: ByteBuffer]): Unit = {
      val pub = Source.single(filecontent)
        .mapConcat(_.asByteBuffers)
        .runWith(Sink.asPublisher[ByteBuffer](fanout = false))
      pub.subscribe(s)
    }
  }

  val putF = s3.putObject(putReq, asyncProvider).asScala
  val putResp = Await.result(putF, 10.seconds)

//  val f = FutureConverters.toScala(jf)
//  Await.ready(f, 10.seconds)
//  val x = f.value.get
//  println(x)
  system.terminate()
}


