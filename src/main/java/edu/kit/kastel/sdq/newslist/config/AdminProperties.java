package edu.kit.kastel.sdq.newslist.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.util.List;

/**
 * Admin configuration. {@code ids} are values of the SAML2 attribute configured via {@code news.saml2-key}.
 *
 * @param ids           the user IDs that may use the admin interface (empty: no admins)
 * @param maxUploadSize the maximum size of an uploaded CSV file (default 10MB). Must be below the
 *                      {@code spring.servlet.multipart} limits, which act as a hard cap.
 */
@ConfigurationProperties("news.admin")
public record AdminProperties(List<String> ids, DataSize maxUploadSize) {
    public AdminProperties {
        ids = ids == null ? List.of() : List.copyOf(ids);
        maxUploadSize = maxUploadSize == null ? DataSize.ofMegabytes(10) : maxUploadSize;
    }
}
