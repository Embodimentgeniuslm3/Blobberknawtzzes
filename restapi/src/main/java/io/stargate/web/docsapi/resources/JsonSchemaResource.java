package io.stargate.web.docsapi.resources;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.stargate.web.docsapi.dao.DocumentDB;
import io.stargate.web.docsapi.exception.ErrorCode;
import io.stargate.web.docsapi.exception.ErrorCodeRuntimeException;
import io.stargate.web.docsapi.models.JsonSchemaResponse;
import io.stargate.web.docsapi.service.DocsSchemaChecker;
import io.stargate.web.docsapi.service.JsonSchemaHandler;
import io.stargate.web.models.Error;
import io.stargate.web.resources.Db;
import io.stargate.web.resources.RequestHandler;
import io.swagger.annotations.*;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.glassfish.jersey.server.ManagedAsync;

@Api(
    produces = MediaType.APPLICATION_JSON,
    consumes = MediaType.APPLICATION_JSON,
    tags = {"documents"})
@Path("/v2/namespaces/{namespace-id: [a-zA-Z_0-9]+}")
@Produces(MediaType.APPLICATION_JSON)
public class JsonSchemaResource {
  @Inject private Db dbFactory;
  @Inject private ObjectMapper mapper;
  @Inject private JsonSchemaHandler jsonSchemaHandler;
  @Inject private DocsSchemaChecker schemaChecker;

  @PUT
  @ManagedAsync
  @ApiOperation(
      value =
          "Assign a JSON schema to a collection. This will erase any schema that already exists.")
  @ApiResponses(
      value = {
        @ApiResponse(code = 200, message = "OK"),
        @ApiResponse(code = 400, message = "Bad request", response = Error.class),
        @ApiResponse(code = 401, message = "Unauthorized", response = Error.class),
        @ApiResponse(code = 403, message = "Forbidden", response = Error.class),
        @ApiResponse(code = 500, message = "Internal Server Error", response = Error.class)
      })
  @Path("collections/{collection-id}/json-schema")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response attachJsonSchema(
      @ApiParam(
              value =
                  "The token returned from the authorization endpoint. Use this token in each request.",
              required = true)
          @HeaderParam("X-Cassandra-Token")
          String token,
      @ApiParam(value = "the namespace of the collection", required = true)
          @PathParam("namespace-id")
          String namespace,
      @ApiParam(value = "the collection to add a JSON schema to", required = true)
          @PathParam("collection-id")
          String collection,
      @ApiParam(value = "The JSON schema to attach") String payload,
      @Context HttpServletRequest request) {
    return RequestHandler.handle(
        () -> {
          DocumentDB db =
              dbFactory.getDocDataStoreForToken(
                  token, RequestToHeadersMapper.getAllHeaders(request));
          JsonNode schemaRaw = null;
          try {
            schemaRaw = mapper.readTree(payload);
          } catch (JsonProcessingException e) {
            throw new ErrorCodeRuntimeException(
                ErrorCode.DOCS_API_JSON_SCHEMA_INVALID, "Malformed JSON schema provided.");
          }
          schemaChecker.checkValidity(namespace, collection, db);
          JsonSchemaResponse resp =
              jsonSchemaHandler.attachSchemaToCollection(db, namespace, collection, schemaRaw);
          return Response.ok(resp).build();
        });
  }

  @GET
  @ManagedAsync
  @ApiOperation(value = "Get a JSON schema from a collection")
  @ApiResponses(
      value = {
        @ApiResponse(code = 200, message = "OK"),
        @ApiResponse(code = 401, message = "Unauthorized", response = Error.class),
        @ApiResponse(code = 403, message = "Forbidden", response = Error.class),
        @ApiResponse(code = 404, message = "Not found", response = Error.class),
        @ApiResponse(code = 500, message = "Internal Server Error", response = Error.class)
      })
  @Path("collections/{collection-id}/json-schema")
  @Produces(MediaType.APPLICATION_JSON)
  public Response getJsonSchema(
      @ApiParam(
              value =
                  "The token returned from the authorization endpoint. Use this token in each request.",
              required = true)
          @HeaderParam("X-Cassandra-Token")
          String token,
      @ApiParam(value = "the namespace of the collection", required = true)
          @PathParam("namespace-id")
          String namespace,
      @ApiParam(value = "the collection to add a JSON schema to", required = true)
          @PathParam("collection-id")
          String collection,
      @Context HttpServletRequest request) {
    return RequestHandler.handle(
        () -> {
          DocumentDB db =
              dbFactory.getDocDataStoreForToken(
                  token, RequestToHeadersMapper.getAllHeaders(request));
          schemaChecker.checkValidity(namespace, collection, db);
          JsonSchemaResponse resp =
              jsonSchemaHandler.getJsonSchemaForCollection(db, namespace, collection);
          return Response.ok(resp).build();
        });
  }
}
