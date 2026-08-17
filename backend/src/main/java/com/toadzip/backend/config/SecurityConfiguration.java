package com.toadzip.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfiguration {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http.csrf(csrf -> csrf.ignoringRequestMatchers("/admin/**"))
			.authorizeHttpRequests(
					authorize -> authorize.requestMatchers("/admin/**").hasRole("ADMIN").anyRequest().permitAll())
			.httpBasic(Customizer.withDefaults());
		return http.build();
	}

}
