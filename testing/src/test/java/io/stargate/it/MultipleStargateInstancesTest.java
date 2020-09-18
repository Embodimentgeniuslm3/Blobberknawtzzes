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
package io.stargate.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.codahale.metrics.Timer;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.CqlSessionBuilder;
import com.datastax.oss.driver.api.core.config.DefaultDriverOption;
import com.datastax.oss.driver.api.core.config.DriverConfigLoader;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.datastax.oss.driver.api.core.metadata.Node;
import com.datastax.oss.driver.api.core.metadata.NodeState;
import com.datastax.oss.driver.api.core.metrics.DefaultNodeMetric;
import com.datastax.oss.driver.internal.core.loadbalancing.DcInferringLoadBalancingPolicy;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import net.jcip.annotations.NotThreadSafe;
import org.jetbrains.annotations.NotNull;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.osgi.framework.BundleException;

@RunWith(Parameterized.class)
@NotThreadSafe
public class MultipleStargateInstancesTest extends BaseOsgiIntegrationTest {

  @Rule public TestName name = new TestName();

  private String table;

  private String keyspace;

  private CqlSession session;

  @Before
  public void setup() {
    DriverConfigLoader loader =
        DriverConfigLoader.programmaticBuilder()
            .withBoolean(DefaultDriverOption.METADATA_TOKEN_MAP_ENABLED, false)
            .withString(
                DefaultDriverOption.LOAD_BALANCING_POLICY_CLASS,
                DcInferringLoadBalancingPolicy.class.getName())
            .withDuration(DefaultDriverOption.REQUEST_TIMEOUT, Duration.ofSeconds(5))
            .withDuration(DefaultDriverOption.CONNECTION_INIT_QUERY_TIMEOUT, Duration.ofSeconds(5))
            .withDuration(DefaultDriverOption.CONTROL_CONNECTION_TIMEOUT, Duration.ofSeconds(5))
            .withDuration(DefaultDriverOption.REQUEST_TRACE_INTERVAL, Duration.ofSeconds(1))
            .withStringList(
                DefaultDriverOption.METRICS_NODE_ENABLED,
                Collections.singletonList(DefaultNodeMetric.CQL_MESSAGES.getPath()))
            .build();

    CqlSessionBuilder cqlSessionBuilder = CqlSession.builder().withConfigLoader(loader);

    for (String host : getStargateHosts()) {
      cqlSessionBuilder.addContactPoint(new InetSocketAddress(host, 9043));
    }
    session = cqlSessionBuilder.build();

    String testName = name.getMethodName();
    if (testName.contains("[")) {
      testName = testName.substring(0, testName.indexOf("["));
    }
    keyspace = "ks_" + testName;
    table = testName;
  }

  @Test
  public void shouldConnectToMultipleStargateNodes() {
    List<Row> all = session.execute("SELECT * FROM system.peers").all();
    // system.peers should have 2 records (all stargate nodes - 1)
    assertThat(all.size()).isEqualTo(2);
  }

  @Test
  public void distributeTrafficUniformly() {
    // given
    createKeyspaceAndTable();
    long totalNumberOfRequests = 300;
    long numberOfRequestPerNode = totalNumberOfRequests / numberOfStargateNodes;
    // difference tolerance - every node should have numberOfRequestPerNode +- tolerance
    long tolerance = 5;

    // when
    insertRecords(totalNumberOfRequests);

    // then
    Collection<Node> nodes = session.getMetadata().getNodes().values();
    assertThat(nodes.size()).isEqualTo(numberOfStargateNodes);
    for (Node n : nodes) {
      long cqlMessages = getCqlMessages(n);
      assertThat(cqlMessages)
          .isBetween(numberOfRequestPerNode - tolerance, numberOfRequestPerNode + tolerance);
    }
  }

