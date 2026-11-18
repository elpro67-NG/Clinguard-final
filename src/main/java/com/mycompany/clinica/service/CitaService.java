
package com.mycompany.clinica.service;

import com.mycompany.clinica.entity.Cita;
import com.mycompany.clinica.entity.Paciente;
import com.mycompany.clinica.entity.Rol;
import com.mycompany.clinica.entity.Usuario;
import com.mycompany.clinica.repository.CitaRepository;
import com.mycompany.clinica.repository.PacienteRepository;
import com.mycompany.clinica.repository.UsuarioRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Service
public class CitaService {

        private static final Logger log = LoggerFactory.getLogger(CitaService.class);

        private static final ZoneId ZONA_COLOMBIA = ZoneId.of("America/Bogota");

        private static final DateTimeFormatter FORMATO_HORA = DateTimeFormatter.ofPattern("HH:mm");

        private static final Set<String> ESTADOS_NO_DISPONIBLES = Set.of("CANCELADA", "CANCELADO", "RECHAZADA",
                        "RECHAZADO");

        private static final Set<String> ESTADOS_FINALES = Set.of(
                        "CANCELADA", "CANCELADO",
                        "RECHAZADA", "RECHAZADO",
                        "COMPLETADA", "COMPLETADO",
                        "ATENDIDA", "ATENDIDO");

        private static final Set<String> ESTADOS_VALIDOS = Set.of(
                        "PROGRAMADA", "PENDIENTE",
                        "CONFIRMADA", "CANCELADA",
                        "CANCELADO", "RECHAZADA",
                        "RECHAZADO", "COMPLETADA",
                        "COMPLETADO", "ATENDIDA",
                        "ATENDIDO", "EN_CURSO",
                        "REPROGRAMADA");

        private final CitaRepository citaRepository;
        private final UsuarioRepository usuarioRepository;
        private final PacienteRepository pacienteRepository;
        private final NotificacionService notificacionService;

        public CitaService(
                        CitaRepository citaRepository,
                        UsuarioRepository usuarioRepository,
                        PacienteRepository pacienteRepository,
                        NotificacionService notificacionService) {

                this.citaRepository = citaRepository;
                this.usuarioRepository = usuarioRepository;
                this.pacienteRepository = pacienteRepository;
                this.notificacionService = notificacionService;
        }

        // ==========================================
        // CONSULTAS GENERALES
        // ==========================================

        public List<Cita> listarTodas() {
                return citaRepository.findAll();
        }

        public Optional<Cita> buscarPorId(String id) {
                validarTexto(id, "El identificador");
                return citaRepository.findById(id.trim());
        }

        public void eliminar(String id) {
                validarTexto(id, "El identificador");
                citaRepository.deleteById(id.trim());
        }

        public boolean existePorId(String id) {
                return id != null
                                && !id.isBlank()
                                && citaRepository.existsById(id.trim());
        }

        // ==========================================
        // GUARDAR CITA Y GENERAR NOTIFICACIONES
        // ==========================================

