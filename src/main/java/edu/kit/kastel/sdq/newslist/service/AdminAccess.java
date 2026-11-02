package edu.kit.kastel.sdq.newslist.service;

import edu.kit.kastel.sdq.newslist.config.AdminProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticatedPrincipal;
import org.springframework.stereotype.Component;

/**
 * Single source of truth for deciding whether a user is an admin.
 */
@Component
public class AdminAccess {

    private final AdminProperties adminProperties;
    private final String newsSAML2Key;

    public AdminAccess(AdminProperties adminProperties, @Value("${news.saml2-key}") String newsSAML2Key) {
        this.adminProperties = adminProperties;
        this.newsSAML2Key = newsSAML2Key;
    }

    public boolean isAdmin(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        return authentication.getPrincipal() instanceof Saml2AuthenticatedPrincipal principal && isAdmin(principal);
    }

    public boolean isAdmin(Saml2AuthenticatedPrincipal principal) {
        if (principal == null) {
            return false;
        }
        String id = principal.getFirstAttribute(newsSAML2Key);
        return id != null && adminProperties.ids().contains(id);
    }
}
