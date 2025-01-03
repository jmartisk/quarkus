package io.quarkus.smallrye.graphql.client.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import jakarta.inject.Inject;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.EmptyAsset;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.smallrye.graphql.client.deployment.model.Person;
import io.quarkus.smallrye.graphql.client.deployment.model.PersonDto;
import io.quarkus.smallrye.graphql.client.deployment.model.TestingGraphQLApi;
import io.quarkus.smallrye.graphql.client.deployment.model.TestingGraphQLClientApiWithNoConfigKey;
import io.quarkus.test.QuarkusUnitTest;

/**
 * Verify that it's possible to configure a typesafe client using the fully qualified class name.
 */
public class TypesafeGraphQLClientFQNameTest {

    static String url = "http://" + System.getProperty("quarkus.http.host", "localhost") + ":" +
            System.getProperty("quarkus.http.test-port", "8081") + "/graphql";

    static JavaArchive dependency = ShrinkWrap.create(JavaArchive.class)
            .addClasses(TestingGraphQLClientApiWithNoConfigKey.class, Person.class);

    @RegisterExtension
    static QuarkusUnitTest test = new QuarkusUnitTest()
            .withApplicationRoot((jar) -> jar
                    .addClasses(TestingGraphQLApi.class, Person.class,
                            PersonDto.class)
                    .addAsResource(
                            new StringAsset(
                                    "quarkus.smallrye-graphql-client.\"io.quarkus.smallrye.graphql." +
                                            "client.deployment.model.TestingGraphQLClientApiWithNoConfigKey\".url="
                                            + url),
                            "application.properties")
                    .addAsManifestResource(EmptyAsset.INSTANCE, "beans.xml"))
            .addAdditionalDependency(dependency);

    @Inject
    TestingGraphQLClientApiWithNoConfigKey client;

    @Test
    public void performQuery() {
        List<Person> people = client.people();
        assertEquals("John", people.get(0).getFirstName());
        assertEquals("Arthur", people.get(1).getFirstName());
    }

}