        public synchronized Cita guardar(Cita cita) {

                if (cita == null) {
                        throw new IllegalArgumentException(
                                        "No se puede guardar una cita vacía.");
                }

                String estado = normalizarEstado(cita.getEstado());

                if (estado.isBlank()) {
                        estado = "PROGRAMADA";
                }

                if (!ESTADOS_VALIDOS.contains(estado)) {
                        throw new IllegalArgumentException(
                                        "Estado de cita no permitido: " + estado);
                }

                cita.setEstado(estado);

                Cita anterior = null;

                if (cita.getId() != null && !cita.getId().isBlank()) {
                        anterior = citaRepository.findById(cita.getId())
                                        .orElseThrow(() -> new IllegalArgumentException(
                                                        "La cita no existe."));
                }

                boolean cambioAgenda = anterior == null
                                || !Objects.equals(anterior.getFecha(), cita.getFecha())
                                || !Objects.equals(anterior.getHora(), cita.getHora())
                                || !Objects.equals(
                                                anterior.getMedicoUsername(),
                                                cita.getMedicoUsername())
                                || !Objects.equals(
                                                anterior.getUsuarioUsername(),
                                                cita.getUsuarioUsername())
                                || !Objects.equals(
                                                anterior.getPacienteId(),
                                                cita.getPacienteId());

                if (cambioAgenda) {

                        if (anterior != null
                                        && ESTADOS_FINALES.contains(
                                                        normalizarEstado(anterior.getEstado()))) {

                                throw new IllegalStateException(
                                                "No se puede reprogramar una cita cancelada o finalizada.");
                        }

                        String fecha = normalizarFecha(cita.getFecha());
                        String hora = normalizarHora(cita.getHora());

                        String medico = exigirUsername(
                                        cita.getMedicoUsername(), "El médico");

                        String paciente = cita.getUsuarioUsername();
                        String pacienteId = cita.getPacienteId();

                        if (pacienteId != null) {
                                pacienteId = pacienteId.trim();
                        }

                        validarFechaHoraFutura(fecha, hora);
                        validarMedicoActivo(medico);

                        if (pacienteId != null && !pacienteId.isBlank()) {

                                validarPacienteActivo(pacienteId);

                                if (!ESTADOS_NO_DISPONIBLES.contains(estado)) {
                                        validarSinCrucesDirectorio(
                                                        pacienteId, medico, fecha,
                                                        hora, cita.getId());
                                }

                        } else {

                                paciente = exigirUsername(
                                                paciente, "El usuario del paciente");

                                validarUsuarioPacienteActivo(paciente);

                                if (!ESTADOS_NO_DISPONIBLES.contains(estado)) {
                                        validarSinCruces(
                                                        paciente, medico, fecha,
                                                        hora, cita.getId());
                                }
                        }

                        cita.setFecha(fecha);
                        cita.setHora(hora);
                        cita.setMedicoUsername(medico);
                        cita.setPacienteId(
                                        pacienteId == null || pacienteId.isBlank()
                                                        ? null
                                                        : pacienteId);
                        cita.setUsuarioUsername(paciente);
                }

                // Guardar primero en MongoDB.
                Cita guardada = citaRepository.save(cita);

                log.info(
                                "Cita guardada: id={}, medico={}, nueva={}",
                                guardada.getId(),
                                guardada.getMedicoUsername(),
                                anterior == null);

                // Notificar únicamente cuando corresponda.
                if (anterior == null) {

                        notificarNuevaCita(guardada);

                } else {
                        // El objeto recibido puede ser el mismo que tiene el controlador,
                        // pero 'anterior' se consultó desde MongoDB antes de guardar.
                        String estadoAnterior = normalizarEstado(anterior.getEstado());
                        if (!estadoAnterior.equals(normalizarEstado(guardada.getEstado()))) {
                                notificarEstadoAlPaciente(guardada, guardada.getEstado());
                        }
                        if (!Objects.equals(anterior.getMedicoUsername(),
                                guardada.getMedicoUsername())) {
                                notificarReasignacion(anterior, guardada);
                        }
                }

                return guardada;
        }

        // ==========================================
        // NOTIFICACION DE NUEVA CITA
        // ==========================================

        private void notificarNuevaCita(Cita cita) {

                try {

                        notificacionService.citaCreada(
                                        cita.getId(),
                                        null, // El aviso al paciente se personaliza abajo.
                                        cita.getMedicoUsername(),
                                        cita.getEnfermeroUsername());

                        // Aviso individual generado fuera de este try/catch.

                        log.info(
                                        "Avisos de cita {} procesados para usuarios activos",
                                        cita.getId());

                } catch (Exception ex) {

                        log.error(
                                        "Cita {} guardada, pero no se pudo notificar",
                                        cita.getId(),
                                        ex);
                }
                // Aunque falle el aviso administrativo, se intenta el aviso personal.
                notificarEstadoAlPaciente(cita, cita.getEstado());
        }

