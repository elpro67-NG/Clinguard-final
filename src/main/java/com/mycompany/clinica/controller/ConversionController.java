
package com.mycompany.clinica.controller;

import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.bson.Document;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.dao.DataAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Calcular no guarda. Validar registra únicamente un evento de auditoría,
 * pero no guarda una conversión. Solo /guardar almacena conversiones.
 */
@RestController
@RequestMapping("/api/conversiones")
public class ConversionController {

    private static final Logger log = LoggerFactory.getLogger(ConversionController.class);
    private static final String COLECCION_AUDITORIA = "historial_conversiones";

    private static final Set<String> ROLES = Set.of(
        "ROLE_ADMIN", "ROLE_MEDICO", "ROLE_ENFERMERO"
    );

    private static final Set<String> OPERACIONES = Set.of(
        "g-mg", "mg-g", "ug-mg", "kg-g",
        "g-kg", "mg-kg", "ml-l", "l-ml",
        "dosis-mg-kg", "dosis-ug-kg",
        "dosis-total", "dosis-volumen"
    );

    private final MongoTemplate mongo;

    public ConversionController(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    // =========================================
    // 1. CALCULAR SIN GUARDAR
    // =========================================

    @PostMapping("/calcular")
    public ResponseEntity<?> calcular(
            @RequestBody Map<String, Object> entrada,
            Authentication auth) {

        if (!autorizado(auth)) {
            return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "No autorizado."));
        }

