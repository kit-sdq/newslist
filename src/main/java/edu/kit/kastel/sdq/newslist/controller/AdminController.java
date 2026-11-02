package edu.kit.kastel.sdq.newslist.controller;

import edu.kit.kastel.sdq.newslist.config.AdminProperties;
import edu.kit.kastel.sdq.newslist.service.CsvFileInfo;
import edu.kit.kastel.sdq.newslist.service.NewsService;
import edu.kit.kastel.sdq.newslist.service.UploadResult;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;

/**
 * Admin interface to upload and delete news CSV files. Access is restricted in the security configuration.
 */
@Controller
public class AdminController {

    private final NewsService newsService;
    private final AdminProperties adminProperties;
    private final Log logger = LogFactory.getLog(this.getClass());

    public AdminController(NewsService newsService, AdminProperties adminProperties) {
        this.newsService = newsService;
        this.adminProperties = adminProperties;
    }

    @GetMapping("/admin")
    public String admin(Model model) {
        model.addAttribute("files", newsService.listFiles());
        model.addAttribute("maxUploadBytes", adminProperties.maxUploadSize().toBytes());
        model.addAttribute("maxUploadDisplay", CsvFileInfo.formatBytes(adminProperties.maxUploadSize().toBytes()));
        return "admin";
    }

    @PostMapping("/admin/upload")
    public String upload(@RequestParam("file") MultipartFile file, RedirectAttributes redirect) {
        if (file.isEmpty()) {
            redirect.addFlashAttribute("error", "Please select a non-empty CSV file.");
            return "redirect:/admin";
        }
        // Enforced here rather than via spring.servlet.multipart: the CSRF filter parses the multipart body
        // before Spring MVC runs, so a container-level limit can only produce a bare HTTP 413 page.
        if (file.getSize() > adminProperties.maxUploadSize().toBytes()) {
            redirect.addFlashAttribute("error", "The file is too large (maximum "
                    + CsvFileInfo.formatBytes(adminProperties.maxUploadSize().toBytes()) + ").");
            return "redirect:/admin";
        }
        try {
            UploadResult result = newsService.store(file.getOriginalFilename(), file.getBytes());
            redirect.addFlashAttribute("success", "Uploaded " + result.filename() + " (" + result.records() + " records).");
            if (result.skippedRows() > 0) {
                redirect.addFlashAttribute("warning", result.skippedRows()
                        + " row(s) did not have exactly two columns and were skipped.");
            }
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        } catch (IOException e) {
            logger.warn("Upload failed", e);
            redirect.addFlashAttribute("error", "Upload failed: " + e.getMessage());
        }
        return "redirect:/admin";
    }

    @PostMapping("/admin/delete")
    public String delete(@RequestParam("path") String path, RedirectAttributes redirect) {
        try {
            newsService.delete(path);
            redirect.addFlashAttribute("success", "Deleted " + path + ".");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        } catch (IOException e) {
            logger.warn("Delete failed", e);
            redirect.addFlashAttribute("error", "Delete failed: " + e.getMessage());
        }
        return "redirect:/admin";
    }

    @PostMapping("/admin/reload")
    public String reload(RedirectAttributes redirect) {
        newsService.reload();
        redirect.addFlashAttribute("success", "Reloaded from disk.");
        return "redirect:/admin";
    }
}