        // ==========================================
        // NOTIFICACION PERSONAL DEL PACIENTE
        // ==========================================
        /**
         * Solo se usa la vinculacion verificada por administracion.
         * Si la cita tiene pacienteId, nunca se usa un username no vinculado
         * como respaldo: evita enviar datos a otra persona.
         * No se envian correos ni SMS; aparece en /notificaciones.
         */
        private void notificarEstadoAlPaciente(Cita cita, String nuevoEstado) {
                if (cita == null || cita.getId() == null) return;

                try {
                        Usuario destinatario = null;
                        String pacienteId = cita.getPacienteId();

                        if (pacienteId != null && !pacienteId.isBlank()) {
                                // Buscar por el mismo pacienteId usado en Mis citas y Mi historial.
                                // Nunca deducir el destinatario por nombre, correo o documento.
                                List<Usuario> vinculados = usuarioRepository
                                        .findAllByPacienteId(pacienteId.trim())
                                        .stream()
                                        .filter(u -> u.isActivo() && u.getRol() == Rol.USUARIO)
                                        .toList();

                                if (vinculados.size() != 1) {
                                        log.warn("AVISO_CITA_OMITIDO: cita={}, pacienteId={}, usuariosActivosVinculados={}. "
                                                        + "Verifica el vinculo ADMIN -> Pacientes -> Vincular cuenta.",
                                                cita.getId(), pacienteId, vinculados.size());
                                        return;
                                }
                                destinatario = vinculados.get(0);
                        } else if (cita.getUsuarioUsername() != null
                                && !cita.getUsuarioUsername().isBlank()) {
                                destinatario = usuarioRepository
                                        .findByNombreUsuario(cita.getUsuarioUsername().trim())
                                        .filter(u -> u.isActivo() && u.getRol() == Rol.USUARIO)
                                        .orElse(null);
                        }

                        if (destinatario == null) {
                                log.warn("AVISO_CITA_OMITIDO: cita={}, sin cuenta USUARIO destinataria; pacienteId={}, usuarioUsername={}",
                                        cita.getId(), cita.getPacienteId(), cita.getUsuarioUsername());
                                return;
                        }

                        String estado = normalizarEstado(nuevoEstado);
                        String titulo;
                        String mensaje;
                        String agenda = (cita.getFecha() == null ? "" : cita.getFecha())
                                + (cita.getHora() == null ? "" : " a las " + cita.getHora());

                        switch (estado) {
                                case "PROGRAMADA" -> {
                                        titulo = "Cita programada";
                                        mensaje = "Tu cita médica ha sido programada para " + agenda.trim() + ".";
                                }
                                case "CONFIRMADA" -> {
                                        titulo = "Cita confirmada";
                                        mensaje = "Tu cita médica del " + agenda.trim() + " ha sido confirmada.";
                                }
                                case "CANCELADA", "CANCELADO" -> {
                                        titulo = "Cita cancelada";
                                        mensaje = "Tu cita médica del " + agenda.trim() + " ha sido cancelada.";
                                }
                                case "ATENDIDA", "ATENDIDO", "COMPLETADA", "COMPLETADO" -> {
                                        titulo = "Cita atendida";
                                        mensaje = "Tu cita médica del " + agenda.trim() + " fue marcada como atendida.";
                                }
                                default -> { return; }
                        }

                        notificacionService.crear(
                                destinatario.getNombreUsuario(), "CITA",
                                titulo, mensaje, cita.getId(), "CITA");
                        log.info("Notificacion de cita {} enviada al usuario {} (estado={})",
                                cita.getId(), destinatario.getNombreUsuario(), estado);
                } catch (Exception ex) {
                        // La notificacion no debe deshacer una cita ya guardada.
                        log.error("Cita {} guardada, pero fallo su notificacion al paciente",
                                cita.getId(), ex);
                }
        }

        // ==========================================
        // NOTIFICACION DE REASIGNACION
        // ==========================================

        private void notificarReasignacion(
                        Cita anterior,
                        Cita nueva) {

                try {

                        notificacionService.citaReasignada(
                                        nueva.getId(),
                                        nueva.getUsuarioUsername(),
                                        anterior.getMedicoUsername(),
                                        anterior.getEnfermeroUsername(),
                                        nueva.getMedicoUsername(),
                                        nueva.getEnfermeroUsername());

                } catch (Exception ex) {

                        log.error(
                                        "Cita {} reasignada, pero no se pudo notificar",
                                        nueva.getId(),
                                        ex);
                }
        }

