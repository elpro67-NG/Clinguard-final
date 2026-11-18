
package com.mycompany.clinica.controller;

import com.mycompany.clinica.service.RecepcionistaDashboardService;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.dao.DataAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/recepcionista")
@PreAuthorize("hasRole('RECEPCIONISTA')")
public class RecepcionistaController {

    private static final Logger log =
            LoggerFactory.getLogger(RecepcionistaController.class);

    private final RecepcionistaDashboardService dashboardService;

    public RecepcionistaController(
            RecepcionistaDashboardService dashboardService) {

        this.dashboardService = dashboardService;
    }

    @GetMapping("/dashboard")
    public String dashboard(
            Authentication authentication,
            Model model) {

        model.addAttribute(
                "username",
                authentication.getName()
        );

        model.addAttribute(
                "rol",
                "RECEPCIONISTA"
        );

        try {

            dashboardService.cargarIndicadores(model);

        } catch (DataAccessException ex) {

            log.error(
                    "No fue posible cargar el dashboard de recepción desde MongoDB",
                    ex
            );

            model.addAttribute(
                    "dashboardError",
                    "No se pudieron consultar los datos de MongoDB. "
                    + "Revisa la conexión y la base configurada."
            );

            model.addAttribute("totalPacientes", 0);
            model.addAttribute("citasHoy", 0);
            model.addAttribute("citasPendientes", 0);
            model.addAttribute("citasConfirmadas", 0);

            model.addAttribute(
                    "proximasCitas",
                    java.util.List.of()
            );
        }

        return "dashboard-recepcionista";
    }
}
