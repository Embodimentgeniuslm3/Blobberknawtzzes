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
package io.stargate.it.http.graphql.graphqlfirst;

import static org.assertj.core.api.Assertions.assertThat;

import com.datastax.oss.driver.api.core.CqlIdentifier;
import com.datastax.oss.driver.api.core.CqlSession;
import com.jayway.jsonpath.JsonPath;
import io.stargate.it.driver.CqlSessionExtension;
import io.stargate.it.driver.TestKeyspace;
import io.stargate.it.http.RestUtils;
import io.stargate.it.storage.StargateConnectionInfo;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(CqlSessionExtension.class)
public class AtomicTest extends GraphqlFirstTestBase {

  private static GraphqlFirstClient CLIENT;
  private static String KEYSPACE;
  private static CqlSession SESSION;

  @BeforeAll
  public static void setup(
      StargateConnectionInfo cluster, @TestKeyspace CqlIdentifier keyspaceId, CqlSession session) {
    CLIENT =
        new GraphqlFirstClient(
            cluster.seedAddress(), RestUtils.getAuthToken(cluster.seedAddress()));
    KEYSPACE = keyspaceId.asInternal();
    SESSION = session;
    CLIENT.deploySchema(
        KEYSPACE,
        "type Foo @cql_input {\n"
            + "  k: Int @cql_column(partitionKey: true)\n"
            + "  cc: Int @cql_column(clusteringOrder: ASC)\n"
            + "  v: Int\n"
            + "}\n"
            + "type InsertFooResponse @cql_payload {\n"
            + "  foo: Foo\n"
            + "  applied: Boolean\n"
            + "}\n"
            + "type Query {\n"
            + "  foo(k: Int, cc: Int): Foo\n"
            + "}\n"
            + "type Mutation {\n"
            + "  insertFoo(foo: FooInput): InsertFooResponse\n"
            + "  insertFooIfNotExists(foo: FooInput): InsertFooResponse\n"
            + "  insertFoos(foos: [FooInput]): [InsertFooResponse]\n"
            + "  insertFoosIfNotExists(foos: [FooInput]): [InsertFooResponse]\n"
            + "}\n");
  }

  @BeforeEach
  public void cleanupData() {
    SESSION.execute("truncate table \"Foo\"");
  }

  @Test
  @DisplayName("Should batch simple operations")
  public void simpleOperations() {
    // Given
    String query =
        "mutation @atomic {\n"
            + "  insert1: insertFoo(foo: { k: 1, cc: 1, v: 1 }) { applied }\n"
            + "  insert2: insertFoo(foo: { k: 1, cc: 2, v: 2 }) { applied }\n"
            + "}\n";

    // When
    Object response = CLIENT.executeKeyspaceQuery(KEYSPACE, query);

    // Then
    assertThat(JsonPath.<Boolean>read(response, "$.insert1.applied")).isTrue();
    assertThat(JsonPath.<Boolean>read(response, "$.insert2.applied")).isTrue();

    long writeTime1 = getWriteTime(1, 1);
    long writeTime2 = getWriteTime(1, 2);
    assertThat(writeTime1).isEqualTo(writeTime2);
  }

  @Test
  @DisplayName("Should batch bulk insert")
  public void bulkInsert() {
    // Given
    String query =
        "mutation @atomic {\n"
            + "  insertFoos(foos: [\n"
            + "    { k: 1, cc: 1, v: 1 }, "
            + "    { k: 1, cc: 2, v: 2 } "
            + "  ]) { applied }\n"
            + "}\n";

    // When
    Object response = CLIENT.executeKeyspaceQuery(KEYSPACE, query);

    // Then
    assertThat(JsonPath.<Boolean>read(response, "$.insertFoos[0].applied")).isTrue();
    assertThat(JsonPath.<Boolean>read(response, "$.insertFoos[1].applied")).isTrue();

    long writeTime1 = getWriteTime(1, 1);
    long writeTime2 = getWriteTime(1, 2);
    assertThat(writeTime1).isEqualTo(writeTime2);
  }