        // ==========================================
        // BUSQUEDAS POR USUARIO Y PROFESIONALES
        // ==========================================

        public List<Cita> buscarPorUsuario(String username) {
                validarTexto(username, "El usuario");
                return citaRepository.findByUsuarioUsername(
                                username.trim());
        }

        public List<Cita> buscarPorMedico(String username) {
                validarTexto(username, "El médico");
                return citaRepository.findByMedicoUsername(
                                username.trim());
        }

        public List<Cita> buscarPorEnfermero(String username) {
                validarTexto(username, "El enfermero");
                return citaRepository.findByEnfermeroUsername(
                                username.trim());
        }

        public List<Cita> buscarPorFecha(String fecha) {
                return citaRepository.findByFecha(
                                normalizarFecha(fecha));
        }

        public List<Cita> buscarPorEstado(String estado) {
                validarTexto(estado, "El estado");
                return citaRepository.findByEstado(estado.trim());
        }

        public List<Cita> buscarPorFechaYEstado(
                        String fecha,
                        String estado) {

                validarTexto(estado, "El estado");

                return citaRepository.findByFechaAndEstado(
                                normalizarFecha(fecha),
                                estado.trim());
        }

        public List<Cita> obtenerAgendaMedico(
                        String medicoUsername,
                        String fecha) {

                validarTexto(medicoUsername, "El médico");

                return citaRepository
                                .findByMedicoUsernameAndFechaOrderByHoraAsc(
                                                medicoUsername.trim(),
                                                normalizarFecha(fecha));
        }

        public List<Cita> listarOrdenadas() {
                return citaRepository
                                .findAllByOrderByFechaAscHoraAsc();
        }

        // ==========================================
        // PROXIMAS CITAS
        // ==========================================

        public List<Cita> listarProximas() {

                LocalDateTime ahora = LocalDateTime.now(ZONA_COLOMBIA);

                return citaRepository
                                .findByFechaGreaterThanEqualOrderByFechaAscHoraAsc(
                                                ahora.toLocalDate().toString())
                                .stream()
                                .filter(cita -> {
                                        LocalDateTime fechaHora = obtenerFechaHora(cita);
                                        return fechaHora != null
                                                        && !fechaHora.isBefore(ahora);
                                })
                                .filter(cita -> !ESTADOS_FINALES.contains(
                                                normalizarEstado(cita.getEstado())))
                                .toList();
        }

        // ==========================================
        // DISPONIBILIDAD DEL MEDICO
        // ==========================================

        public boolean estaDisponible(
                        String medicoUsername,
                        String fecha,
                        String hora) {

                return estaDisponible(
                                medicoUsername, fecha, hora, null);
        }

        public boolean estaDisponible(
                        String medicoUsername,
                        String fecha,
                        String hora,
                        String citaExcluirId) {

                validarTexto(medicoUsername, "El médico");

                String f = normalizarFecha(fecha);
                LocalTime solicitada = convertirHora(hora);

                return citaRepository
                                .findByMedicoUsernameAndFechaOrderByHoraAsc(
                                                medicoUsername.trim(), f)
                                .stream()
                                .filter(c -> citaExcluirId == null
                                                || !citaExcluirId.equals(c.getId()))
                                .filter(c -> !ESTADOS_NO_DISPONIBLES.contains(
                                                normalizarEstado(c.getEstado())))
                                .noneMatch(c -> {
                                        LocalTime existente = obtenerHora(c);
                                        return existente == null
                                                        || existente.equals(solicitada);
                                });
        }

        // ==========================================
        // REGISTRAR DESDE RECEPCION
        // ==========================================

        public synchronized Cita registrarDesdeRecepcion(
                        String usuarioUsername,
                        String medicoUsername,
                        String fecha,
                        String hora) {

                String usuario = exigirUsername(
                                usuarioUsername, "El usuario");
                String medico = exigirUsername(
                                medicoUsername, "El médico");

                validarUsuarioPacienteActivo(usuario);
                validarMedicoActivo(medico);

                String f = normalizarFecha(fecha);
                String h = normalizarHora(hora);

                validarFechaHoraFutura(f, h);
                validarSinCruces(usuario, medico, f, h, null);

                Cita cita = new Cita();

                cita.setUsuarioUsername(usuario);
                cita.setMedicoUsername(medico);
                cita.setFecha(f);
                cita.setHora(h);
                cita.setEstado("PROGRAMADA");

                Cita guardada = citaRepository.save(cita);

                notificarNuevaCita(guardada);

                return guardada;
        }