  @Test
  public void continueServeTrafficWhenInstanceGoesDownAndUp()
      throws InterruptedException, BundleException {
    // given
    createKeyspaceAndTable();
    long totalNumberOfRequests = 300;
    long numberOfRequestPerOneNodeOfN = totalNumberOfRequests / numberOfStargateNodes;
    long numberOfRequestPerOneNodeOfNMinus1 = totalNumberOfRequests / (numberOfStargateNodes - 1);
    // difference tolerance - every node should have numberOfRequestPerNode +- tolerance
    long tolerance = 5;

    // when
    insertRecords(totalNumberOfRequests);

    // then
    Collection<Node> nodes = getUpNodes();
    assertThat(nodes.size()).isEqualTo(numberOfStargateNodes);
    for (Node n : nodes) {
      long cqlMessages = getCqlMessages(n);
      assertThat(cqlMessages)
          .isBetween(
              numberOfRequestPerOneNodeOfN - tolerance, numberOfRequestPerOneNodeOfN + tolerance);
    }

    // when stop 1st stargate node
    stopStargateInstance(0);

    assertThat(getUpNodes().size()).isEqualTo(numberOfStargateNodes - 1);
    insertRecords(totalNumberOfRequests);

    for (Node n : getUpNodes()) {
      long cqlMessages = getCqlMessages(n);
      // there should be N - 1 nodes, where every node has numberOfRequestPerOneNodeOfN +
      // numberOfRequestPerOneNodeOfNMinus1
      // because after killing one node, the traffic is distributed to N - 1.
      long expectedNumberOfRequests =
          numberOfRequestPerOneNodeOfN + numberOfRequestPerOneNodeOfNMinus1;
      assertThat(cqlMessages)
          .isBetween(expectedNumberOfRequests - tolerance, expectedNumberOfRequests + tolerance);
    }

    // when restart the stargate node
    startStargateInstance(0);

    nodes = getUpNodes();
    assertThat(nodes.size()).isEqualTo(numberOfStargateNodes);

    // then
    for (Node n : nodes) {
      long cqlMessages = getCqlMessages(n);
      // there should be N nodes again, where every node has numberOfRequestPerOneNodeOfN * 2 +
      // numberOfRequestPerOneNodeOfNMinus1
      // because after restart, the traffic should be distributed to N nodes again.
      long expectedNumberOfRequests =
          numberOfRequestPerOneNodeOfN * 2 + numberOfRequestPerOneNodeOfNMinus1;
      assertThat(cqlMessages)
          .isBetween(expectedNumberOfRequests - tolerance, expectedNumberOfRequests + tolerance);
    }
  }

  private long getCqlMessages(Node n) {
    return ((Timer)
            session.getMetrics().get().getNodeMetric(n, DefaultNodeMetric.CQL_MESSAGES).get())
        .getCount();
  }

  private void insertRecords(long totalNumberOfRequests) {
    for (int i = 0; i < totalNumberOfRequests; i++) {
      session.execute(
          SimpleStatement.newInstance(
              String.format(
                  "INSERT INTO \"%s\".\"%s\" (k, cc, v) VALUES (1, ?, ?)", keyspace, table),
              i,
              i));
    }
  }

  @NotNull
  private List<Node> getUpNodes() {
    return session.getMetadata().getNodes().values().stream()
        .filter(n -> n.getState().equals(NodeState.UP))
        .collect(Collectors.toList());
  }

  private void createKeyspace() {
    session.execute(
        String.format(
            "CREATE KEYSPACE IF NOT EXISTS \"%s\" WITH REPLICATION = { 'class' : 'SimpleStrategy', 'replication_factor' : 1 }",
            keyspace));
  }

  private void createKeyspaceAndTable() {
    createKeyspace();

    session.execute(
        SimpleStatement.newInstance(
            String.format(
                "CREATE TABLE IF NOT EXISTS \"%s\".\"%s\" (k int, cc int, v int, PRIMARY KEY(k, cc))",
                keyspace, table)));
  }
}