  @Test
  @DisplayName("Should batch mix of simple and insert operations")
  public void simpleAndBulkOperations() {
    // Given
    String query =
        "mutation @atomic {\n"
            + "  insertFoos(foos: [\n"
            + "    { k: 1, cc: 1, v: 1 }, "
            + "    { k: 1, cc: 2, v: 2 } "
            + "  ]) { applied }\n"
            + "  insertFoo(foo: { k: 1, cc: 3, v: 3 }) { applied }\n"
            + "}\n";

    // When
    Object response = CLIENT.executeKeyspaceQuery(KEYSPACE, query);

    // Then
    assertThat(JsonPath.<Boolean>read(response, "$.insertFoos[0].applied")).isTrue();
    assertThat(JsonPath.<Boolean>read(response, "$.insertFoos[1].applied")).isTrue();
    assertThat(JsonPath.<Boolean>read(response, "$.insertFoo.applied")).isTrue();

    long writeTime1 = getWriteTime(1, 1);
    long writeTime2 = getWriteTime(1, 2);
    long writeTime3 = getWriteTime(1, 3);
    assertThat(writeTime1).isEqualTo(writeTime2).isEqualTo(writeTime3);
  }

  @Test
  @DisplayName("Should handle successful conditional batch")
  public void successfulConditionalBatch() {
    // Given
    String query =
        "mutation @atomic {\n"
            + "  insert1: insertFooIfNotExists(foo: { k: 1, cc: 1, v: 1 }) {\n"
            + "    applied, foo { k cc v }\n"
            + "  }\n"
            // Not all queries have to be LWTs, a conditional batch can also contain regular ones:
            + "  insert2: insertFoo(foo: { k: 1, cc: 2, v: 2 }) {\n"
            + "    applied, foo { k cc v }\n"
            + "  }\n"
            + "}\n";

    // When
    Object response = CLIENT.executeKeyspaceQuery(KEYSPACE, query);

    // Then
    assertThat(JsonPath.<Boolean>read(response, "$.insert1.applied")).isTrue();
    assertThat(JsonPath.<Integer>read(response, "$.insert1.foo.k")).isEqualTo(1);
    assertThat(JsonPath.<Integer>read(response, "$.insert1.foo.cc")).isEqualTo(1);
    assertThat(JsonPath.<Integer>read(response, "$.insert1.foo.v")).isEqualTo(1);

    assertThat(JsonPath.<Boolean>read(response, "$.insert2.applied")).isTrue();
    assertThat(JsonPath.<Integer>read(response, "$.insert2.foo.k")).isEqualTo(1);
    assertThat(JsonPath.<Integer>read(response, "$.insert2.foo.cc")).isEqualTo(2);
    assertThat(JsonPath.<Integer>read(response, "$.insert2.foo.v")).isEqualTo(2);

    long writeTime1 = getWriteTime(1, 1);
    long writeTime2 = getWriteTime(1, 2);
    assertThat(writeTime1).isEqualTo(writeTime2);
  }

  @Test
  @DisplayName("Should handle failed conditional batch with non-LWT queries")
  public void failedConditionalBatch() {
    // Given
    SESSION.execute("INSERT INTO \"Foo\" (k, cc, v) VALUES (1, 1, 2)");
    String query =
        "mutation @atomic {\n"
            + "  insert1: insertFooIfNotExists(foo: { k: 1, cc: 1, v: 1 }) {\n"
            + "    applied, foo { k cc v }\n"
            + "  }\n"
            + "  insert2: insertFoo(foo: { k: 1, cc: 2, v: 2 }) {\n"
            + "    applied, foo { k cc v }\n"
            + "  }\n"
            + "}\n";

    // When
    Object response = CLIENT.executeKeyspaceQuery(KEYSPACE, query);

    // Then
    assertThat(JsonPath.<Boolean>read(response, "$.insert1.applied")).isFalse();
    assertThat(JsonPath.<Integer>read(response, "$.insert1.foo.k")).isEqualTo(1);
    assertThat(JsonPath.<Integer>read(response, "$.insert1.foo.cc")).isEqualTo(1);
    assertThat(JsonPath.<Integer>read(response, "$.insert1.foo.v")).isEqualTo(2);

    // For non-LWT queries, we don't have any data to echo back because the batch response does not
    // contain that row
    assertThat(JsonPath.<Boolean>read(response, "$.insert2.applied")).isFalse();
    assertThat(JsonPath.<Integer>read(response, "$.insert2.foo.k")).isNull();
    assertThat(JsonPath.<Integer>read(response, "$.insert2.foo.cc")).isNull();
    assertThat(JsonPath.<Integer>read(response, "$.insert2.foo.v")).isNull();
  }