        // ==========================================
        // REGISTRAR PACIENTE DESDE RECEPCION
        // ==========================================

        public synchronized Cita registrarPacienteDesdeRecepcion(
                        String pacienteId,
                        String medicoUsername,
                        String fecha,
                        String hora) {

                validarTexto(pacienteId, "El paciente");

                String medico = exigirUsername(
                                medicoUsername, "El médico");

                String f = normalizarFecha(fecha);
                String h = normalizarHora(hora);

                validarPacienteActivo(pacienteId);
                validarMedicoActivo(medico);
                validarFechaHoraFutura(f, h);

                validarSinCrucesDirectorio(
                                pacienteId.trim(), medico, f, h, null);

                Cita cita = new Cita();

                cita.setPacienteId(pacienteId.trim());
                cita.setMedicoUsername(medico);
                cita.setFecha(f);
                cita.setHora(h);
                cita.setEstado("PROGRAMADA");

                Cita guardada = citaRepository.save(cita);

                notificarNuevaCita(guardada);

                return guardada;
        }

        // ==========================================
        // REPROGRAMAR DESDE RECEPCION
        // ==========================================

        public synchronized Cita reprogramarDesdeRecepcion(
                        String id,
                        String nuevaFecha,
                        String nuevaHora) {

                Cita cita = obtenerCitaObligatoria(id);

                if (ESTADOS_FINALES.contains(
                                normalizarEstado(cita.getEstado()))) {

                        throw new IllegalStateException(
                                        "No se puede reprogramar una cita cancelada o finalizada.");
                }

                String f = normalizarFecha(nuevaFecha);
                String h = normalizarHora(nuevaHora);

                validarFechaHoraFutura(f, h);
                validarMedicoActivo(cita.getMedicoUsername());

                if (cita.getPacienteId() != null
                                && !cita.getPacienteId().isBlank()) {

                        validarSinCrucesDirectorio(
                                        cita.getPacienteId(),
                                        cita.getMedicoUsername(),
                                        f, h, cita.getId());

                } else {

                        validarSinCruces(
                                        cita.getUsuarioUsername(),
                                        cita.getMedicoUsername(),
                                        f, h, cita.getId());
                }

                cita.setFecha(f);
                cita.setHora(h);
                cita.setEstado("PROGRAMADA");

                Cita guardada = citaRepository.save(cita);
                notificarEstadoAlPaciente(guardada, "PROGRAMADA");
                return guardada;
        }

        // ==========================================
        // CONFIRMAR DESDE RECEPCION
        // ==========================================

        public Cita confirmarDesdeRecepcion(String id) {

                Cita cita = obtenerCitaObligatoria(id);
                String estado = normalizarEstado(cita.getEstado());

                if ("CONFIRMADA".equals(estado)) {
                        throw new IllegalStateException(
                                        "Esta cita ya se encuentra confirmada.");
                }

                if (ESTADOS_FINALES.contains(estado)) {
                        throw new IllegalStateException(
                                        "No se puede confirmar una cita cancelada o finalizada.");
                }

                LocalDateTime fechaHora = obtenerFechaHora(cita);

                if (fechaHora == null
                                || !fechaHora.isAfter(
                                                LocalDateTime.now(ZONA_COLOMBIA))) {

                        throw new IllegalStateException(
                                        "No se puede confirmar una cita cuya fecha y hora ya pasaron.");
                }

                if (!Set.of(
                                "PROGRAMADA", "PENDIENTE", "REPROGRAMADA")
                                .contains(estado)) {

                        throw new IllegalStateException(
                                        "El estado actual no permite confirmar esta cita.");
                }

                validarMedicoActivo(cita.getMedicoUsername());

                if (cita.getPacienteId() != null
                                && !cita.getPacienteId().isBlank()) {

                        validarSinCrucesDirectorio(
                                        cita.getPacienteId(),
                                        cita.getMedicoUsername(),
                                        normalizarFecha(cita.getFecha()),
                                        normalizarHora(cita.getHora()),
                                        cita.getId());

                } else {

                        validarSinCruces(
                                        cita.getUsuarioUsername(),
                                        cita.getMedicoUsername(),
                                        normalizarFecha(cita.getFecha()),
                                        normalizarHora(cita.getHora()),
                                        cita.getId());
                }

                cita.setEstado("CONFIRMADA");

                Cita guardada = citaRepository.save(cita);
                notificarEstadoAlPaciente(guardada, "CONFIRMADA");
                return guardada;
        }

