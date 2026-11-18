
package com.mycompany.clinica.security;

import com.mycompany.clinica.entity.Usuario;
import com.mycompany.clinica.repository.UsuarioRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Locale;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

@Component
public class OAuth2AuthenticationSuccessHandler implements AuthenticationSuccessHandler {

    private final UsuarioRepository repository;
    private final JwtService jwtService;
    private final JwtCookieService cookieService;

    public OAuth2AuthenticationSuccessHandler(
            UsuarioRepository repository,
            JwtService jwtService,
            JwtCookieService cookieService) {

        this.repository = repository;
        this.jwtService = jwtService;
        this.cookieService = cookieService;
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) throws IOException {

        OAuth2User principal = (OAuth2User) authentication.getPrincipal();

        String subject = principal.getAttribute("sub");
        String email = principal.getAttribute("email");
        Object verified = principal.getAttribute("email_verified");

        if (subject == null || subject.isBlank()
                || email == null || email.isBlank()
                || !Boolean.TRUE.equals(verified)) {

            response.sendRedirect(
                    request.getContextPath() + "/login?googleError=true");
            return;
        }

        Usuario user = repository.findByGoogleSubject(subject).orElse(null);

        // CUENTA NUEVA DE GOOGLE
        if (user == null) {

            String normalizedEmail =
                    email.trim().toLowerCase(Locale.ROOT);

            // Evitar vincular automáticamente cuentas locales existentes
            if (repository.findByEmail(normalizedEmail).isPresent()
                    || repository.findByUsername(normalizedEmail).isPresent()
                    || repository.findByNombreUsuario(normalizedEmail).isPresent()) {

                request.getSession().invalidate();
                SecurityContextHolder.clearContext();

                response.sendRedirect(
                        request.getContextPath()
                        + "/login?googleLinkRequired=true");
                return;
            }

            // No crear todavía el usuario en MongoDB
            // No generar JWT

            request.getSession().invalidate();
            SecurityContextHolder.clearContext();

            HttpSession pending = request.getSession(true);

            pending.setAttribute("GOOGLE_REG_SUB", subject);
            pending.setAttribute("GOOGLE_REG_EMAIL", normalizedEmail);

            String nombre = principal.getAttribute("name");

            pending.setAttribute(
                    "GOOGLE_REG_NAME",
                    nombre == null ? "" : nombre
            );

            pending.setAttribute(
                    "GOOGLE_REG_AT",
                    System.currentTimeMillis()
            );

            // Redirigir al formulario de registro
            response.sendRedirect(
                    request.getContextPath() + "/login?tab=registro");

            return;
        }

        // CUENTA DE GOOGLE YA REGISTRADA
        if (!user.isEnabled() || user.getRol() == null) {

            response.sendRedirect(
                    request.getContextPath() + "/login?googleDenied=true");
            return;
        }

        String token = jwtService.generateToken(user);

        cookieService.addJwtCookie(response, token);

        var localAuth = new UsernamePasswordAuthenticationToken(
                user,
                null,
                user.getAuthorities()
        );

        SecurityContextHolder.getContext()
                .setAuthentication(localAuth);

        response.sendRedirect(
                request.getContextPath() + "/dashboard");
    }
}
