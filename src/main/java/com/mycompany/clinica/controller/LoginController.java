package com.mycompany.clinica.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.ui.Model;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class LoginController {

    @Value("${google.oauth.enabled:false}")
    private boolean googleOAuthEnabled;


    // =========================================
    // MOSTRAR LOGIN
    // =========================================

    @GetMapping("/login")
    public String login(Model model) {
        model.addAttribute("googleOAuthEnabled", googleOAuthEnabled);
        return "login";
    }


    // =========================================
    // SESIÃ“N EXPIRADA
    // =========================================

    @GetMapping("/session-expired")
    public String sessionExpired() {

        return "redirect:/login?expired=true";
    }

}