        // ==========================================
        // CANCELAR DESDE RECEPCION
        // ==========================================

        public Cita cancelarDesdeRecepcion(String id) {

                Cita cita = obtenerCitaObligatoria(id);

                if (ESTADOS_FINALES.contains(
                                normalizarEstado(cita.getEstado()))) {

                        throw new IllegalStateException(
                                        "No se puede cancelar una cita ya cancelada o finalizada.");
                }

                cita.setEstado("CANCELADA");

                Cita guardada = citaRepository.save(cita);
                notificarEstadoAlPaciente(guardada, "CANCELADA");
                return guardada;
        }

        // ==========================================
        // CONTADORES
        // ==========================================

        public long contarCitasHoy() {
                return citaRepository.countByFecha(
                                LocalDate.now(ZONA_COLOMBIA).toString());
        }

        public long contarPorEstado(String estado) {
                validarTexto(estado, "El estado");
                return citaRepository.countByEstado(estado.trim());
        }

        public long contarPorFechaYEstado(
                        String fecha,
                        String estado) {

                validarTexto(estado, "El estado");

                return citaRepository.countByFechaAndEstado(
                                normalizarFecha(fecha),
                                estado.trim());
        }

        // ==========================================
        // VALIDACIONES
        // ==========================================

        private Cita obtenerCitaObligatoria(String id) {

                validarTexto(id, "El identificador de la cita");

                return citaRepository.findById(id.trim())
                                .orElseThrow(() -> new IllegalArgumentException(
                                                "La cita solicitada no existe."));
        }

        private void validarPacienteActivo(String id) {

                Paciente paciente = pacienteRepository
                                .findById(id.trim())
                                .orElseThrow(() -> new IllegalArgumentException(
                                                "El paciente seleccionado no está registrado."));

                if (!paciente.isActivo()) {
                        throw new IllegalStateException(
                                        "El paciente está inactivo.");
                }
        }

        private void validarMedicoActivo(String username) {

                validarTexto(username, "El médico");

                Usuario medico = usuarioRepository
                                .findByUsername(username.trim())
                                .orElseThrow(() -> new IllegalArgumentException(
                                                "El médico seleccionado no está registrado."));

                if (medico.getRol() != Rol.MEDICO) {
                        throw new IllegalArgumentException(
                                        "El usuario seleccionado no tiene el rol de médico.");
                }

                if (!medico.isActivo()) {
                        throw new IllegalStateException(
                                        "No se pueden asignar citas a un médico inactivo.");
                }
        }

        private void validarUsuarioPacienteActivo(String username) {

                validarTexto(username, "El usuario del paciente");

                Usuario usuario = usuarioRepository
                                .findByUsername(username.trim())
                                .orElseThrow(() -> new IllegalArgumentException(
                                                "El usuario del paciente no está registrado."));

                if (usuario.getRol() != Rol.USUARIO) {
                        throw new IllegalArgumentException(
                                        "La cuenta seleccionada no tiene el rol de usuario paciente.");
                }

                if (!usuario.isActivo()) {
                        throw new IllegalStateException(
                                        "No se pueden registrar citas para una cuenta inactiva.");
                }
        }

