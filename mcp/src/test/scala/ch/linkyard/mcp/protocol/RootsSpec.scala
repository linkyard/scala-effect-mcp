package ch.linkyard.mcp.protocol

import ch.linkyard.mcp.jsonrpc2.JsonRpc
import ch.linkyard.mcp.protocol.*
import ch.linkyard.mcp.protocol.Codec
import io.circe.literal.*
import io.circe.parser.*
import io.circe.syntax.*
import org.scalatest.EitherValues.*
import org.scalatest.OptionValues
import org.scalatest.funspec.AnyFunSpec

class RootsSpec extends AnyFunSpec with OptionValues {
  describe("Roots messages") {
    describe("ListRoots request") {
      it("should serialize listRoots request correctly") {
        val listRoots = Roots.ListRoots()

        val jsonRpc = Codec.encodeServerRequest(RequestId.IdNumber(1), listRoots)
        val json = jsonRpc.asJson

        val expected = json"""
        {
          "jsonrpc": "2.0",
          "id": 1,
          "method": "roots/list"
        }
        """

        assert(json == expected)
      }

      it("should deserialize listRoots request correctly") {
        val json = json"""
        {
          "jsonrpc": "2.0",
          "id": 1,
          "method": "roots/list"
        }
        """

        val result = decode[JsonRpc.Request](json.noSpaces)
        assert(result.isRight)

        val request = result.value
        assert(request.method == "roots/list")
        assert(request.id == JsonRpc.Id.IdInt(1))
      }
    }

    describe("ListRoots response") {
      it("should serialize listRoots response correctly") {
        val response = Roots.ListRoots.Response(roots =
          List(
            Root(uri = "file:///path/to/root1", name = Some("Root 1")),
            Root(uri = "file:///path/to/root2", name = Some("Root 2")),
          )
        )

        val jsonRpc = Codec.encodeResponse(RequestId.IdNumber(1), response)
        val json = jsonRpc.asJson

        val expected = json"""
        {
          "jsonrpc": "2.0",
          "id": 1,
          "result": {
            "roots": [
              {
                "uri": "file:///path/to/root1",
                "name": "Root 1"
              },
              {
                "uri": "file:///path/to/root2",
                "name": "Root 2"
              }
            ]
          }
        }
        """

        assert(json == expected)
      }

      it("should deserialize listRoots response correctly") {
        val json = json"""
        {
          "jsonrpc": "2.0",
          "id": 1,
          "result": {
            "roots": [
              {
                "uri": "file:///path/to/root1",
                "name": "Root 1"
              },
              {
                "uri": "file:///path/to/root2",
                "name": "Root 2"
              }
            ]
          }
        }
        """

        val result = decode[JsonRpc.Response.Success](json.noSpaces)
        assert(result.isRight)

        val response = decode[Roots.ListRoots.Response](result.value.result.asJson.noSpaces)
        assert(response.isRight)
        assert(response.value.roots.length == 2)
        assert(response.value.roots.head.uri == "file:///path/to/root1")
        assert(response.value.roots.head.name.contains("Root 1"))
      }
    }

    describe("RootsListChanged notification") {
      it("should serialize rootsListChanged notification correctly") {
        val listChanged = Roots.ListChanged()

        val jsonRpc = Codec.encodeClientNotification(listChanged)
        val json = jsonRpc.asJson

        val expected = json"""
        {
          "jsonrpc": "2.0",
          "method": "notifications/roots/list_changed"
        }
        """

        assert(json == expected)
      }

      it("should deserialize rootsListChanged notification correctly") {
        val json = json"""
        {
          "jsonrpc": "2.0",
          "method": "notifications/roots/list_changed"
        }
        """

        val result = decode[JsonRpc.Notification](json.noSpaces)
        assert(result.isRight)

        val notification = result.value
        assert(notification.method == "notifications/roots/list_changed")
      }
    }

    describe("Root object") {
      it("should serialize Root object correctly") {
        val root = Root(
          uri = "file:///path/to/root",
          name = Some("Test Root"),
          _meta = Some(io.circe.JsonObject("custom" -> "value".asJson)),
        )

        val json = root.asJson

        val expected = json"""
        {
          "uri": "file:///path/to/root",
          "name": "Test Root",
          "_meta": {
            "custom": "value"
          }
        }
        """

        assert(json == expected)
      }

      it("should deserialize Root object correctly") {
        val json = json"""
        {
          "uri": "file:///path/to/root",
          "name": "Test Root",
          "_meta": {
            "custom": "value"
          }
        }
        """

        val result = decode[Root](json.noSpaces)
        assert(result.isRight)
        assert(result.value.uri == "file:///path/to/root")
        assert(result.value.name.contains("Test Root"))
        assert(result.value._meta.isDefined)
      }
    }
  }
}
