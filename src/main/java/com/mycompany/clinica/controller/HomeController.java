package com.mycompany.clinica.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {

    /**
     * Pagina publica de entrada de ClinGuard.
     * La plantilla templates/index.html es la landing page.
     * El ingreso se realiza desde el boton "Iniciar sesion" -> /login.
     * El usuario autenticado conserva su acceso a /dashboard.
     */
    @GetMapping("/")
    public String inicio() {
        return "index";
    }
}
