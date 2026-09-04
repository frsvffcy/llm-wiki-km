package org.km.llmwiki.testsupport;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Shared Spring integration-test configuration.
 *
 * <p>Keeping the database property and MockMvc setup on one inherited annotation gives the
 * Spring TestContext framework one stable cache signature for tests that do not need a custom
 * provider or test configuration. Database state is reset by {@link IsolatedIntegrationTest}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Tag("integration")
@SpringBootTest(properties = "app.persistence.sqlite.path=target/test-data/integration-shared/knowledge.db")
@AutoConfigureMockMvc
public @interface SpringIntegrationTest {
}
