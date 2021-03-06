package killrvideo.configuration;

import static java.util.stream.Collectors.toList;

import java.util.List;
import java.util.stream.Collectors;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.Session;
import com.xqbase.etcd4j.EtcdClient;

import info.archinnov.achilles.generated.ManagerFactory;
import info.archinnov.achilles.generated.ManagerFactoryBuilder;
import info.archinnov.achilles.script.ScriptExecutor;
import killrvideo.utils.ExceptionUtils;


@Configuration
public class CassandraConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(CassandraConfiguration.class);

    private static final String CLUSTER_NAME = "killrvideo";

    @Inject
    Environment env;

    @Inject
    EtcdClient etcdClient;

    @Bean(destroyMethod = "shutDown")
    public ManagerFactory cassandraNativeClusterProduction() {

        LOGGER.info("Initializing connection to Cassandra");

        try {

            List<String> cassandraHostsAndPorts = etcdClient.listDir("/killrvideo/services/cassandra")
                    .stream()
                    .map(node -> node.value)
                    .collect(toList());

            final String cassandraHosts = cassandraHostsAndPorts
                    .stream()
                    .map(x -> x.split(":")[0])
                    .collect(Collectors.joining(","));

            final int cassandraPort = Integer.parseInt(
                    cassandraHostsAndPorts
                            .get(0)
                            .split(":")[1]);


            LOGGER.info(String.format("Retrieving cassandra hosts %s and port %s from etcd", cassandraHosts, cassandraPort));

            Cluster cluster = Cluster.builder()
                    .addContactPoints(cassandraHosts)
                    .withPort(cassandraPort)
                    .withClusterName(CLUSTER_NAME)
                    .build();

            final Session session = cluster.connect();

            maybeCreateSchema(session);

            final ManagerFactory factory = ManagerFactoryBuilder
                    .builder(cluster)
                    .withBeanValidation(true)
                    .withPostLoadBeanValidation(true)
                    .build();

            return factory;

        } catch (Throwable e) {

            LOGGER.error("Exception : " + e.getMessage());
            LOGGER.error(ExceptionUtils.mergeStackTrace(e));

            throw new IllegalStateException("Cannot find 'killrvideo/services/cassandra' from etcd");
        }
    }

    private void maybeCreateSchema(Session session) {
        LOGGER.info("Execute schema creation script 'schema.cql' if necessary");
        final ScriptExecutor scriptExecutor = new ScriptExecutor(session);
        scriptExecutor.executeScript("schema.cql");

    }
}
