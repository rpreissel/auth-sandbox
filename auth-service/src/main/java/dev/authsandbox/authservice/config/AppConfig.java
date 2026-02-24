package dev.authsandbox.authservice.config;

import dev.authsandbox.authservice.security.KeyLoader;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;

@Configuration
@EnableConfigurationProperties({
        JwtProperties.class,
        ChallengeProperties.class,
        KeycloakProperties.class,
        KeycloakAdminProperties.class
})
public class AppConfig {

    @Bean
    public KeyPair jwtKeyPair(JwtProperties jwtProperties) throws Exception {
        PrivateKey privateKey = KeyLoader.loadPrivateKey(jwtProperties.privateKeyPath());
        PublicKey publicKey = KeyLoader.loadPublicKey(jwtProperties.publicKeyPath());
        return new KeyPair(publicKey, privateKey);
    }
}
