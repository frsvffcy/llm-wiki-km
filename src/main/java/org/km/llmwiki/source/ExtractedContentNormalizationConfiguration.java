package org.km.llmwiki.source;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ExtractedContentNormalizationProperties.class)
class ExtractedContentNormalizationConfiguration {
}
