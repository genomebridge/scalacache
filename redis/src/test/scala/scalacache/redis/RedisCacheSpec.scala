package scalacache.redis

import org.scalatest.{ BeforeAndAfter, ShouldMatchers, FlatSpec }
import scala.concurrent.duration._
import org.scalatest.concurrent.{ ScalaFutures, Eventually }
import org.scalatest.time.{ Span, Seconds }

import scala.language.postfixOps
import scala.util.{ Success, Failure, Try }
import redis.clients.jedis.Jedis
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

/**
 *
 * Author: c-birchall
 * Date:   13/11/07
 */
class RedisCacheSpec
    extends FlatSpec with ShouldMatchers with Eventually with BeforeAndAfter with RedisSerialization with ScalaFutures {

  Try {
    val jedis = new Jedis("localhost", 6379)
    jedis.ping()
    jedis
  } match {
    case Failure(_) => alert("Skipping tests because Redis does not appear to be running on localhost.")
    case Success(client) => {

      val cache = RedisCache(client)

      before {
        client.flushDB()
      }

      behavior of "get"

      it should "return the value stored in Redis" in {
        client.set(bytes("key1"), serialize(123))
        whenReady(cache.get("key1")) { _ should be(Some(123)) }
      }

      it should "return None if the given key does not exist in the underlying cache" in {
        whenReady(cache.get("non-existent-key")) { _ should be(None) }
      }

      behavior of "put"

      it should "store the given key-value pair in the underlying cache" in {
        whenReady(cache.put("key2", 123, None)) { _ =>
          deserialize[Int](client.get(bytes("key2"))) should be(123)
        }
      }

      behavior of "put with TTL"

      it should "store the given key-value pair in the underlying cache" in {
        whenReady(cache.put("key3", 123, Some(1 second))) { _ =>
          deserialize[Int](client.get(bytes("key3"))) should be(123)

          // Should expire after 1 second
          eventually(timeout(Span(2, Seconds))) {
            client.get(bytes("key3")) should be(null)
          }
        }
      }

      behavior of "put with TTL of zero"

      it should "store the given key-value pair in the underlying cache with no expiry" in {
        whenReady(cache.put("key4", 123, Some(Duration.Zero))) { _ =>
          deserialize[Int](client.get(bytes("key4"))) should be(123)
          client.ttl("key4") should be(-1L)
        }
      }

      behavior of "put with TTL of less than 1 second"

      it should "store the given key-value pair in the underlying cache" in {
        whenReady(cache.put("key5", 123, Some(100 milliseconds))) { _ =>
          deserialize[Int](client.get(bytes("key5"))) should be(123)
          client.pttl("key5").toLong should be > 0L

          // Should expire after 1 second
          eventually(timeout(Span(2, Seconds))) {
            client.get("key5") should be(null)
          }
        }
      }

      behavior of "caching with serialization"

      def roundTrip[V](key: String, value: V): Future[Option[V]] = {
        cache.put(key, value, None).flatMap(_ => cache.get(key))
      }

      it should "round-trip a String" in {
        whenReady(roundTrip("string", "hello")) { _ should be(Some("hello")) }
      }

      it should "round-trip a byte array" in {
        whenReady(roundTrip("bytearray", bytes("world"))) { result =>
          new String(result.get, "UTF-8") should be("world")
        }
      }

      it should "round-trip an Int" in {
        whenReady(roundTrip("int", 345)) { _ should be(Some(345)) }
      }

      it should "round-trip a Double" in {
        whenReady(roundTrip("double", 1.23)) { _ should be(Some(1.23)) }
      }

      it should "round-trip a Long" in {
        whenReady(roundTrip("long", 3456L)) { _ should be(Some(3456L)) }
      }

      it should "round-trip a Serializable case class" in {
        val cc = CaseClass(123, "wow")
        whenReady(roundTrip("caseclass", cc)) { _ should be(Some(cc)) }
      }

      behavior of "remove"

      it should "delete the given key and its value from the underlying cache" in {
        client.set(bytes("key1"), serialize(123))
        deserialize[Int](client.get(bytes("key1"))) should be(123)

        whenReady(cache.remove("key1")) { _ =>
          client.get("key1") should be(null)
        }
      }

    }

  }

  def bytes(s: String) = s.getBytes("utf-8")

}

