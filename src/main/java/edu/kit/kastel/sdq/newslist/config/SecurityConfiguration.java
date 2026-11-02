package edu.kit.kastel.sdq.newslist.config;

import edu.kit.kastel.sdq.newslist.service.AdminAccess;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.saml2.provider.service.metadata.OpenSaml5MetadataResolver;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository;
import org.springframework.security.saml2.provider.service.web.DefaultRelyingPartyRegistrationResolver;
import org.springframework.security.saml2.provider.service.web.RelyingPartyRegistrationResolver;
import org.springframework.security.saml2.provider.service.web.Saml2MetadataFilter;
import org.springframework.security.web.SecurityFilterChain;

import java.security.Security;

/**
 * Security configuration using SAML2. Sample project:
 * https://github.com/spring-projects/spring-security-samples/tree/main/servlet/spring-boot/java/saml2/login
 *
 * @author Lucas Alber
 */
@Configuration
public class SecurityConfiguration {

    public SecurityConfiguration() {
        Security.addProvider(new BouncyCastleProvider());
    }

    @Bean
    SecurityFilterChain app(HttpSecurity http, AdminAccess adminAccess) throws Exception {
        http.authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/admin", "/admin/**").access((auth, ctx) -> new AuthorizationDecision(adminAccess.isAdmin(auth.get())))
                .anyRequest().authenticated()).saml2Login(Customizer.withDefaults());
        return http.build();
    }

    @Bean
    RelyingPartyRegistrationResolver relyingPartyRegistrationResolver(
            RelyingPartyRegistrationRepository registrations) {
        return new DefaultRelyingPartyRegistrationResolver(registrations);
    }

    @Bean
    FilterRegistrationBean<Saml2MetadataFilter> metadata(RelyingPartyRegistrationResolver registrations) {
        Saml2MetadataFilter metadata = new Saml2MetadataFilter(registrations, new OpenSaml5MetadataResolver());
        FilterRegistrationBean<Saml2MetadataFilter> filter = new FilterRegistrationBean<>(metadata);
        filter.setOrder(-101);
        return filter;
    }

}
