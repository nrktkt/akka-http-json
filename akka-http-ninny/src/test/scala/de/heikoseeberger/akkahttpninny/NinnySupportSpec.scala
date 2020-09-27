package de.heikoseeberger.akkahttpninny

import akka.actor.ActorSystem
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model.ContentTypes.{ `application/json`, `text/plain(UTF-8)` }
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshaller.UnsupportedContentTypeException
import akka.http.scaladsl.unmarshalling.{ Unmarshal, Unmarshaller }
import akka.stream.scaladsl.{ Sink, Source }
import io.github.kag0.ninny.JsonException
import org.scalatest.BeforeAndAfterAll

import scala.collection.immutable.Seq
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec

object NinnySupportSpec {

  final case class Foo(bar: String) {
    require(bar startsWith "bar", "bar must start with 'bar'!")
  }
}

final class NinnySupportSpec extends AsyncWordSpec with Matchers with BeforeAndAfterAll {
  import NinnySupport._
  import NinnySupportSpec._
  import io.github.kag0.ninny.Auto._

  private implicit val system = ActorSystem()

  "NinnySupport" should {
    "enable marshalling and unmarshalling objects for which `ToJson` and `FromSomeJson` exist" in {
      val foo = Foo("bar")
      Marshal(foo)
        .to[RequestEntity]
        .flatMap(Unmarshal(_).to[Foo])
        .map(_ shouldBe foo)
    }

    "enable streamed marshalling and unmarshalling for json arrays" in {
      val foos = (0 to 100).map(i => Foo(s"bar-$i")).toList

      Marshal(Source(foos))
        .to[RequestEntity]
        .flatMap(entity => Unmarshal(entity).to[SourceOf[Foo]])
        .flatMap(_.runWith(Sink.seq))
        .map(_ shouldBe foos)
    }

    "provide proper error messages for requirement errors" in {
      val entity = HttpEntity(MediaTypes.`application/json`, """{ "bar": "baz" }""")
      Unmarshal(entity)
        .to[Foo]
        .failed
        .map(_ should have message "requirement failed: bar must start with 'bar'!")
    }

    "provide stringified error representation for parsing errors" in {
      val entity = HttpEntity(MediaTypes.`application/json`, """{ "bar": 5 }""")
      Unmarshal(entity)
        .to[Foo]
        .failed
        .map { err =>
          err shouldBe a[JsonException]
          err should have message "Expected string, got 5"
        }
    }

    "fail with NoContentException when unmarshalling empty entities" in {
      val entity = HttpEntity.empty(`application/json`)
      Unmarshal(entity)
        .to[Foo]
        .failed
        .map(_ shouldBe Unmarshaller.NoContentException)
    }

    "fail with UnsupportedContentTypeException when Content-Type is not `application/json`" in {
      val entity = HttpEntity("""{ "bar": "bar" }""")
      Unmarshal(entity)
        .to[Foo]
        .failed
        .map(
          _ shouldBe UnsupportedContentTypeException(Some(`text/plain(UTF-8)`), `application/json`)
        )
    }

    "allow unmarshalling with passed in Content-Types" in {
      val foo = Foo("bar")
      val `application/json-home` =
        MediaType.applicationWithFixedCharset("json-home", HttpCharsets.`UTF-8`, "json-home")

      final object CustomNinnySupport extends NinnySupport {
        override def unmarshallerContentTypes = List(`application/json`, `application/json-home`)
      }
      import CustomNinnySupport._

      val entity = HttpEntity(`application/json-home`, """{ "bar": "bar" }""")
      Unmarshal(entity).to[Foo].map(_ shouldBe foo)
    }
  }

  override protected def afterAll() = {
    Await.ready(system.terminate(), 42.seconds)
    super.afterAll()
  }
}