        private void validarSinCrucesDirectorio(
                        String pacienteId,
                        String medico,
                        String fecha,
                        String hora,
                        String excluirId) {

                if (!estaDisponible(medico, fecha, hora, excluirId)) {
                        throw new IllegalStateException(
                                        "El médico ya tiene una cita en ese horario.");
                }

                LocalTime solicitada = convertirHora(hora);

                boolean ocupado = citaRepository.findByFecha(fecha)
                                .stream()
                                .filter(c -> excluirId == null
                                                || !excluirId.equals(c.getId()))
                                .filter(c -> pacienteId.equals(c.getPacienteId()))
                                .filter(c -> !ESTADOS_NO_DISPONIBLES.contains(
                                                normalizarEstado(c.getEstado())))
                                .anyMatch(c -> solicitada.equals(obtenerHora(c)));

                if (ocupado) {
                        throw new IllegalStateException(
                                        "El paciente ya tiene una cita en ese horario.");
                }
        }

        private void validarSinCruces(
                        String usuario,
                        String medico,
                        String fecha,
                        String hora,
                        String excluirId) {

                if (!estaDisponible(medico, fecha, hora, excluirId)) {
                        throw new IllegalStateException(
                                        "El médico ya tiene una cita en ese horario.");
                }

                LocalTime solicitada = convertirHora(hora);

                boolean ocupado = citaRepository
                                .findByUsuarioUsername(usuario)
                                .stream()
                                .filter(c -> excluirId == null
                                                || !excluirId.equals(c.getId()))
                                .filter(c -> fecha.equals(c.getFecha()))
                                .filter(c -> !ESTADOS_NO_DISPONIBLES.contains(
                                                normalizarEstado(c.getEstado())))
                                .anyMatch(c -> {
                                        LocalTime existente = obtenerHora(c);
                                        return existente == null
                                                        || existente.equals(solicitada);
                                });

                if (ocupado) {
                        throw new IllegalStateException(
                                        "El paciente ya tiene una cita en ese horario.");
                }
        }

        private String exigirUsername(String valor, String nombre) {
                validarTexto(valor, nombre);
                return valor.trim();
        }

        private void validarTexto(String valor, String campo) {
                if (valor == null || valor.isBlank()) {
                        throw new IllegalArgumentException(
                                        campo + " es obligatorio.");
                }
        }

        private String normalizarFecha(String fecha) {

                validarTexto(fecha, "La fecha");

                try {
                        return LocalDate.parse(fecha.trim()).toString();

                } catch (DateTimeParseException ex) {
                        throw new IllegalArgumentException(
                                        "La fecha debe tener el formato yyyy-MM-dd.");
                }
        }

        private LocalTime convertirHora(String hora) {

                validarTexto(hora, "La hora");

                try {
                        return LocalTime.parse(hora.trim());

                } catch (DateTimeParseException ex) {
                        throw new IllegalArgumentException(
                                        "La hora debe tener un formato válido, por ejemplo 09:30.");
                }
        }

        private String normalizarHora(String hora) {

                LocalTime valor = convertirHora(hora);

                if (valor.getSecond() != 0 || valor.getNano() != 0) {
                        throw new IllegalArgumentException(
                                        "Las citas deben programarse con precisión de minutos.");
                }

                return valor.format(FORMATO_HORA);
        }

        private void validarFechaHoraFutura(
                        String fecha,
                        String hora) {

                LocalDateTime fechaHora = LocalDateTime.of(
                                LocalDate.parse(normalizarFecha(fecha)),
                                convertirHora(hora));

                if (!fechaHora.isAfter(
                                LocalDateTime.now(ZONA_COLOMBIA))) {

                        throw new IllegalArgumentException(
                                        "La fecha y hora de la cita deben ser futuras.");
                }
        }

        private LocalDateTime obtenerFechaHora(Cita cita) {

                if (cita == null) {
                        return null;
                }

                try {
                        return LocalDateTime.of(
                                        LocalDate.parse(cita.getFecha()),
                                        LocalTime.parse(cita.getHora()));

                } catch (RuntimeException ex) {
                        return null;
                }
        }

        private LocalTime obtenerHora(Cita cita) {

                if (cita == null || cita.getHora() == null) {
                        return null;
                }

                try {
                        return LocalTime.parse(cita.getHora());

                } catch (RuntimeException ex) {
                        return null;
                }
        }

        private String normalizarEstado(String estado) {

                if (estado == null) {
                        return "";
                }

                return estado.trim().toUpperCase(Locale.ROOT);
        }
}
