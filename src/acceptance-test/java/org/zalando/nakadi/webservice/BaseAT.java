package org.zalando.nakadi.webservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.restassured.RestAssured;
import com.jayway.restassured.parsing.Parser;
import java.util.concurrent.TimeUnit;
import org.apache.http.params.CoreConnectionPNames;
import org.junit.BeforeClass;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.zalando.nakadi.config.JsonConfig;
import org.zalando.nakadi.domain.Storage;
import org.zalando.nakadi.repository.db.EventTypeDbRepository;
import org.zalando.nakadi.repository.db.StorageDbRepository;
import org.zalando.nakadi.repository.db.TimelineDbRepository;

public abstract class BaseAT {

    public static final String POSTGRES_URL = "jdbc:postgresql://localhost:5432/local_nakadi_db";
    public static final String POSTGRES_USER = "nakadi";
    public static final String POSTGRES_PWD = "nakadi";

    protected static final int PORT = 8080;
    public static final String URL = "http://localhost:" + PORT;

    protected static final String ZOOKEEPER_URL = "localhost:2181";
    protected static final String KAFKA_URL = "localhost:9092";

    private static final JdbcTemplate JDBC_TEMPLATE = new JdbcTemplate(
            new DriverManagerDataSource(POSTGRES_URL, POSTGRES_USER, POSTGRES_PWD));
    protected static final ObjectMapper MAPPER = (new JsonConfig()).jacksonObjectMapper();
    protected static final EventTypeDbRepository EVENT_TYPE_REPO = new EventTypeDbRepository(JDBC_TEMPLATE, MAPPER);
    protected static final StorageDbRepository STORAGE_DB_REPOSITORY = new StorageDbRepository(JDBC_TEMPLATE, MAPPER);
    protected static final TimelineDbRepository TIMELINE_REPOSITORY = new TimelineDbRepository(JDBC_TEMPLATE, MAPPER);

    static {
        RestAssured.port = PORT;
        RestAssured.defaultParser = Parser.JSON;
        RestAssured.config().getHttpClientConfig()
                .setParam(CoreConnectionPNames.SO_TIMEOUT, TimeUnit.SECONDS.toMillis(30));
        RestAssured.config().getHttpClientConfig().setParam(
                CoreConnectionPNames.CONNECTION_TIMEOUT, TimeUnit.SECONDS.toMillis(5));
    }

    @BeforeClass
    public static void createDefaultStorage() {
        final Storage storage = new Storage();
        storage.setId("default");
        storage.setType(Storage.Type.KAFKA);
        storage.setConfiguration(new Storage.KafkaConfiguration(null, null, ZOOKEEPER_URL, ""));
        try {
            STORAGE_DB_REPOSITORY.createStorage(storage);
        } catch (final Exception ignore) {
        }
    }
}
