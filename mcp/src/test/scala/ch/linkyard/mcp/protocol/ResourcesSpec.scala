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

import java.time.Instant

class ResourcesSpec extends AnyFunSpec with OptionValues {
  describe("Resources messages") {
    describe("ListResources request") {
      it("should serialize listResources request correctly") {
        val listResources = Resources.ListResources(cursor =
          Some("cursor-123")
        )

        val jsonRpc = Codec.encodeClientRequest(RequestId.IdNumber(1), listResources)
        val json = jsonRpc.asJson

        val expected = json"""
        {
          "jsonrpc": "2.0",
          "id": 1,
          "method": "resources/list",
          "params": {
            "cursor": "cursor-123"
          }
        }
        """

        assert(json == expected)
      }

      it("should deserialize listResources request correctly") {
        val json = json"""
        {
          "jsonrpc": "2.0",
          "id": 1,
          "method": "resources/list",
          "params": {
            "cursor": "cursor-123"
          }
        }
        """

        val result = decode[JsonRpc.Request](json.noSpaces)
        assert(result.isRight)

        val request = result.value
        assert(request.method == "resources/list")
        assert(request.id == JsonRpc.Id.IdInt(1))

        val params = request.params.value
        val listResources = decode[Resources.ListResources](params.asJson.noSpaces)
        assert(listResources.isRight)
        assert(listResources.value.cursor.contains("cursor-123"))
      }
    }

    describe("ListResources response") {
      it("should serialize listResources response correctly") {
        val response = Resources.ListResources.Response(
          resources = List(
            Resource(
              uri = "file:///path/to/file1.txt",
              name = "file1.txt",
              title = Some("File 1"),
              description = Some("First test file"),
              mimeType = Some("text/plain"),
              size = Some(1024L),
              annotations = Some(Resource.Annotations(
                audience = Some(List(Role.User)),
                priority = Some(0.8),
                lastModified = Some(Instant.parse("2023-01-01T00:00:00Z")),
              )),
            ),
            Resource(
              uri = "file:///path/to/file2.txt",
              name = "file2.txt",
              title = Some("File 2"),
              description = Some("Second test file"),
              mimeType = Some("text/plain"),
              size = Some(2048L),
              annotations = None,
            ),
          ),
          nextCursor = Some("cursor-456"),
        )

        val jsonRpc = Codec.encodeResponse(RequestId.IdNumber(1), response)
        val json = jsonRpc.asJson

        val expected = json"""
        {
          "jsonrpc": "2.0",
          "id": 1,
          "result": {
            "resources": [
              {
                "uri": "file:///path/to/file1.txt",
                "name": "file1.txt",
                "title": "File 1",
                "description": "First test file",
                "mimeType": "text/plain",
                "size": 1024,
                "annotations": {
                  "audience": ["user"],
                  "priority": 0.8,
                  "lastModified": "2023-01-01T00:00:00Z"
                }
              },
              {
                "uri": "file:///path/to/file2.txt",
                "name": "file2.txt",
                "title": "File 2",
                "description": "Second test file",
                "mimeType": "text/plain",
                "size": 2048
              }
            ],
            "nextCursor": "cursor-456"
          }
        }
        """

        assert(json == expected)
      }

      it("should deserialize listResources response correctly") {
        val json = json"""
        {
          "jsonrpc": "2.0",
          "id": 1,
          "result": {
            "resources": [
              {
                "uri": "file:///path/to/file1.txt",
                "name": "file1.txt",
                "title": "File 1",
                "description": "First test file",
                "mimeType": "text/plain",
                "size": 1024,
                "annotations": {
                  "audience": ["user"],
                  "priority": 0.8,
                  "lastModified": "2023-01-01T00:00:00Z"
                }
              },
              {
                "uri": "file:///path/to/file2.txt",
                "name": "file2.txt",
                "title": "File 2",
                "description": "Second test file",
                "mimeType": "text/plain",
                "size": 2048
              }
            ],
            "nextCursor": "cursor-456"
          }
        }
        """

        val result = decode[JsonRpc.Response.Success](json.noSpaces)
        assert(result.isRight)

        val response = decode[Resources.ListResources.Response](result.value.result.asJson.noSpaces)
        assert(response.isRight)
        assert(response.value.resources.length == 2)
        assert(response.value.resources.head.uri == "file:///path/to/file1.txt")
        assert(response.value.resources.head.name == "file1.txt")
        assert(response.value.resources.head.title.contains("File 1"))
        assert(response.value.nextCursor.contains("cursor-456"))
      }
    }

    describe("ReadResource request") {
      it("should serialize readResource request correctly") {
        val readResource = Resources.ReadResource(uri =
          "file:///path/to/file.txt"
        )

        val jsonRpc = Codec.encodeClientRequest(RequestId.IdNumber(1), readResource)
        val json = jsonRpc.asJson

        val expected = json"""
        {
          "jsonrpc": "2.0",
          "id": 1,
          "method": "resources/read",
          "params": {
            "uri": "file:///path/to/file.txt"
          }
        }
        """

        assert(json == expected)
      }

      it("should deserialize readResource request correctly") {
        val json = json"""
        {
          "jsonrpc": "2.0",
          "id": 1,
          "method": "resources/read",
          "params": {
            "uri": "file:///path/to/file.txt"
          }
        }
        """

        val result = decode[JsonRpc.Request](json.noSpaces)
        assert(result.isRight)

        val request = result.value
        assert(request.method == "resources/read")
        assert(request.id == JsonRpc.Id.IdInt(1))

        val params = request.params.value
        val readResource = decode[Resources.ReadResource](params.asJson.noSpaces)
        assert(readResource.isRight)
        assert(readResource.value.uri == "file:///path/to/file.txt")
      }
    }

    describe("ReadResource response") {
      it("should serialize readResource response correctly") {
        val response = Resources.ReadResource.Response(contents =
          List(
            Resource.Contents.Text(
              uri = "file:///path/to/file.txt",
              mimeType = Some("text/plain"),
              text = "Hello, world!",
              _meta = Some(io.circe.JsonObject("encoding" -> "utf-8".asJson)),
            )
          )
        )

        val jsonRpc = Codec.encodeResponse(RequestId.IdNumber(1), response)
        val json = jsonRpc.asJson

        val expected = json"""
        {
          "jsonrpc": "2.0",
          "id": 1,
          "result": {
            "contents": [
              {
                "uri": "file:///path/to/file.txt",
                "mimeType": "text/plain",
                "text": "Hello, world!",
                "_meta": {
                  "encoding": "utf-8"
                }
              }
            ]
          }
        }
        """

        assert(json == expected)
      }

      it("should deserialize readResource response correctly") {
        val json = json"""
        {
          "jsonrpc": "2.0",
          "id": 1,
          "result": {
            "contents": [
              {
                "uri": "file:///path/to/file.txt",
                "mimeType": "text/plain",
                "text": "Hello, world!",
                "_meta": {
                  "encoding": "utf-8"
                }
              }
            ]
          }
        }
        """

        val result = decode[JsonRpc.Response.Success](json.noSpaces)
        assert(result.isRight)

        val response = decode[Resources.ReadResource.Response](result.value.result.asJson.noSpaces)
        assert(response.isRight)
        assert(response.value.contents.length == 1)
        val content = response.value.contents.head.asInstanceOf[Resource.Contents.Text]
        assert(content.uri == "file:///path/to/file.txt")
        assert(content.text == "Hello, world!")
        assert(content.mimeType.contains("text/plain"))
      }
    }

    describe("Subscribe request") {
      it("should serialize subscribe request correctly") {
        val subscribe = Resources.Subscribe(uri =
          "file:///path/to/file.txt"
        )

        val jsonRpc = Codec.encodeClientRequest(RequestId.IdNumber(1), subscribe)
        val json = jsonRpc.asJson

        val expected = json"""
        {
          "jsonrpc": "2.0",
          "id": 1,
          "method": "resources/subscribe",
          "params": {
            "uri": "file:///path/to/file.txt"
          }
        }
        """

        assert(json == expected)
      }

      it("should deserialize subscribe request correctly") {
        val json = json"""
        {
          "jsonrpc": "2.0",
          "id": 1,
          "method": "resources/subscribe",
          "params": {
            "uri": "file:///path/to/file.txt"
          }
        }
        """

        val result = decode[JsonRpc.Request](json.noSpaces)
        assert(result.isRight)

        val request = result.value
        assert(request.method == "resources/subscribe")
        assert(request.id == JsonRpc.Id.IdInt(1))

        val params = request.params.value
        val subscribe = decode[Resources.Subscribe](params.asJson.noSpaces)
        assert(subscribe.isRight)
        assert(subscribe.value.uri == "file:///path/to/file.txt")
      }
    }

    describe("Subscribe response") {
      it("should serialize subscribe response correctly") {
        val response = Resources.Subscribe.Response()

        val jsonRpc = Codec.encodeResponse(RequestId.IdNumber(1), response)
        val json = jsonRpc.asJson

        val expected = json"""
        {
          "jsonrpc": "2.0",
          "id": 1,
          "result": {}
        }
        """

        assert(json == expected)
      }

      it("should deserialize subscribe response correctly") {
        val json = json"""
        {
          "jsonrpc": "2.0",
          "id": 1,
          "result": {}
        }
        """

        val result = decode[JsonRpc.Response.Success](json.noSpaces)
        assert(result.isRight)

        val response = decode[Resources.Subscribe.Response](result.value.result.asJson.noSpaces)
        assert(response.isRight)
      }
    }

    describe("Unsubscribe request") {
      it("should serialize unsubscribe request correctly") {
        val unsubscribe = Resources.Unsubscribe(uri =
          "file:///path/to/file.txt"
        )

        val jsonRpc = Codec.encodeClientRequest(RequestId.IdNumber(1), unsubscribe)
        val json = jsonRpc.asJson

        val expected = json"""
        {
          "jsonrpc": "2.0",
          "id": 1,
          "method": "resources/unsubscribe",
          "params": {
            "uri": "file:///path/to/file.txt"
          }
        }
        """

        assert(json == expected)
      }

      it("should deserialize unsubscribe request correctly") {
        val json = json"""
        {
          "jsonrpc": "2.0",
          "id": 1,
          "method": "resources/unsubscribe",
          "params": {
            "uri": "file:///path/to/file.txt"
          }
        }
        """

        val result = decode[JsonRpc.Request](json.noSpaces)
        assert(result.isRight)

        val request = result.value
        assert(request.method == "resources/unsubscribe")
        assert(request.id == JsonRpc.Id.IdInt(1))

        val params = request.params.value
        val unsubscribe = decode[Resources.Unsubscribe](params.asJson.noSpaces)
        assert(unsubscribe.isRight)
        assert(unsubscribe.value.uri == "file:///path/to/file.txt")
      }
    }

    describe("Unsubscribe response") {
      it("should serialize unsubscribe response correctly") {
        val response = Resources.Unsubscribe.Response()

        val jsonRpc = Codec.encodeResponse(RequestId.IdNumber(1), response)
        val json = jsonRpc.asJson

        val expected = json"""
        {
          "jsonrpc": "2.0",
          "id": 1,
          "result": {}
        }
        """

        assert(json == expected)
      }

      it("should deserialize unsubscribe response correctly") {
        val json = json"""
        {
          "jsonrpc": "2.0",
          "id": 1,
          "result": {}
        }
        """

        val result = decode[JsonRpc.Response.Success](json.noSpaces)
        assert(result.isRight)

        val response = decode[Resources.Unsubscribe.Response](result.value.result.asJson.noSpaces)
        assert(response.isRight)
      }
    }

    describe("ResourceUpdated notification") {
      it("should serialize resourceUpdated notification correctly") {
        val updated = Resources.Updated(uri =
          "file:///path/to/file.txt"
        )

        val jsonRpc = Codec.encodeServerNotification(updated)
        val json = jsonRpc.asJson

        val expected = json"""
        {
          "jsonrpc": "2.0",
          "method": "notifications/resources/updated",
          "params": {
            "uri": "file:///path/to/file.txt"
          }
        }
        """

        assert(json == expected)
      }

      it("should deserialize resourceUpdated notification correctly") {
        val json = json"""
        {
          "jsonrpc": "2.0",
          "method": "notifications/resources/updated",
          "params": {
            "uri": "file:///path/to/file.txt"
          }
        }
        """

        val result = decode[JsonRpc.Notification](json.noSpaces)
        assert(result.isRight)

        val notification = result.value
        assert(notification.method == "notifications/resources/updated")

        val params = notification.params.value
        val updated = decode[Resources.Updated](params.asJson.noSpaces)
        assert(updated.isRight)
        assert(updated.value.uri == "file:///path/to/file.txt")
      }
    }

    describe("ResourceListChanged notification") {
      it("should serialize resourceListChanged notification correctly") {
        val listChanged = Resources.ListChanged()

        val jsonRpc = Codec.encodeServerNotification(listChanged)
        val json = jsonRpc.asJson

        val expected = json"""
        {
          "jsonrpc": "2.0",
          "method": "notifications/resources/list_changed"
        }
        """

        assert(json == expected)
      }

      it("should deserialize resourceListChanged notification correctly") {
        val json = json"""
        {
          "jsonrpc": "2.0",
          "method": "notifications/resources/list_changed"
        }
        """

        val result = decode[JsonRpc.Notification](json.noSpaces)
        assert(result.isRight)

        val notification = result.value
        assert(notification.method == "notifications/resources/list_changed")
      }
    }

    describe("Resource object") {
      it("should serialize Resource object correctly") {
        val resource = Resource(
          uri = "file:///path/to/file.txt",
          name = "file.txt",
          title = Some("Test File"),
          description = Some("A test file"),
          mimeType = Some("text/plain"),
          size = Some(1024L),
          annotations = Some(Resource.Annotations(
            audience = Some(List(Role.User)),
            priority = Some(0.8),
            lastModified = Some(Instant.parse("2023-01-01T00:00:00Z")),
          )),
        )

        val json = resource.asJson

        val expected = json"""
        {
          "uri": "file:///path/to/file.txt",
          "name": "file.txt",
          "title": "Test File",
          "description": "A test file",
          "mimeType": "text/plain",
          "size": 1024,
          "annotations": {
            "audience": ["user"],
            "priority": 0.8,
            "lastModified": "2023-01-01T00:00:00Z"
          }
        }
        """

        assert(json == expected)
      }

      it("should deserialize Resource object correctly") {
        val json = json"""
        {
          "uri": "file:///path/to/file.txt",
          "name": "file.txt",
          "title": "Test File",
          "description": "A test file",
          "mimeType": "text/plain",
          "size": 1024,
          "annotations": {
            "audience": ["user"],
            "priority": 0.8,
            "lastModified": "2023-01-01T00:00:00Z"
          }
        }
        """

        val result = decode[Resource](json.noSpaces)
        assert(result.isRight)
        assert(result.value.uri == "file:///path/to/file.txt")
        assert(result.value.name == "file.txt")
        assert(result.value.title.contains("Test File"))
        assert(result.value.annotations.isDefined)
        assert(result.value.annotations.get.audience.contains(List(Role.User)))
      }
    }

    describe("Resource.Contents") {
      it("should serialize Text content correctly") {
        val content = Resource.Contents.Text(
          uri = "file:///path/to/file.txt",
          mimeType = Some("text/plain"),
          text = "Hello, world!",
          _meta = Some(io.circe.JsonObject("encoding" -> "utf-8".asJson)),
        )

        val json = content.asJson

        val expected = json"""
        {
          "uri": "file:///path/to/file.txt",
          "mimeType": "text/plain",
          "text": "Hello, world!",
          "_meta": {
            "encoding": "utf-8"
          }
        }
        """

        assert(json == expected)
      }

      it("should serialize Blob content correctly") {
        val content = Resource.Contents.Blob(
          uri = "file:///path/to/file.bin",
          mimeType = Some("application/octet-stream"),
          blob = "SGVsbG8sIHdvcmxkIQ==",
          _meta = Some(io.circe.JsonObject("encoding" -> "base64".asJson)),
        )

        val json = content.asJson

        val expected = json"""
        {
          "uri": "file:///path/to/file.bin",
          "mimeType": "application/octet-stream",
          "blob": "SGVsbG8sIHdvcmxkIQ==",
          "_meta": {
            "encoding": "base64"
          }
        }
        """

        assert(json == expected)
      }

      it("should deserialize Text content correctly") {
        val json = json"""
        {
          "uri": "file:///path/to/file.txt",
          "mimeType": "text/plain",
          "text": "Hello, world!",
          "_meta": {
            "encoding": "utf-8"
          }
        }
        """

        val result = decode[Resource.Contents](json.noSpaces)
        assert(result.isRight)
        val content = result.value.asInstanceOf[Resource.Contents.Text]
        assert(content.uri == "file:///path/to/file.txt")
        assert(content.text == "Hello, world!")
        assert(content.mimeType.contains("text/plain"))
      }

      it("should deserialize Blob content correctly") {
        val json = json"""
        {
          "uri": "file:///path/to/file.bin",
          "mimeType": "application/octet-stream",
          "blob": "SGVsbG8sIHdvcmxkIQ==",
          "_meta": {
            "encoding": "base64"
          }
        }
        """

        val result = decode[Resource.Contents](json.noSpaces)
        assert(result.isRight)
        val content = result.value.asInstanceOf[Resource.Contents.Blob]
        assert(content.uri == "file:///path/to/file.bin")
        assert(content.blob == "SGVsbG8sIHdvcmxkIQ==")
        assert(content.mimeType.contains("application/octet-stream"))
      }
    }

    describe("Resource.Template object") {
      it("should serialize Resource.Template object correctly") {
        val template = Resource.Template(
          uriTemplate = "file:///path/to/{name}.txt",
          name = "text-file-template",
          title = Some("Text File Template"),
          description = Some("Template for text files"),
          mimeType = Some("text/plain"),
          annotations = Some(Resource.Annotations(
            audience = Some(List(Role.User)),
            priority = Some(0.8),
            lastModified = Some(Instant.parse("2023-01-01T00:00:00Z")),
          )),
        )

        val json = template.asJson

        val expected = json"""
        {
          "uriTemplate": "file:///path/to/{name}.txt",
          "name": "text-file-template",
          "title": "Text File Template",
          "description": "Template for text files",
          "mimeType": "text/plain",
          "annotations": {
            "audience": ["user"],
            "priority": 0.8,
            "lastModified": "2023-01-01T00:00:00Z"
          }
        }
        """

        assert(json == expected)
      }

      it("should deserialize Resource.Template object correctly") {
        val json = json"""
        {
          "uriTemplate": "file:///path/to/{name}.txt",
          "name": "text-file-template",
          "title": "Text File Template",
          "description": "Template for text files",
          "mimeType": "text/plain",
          "annotations": {
            "audience": ["user"],
            "priority": 0.8,
            "lastModified": "2023-01-01T00:00:00Z"
          }
        }
        """

        val result = decode[Resource.Template](json.noSpaces)
        assert(result.isRight)
        assert(result.value.uriTemplate == "file:///path/to/{name}.txt")
        assert(result.value.name == "text-file-template")
        assert(result.value.title.contains("Text File Template"))
        assert(result.value.description.contains("Template for text files"))
        assert(result.value.mimeType.contains("text/plain"))
        assert(result.value.annotations.isDefined)
        assert(result.value.annotations.get.audience.contains(List(Role.User)))
      }
    }
  }
}
