package dev.authsandbox.devicelogin.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Password encoder used for admin credentials.
     * Strength 12 is the recommended minimum.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    /**
     * In-memory admin user backed by the Keycloak admin credentials from the environment.
     * The plain-text password is encoded at startup; it is never stored in plain text.
     */
    @Bean
    public UserDetailsService userDetailsService(
            @Value("${app.admin.username}") String adminUsername,
            @Value("${app.admin.password}") String adminPassword,
            PasswordEncoder passwordEncoder) {
        var admin = User.builder()
                .username(adminUsername)
                .password(passwordEncoder.encode(adminPassword))
                .roles("ADMIN")
                .build();
        return new InMemoryUserDetailsManager(admin);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/api/v1/devices/**",
                    "/api/v1/auth/**",
                    "/actuator/health",
                    "/actuator/info"
                ).permitAll()
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            .httpBasic(basic -> {});

        return http.build();
    }
}
