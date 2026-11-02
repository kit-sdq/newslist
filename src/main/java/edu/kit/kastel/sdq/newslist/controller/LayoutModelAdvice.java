package edu.kit.kastel.sdq.newslist.controller;

import edu.kit.kastel.sdq.newslist.service.AdminAccess;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticatedPrincipal;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Provides the model attributes needed by the shared layout (navbar) on every page.
 */
@ControllerAdvice(assignableTypes = {IndexController.class, AdminController.class})
public class LayoutModelAdvice {

    private final AdminAccess adminAccess;
    private final String mailSAML2Key;

    public LayoutModelAdvice(AdminAccess adminAccess, @Value("${news.mail-saml2-key}") String mailSAML2Key) {
        this.adminAccess = adminAccess;
        this.mailSAML2Key = mailSAML2Key;
    }

    @ModelAttribute("emailAddress")
    public String emailAddress(@AuthenticationPrincipal Saml2AuthenticatedPrincipal principal) {
        String email = principal == null ? null : principal.getFirstAttribute(mailSAML2Key);
        return email == null ? "anonymous" : email;
    }

    @ModelAttribute("isAdmin")
    public boolean isAdmin(@AuthenticationPrincipal Saml2AuthenticatedPrincipal principal) {
        return adminAccess.isAdmin(principal);
    }
}