  @Test
  @DisplayName("Should handle failed conditional batch when queries are not in PK order")
  public void failedConditionalBatchOutOfOrder() {
    // Given
    SESSION.execute("INSERT INTO \"Foo\" (k, cc, v) VALUES (1, 1, 2)");
    SESSION.execute("INSERT INTO \"Foo\" (k, cc, v) VALUES (1, 2, 3)");
    String query =
        "mutation @atomic {\n"
            + "  insert2: insertFooIfNotExists(foo: { k: 1, cc: 2, v: 2 }) {\n"
            + "    applied, foo { k cc v }\n"
            + "  }\n"
            + "  insert1: insertFooIfNotExists(foo: { k: 1, cc: 1, v: 1 }) {\n"
            + "    applied, foo { k cc v }\n"
            + "  }\n"
            + "}\n";

    // When
    Object response = CLIENT.executeKeyspaceQuery(KEYSPACE, query);

    // Then
    assertThat(JsonPath.<Boolean>read(response, "$.insert2.applied")).isFalse();
    assertThat(JsonPath.<Integer>read(response, "$.insert2.foo.k")).isEqualTo(1);
    assertThat(JsonPath.<Integer>read(response, "$.insert2.foo.cc")).isEqualTo(2);
    assertThat(JsonPath.<Integer>read(response, "$.insert2.foo.v")).isEqualTo(3);

    assertThat(JsonPath.<Boolean>read(response, "$.insert1.applied")).isFalse();
    assertThat(JsonPath.<Integer>read(response, "$.insert1.foo.k")).isEqualTo(1);
    assertThat(JsonPath.<Integer>read(response, "$.insert1.foo.cc")).isEqualTo(1);
    assertThat(JsonPath.<Integer>read(response, "$.insert1.foo.v")).isEqualTo(2);
  }

  @Test
  @DisplayName(
      "Should handle failed conditional batch when multiple queries operate on the same PK")
  public void failedConditionalDuplicatePks() {
    // Given
    SESSION.execute("INSERT INTO \"Foo\" (k, cc, v) VALUES (1, 1, 2)");
    String query =
        "mutation @atomic {\n"
            + "  insert1: insertFooIfNotExists(foo: { k: 1, cc: 1, v: 1 }) {\n"
            + "    applied, foo { k cc v }\n"
            + "  }\n"
            + "  insert2: insertFooIfNotExists(foo: { k: 1, cc: 1, v: 3 }) {\n"
            + "    applied, foo { k cc v }\n"
            + "  }\n"
            + "}\n";

    // When
    Object response = CLIENT.executeKeyspaceQuery(KEYSPACE, query);

    // Then
    assertThat(JsonPath.<Boolean>read(response, "$.insert1.applied")).isFalse();
    assertThat(JsonPath.<Integer>read(response, "$.insert1.foo.k")).isEqualTo(1);
    assertThat(JsonPath.<Integer>read(response, "$.insert1.foo.cc")).isEqualTo(1);
    assertThat(JsonPath.<Integer>read(response, "$.insert1.foo.v")).isEqualTo(2);

    assertThat(JsonPath.<Boolean>read(response, "$.insert2.applied")).isFalse();
    assertThat(JsonPath.<Integer>read(response, "$.insert2.foo.k")).isEqualTo(1);
    assertThat(JsonPath.<Integer>read(response, "$.insert2.foo.cc")).isEqualTo(1);
    assertThat(JsonPath.<Integer>read(response, "$.insert2.foo.v")).isEqualTo(2);
  }

  private long getWriteTime(int k, int cc) {
    return SESSION
        .execute("SELECT writetime(v) FROM \"Foo\" WHERE k = ? AND cc = ?", k, cc)
        .one()
        .getLong(0);
  }
}
