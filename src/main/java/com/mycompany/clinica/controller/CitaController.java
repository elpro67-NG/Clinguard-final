
package com.mycompany.clinica.controller;

import com.mycompany.clinica.entity.Cita;
import com.mycompany.clinica.entity.Rol;
import com.mycompany.clinica.entity.Paciente;
import com.mycompany.clinica.repository.CitaRepository;
import com.mycompany.clinica.repository.UsuarioRepository;
import com.mycompany.clinica.repository.PacienteRepository;
import com.mycompany.clinica.service.CitaService;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;

@Controller
@RequestMapping("/citas")
public class CitaController {

    private final CitaService citaService;
    private final CitaRepository citaRepository;
    private final UsuarioRepository usuarioRepository;
    private final PacienteRepository pacienteRepository;

    public CitaController(
            CitaService citaService,
            CitaRepository citaRepository,
            UsuarioRepository usuarioRepository,
            PacienteRepository pacienteRepository) {

        this.citaService = citaService;
        this.citaRepository = citaRepository;
        this.usuarioRepository = usuarioRepository;
        this.pacienteRepository = pacienteRepository;
    }

    // =============================================
    // PERMISOS SEGUN EL ROL
    // =============================================

    private boolean rol(Authentication auth, String nombre) {
        return auth != null
                && auth.isAuthenticated()
                && auth.getAuthorities().stream()
                .anyMatch(a ->
                    a.getAuthority().equals("ROLE_" + nombre));
    }

    private boolean gestor(Authentication auth) {
        return rol(auth, "ADMIN")
                || rol(auth, "MEDICO")
                || rol(auth, "ENFERMERO")
                || rol(auth, "RECEPCIONISTA");
    }

    private boolean puedeGestionar(
            Authentication auth, Cita cita) {

        if (rol(auth, "ADMIN")
                || rol(auth, "RECEPCIONISTA")) {
            return true;
        }

        String username = auth.getName();

        return (rol(auth, "MEDICO")
                && username.equals(cita.getMedicoUsername()))
                || (rol(auth, "ENFERMERO")
                && username.equals(cita.getEnfermeroUsername()));
    }

    private void exigirGestor(Authentication auth) {
        if (!gestor(auth)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Acceso no autorizado");
        }
    }

