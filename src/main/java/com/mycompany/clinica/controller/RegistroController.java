package com.mycompany.clinica.controller;

import com.mycompany.clinica.entity.Rol;
import com.mycompany.clinica.entity.Usuario;
import com.mycompany.clinica.repository.UsuarioRepository;

import java.util.Locale;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class RegistroController {

    // =====================================================
    // DEPENDENCIAS
    // =====================================================

    private final UsuarioRepository usuarioRepository;
    private final PasswordEncoder passwordEncoder;

    public RegistroController(
            UsuarioRepository usuarioRepository,
            PasswordEncoder passwordEncoder) {

        this.usuarioRepository = usuarioRepository;
        this.passwordEncoder = passwordEncoder;
    }

    // =====================================================
    // REGISTRAR USUARIO
    // =====================================================

    @PostMapping("/registro")
    public String registrarUsuario(

            @RequestParam("nombre") String nombre,

            @RequestParam("username") String username,

            @RequestParam("correo") String correo,

            @RequestParam("password") String password,

            @RequestParam("confirmar_password")
            String confirmarPassword,

            @RequestParam(
                    value = "rol",
                    defaultValue = "USUARIO"
            )
            String rol,

            Authentication authentication,
            HttpServletRequest httpRequest,
            RedirectAttributes redirectAttributes) {

        // =================================================
        // VALIDAR ROL SOLICITADO
        // =================================================

        Rol rolSeleccionado;

        try {

            rolSeleccionado = Rol.valueOf(
                    rol.trim().toUpperCase(Locale.ROOT)
            );

        } catch (IllegalArgumentException | NullPointerException ex) {

            return error(
                    redirectAttributes,
                    "Selecciona un rol de registro válido."
            );
        }

        // =================================================
        // VERIFICAR SI ES ADMINISTRADOR
        // =================================================

        boolean esAdmin = authentication != null
                && authentication.isAuthenticated()
                && authentication.getAuthorities()
                    .stream()
                    .map(GrantedAuthority::getAuthority)
                    .anyMatch("ROLE_ADMIN"::equals);

        // =================================================
        // CONTROL DE ROLES PRIVILEGIADOS
        // =================================================

        if (rolSeleccionado != Rol.USUARIO && !esAdmin) {

            return error(
                    redirectAttributes,
                    "No tienes permisos para registrar "
                    + "usuarios con este rol."
            );
        }

        // Vinculación solo si Google confirmó previamente el correo en ESTA sesión.
        HttpSession session = httpRequest.getSession(false);
        String googleSubject = null;
        String googleEmail = null;
        if (session != null) {
            Object pendingSub = session.getAttribute("GOOGLE_REG_SUB");
            Object pendingEmail = session.getAttribute("GOOGLE_REG_EMAIL");
            Object pendingAt = session.getAttribute("GOOGLE_REG_AT");
            if (pendingSub instanceof String sub && !sub.isBlank()
                    && pendingEmail instanceof String mail && !mail.isBlank()
                    && pendingAt instanceof Long issuedAt
                    && issuedAt <= System.currentTimeMillis()
                    && System.currentTimeMillis() - issuedAt <= 10 * 60 * 1000L) {
                googleSubject = sub;
                googleEmail = mail;
            }
        }

        // =================================================
        // VALIDAR CAMPOS OBLIGATORIOS
        // =================================================

        if (nombre == null || nombre.isBlank()) {

            return error(
                    redirectAttributes,
                    "El nombre completo es obligatorio."
            );
        }

        if (username == null || username.isBlank()) {

            return error(
                    redirectAttributes,
                    "El nombre de usuario es obligatorio."
            );
        }

        if (correo == null || correo.isBlank()) {

            return error(
                    redirectAttributes,
                    "El correo electrónico es obligatorio."
            );
        }

        if (password == null || password.isBlank()) {

            return error(
                    redirectAttributes,
                    "La contraseña es obligatoria."
            );
        }

        if (confirmarPassword == null
                || confirmarPassword.isBlank()) {

            return error(
                    redirectAttributes,
                    "Debes confirmar la contraseña."
            );
        }

        // =================================================
        // NORMALIZAR DATOS
        // =================================================

        String nombreLimpio = nombre.trim();

        String usernameLimpio = username.trim();

        String correoLimpio = correo
                .trim()
                .toLowerCase(Locale.ROOT);

        if (googleSubject != null && !correoLimpio.equals(googleEmail)) {
            return error(redirectAttributes,
                "El correo del registro debe ser el mismo que verificaste con Google.");
        }

        // =================================================
        // VALIDAR CONTRASEÑA
        // =================================================

        if (password.length() < 8) {

            return error(
                    redirectAttributes,
                    "La contraseña debe tener al menos 8 caracteres."
            );
        }

        if (!password.equals(confirmarPassword)) {

            return error(
                    redirectAttributes,
                    "Las contraseñas no coinciden."
            );
        }

        // =================================================
        // VALIDAR USERNAME DUPLICADO
        // =================================================

        if (usuarioRepository.existsByUsername(usernameLimpio)) {

            return error(
                    redirectAttributes,
                    "El nombre de usuario ya está registrado."
            );
        }

        // =================================================
        // VALIDAR CORREO DUPLICADO
        // =================================================

        if (usuarioRepository.existsByEmail(correoLimpio)) {

            return error(
                    redirectAttributes,
                    "El correo electrónico ya está registrado."
            );
        }

        // =================================================
        // CREAR USUARIO
        // =================================================

        Usuario usuario = new Usuario();

        usuario.setNombre(nombreLimpio);

        usuario.setUsername(usernameLimpio);

        usuario.setEmail(correoLimpio);

        usuario.setPassword(
                passwordEncoder.encode(password)
        );

        usuario.setRol(rolSeleccionado);

        usuario.setActivo(true);
        if (googleSubject != null) {
            usuario.setGoogleSubject(googleSubject);
        }

        // =================================================
        // GUARDAR EN MONGODB
        // =================================================

        usuarioRepository.save(usuario);
        if (session != null && googleSubject != null) {
            session.removeAttribute("GOOGLE_REG_SUB");
            session.removeAttribute("GOOGLE_REG_EMAIL");
            session.removeAttribute("GOOGLE_REG_NAME");
            session.removeAttribute("GOOGLE_REG_AT");
        }

        // =================================================
        // REDIRECCIÓN
        // =================================================

        if (esAdmin) {

            redirectAttributes.addFlashAttribute(
                    "mensaje",
                    "Usuario registrado correctamente."
            );

            // CORRECCIÓN APLICADA AQUÍ: Redirige al panel de admin
            return "redirect:/admin/usuarios/nuevo"; 
        }

        return "redirect:/login?registro=true";
    }

    // =====================================================
    // MANEJO DE ERRORES
    // =====================================================

    private String error(
            RedirectAttributes redirectAttributes,
            String mensaje) {

        redirectAttributes.addFlashAttribute(
                "errorRegistro",
                mensaje
        );

        return "redirect:/login?tab=registro";
    }
}