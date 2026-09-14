package org.km.llmwiki.system;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.km.llmwiki.web.security.OwnerSecurityProperties;

/**
 * Spring wiring for the deployment profile (#418).
 *
 * <p>The validator bean runs at startup so an invalid profile fails fast
 * before serving, and every restart/redeploy re-applies the same closed
 * boundary. Defaults preserve the local-only baseline exactly.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DeploymentProperties.class)
public class DeploymentConfiguration {

    @Bean
    public DeploymentProfileValidator deploymentProfileValidator(
            DeploymentProperties deployment,
            OwnerSecurityProperties owner,
            @Value("${server.address:127.0.0.1}") String serverAddress,
            @Value("${server.port:8765}") int serverPort) {
        DeploymentProfileValidator validator =
                new DeploymentProfileValidator(deployment, owner, serverAddress, serverPort);
        validator.validate();
        return validator;
    }

    @Bean
    public DeploymentReadinessService deploymentReadinessService(
            DeploymentProperties deployment,
            OwnerSecurityProperties owner,
            @Value("${server.address:127.0.0.1}") String serverAddress,
            @Value("${server.port:8765}") int serverPort) {
        return new DeploymentReadinessService(deployment, owner, serverAddress, serverPort);
    }
}
