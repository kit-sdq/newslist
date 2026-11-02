package edu.kit.kastel.sdq.newslist;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * @author Lucas Alber
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class Saml2LoginApplication {

	public static void main(String[] args) {
		SpringApplication.run(Saml2LoginApplication.class, args);
	}

}