    private void exigirGestion(
            Authentication auth, Cita cita) {

        exigirGestor(auth);

        if (!puedeGestionar(auth, cita)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "La cita no está asignada a este usuario");
        }
    }

    private Cita citaExistente(String id) {
        return citaRepository.findById(id)
                .orElseThrow(() ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "La cita no existe"));
    }

    // =============================================
    // PREPARAR FORMULARIO
    // =============================================

    private void prepararFormulario(
            Model model,
            Cita cita,
            Authentication auth) {

        List<Paciente> pacientes =
                pacienteRepository.findAll();

        model.addAttribute("cita", cita);

        model.addAttribute(
                "pacientesRegistrados", pacientes);

        model.addAttribute(
                "medicosRegistrados",
                usuarioRepository.findByRol(Rol.MEDICO));

        model.addAttribute(
                "enfermerosRegistrados",
                usuarioRepository.findByRol(Rol.ENFERMERO));

        model.addAttribute(
                "estados",
                List.of(
                    "PROGRAMADA",
                    "PENDIENTE",
                    "CONFIRMADA",
                    "EN_CURSO",
                    "ATENDIDA",
                    "COMPLETADA",
                    "CANCELADA",
                    "REPROGRAMADA"
                )
        );

        model.addAttribute(
                "modoEdicion", cita.getId() != null);

        model.addAttribute("username", auth.getName());

        String nombre = pacientes.stream()
                .filter(p -> Objects.equals(
                    p.getId(), cita.getPacienteId()))
                .map(p ->
                    (Objects.toString(p.getNombres(), "")
                    + " "
                    + Objects.toString(p.getApellidos(), ""))
                    .trim())
                .findFirst()
                .orElse("Paciente asignado");

        model.addAttribute(
                "pacienteNombreDisplay", nombre);
    }

    // =============================================
    // LISTAR CITAS POR ROL
    // =============================================

    @GetMapping
    public String listarCitas(
            Authentication auth, Model model) {

        if (auth == null) {
            return "redirect:/login";
        }

        String username = auth.getName();

        boolean admin = rol(auth, "ADMIN");
        boolean medico = rol(auth, "MEDICO");
        boolean enfermero = rol(auth, "ENFERMERO");
        boolean recepcionista = rol(auth, "RECEPCIONISTA");
        boolean usuario = rol(auth, "USUARIO");

        List<Cita> citas;

        if (admin || recepcionista) {
            citas = citaService.listarTodas();

        } else if (medico) {
            citas = citaService.buscarPorMedico(username);

        } else if (enfermero) {
            citas = citaService.buscarPorEnfermero(username);

        } else if (usuario) {
            // Mostrar citas asignadas al usuario, incluso si recepción guardó
            // solo pacienteId. No exponer citas de otros pacientes.
            var cuenta = usuarioRepository.findByNombreUsuario(username);
            String pacienteId = cuenta.map(u -> u.getPacienteId()).orElse(null);
            boolean vinculado = pacienteId != null && !pacienteId.isBlank()
                    && pacienteRepository.existsById(pacienteId);
            Map<String, Cita> propias = new LinkedHashMap<>();
            for (Cita c : citaService.buscarPorUsuario(username)) {
                if ((c.getPacienteId() == null || c.getPacienteId().isBlank())
                        || (vinculado && pacienteId.equals(c.getPacienteId()))) {
                    propias.put(c.getId(), c);
                }
            }
            if (vinculado) {
                for (Cita c : citaRepository.findByPacienteId(pacienteId)) {
                    propias.put(c.getId(), c);
                }
            }
            citas = new ArrayList<>(propias.values());
            model.addAttribute("pacienteVinculado", vinculado);

        } else {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN);
        }

        citas.sort(
            Comparator.comparing(
                Cita::getFecha,
                Comparator.nullsLast(String::compareTo))
            .thenComparing(
                Cita::getHora,
                Comparator.nullsLast(String::compareTo))
        );

        Map<String, String> mapa = new HashMap<>();

        pacienteRepository.findAll().forEach(p ->
            mapa.put(
                p.getId(),
                (Objects.toString(p.getNombres(), "")
                + " "
                + Objects.toString(p.getApellidos(), ""))
                .trim()
            )
        );

        model.addAttribute("citas", citas);
        model.addAttribute("mapaPacientes", mapa);

        model.addAttribute("esAdmin", admin);
        model.addAttribute("esMedico", medico);
        model.addAttribute("esEnfermero", enfermero);
        model.addAttribute("esRecepcionista", recepcionista);
        model.addAttribute("esUsuario", usuario);

        model.addAttribute("puedeCrear", gestor(auth));
        model.addAttribute("username", username);

        return "citas";
    }

    // =============================================
    // CREAR NUEVA CITA
    // =============================================

    @GetMapping("/nueva")
    public String nuevaCita(
            Authentication auth, Model model) {

        exigirGestor(auth);

        Cita cita = new Cita();

        cita.setFecha(
            LocalDate.now(
                ZoneId.of("America/Bogota"))
                .toString()
        );

        cita.setHora(
            LocalTime.now(
                ZoneId.of("America/Bogota"))
                .withSecond(0)
                .withNano(0)
                .toString()
        );

        cita.setEstado("PROGRAMADA");

        if (rol(auth, "MEDICO")) {
            cita.setMedicoUsername(auth.getName());
        }

        if (rol(auth, "ENFERMERO")) {
            cita.setEnfermeroUsername(auth.getName());
        }

        prepararFormulario(model, cita, auth);

        return "cita-form";
    }

    // =============================================
    // GUARDAR NUEVA CITA
    // =============================================

    @PostMapping("/guardar")
    public String guardarCita(
            @ModelAttribute("cita") Cita cita,
            Authentication auth,
            RedirectAttributes flash) {

        exigirGestor(auth);

        cita.setId(null);
        cita.setEstado("PROGRAMADA");

        if (rol(auth, "MEDICO")) {
            cita.setMedicoUsername(auth.getName());
        }

        if (rol(auth, "ENFERMERO")) {
            cita.setEnfermeroUsername(auth.getName());
        }

        try {
            citaService.guardar(cita);

            flash.addFlashAttribute(
                "exito",
                "Cita registrada correctamente.");

            return "redirect:/citas";

        } catch (IllegalArgumentException
                | IllegalStateException ex) {

            flash.addFlashAttribute(
                "error", ex.getMessage());

            return "redirect:/citas/nueva";
        }
    }

    // =============================================
    // EDITAR CITA
    // =============================================

    @GetMapping("/editar/{id}")
    public String editarCita(
            @PathVariable String id,
            Authentication auth,
            Model model) {

        Cita cita = citaExistente(id);

        exigirGestion(auth, cita);

        prepararFormulario(model, cita, auth);

        return "cita-form";
    }

    // =============================================
    // ACTUALIZAR CITA
    // =============================================

    @PostMapping("/actualizar/{id}")
    public String actualizarCita(
            @PathVariable String id,
            @ModelAttribute("cita") Cita nueva,
            Authentication auth,
            RedirectAttributes flash) {

        Cita original = citaExistente(id);

        exigirGestion(auth, original);

        // Solo administracion y recepcion pueden
        // reasignar pacientes o profesionales.

        if (rol(auth, "ADMIN")
                || rol(auth, "RECEPCIONISTA")) {

            if (nueva.getPacienteId() != null
                    && !nueva.getPacienteId().isBlank()) {

                original.setPacienteId(
                    nueva.getPacienteId());
            }

            original.setMedicoUsername(
                nueva.getMedicoUsername());

            original.setEnfermeroUsername(
                nueva.getEnfermeroUsername());
        }

        original.setFecha(nueva.getFecha());
        original.setHora(nueva.getHora());
        original.setMotivo(nueva.getMotivo());

        original.setObservaciones(
            nueva.getObservaciones());

        if (nueva.getEstado() != null) {
            original.setEstado(nueva.getEstado());
        }

        try {
            citaService.guardar(original);

            flash.addFlashAttribute(
                "exito",
                "Cita actualizada correctamente.");

            return "redirect:/citas";

        } catch (IllegalArgumentException
                | IllegalStateException ex) {

            flash.addFlashAttribute(
                "error", ex.getMessage());

            return "redirect:/citas/editar/" + id;
        }
    }

    // =============================================
    // CAMBIAR ESTADO DE CITA
    // =============================================

    @PostMapping("/estado/{id}")
    public String cambiarEstado(
            @PathVariable String id,
            @RequestParam String estado,
            Authentication auth,
            RedirectAttributes flash) {

        Cita cita = citaExistente(id);

        exigirGestion(auth, cita);

        try {
            cita.setEstado(
                estado.toUpperCase(Locale.ROOT));

            citaService.guardar(cita);

            flash.addFlashAttribute(
                "exito",
                "Estado actualizado correctamente.");

        } catch (IllegalArgumentException
                | IllegalStateException ex) {

            flash.addFlashAttribute(
                "error", ex.getMessage());
        }

        return "redirect:/citas";
    }

    // =============================================
    // ELIMINAR CITA
    // =============================================

    @PostMapping("/eliminar/{id}")
    public String eliminarCita(
            @PathVariable String id,
            Authentication auth,
            RedirectAttributes flash) {

        if (!rol(auth, "ADMIN")) {
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN);
        }

        citaExistente(id);

        citaService.eliminar(id);

        flash.addFlashAttribute(
            "exito",
            "Cita eliminada correctamente.");

        return "redirect:/citas";
    }
}
