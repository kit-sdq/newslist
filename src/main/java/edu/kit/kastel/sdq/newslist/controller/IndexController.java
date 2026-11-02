package edu.kit.kastel.sdq.newslist.controller;

import edu.kit.kastel.sdq.newslist.service.NewsService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticatedPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * @author Lucas Alber
 */
@Controller
public class IndexController {

    @Value("${news.saml2-key}")
    private String newsSAML2Key;

    private final NewsService newsService;

    public IndexController(NewsService newsService) {
        this.newsService = newsService;
    }

    @GetMapping("/")
    public String index(Model model, @AuthenticationPrincipal Saml2AuthenticatedPrincipal principal) {
        String newsKey = principal.getFirstAttribute(newsSAML2Key);
        // If saml value is not present, simply set to empty string by default
        if (newsKey == null)
            newsKey = "";

        model.addAttribute("saml2key", newsKey);
        model.addAttribute("news", newsService.getNewsForKey(newsKey));
        return "index";
    }

}