        try {
            return ResponseEntity.ok(calculo(entrada));

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                .body(Map.of("error", e.getMessage()));
        }
    }

    // =========================================
    // 2. VALIDAR DOSIS Y REGISTRAR EVENTO DE AUDITORIA
    // =========================================

    @PostMapping("/validar-dosis")
    public ResponseEntity<?> validar(
            @RequestBody Map<String, Object> entrada,
            Authentication auth) {

        if (!autorizado(auth)) {
            return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "No autorizado."));
        }

        try {

            String op = cadena(entrada.get("operacion"));

            if (!Set.of(
                    "g-mg", "mg-g", "ug-mg"
            ).contains(op)) {

                throw new IllegalArgumentException(
                    "Esta operación no representa una dosis total expresable en mg."
                );
            }

            double valor = numero(
                entrada.get("valor"), "Valor", false
            );

            String pacienteId = cadena(
                entrada.get("pacienteId")
            );

            String medicamentoId = cadena(
                entrada.get("medicamentoId")
            );

            if (pacienteId.isEmpty() || medicamentoId.isEmpty()) {
                throw new IllegalArgumentException(
                    "Selecciona paciente y medicamento."
                );
            }

            Document paciente = buscar(
                pacienteId, "pacientes"
            );

            Document medicamento = buscar(
                medicamentoId, "medicamentos"
            );

            if (paciente == null || medicamento == null) {
                throw new IllegalArgumentException(
                    "Paciente o medicamento no registrado en Atlas."
                );
            }

            if (!pesoVigente(paciente)) {
                throw new IllegalArgumentException(
                    "El peso del paciente no consta como verificado. "
                    + "La validación por peso está deshabilitada."
                );
            }

            double peso = numero(
                paciente.get("peso"),
                "Peso registrado",
                true
            );

            double minimo = numero(
                medicamento.get("dosisMinimaMgKg"),
                "Rango mínimo mg/kg",
                false
            );

            double maximo = numero(
                medicamento.get("dosisMaximaMgKg"),
                "Rango máximo mg/kg",
                true
            );

            if (minimo > maximo) {
                throw new IllegalArgumentException(
                    "El rango mg/kg del medicamento es inválido."
                );
            }

            double dosisMg = switch (op) {
                case "g-mg" -> valor * 1000;
                case "ug-mg" -> valor / 1000;
                default -> valor;
            };

            if (!Double.isFinite(dosisMg)
                    || !Double.isFinite(peso * maximo)) {

                throw new IllegalArgumentException(
                    "La dosis o el rango excede los valores válidos."
                );
            }

            double minimoMg = minimo * peso;
            double maximoMg = maximo * peso;
            if (!Double.isFinite(minimoMg) || !Double.isFinite(maximoMg)) {
                throw new IllegalArgumentException("Rango calculado inválido.");
            }

            String estado = dosisMg < minimoMg
                ? "POR_DEBAJO_DEL_RANGO"
                : dosisMg > maximoMg
                    ? "POR_ENCIMA_DEL_RANGO"
                    : "DENTRO_DEL_RANGO_CONFIGURADO";

            // Registrar la acción de validar por trazabilidad.
            // No se crea una conversión en conversiones_medicas.
            Document evento = new Document("tipoEvento", "VALIDACION_DOSIS")
                .append("usuario", auth.getName())
                .append("usuarioUsername", auth.getName())
                .append("pacienteId", pacienteId)
                .append("medicamentoId", medicamentoId)
                .append("operacion", op)
                .append("valorOriginal", valor)
                .append("dosisRecetada", dosisMg)
                .append("dosisMg", dosisMg)
                .append("pesoKg", peso)
                .append("dosisMinimaMgKg", minimo)
                .append("dosisMaximaMgKg", maximo)
                .append("minimoMg", minimoMg)
                .append("maximoMg", maximoMg)
                .append("estado", estado)
                .append("fecha", Date.from(Instant.now()))
                .append("interpretacion", "COMPARACION_MATEMATICA_NO_CLINICA");

            mongo.insert(evento, COLECCION_AUDITORIA);
            log.info("Auditoría de dosis guardada id={}, estado={}",
                     evento.get("_id"), estado);

            return ResponseEntity.ok(Map.of(
                "estado", estado,
                "mensaje",
                "Comparación matemática con el rango registrado: "
                    + estado
                    + ". No establece seguridad clínica ni sustituye "
                    + "la revisión profesional.",
                "dosisMg", dosisMg,
                "minimoMg", minimoMg,
                "maximoMg", maximoMg
            ));

        } catch (IllegalArgumentException e) {

            return ResponseEntity.badRequest()
                .body(Map.of("error", e.getMessage()));
        } catch (DataAccessException e) {
            log.error("No se pudo persistir la auditoría de dosis", e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "La comparación se calculó, pero no se pudo registrar la auditoría. No se guardó ninguna conversión. Revisa MongoDB Atlas."));
        }
    }

    // =========================================
    // 3. GUARDAR CONVERSIÓN EN ATLAS
    // =========================================

    @PostMapping("/guardar")
    public ResponseEntity<?> guardar(
            @RequestBody Map<String, Object> entrada,
            Authentication auth) {

        if (!autorizado(auth)) {
            return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "No autorizado."));
        }

        try {

            Map<String, Object> calculado = calculo(entrada);

            String pacienteId = cadena(
                entrada.get("pacienteId")
            );

            String medicamentoId = cadena(
                entrada.get("medicamentoId")
            );

            if (!pacienteId.isEmpty()
                    && buscar(pacienteId, "pacientes") == null) {

                throw new IllegalArgumentException(
                    "Paciente no encontrado."
                );
            }

            if (!medicamentoId.isEmpty()
                    && buscar(medicamentoId, "medicamentos") == null) {

                throw new IllegalArgumentException(
                    "Medicamento no encontrado."
                );
            }

            Date fecha = Date.from(Instant.now());

            Document registro = new Document(
                "usuarioUsername", auth.getName()
            )
                .append(
                    "operacion",
                    cadena(entrada.get("operacion"))
                )
                .append(
                    "valor",
                    numero(entrada.get("valor"), "Valor", false)
                )
                .append(
                    "resultado",
                    calculado.get("resultado")
                )
                .append(
                    "unidad",
                    calculado.get("unidad")
                )
                .append(
                    "pacienteId",
                    pacienteId.isEmpty() ? null : pacienteId
                )
                .append(
                    "medicamentoId",
                    medicamentoId.isEmpty() ? null : medicamentoId
                )
                .append("fecha", fecha)
                .append(
                    "validacionClinica",
                    "NO_REALIZADA"
                );

            mongo.insert(
                registro,
                "conversiones_medicas"
            );

            return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(Map.of(
                    "id",
                    String.valueOf(registro.get("_id")),
                    "fecha",
                    fecha.toInstant().toString(),
                    "resultado",
                    calculado.get("resultado"),
                    "unidad",
                    calculado.get("unidad")
                ));

        } catch (IllegalArgumentException e) {

            return ResponseEntity.badRequest()
                .body(Map.of("error", e.getMessage()));
        }
    }

    // =========================================
    // MOTOR DE CONVERSIONES
    // =========================================

    private Map<String, Object> calculo(
            Map<String, Object> e) {

        if (e == null) {
            throw new IllegalArgumentException(
                "Faltan los datos del cálculo."
            );
        }

        String op = cadena(e.get("operacion"));

        if (!OPERACIONES.contains(op)) {
            throw new IllegalArgumentException(
                "Operación desconocida."
            );
        }

        double valor = numero(
            e.get("valor"), "Valor", false
        );

        double resultado;
        String unidad;

        switch (op) {

            case "g-mg" -> {
                resultado = valor * 1000;
                unidad = "mg";
            }

            case "mg-g" -> {
                resultado = valor / 1000;
                unidad = "g";
            }

            case "ug-mg" -> {
                resultado = valor / 1000;
                unidad = "mg";
            }

            case "kg-g" -> {
                resultado = valor * 1000;
                unidad = "g";
            }

            case "g-kg" -> {
                resultado = valor / 1000;
                unidad = "kg";
            }

            case "mg-kg" -> {
                resultado = valor / 1_000_000;
                unidad = "kg";
            }

            case "ml-l" -> {
                resultado = valor / 1000;
                unidad = "L";
            }

            case "l-ml" -> {
                resultado = valor * 1000;
                unidad = "mL";
            }

            case "dosis-volumen" -> {

                double dosis = numero(
                    e.get("dosisDisponible"),
                    "Dosis disponible",
                    true
                );

                double volumen = numero(
                    e.get("volumenDisponible"),
                    "Volumen disponible",
                    true
                );

                resultado = valor / dosis * volumen;
                unidad = "mL";
            }

            case "dosis-mg-kg",
                 "dosis-ug-kg",
                 "dosis-total" -> {

                String pacienteId = cadena(
                    e.get("pacienteId")
                );

                Document paciente = buscar(
                    pacienteId, "pacientes"
                );

                if (paciente == null || !pesoVigente(paciente)) {

                    throw new IllegalArgumentException(
                        "El cálculo por peso requiere un paciente "
                        + "con peso verificado en el expediente."
                    );
                }

                double peso = numero(
                    paciente.get("peso"),
                    "Peso verificado",
                    true
                );

                if (op.equals("dosis-total")) {
                    resultado = valor * peso;
                    unidad = "mg";
                } else {
                    resultado = valor / peso;

                    unidad = op.equals("dosis-mg-kg")
                        ? "mg/kg" : "µg/kg";
                }
            }

            default -> throw new IllegalArgumentException(
                "Operación desconocida."
            );
        }

        if (!Double.isFinite(resultado)) {
            throw new IllegalArgumentException(
                "Resultado numérico inválido."
            );
        }

        Map<String, Object> datos = new LinkedHashMap<>();

        datos.put("resultado", resultado);
        datos.put("unidad", unidad);

        return datos;
    }

    // =========================================
    // COMPROBAR PESO VERIFICADO Y VIGENTE
    // =========================================

    private boolean pesoVigente(Document paciente) {

        if (paciente == null
                || !Boolean.TRUE.equals(
                    paciente.get("pesoVerificado"))) {
            return false;
        }

        if (!(paciente.get("fechaVerificacionPeso") instanceof Date)) {
            return false;
        }

        if (cadena(
                paciente.get("profesionalVerificadorPeso")
        ).isBlank()) {
            return false;
        }

        Object fecha = paciente.get("fechaMedicionPeso");

        if (fecha == null) {
            return false;
        }

        try {

            java.time.LocalDate medicion;

            if (fecha instanceof Date d) {

                medicion = d.toInstant()
                    .atZone(java.time.ZoneOffset.UTC)
                    .toLocalDate();

            } else if (fecha instanceof java.time.LocalDate local) {

                medicion = local;

            } else {

                medicion = java.time.LocalDate.parse(
                    String.valueOf(fecha).substring(0, 10)
                );
            }

            java.time.LocalDate hoy =
                java.time.LocalDate.now(
                    java.time.ZoneId.of("America/Bogota")
                );

            return !medicion.isAfter(hoy)
                && !medicion.isBefore(hoy.minusDays(30));

        } catch (RuntimeException ex) {
            return false;
        }
    }

    // =========================================
    // CONSULTAR DOCUMENTOS EN MONGODB
    // =========================================

    private Document buscar(
            String id,
            String coleccion) {

        if (id == null || id.isBlank()) {
            return null;
        }

        if (ObjectId.isValid(id)) {

            Document documento = mongo.findOne(
                Query.query(
                    Criteria.where("_id")
                        .is(new ObjectId(id))
                ),
                Document.class,
                coleccion
            );

            if (documento != null) {
                return documento;
            }
        }

        return mongo.findOne(
            Query.query(
                Criteria.where("_id").is(id)
            ),
            Document.class,
            coleccion
        );
    }

    // =========================================
    // VALIDAR VALORES NUMÉRICOS
    // =========================================

    private double numero(
            Object valor,
            String nombre,
            boolean positivo) {

        if (valor == null || cadena(valor).isBlank()) {
            throw new IllegalArgumentException(
                "Falta " + nombre + "."
            );
        }

        try {

            double numero = valor instanceof Decimal128 d
                ? d.bigDecimalValue().doubleValue()
                : valor instanceof Number n
                    ? n.doubleValue()
                    : Double.parseDouble(cadena(valor));

            if (!Double.isFinite(numero)
                    || numero < 0
                    || (positivo && numero <= 0)) {

                throw new NumberFormatException();
            }

            return numero;

        } catch (NumberFormatException ex) {

            throw new IllegalArgumentException(
                "Valor inválido para " + nombre + "."
            );
        }
    }

    // =========================================
    // NORMALIZAR TEXTO
    // =========================================

    private String cadena(Object valor) {
        return valor == null
            ? ""
            : String.valueOf(valor).trim();
    }

    // =========================================
    // COMPROBAR PERMISOS
    // =========================================

    private boolean autorizado(Authentication auth) {

        return auth != null
            && auth.isAuthenticated()
            && !(auth instanceof AnonymousAuthenticationToken)
            && auth.getAuthorities().stream()
                .anyMatch(a ->
                    ROLES.contains(a.getAuthority())
                );
    }
}
