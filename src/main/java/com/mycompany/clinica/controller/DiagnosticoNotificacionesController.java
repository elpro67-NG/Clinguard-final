
package com.mycompany.clinica.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import com.mycompany.clinica.entity.Rol;
import com.mycompany.clinica.entity.Usuario;
import com.mycompany.clinica.repository.CitaRepository;
import com.mycompany.clinica.repository.NotificacionRepository;
import com.mycompany.clinica.repository.UsuarioRepository;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Diagnóstico de solo lectura, reservado a ADMIN.
 * No expone cadenas de conexión.
 */
@RestController
@PreAuthorize("hasRole('ADMIN')")
public class DiagnosticoNotificacionesController {

    private final MongoTemplate mongoTemplate;
    private final CitaRepository citas;
    private final NotificacionRepository notificaciones;
    private final UsuarioRepository usuarios;

    public DiagnosticoNotificacionesController(
            MongoTemplate mongoTemplate,
            CitaRepository citas,
            NotificacionRepository notificaciones,
            UsuarioRepository usuarios) {

        this.mongoTemplate = mongoTemplate;
        this.citas = citas;
        this.notificaciones = notificaciones;
        this.usuarios = usuarios;
    }

    @GetMapping(
        value = "/admin/diagnostico-notificaciones",
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    public Map<String, Object> diagnosticar(
            Authentication authentication) {

        Map<String, Object> resultado = new LinkedHashMap<>();

        resultado.put(
            "baseDeDatosConectada",
            mongoTemplate.getDb().getName()
        );

        resultado.put(
            "coleccionCitas",
            mongoTemplate.getCollectionName(
                com.mycompany.clinica.entity.Cita.class
            )
        );

        resultado.put(
            "totalCitas",
            citas.count()
        );

        resultado.put(
            "coleccionNotificaciones",
            mongoTemplate.getCollectionName(
                com.mycompany.clinica.entity.Notificacion.class
            )
        );

        resultado.put(
            "totalNotificaciones",
            notificaciones.count()
        );

        resultado.put(
            "notificacionesMiUsuario",
            notificaciones.countByDestinatarioUsername(
                authentication.getName()
            )
        );

        resultado.put(
            "administradoresActivos",
            contarActivos(Rol.ADMIN)
        );

        resultado.put(
            "recepcionistasActivos",
            contarActivos(Rol.RECEPCIONISTA)
        );

        resultado.put(
            "medicosActivos",
            contarActivos(Rol.MEDICO)
        );

        resultado.put(
            "sugerencia",
            "Registra una cita NUEVA y vuelve a abrir esta página. "
            + "Revisa los logs de Spring si el total de "
            + "notificaciones no crece."
        );

        return resultado;
    }

    private long contarActivos(Rol rol) {

        return usuarios.findByRol(rol)
                .stream()
                .filter(Usuario::isActivo)
                .count();
    }
}
