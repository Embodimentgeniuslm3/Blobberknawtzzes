/*
 * Copyright The Stargate Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.stargate.web.docsapi.service;

import static io.stargate.db.schema.Column.Kind.Clustering;
import static io.stargate.db.schema.Column.Kind.PartitionKey;
import static io.stargate.db.schema.Column.Kind.Regular;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.datastax.oss.driver.shaded.guava.common.collect.ImmutableList;
import com.datastax.oss.driver.shaded.guava.common.collect.ImmutableMap;
import com.datastax.oss.driver.shaded.guava.common.collect.ImmutableMap.Builder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.stargate.auth.AuthenticationService;
import io.stargate.auth.AuthenticationSubject;
import io.stargate.auth.AuthorizationService;
import io.stargate.auth.Scope;
import io.stargate.auth.SourceAPI;
import io.stargate.auth.UnauthorizedException;
import io.stargate.db.datastore.AbstractDataStoreTest;
import io.stargate.db.datastore.DataStoreFactory;
import io.stargate.db.schema.Column;
import io.stargate.db.schema.Column.Type;
import io.stargate.db.schema.ImmutableColumn;
import io.stargate.db.schema.ImmutableKeyspace;
import io.stargate.db.schema.ImmutableSchema;
import io.stargate.db.schema.ImmutableTable;
import io.stargate.db.schema.Keyspace;
import io.stargate.db.schema.Schema;
import io.stargate.db.schema.Table;
import io.stargate.web.docsapi.dao.DocumentDB;
import io.stargate.web.docsapi.models.DocumentResponseWrapper;
import io.stargate.web.docsapi.resources.DocumentResourceV2;
import io.stargate.web.resources.Db;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.PathSegment;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriInfo;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class DocumentServiceTest extends AbstractDataStoreTest {

  private static final Object SEPARATOR = new Object();

  protected static final Table table =
      ImmutableTable.builder()
          .keyspace("test_docs")
          .name("collection1")
          .addColumns(
              ImmutableColumn.builder().name("key").type(Type.Text).kind(PartitionKey).build())
          .addColumns(clusteringColumns())
          .addColumns(ImmutableColumn.builder().name("leaf").type(Type.Text).kind(Regular).build())
          .addColumns(
              ImmutableColumn.builder().name("text_value").type(Type.Text).kind(Regular).build())
          .addColumns(
              ImmutableColumn.builder().name("dbl_value").type(Type.Double).kind(Regular).build())
          .addColumns(
              ImmutableColumn.builder().name("bool_value").type(Type.Boolean).kind(Regular).build())
          .build();

  private static final Keyspace keyspace =
      ImmutableKeyspace.builder().name("test_docs").addTables(table).build();

  private static final Schema schema = ImmutableSchema.builder().addKeyspaces(keyspace).build();
  private static final ObjectMapper mapper = new ObjectMapper();

  private final AtomicLong now = new AtomicLong();
  private final TimeSource timeSource = now::get;
  private final DocsApiConfiguration config = new DocsApiConfiguration() {};
  private final DocsSchemaChecker schemaChecker = new DocsSchemaChecker();
  private final JsonConverter converter = new JsonConverter(mapper, config);
  private final String authToken = "test-auth-token";
  private final AuthenticationSubject subject = AuthenticationSubject.of(authToken, "user1", false);
  @Mock private AuthenticationService authenticationService;
  @Mock private AuthorizationService authorizationService;
  @Mock private DataStoreFactory dataStoreFactory;
  @Mock private UriInfo uriInfo;

  @Mock(answer = RETURNS_DEEP_STUBS)
  private HttpHeaders headers;

  @Mock(answer = RETURNS_DEEP_STUBS)
  private HttpServletRequest request;

  private Db db;
  private DocumentService service;
  private DocumentResourceV2 resource;

  private static Column[] clusteringColumns() {
    Column[] columns = new Column[DocumentDB.MAX_DEPTH];
    for (int i = 0; i < columns.length; i++) {
      columns[i] = ImmutableColumn.builder().name("p" + i).type(Type.Text).kind(Clustering).build();
    }
    return columns;
  }

  @Override
  protected Schema schema() {
    return schema;
  }

  @BeforeEach
  void setup() throws UnauthorizedException {
    when(dataStoreFactory.createInternal(any())).thenReturn(datastore());
    when(dataStoreFactory.create(any(), any())).thenReturn(datastore());

    db = new Db(authenticationService, authorizationService, dataStoreFactory);

    when(authenticationService.validateToken(eq(authToken), anyMap())).thenReturn(subject);
    service = new DocumentService(timeSource, mapper, converter, config, schemaChecker);
    resource = new DocumentResourceV2(db, mapper, service, config, schemaChecker);
  }

  private Map<String, Object> leafRow(String id) {
    return m("key", id, "leaf", "test-value");
  }

  private Map<String, Object> row(String id, Double value, String... path) {
    Builder<String, Object> map = row(id, path);
    map.put("dbl_value", value);
    return map.build();
  }

  private Map<String, Object> row(String id, Boolean value, String... path) {
    Builder<String, Object> map = row(id, path);
    map.put("bool_value", value);
    return map.build();
  }

  private Map<String, Object> row(String id, String value, String... path) {
    Builder<String, Object> map = row(id, path);
    map.put("text_value", value);
    return map.build();
  }

  private Builder<String, Object> row(String id, String... path) {
    Builder<String, Object> map = ImmutableMap.builder();
    map.put("key", id);
    map.put("writetime(leaf)", 123);
    String leaf = null;
    for (int i = 0; i < DocumentDB.MAX_DEPTH; i++) {
      String p;
      if (i < path.length) {
        p = path[i];
        leaf = p;
      } else {
        p = "";
      }

      map.put("p" + i, p);
    }

    assertThat(leaf).isNotNull();
    map.put("leaf", leaf);
    return map;
  }

  private <T> DocumentResponseWrapper<T> unwrap(Response r) throws JsonProcessingException {
    assertThat(r.getStatus())
        .withFailMessage("Unexpected error code: " + r.getStatus() + ", message: " + r.getEntity())
        .isEqualTo(Status.OK.getStatusCode());

    String entity = (String) r.getEntity();
    @SuppressWarnings("unchecked")
    DocumentResponseWrapper<T> resp = mapper.readValue(entity, DocumentResponseWrapper.class);

    assertThat(resp).isNotNull();
    return resp;
  }

  private <T> DocumentResponseWrapper<T> getDocPath(
      String id, String where, String fields, List<PathSegment> path)
      throws JsonProcessingException {
    return unwrap(
        resource.getDocPath(
            headers,
            uriInfo,
            authToken,
            keyspace.name(),
            table.name(),
            id,
            path,
            where,
            fields,
            0,
            null,
            false,
            request));
  }

  private <T> DocumentResponseWrapper<T> searchDoc(String where, String fields)
      throws JsonProcessingException {
    return unwrap(
        resource.searchDoc(
            headers,
            uriInfo,
            authToken,
            keyspace.name(),
            table.name(),
            where,
            fields,
            20,
            null,
            false,
            request));
  }

  private <T> String getDocPathRaw(String id, String where, String fields, List<PathSegment> path) {
    Response r =
        resource.getDocPath(
            headers,
            uriInfo,
            authToken,
            keyspace.name(),
            table.name(),
            id,
            path,
            where,
            fields,
            0,
            null,
            true, // raw
            request);

    assertThat(r.getStatus())
        .withFailMessage("Unexpected error code: " + r.getStatus() + ", message: " + r.getEntity())
        .isEqualTo(Status.OK.getStatusCode());

    return (String) r.getEntity();
  }

  private PathSegment p(String segment) {
    return new PathSegment() {
      @Override
      public String getPath() {
        return segment;
      }

      @Override
      public MultivaluedMap<String, String> getMatrixParameters() {
        throw new UnsupportedOperationException();
      }
    };
  }

  private Map<String, Object> m(Object... keyValues) {
    assertThat(keyValues.length).isEven();
    Builder<String, Object> map = ImmutableMap.builder();
    for (int i = 0; i < keyValues.length; i++) {
      Object key = keyValues[i++];
      Object value = keyValues[i];
      map.put(key.toString(), value);
    }
    return map.build();
  }

  private String selectAll(String where) {
    return "SELECT key, p0, p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11, p12, p13, p14, p15, p16, p17, p18, p19, p20, p21, p22, p23, p24, p25, p26, p27, p28, p29, p30, p31, p32, p33, p34, p35, p36, p37, p38, p39, p40, p41, p42, p43, p44, p45, p46, p47, p48, p49, p50, p51, p52, p53, p54, p55, p56, p57, p58, p59, p60, p61, p62, p63, leaf, text_value, dbl_value, bool_value, WRITETIME(leaf) FROM test_docs.collection1"
        + (where.isEmpty() ? "" : " " + where);
  }

  @Test
  void testGetDocById() throws JsonProcessingException {
    final String id = "id1";
    withQuery(table, selectAll("WHERE key = ?"), id)
        .returning(ImmutableList.of(row(id, 1.0, "a"), row(id, 2.0, "b")));
    DocumentResponseWrapper<Map<String, ?>> r =
        unwrap(
            resource.getDoc(
                headers,
                uriInfo,
                authToken,
                keyspace.name(),
                table.name(),
                id,
                null,
                null,
                0,
                null,
                false,
                request));
    assertThat(r.getDocumentId()).isEqualTo(id);
    assertThat(r.getPageState()).isNull();
    assertThat(r.getData()).isEqualTo(m("a", 1, "b", 2));
  }

  @Test
  void testGetDocPath() throws JsonProcessingException {
    final String id = "id0";
    withQuery(table, selectAll("WHERE key = ? AND p0 = ? AND p1 = ?"), id, "a", "c")
        .returning(ImmutableList.of(row(id, 1.0, "a", "c", "x"), row(id, 2.0, "a", "c", "y")));

    DocumentResponseWrapper<Map<String, ?>> r =
        getDocPath(id, null, null, ImmutableList.of(p("a"), p("c")));
    assertThat(r.getDocumentId()).isEqualTo(id);
    assertThat(r.getPageState()).isNull();
    assertThat(r.getData()).isEqualTo(m("x", 1, "y", 2));
  }

  @Test
  void testGetDocPathRaw() throws JsonProcessingException {
    final String id = "id0";
    withQuery(table, selectAll("WHERE key = ? AND p0 = ? AND p1 = ?"), id, "a", "c")
        .returning(ImmutableList.of(row(id, 1.0, "a", "c", "x"), row(id, 2.0, "a", "c", "y")));

    String result = getDocPathRaw(id, null, null, ImmutableList.of(p("a"), p("c")));
    assertThat(result).isEqualTo("{\"x\":1,\"y\":2}");
  }

  @Test
  void testGetDocPathWhere() throws JsonProcessingException {
    final String id = "id1";
    ImmutableList<Map<String, Object>> rows =
        ImmutableList.of(
            row(id, 3.0, "a", "d", "c"), row(id, 10.0, "a", "e", "c"), row(id, 1.0, "a", "f", "c"));
    withQuery(
            table,
            selectAll(
                "WHERE key = ? AND leaf = ? AND p0 = ? AND p1 > ? AND p2 = ? AND dbl_value > ? ALLOW FILTERING"),
            id,
            "c",
            "a",
            "",
            "c",
            1.0d)
        .returning(rows);
    withQuery(
            table,
            selectAll(
                "WHERE key = ? AND leaf = ? AND p0 = ? AND p1 > ? AND p2 = ? AND dbl_value < ? ALLOW FILTERING"),
            id,
            "c",
            "a",
            "",
            "c",
            1000.0d)
        .returning(rows);

    DocumentResponseWrapper<List<Map<String, ?>>> r =
        getDocPath(id, "{\"a.*.c\":{\"$gt\":1,\"$ne\":10}}", null, ImmutableList.of());
    assertThat(r.getDocumentId()).isEqualTo(id);
    assertThat(r.getPageState()).isNull();
    assertThat(r.getData())
        .isEqualTo(ImmutableList.of(m("a", m("d", m("c", 3))), m("a", m("f", m("c", 1)))));

    r = getDocPath(id, "{\"a.*.c\":{\"$lt\":1000}}", null, ImmutableList.of());
    assertThat(r.getDocumentId()).isEqualTo(id);
    assertThat(r.getPageState()).isNull();
    assertThat(r.getData())
        .isEqualTo(
            ImmutableList.of(
                m("a", m("d", m("c", 3))), m("a", m("e", m("c", 10))), m("a", m("f", m("c", 1)))));
  }

  @Test
  void testGetDocPathFields() throws JsonProcessingException {
    final String id = "id1";
    withQuery(table, selectAll("WHERE key = ? AND p0 = ? AND p1 > ? ALLOW FILTERING"), id, "a", "")
        .returning(
            ImmutableList.of(
                row(id, 3.0, "a", "d", "c"),
                row(id, true, "a", "d", "z"),
                row(id, "yy", new String[] {"a", "d", "y"}),
                row(id, 10.0, "a", "e", "c"),
                row(id, 20.0, "a", "e", "z"),
                row(id, 100.0, "a", "f", "c")));

    DocumentResponseWrapper<List<Map<String, ?>>> r =
        getDocPath(id, "{\"a.*.c\":{\"$gte\":1,\"$ne\":10}}", "[\"c\", \"z\"]", ImmutableList.of());
    assertThat(r.getDocumentId()).isEqualTo(id);
    assertThat(r.getPageState()).isNull();
    assertThat(r.getData())
        .isEqualTo(
            ImmutableList.of(m("a", m("d", m("c", 3, "z", true))), m("a", m("f", m("c", 100)))));

    r =
        getDocPath(
            id, "{\"a.*.c\":{\"$lt\":1000,\"$ne\":10}}", "[\"c\", \"z\"]", ImmutableList.of());
    assertThat(r.getDocumentId()).isEqualTo(id);
    assertThat(r.getPageState()).isNull();
    assertThat(r.getData())
        .isEqualTo(
            ImmutableList.of(m("a", m("d", m("c", 3, "z", true))), m("a", m("f", m("c", 100)))));

    r = getDocPath(id, "{\"a.*.c\":{\"$in\":[3]}}", "[\"c\", \"z\"]", ImmutableList.of());
    assertThat(r.getData()).isEqualTo(ImmutableList.of(m("a", m("d", m("c", 3, "z", true)))));

    r = getDocPath(id, "{\"a.*.c\":{\"$lte\":3}}", "[\"c\", \"z\"]", ImmutableList.of());
    assertThat(r.getData()).isEqualTo(ImmutableList.of(m("a", m("d", m("c", 3, "z", true)))));

    r = getDocPath(id, "{\"a.*.c\":{\"$nin\":[10, 100]}}", "[\"c\", \"z\"]", ImmutableList.of());
    assertThat(r.getData()).isEqualTo(ImmutableList.of(m("a", m("d", m("c", 3, "z", true)))));

    r = getDocPath(id, "{\"a.*.z\":{\"$in\":[true]}}", "[\"c\", \"z\"]", ImmutableList.of());
    assertThat(r.getData()).isEqualTo(ImmutableList.of(m("a", m("d", m("c", 3, "z", true)))));

    r = getDocPath(id, "{\"a.*.y\":{\"$in\":[\"yy\"]}}", "[\"c\", \"y\"]", ImmutableList.of());
    assertThat(r.getData()).isEqualTo(ImmutableList.of(m("a", m("d", m("c", 3, "y", "yy")))));

    assertThat(
            getDocPathRaw(
                id, "{\"a.*.c\":{\"$gt\":1,\"$ne\":10}}", "[\"c\", \"z\"]", ImmutableList.of()))
        .isEqualTo("[{\"a\":{\"d\":{\"c\":3,\"z\":true}}},{\"a\":{\"f\":{\"c\":100}}}]");
  }

  private Object[] fillParams(Object val, int count, double lastValue, Object... params) {
    Object[] result = new Object[params.length + count + 1];
    Arrays.fill(result, val);
    System.arraycopy(params, 0, result, 0, params.length);
    result[result.length - 1] = lastValue;
    return result;
  }

  private Object[] fillParams(int totalCount, Object... params) {
    Object[] result = new Object[totalCount];
    Arrays.fill(result, ""); // default value for pNN columns
    int idx = 0;
    for (Object param : params) {
      if (param == SEPARATOR) {
        idx = totalCount - params.length + idx + 1;
        continue;
      }

      result[idx++] = param;
    }

    return result;
  }

  @Test
  void testSearchDocEmpty() throws JsonProcessingException {
    withQuery(
            table,
            "SELECT key, leaf FROM test_docs.collection1 WHERE p0 = ? AND p1 > ? AND p2 = ? AND p3 = ? AND p4 = ? AND p5 = ? AND p6 = ? AND p7 = ? AND p8 = ? AND p9 = ? AND p10 = ? AND p11 = ? AND p12 = ? AND p13 = ? AND p14 = ? AND p15 = ? AND p16 = ? AND p17 = ? AND p18 = ? AND p19 = ? AND p20 = ? AND p21 = ? AND p22 = ? AND p23 = ? AND p24 = ? AND p25 = ? AND p26 = ? AND p27 = ? AND p28 = ? AND p29 = ? AND p30 = ? AND p31 = ? AND p32 = ? AND p33 = ? AND p34 = ? AND p35 = ? AND p36 = ? AND p37 = ? AND p38 = ? AND p39 = ? AND p40 = ? AND p41 = ? AND p42 = ? AND p43 = ? AND p44 = ? AND p45 = ? AND p46 = ? AND p47 = ? AND p48 = ? AND p49 = ? AND p50 = ? AND p51 = ? AND p52 = ? AND p53 = ? AND p54 = ? AND p55 = ? AND p56 = ? AND p57 = ? AND p58 = ? AND p59 = ? AND p60 = ? AND p61 = ? AND p62 = ? AND p63 = ? AND dbl_value > ? ALLOW FILTERING",
            fillParams("", 61, 1.0d, "a", "", "c"))
        .returningNothing();
    DocumentResponseWrapper<Map<String, ?>> r = searchDoc("{\"a.*.c\":{\"$gt\":1}}", null);
    assertThat(r.getPageState()).isNull();
    assertThat(r.getData()).isEqualTo(m());
  }

  @Test
  void testSearchDoc() throws JsonProcessingException {
    final String id1 = "id1";
    final String id2 = "id2";

    withQuery(
            table,
            "SELECT key, leaf FROM test_docs.collection1 WHERE p0 = ? AND p1 = ? AND p2 = ? AND p3 = ? AND p4 = ? AND p5 = ? AND p6 = ? AND p7 = ? AND p8 = ? AND p9 = ? AND p10 = ? AND p11 = ? AND p12 = ? AND p13 = ? AND p14 = ? AND p15 = ? AND p16 = ? AND p17 = ? AND p18 = ? AND p19 = ? AND p20 = ? AND p21 = ? AND p22 = ? AND p23 = ? AND p24 = ? AND p25 = ? AND p26 = ? AND p27 = ? AND p28 = ? AND p29 = ? AND p30 = ? AND p31 = ? AND p32 = ? AND p33 = ? AND p34 = ? AND p35 = ? AND p36 = ? AND p37 = ? AND p38 = ? AND p39 = ? AND p40 = ? AND p41 = ? AND p42 = ? AND p43 = ? AND p44 = ? AND p45 = ? AND p46 = ? AND p47 = ? AND p48 = ? AND p49 = ? AND p50 = ? AND p51 = ? AND p52 = ? AND p53 = ? AND p54 = ? AND p55 = ? AND p56 = ? AND p57 = ? AND p58 = ? AND p59 = ? AND p60 = ? AND p61 = ? AND p62 = ? AND p63 = ? AND dbl_value > ? ALLOW FILTERING",
            fillParams("", 62, 1.0d, "a", "c"))
        .returning(ImmutableList.of(leafRow(id1), leafRow(id2)));

    withQuery(
            table,
            "SELECT key, leaf FROM test_docs.collection1 WHERE key = ? AND p0 = ? AND p1 = ? AND p2 = ? AND p3 = ? AND p4 = ? AND p5 = ? AND p6 = ? AND p7 = ? AND p8 = ? AND p9 = ? AND p10 = ? AND p11 = ? AND p12 = ? AND p13 = ? AND p14 = ? AND p15 = ? AND p16 = ? AND p17 = ? AND p18 = ? AND p19 = ? AND p20 = ? AND p21 = ? AND p22 = ? AND p23 = ? AND p24 = ? AND p25 = ? AND p26 = ? AND p27 = ? AND p28 = ? AND p29 = ? AND p30 = ? AND p31 = ? AND p32 = ? AND p33 = ? AND p34 = ? AND p35 = ? AND p36 = ? AND p37 = ? AND p38 = ? AND p39 = ? AND p40 = ? AND p41 = ? AND p42 = ? AND p43 = ? AND p44 = ? AND p45 = ? AND p46 = ? AND p47 = ? AND p48 = ? AND p49 = ? AND p50 = ? AND p51 = ? AND p52 = ? AND p53 = ? AND p54 = ? AND p55 = ? AND p56 = ? AND p57 = ? AND p58 = ? AND p59 = ? AND p60 = ? AND p61 = ? AND p62 = ? AND p63 = ? AND dbl_value = ? ALLOW FILTERING",
            fillParams("", 61, 2.0d, id1, "d", "e", "f"))
        .returning(ImmutableList.of(leafRow(id1)));
    withQuery(
            table,
            "SELECT key, leaf FROM test_docs.collection1 WHERE key = ? AND p0 = ? AND p1 = ? AND p2 = ? AND p3 = ? AND p4 = ? AND p5 = ? AND p6 = ? AND p7 = ? AND p8 = ? AND p9 = ? AND p10 = ? AND p11 = ? AND p12 = ? AND p13 = ? AND p14 = ? AND p15 = ? AND p16 = ? AND p17 = ? AND p18 = ? AND p19 = ? AND p20 = ? AND p21 = ? AND p22 = ? AND p23 = ? AND p24 = ? AND p25 = ? AND p26 = ? AND p27 = ? AND p28 = ? AND p29 = ? AND p30 = ? AND p31 = ? AND p32 = ? AND p33 = ? AND p34 = ? AND p35 = ? AND p36 = ? AND p37 = ? AND p38 = ? AND p39 = ? AND p40 = ? AND p41 = ? AND p42 = ? AND p43 = ? AND p44 = ? AND p45 = ? AND p46 = ? AND p47 = ? AND p48 = ? AND p49 = ? AND p50 = ? AND p51 = ? AND p52 = ? AND p53 = ? AND p54 = ? AND p55 = ? AND p56 = ? AND p57 = ? AND p58 = ? AND p59 = ? AND p60 = ? AND p61 = ? AND p62 = ? AND p63 = ? AND dbl_value = ? ALLOW FILTERING",
            fillParams("", 61, 2.0d, id2, "d", "e", "f"))
        .returning(ImmutableList.of(leafRow(id2)));

    withQuery(table, selectAll("WHERE key = ?"), id1)
        .returning(ImmutableList.of(row(id1, 3.0, "a", "b"), row(id1, 4.0, "a", "c")));

    withQuery(table, selectAll("WHERE key = ?"), id2)
        .returning(
            ImmutableList.of(
                row(id2, 5.0, "a", "b"), row(id2, 10.0, "a", "c"), row(id2, 5.0, "a", "d")));

    DocumentResponseWrapper<Map<String, ?>> r =
        searchDoc("{\"a.c\":{\"$gt\":1,\"$ne\":10},\"d.e.f\":{\"$eq\":2}}", null);
    assertThat(r.getPageState()).isNull();
    assertThat(r.getData()).isEqualTo(m(id1, m("a", m("b", 3, "c", 4))));

    r = searchDoc("{\"a.c\":{\"$gt\":1},\"d.e.f\":{\"$eq\":2}}", null);
    assertThat(r.getPageState()).isNull();
    assertThat(r.getData())
        .isEqualTo(m(id1, m("a", m("b", 3, "c", 4)), id2, m("a", m("b", 5, "c", 10, "d", 5))));

    r = searchDoc("{\"a.c\":{\"$gt\":1},\"d.e.f\":{\"$eq\":2}}", "[\"a.b\"]");
    assertThat(r.getPageState()).isNull();
    assertThat(r.getData()).isEqualTo(m(id1, m("a", m("b", 3)), id2, m("a", m("b", 5))));
  }

  @Test
  void testSearchDocInMemory() throws JsonProcessingException {
    final String id1 = "id1";
    final String id2 = "id2";
    final String id3 = "id3";

    // full scan
    withQuery(table, selectAll(""))
        .returning(
            ImmutableList.of(
                row(id1, 3.0, "a", "b"),
                row(id1, 3.0, "a", "c"),
                row(id1, 3.0, "a", "d"),
                row(id2, 4.0, "a", "e"),
                row(id2, 4.0, "a", "c"),
                row(id3, 10.0, "a", "c"),
                row(id3, 20.0, "a", "b")));

    DocumentResponseWrapper<Map<String, ?>> r = searchDoc("{\"a.c\":{\"$ne\":10}}", null);
    assertThat(r.getPageState()).isNull();
    assertThat(r.getData())
        .isEqualTo(m(id1, m("a", m("b", 3, "c", 3, "d", 3)), id2, m("a", m("e", 4, "c", 4))));

    r = searchDoc("{\"a.c\":{\"$ne\":10}}", "[\"a.b\"]");
    assertThat(r.getPageState()).isNull();
    assertThat(r.getData()).isEqualTo(m(id1, m("a", m("b", 3)), id2, m()));
  }

  @Test
  void testSearchDocFullScan() throws JsonProcessingException {
    final String id1 = "id1";
    final String id2 = "id2";
    final String id3 = "id3";

    // full scan
    withQuery(table, selectAll(""))
        .returning(
            ImmutableList.of(
                row(id1, 3.0, "a", "b"),
                row(id1, 3.0, "a", "c"),
                row(id1, 3.0, "a", "d"),
                row(id2, 4.0, "a", "e"),
                row(id2, 4.0, "a", "c"),
                row(id3, 10.0, "a", "c"),
                row(id3, 20.0, "a", "b")));

    DocumentResponseWrapper<Map<String, ?>> r = searchDoc(null, null);
    assertThat(r.getPageState()).isNull();
    assertThat(r.getData())
        .isEqualTo(
            m(
                id1,
                m("a", m("b", 3, "c", 3, "d", 3)),
                id2,
                m("a", m("e", 4, "c", 4)),
                id3,
                m("a", m("b", 20, "c", 10))));

    r = searchDoc(null, "[\"a.b\"]");
    assertThat(r.getPageState()).isNull();
    assertThat(r.getData()).isEqualTo(m(id1, m("a", m("b", 3)), id2, m(), id3, m("a", m("b", 20))));
  }

  @Test
  void testPutAtPathRoot() throws UnauthorizedException {
    withQuery(table, "DELETE FROM %s USING TIMESTAMP ? WHERE key = ?", 99L, "id1")
        .returningNothing();

    String insert =
        "INSERT INTO %s (key, p0, p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11, p12, p13, p14, p15, p16, p17, p18, p19, p20, p21, p22, p23, p24, p25, p26, p27, p28, p29, p30, p31, p32, p33, p34, p35, p36, p37, p38, p39, p40, p41, p42, p43, p44, p45, p46, p47, p48, p49, p50, p51, p52, p53, p54, p55, p56, p57, p58, p59, p60, p61, p62, p63, leaf, text_value, dbl_value, bool_value) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) USING TIMESTAMP ?";
    withQuery(table, insert, fillParams(70, "id1", "a", SEPARATOR, "a", null, 123.0d, null, 100L))
        .returningNothing();
    withQuery(table, insert, fillParams(70, "id1", "b", SEPARATOR, "b", null, null, true, 100L))
        .returningNothing();
    withQuery(table, insert, fillParams(70, "id1", "c", SEPARATOR, "c", "text", null, null, 100L))
        .returningNothing();
    withQuery(
            table,
            insert,
            fillParams(
                70, "id1", "d", SEPARATOR, "d", DocumentDB.EMPTY_OBJECT_MARKER, null, null, 100L))
        .returningNothing();
    withQuery(
            table,
            insert,
            fillParams(
                70, "id1", "e", SEPARATOR, "e", DocumentDB.EMPTY_ARRAY_MARKER, null, null, 100L))
        .returningNothing();
    withQuery(table, insert, fillParams(70, "id1", "f", SEPARATOR, "f", null, null, null, 100L))
        .returningNothing();
    withQuery(
            table,
            insert,
            fillParams(70, "id1", "g", "[000000]", "h", SEPARATOR, "h", null, 1.0d, null, 100L))
        .returningNothing();

    now.set(100);
    service.putAtPath(
        authToken,
        keyspace.name(),
        table.name(),
        "id1",
        "{\"a\":123, \"b\":true, \"c\":\"text\", \"d\":{}, \"e\":[], \"f\":null, \"g\":[{\"h\":1}]}",
        ImmutableList.of(),
        false,
        db,
        true,
        Collections.emptyMap());
  }

  @Test
  void testPutAtPathNested() throws UnauthorizedException {
    withQuery(
            table,
            "DELETE FROM test_docs.collection1 USING TIMESTAMP ? WHERE key = ? AND p0 = ? AND p1 = ? AND p2 = ?",
            199L,
            "id2",
            "x",
            "y",
            "[000000]")
        .returningNothing();

    String insert =
        "INSERT INTO %s (key, p0, p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11, p12, p13, p14, p15, p16, p17, p18, p19, p20, p21, p22, p23, p24, p25, p26, p27, p28, p29, p30, p31, p32, p33, p34, p35, p36, p37, p38, p39, p40, p41, p42, p43, p44, p45, p46, p47, p48, p49, p50, p51, p52, p53, p54, p55, p56, p57, p58, p59, p60, p61, p62, p63, leaf, text_value, dbl_value, bool_value) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) USING TIMESTAMP ?";
    withQuery(
            table,
            insert,
            fillParams(
                70, "id2", "x", "y", "[000000]", "a", SEPARATOR, "a", null, 123.0d, null, 200L))
        .returningNothing();

    now.set(200);
    service.putAtPath(
        authToken,
        keyspace.name(),
        table.name(),
        "id2",
        "{\"a\":123}",
        ImmutableList.of(p("x"), p("y"), p("[000000]")),
        false,
        db,
        true,
        Collections.emptyMap());
  }

  @Test
  void testPutAtPathPatch() throws UnauthorizedException {
    String insert =
        "INSERT INTO %s (key, p0, p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11, p12, p13, p14, p15, p16, p17, p18, p19, p20, p21, p22, p23, p24, p25, p26, p27, p28, p29, p30, p31, p32, p33, p34, p35, p36, p37, p38, p39, p40, p41, p42, p43, p44, p45, p46, p47, p48, p49, p50, p51, p52, p53, p54, p55, p56, p57, p58, p59, p60, p61, p62, p63, leaf, text_value, dbl_value, bool_value) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) USING TIMESTAMP ?";
    withQuery(table, insert, fillParams(70, "id3", "a", SEPARATOR, "a", null, 123.0d, null, 200L))
        .returningNothing();

    withQuery(
            table,
            "DELETE FROM %s USING TIMESTAMP ? WHERE key = ? AND p0 >= ? AND p0 <= ?",
            199L,
            "id3",
            "[000000]",
            "[999999]")
        .returningNothing();
    withQuery(
            table,
            "DELETE FROM %s USING TIMESTAMP ? WHERE key = ? AND p0 IN ?",
            199L,
            "id3",
            ImmutableList.of("a"))
        .returningNothing();

    now.set(200);
    service.putAtPath(
        authToken,
        keyspace.name(),
        table.name(),
        "id3",
        "{\"a\":123}",
        ImmutableList.of(),
        true,
        db,
        true,
        Collections.emptyMap());
  }

  @Test
  void testPutAtPathUnauthorized() throws UnauthorizedException {
    ThrowingCallable action =
        () ->
            service.putAtPath(
                authToken,
                keyspace.name(),
                table.name(),
                "id3",
                "{\"a\":123}",
                ImmutableList.of(),
                true,
                db,
                true,
                Collections.emptyMap());

    Mockito.doThrow(new UnauthorizedException("test1"))
        .when(authorizationService)
        .authorizeDataWrite(
            any(), eq(keyspace.name()), eq(table.name()), eq(Scope.DELETE), eq(SourceAPI.REST));

    assertThatThrownBy(action).hasMessage("test1");

    Mockito.doNothing()
        .when(authorizationService)
        .authorizeDataWrite(
            any(), eq(keyspace.name()), eq(table.name()), eq(Scope.DELETE), eq(SourceAPI.REST));
    Mockito.doThrow(new UnauthorizedException("test2"))
        .when(authorizationService)
        .authorizeDataWrite(
            any(), eq(keyspace.name()), eq(table.name()), eq(Scope.MODIFY), eq(SourceAPI.REST));

    assertThatThrownBy(action).hasMessage("test2");
  }

  @Test
  void testGetDocPathUnauthorized() throws UnauthorizedException {
    Mockito.doThrow(new UnauthorizedException("test3"))
        .when(authorizationService)
        .authorizeDataRead(any(), eq(keyspace.name()), eq(table.name()), eq(SourceAPI.REST));

    assertThatThrownBy(
            () -> getDocPath("id1", "{\"a.*.y\":{\"$in\":[\"yy\"]}}", null, ImmutableList.of()))
        .hasMessageContaining("test3");
  }

  @Test
  void testSearchDocUnauthorized() throws UnauthorizedException {
    Mockito.doThrow(new UnauthorizedException("test4"))
        .when(authorizationService)
        .authorizeDataRead(any(), eq(keyspace.name()), eq(table.name()), eq(SourceAPI.REST));

    assertThatThrownBy(() -> searchDoc("{\"a.*.y\":{\"$in\":[\"yy\"]}}", null))
        .hasMessageContaining("test4");
  }
}
