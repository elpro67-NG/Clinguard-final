
package com.mycompany.clinica.controller;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class PageController {

    private final MongoTemplate mongoTemplate;

    public PageController(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    // =========================================
    // CONVERSIONES MÉDICAS
    // =========================================
    @GetMapping("/conversiones")
    public String conversiones(Model model) {
        List<Map<String, String>> opciones = new ArrayList<>();
        Query query = new Query().with(Sort.by(Sort.Order.asc("apellidos"), Sort.Order.asc("nombres")));
        List<Document> pacientes = mongoTemplate.find(query, Document.class, "pacientes");

        for (Document p : pacientes) {
            Map<String, String> fila = new LinkedHashMap<>();
            fila.put("id", String.valueOf(p.get("_id")));
            fila.put("nombre", (String.valueOf(p.getOrDefault("nombres", "")) + " " + String.valueOf(p.getOrDefault("apellidos", ""))).trim());
            fila.put("documento", String.valueOf(p.getOrDefault("documento", "")));
            Object peso = p.get("peso");
            fila.put("pesoKg", peso == null ? "" : String.valueOf(peso));
            opciones.add(fila);
        }
        model.addAttribute("pacientesConversion", opciones);

        // Medicamentos con rango de dosis
        List<Map<String, String>> meds = new ArrayList<>();
        List<Document> medicamentos = mongoTemplate.find(new Query().with(Sort.by(Sort.Order.asc("nombre"))), Document.class, "medicamentos");

        for (Document m : medicamentos) {
            // Mostrar TODOS los medicamentos registrados en MongoDB.
            // El rango mg/kg es opcional: no debe ocultar un medicamento del conversor.
            Object dosMin = m.get("dosisMinimaMgKg");
            Object dosMax = m.get("dosisMaximaMgKg");

            Map<String, String> med = new LinkedHashMap<>();
            med.put("id", String.valueOf(m.get("_id")));
            med.put("nombre", String.valueOf(m.getOrDefault("nombre", "Sin nombre")));
            med.put("min", dosMin == null ? "" : String.valueOf(dosMin));
            med.put("max", dosMax == null ? "" : String.valueOf(dosMax));
            med.put("presentacion", String.valueOf(m.getOrDefault("presentacion", "")));
            med.put("principioActivo", String.valueOf(m.getOrDefault("principioActivo", "")));
            meds.add(med);
        }
        model.addAttribute("medicamentosConversion", meds);
        return "conversiones";
    }

    // Lectura en vivo del mismo inventario usado por /medicamentos.
    // Solo consulta; no crea ni modifica conversiones.
    @GetMapping("/conversiones/medicamentos-disponibles")
    @ResponseBody
    public Map<String, Object> medicamentosDisponibles() {
        List<Map<String, String>> items = new ArrayList<>();
        List<Document> docs = mongoTemplate.find(
            new Query().with(Sort.by(Sort.Order.asc("nombre"))),
            Document.class, "medicamentos");
        for (Document m : docs) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("id", String.valueOf(m.get("_id")));
            item.put("nombre", String.valueOf(m.getOrDefault("nombre", "Sin nombre")));
            item.put("presentacion", m.get("presentacion") == null ? "" : String.valueOf(m.get("presentacion")));
            item.put("min", m.get("dosisMinimaMgKg") == null ? "" : String.valueOf(m.get("dosisMinimaMgKg")));
            item.put("max", m.get("dosisMaximaMgKg") == null ? "" : String.valueOf(m.get("dosisMaximaMgKg")));
            items.add(item);
        }
        Map<String, Object> respuesta = new LinkedHashMap<>();
        respuesta.put("total", items.size());
        respuesta.put("medicamentos", items);
        return respuesta;
    }

    // =========================================
    // ANÁLISIS
    // =========================================
    @GetMapping("/analisis")
    public String analisis(Model model) {
        // 1. Totales Básicos
        model.addAttribute("totalPacientes", mongoTemplate.count(new Query(), "pacientes"));
        model.addAttribute("totalCitas", mongoTemplate.count(new Query(), "citas"));
        model.addAttribute("totalHistoriales", mongoTemplate.count(new Query(), "historiales"));
        model.addAttribute("totalMedicamentos", mongoTemplate.count(new Query(), "medicamentos"));

        // 2. Datos para gráficos existentes
        List<Document> citas = mongoTemplate.find(new Query(), Document.class, "citas");
        List<Document> historiales = mongoTemplate.find(new Query(), Document.class, "historiales");
        List<Document> pacientes = mongoTemplate.find(new Query(), Document.class, "pacientes");

        Map<String, Long> porEstado = new LinkedHashMap<>();
        long programadas = 0, confirmadas = 0, atendidas = 0, canceladas = 0;

        for (Document cita : citas) {
            String estado = String.valueOf(cita.getOrDefault("estado", "SIN ESTADO")).trim().toUpperCase(Locale.ROOT);
            if (estado.isEmpty()) estado = "SIN ESTADO";
            porEstado.merge(estado, 1L, Long::sum);

            if (estado.contains("PROGRAM") || estado.contains("PENDIENT")) programadas++;
            if (estado.contains("CONFIRM")) confirmadas++;
            if (estado.contains("ATEND") || estado.contains("COMPLET") || estado.contains("FINALIZ")) atendidas++;
            if (estado.contains("CANCEL")) canceladas++;
        }
        model.addAttribute("citasPorEstado", porEstado);
        model.addAttribute("citasProgramadas", programadas);
        model.addAttribute("citasConfirmadas", confirmadas);
        model.addAttribute("citasAtendidas", atendidas);
        model.addAttribute("citasCanceladas", canceladas);

        // Actividad últimos 7 días
        LocalDate hoy = LocalDate.now(ZoneId.of("America/Bogota"));
        List<String> dias = new ArrayList<>();
        List<Long> citasSemana = new ArrayList<>();
        List<Long> historialesSemana = new ArrayList<>();

        for (int i = 6; i >= 0; i--) {
            LocalDate fecha = hoy.minusDays(i);
            dias.add(fecha.toString());
            citasSemana.add(contarEnFecha(citas, "fecha", fecha));
            historialesSemana.add(contarEnFecha(historiales, "fecha", fecha));
        }
        model.addAttribute("actividadDias", dias);
        model.addAttribute("actividadCitas", citasSemana);
        model.addAttribute("actividadHistoriales", historialesSemana);

        // Actividad últimos 6 meses
        List<String> meses = new ArrayList<>();
        List<Long> pacientesPorMes = new ArrayList<>();
        List<Long> historialesPorMes = new ArrayList<>();

        for (int i = 5; i >= 0; i--) {
            YearMonth mes = YearMonth.from(hoy.minusMonths(i));
            meses.add(mes.toString());
            pacientesPorMes.add(contarEnMes(pacientes, "fechaRegistro", mes));
            historialesPorMes.add(contarEnMes(historiales, "fecha", mes));
        }
        model.addAttribute("meses", meses);
        model.addAttribute("pacientesPorMes", pacientesPorMes);
        model.addAttribute("historialesPorMes", historialesPorMes);

        // Auditoría de las comparaciones matemáticas de dosis.
        // Solo se consideran eventos de validación; no conversiones guardadas.
        Criteria validacion = new Criteria().orOperator(
            Criteria.where("tipoEvento").is("VALIDACION_DOSIS"),
            Criteria.where("tipoEvento").exists(false)
                .and("estado").in("DENTRO_DEL_RANGO_CONFIGURADO",
                                  "POR_DEBAJO_DEL_RANGO", "POR_ENCIMA_DEL_RANGO")
        );
        long totalValidaciones = mongoTemplate.count(
            Query.query(validacion), "historial_conversiones");
        long totalSeguras = mongoTemplate.count(
            Query.query(new Criteria().andOperator(validacion,
                Criteria.where("estado").is("DENTRO_DEL_RANGO_CONFIGURADO"))),
            "historial_conversiones");
        long totalInfradosis = mongoTemplate.count(
            Query.query(new Criteria().andOperator(validacion,
                Criteria.where("estado").is("POR_DEBAJO_DEL_RANGO"))),
            "historial_conversiones");
        long totalSobredosis = mongoTemplate.count(
            Query.query(new Criteria().andOperator(validacion,
                Criteria.where("estado").is("POR_ENCIMA_DEL_RANGO"))),
            "historial_conversiones");

        // Se mantienen estas claves para no romper la plantilla existente.
        // "totalSeguras" significa dentro del rango configurado, NO dosis segura.
        model.addAttribute("totalValidaciones", totalValidaciones);
        model.addAttribute("totalSeguras", totalSeguras);
        model.addAttribute("totalInfradosis", totalInfradosis);
        model.addAttribute("totalSobredosis", totalSobredosis);

        Query queryRecientes = Query.query(validacion)
            .with(Sort.by(Sort.Order.desc("fecha"))).limit(10);
        List<Document> validacionesRecientes = mongoTemplate.find(
            queryRecientes, Document.class, "historial_conversiones");
        List<Map<String, String>> auditoriaFormateada = new ArrayList<>();
        for (Document doc : validacionesRecientes) {
            Map<String, String> fila = new LinkedHashMap<>();
            String usuario = texto(doc.get("usuario"));
            if (usuario.isEmpty()) usuario = texto(doc.get("usuarioUsername"));
            fila.put("usuario", usuario.isEmpty() ? "Desconocido" : usuario);
            Object dosis = doc.get("dosisMg") != null
                ? doc.get("dosisMg") : doc.get("dosisRecetada");
            fila.put("dosisRecetada", dosis == null ? "Sin dato" : String.valueOf(dosis));
            String estado = texto(doc.get("estado"));
            fila.put("estado", estado.isEmpty() ? "NO_DISPONIBLE" : estado);
            fila.put("pacienteId", texto(doc.get("pacienteId")));
            fila.put("medicamentoId", texto(doc.get("medicamentoId")));
            fila.put("minimoMg", texto(doc.get("minimoMg")));
            fila.put("maximoMg", texto(doc.get("maximoMg")));
            Object fechaRegistro = doc.get("fecha");
            LocalDate fecha = leerFecha(fechaRegistro);
            fila.put("fecha", fecha != null ? fecha.toString() : "Sin fecha");
            auditoriaFormateada.add(fila);
        }
        model.addAttribute("validacionesRecientes", auditoriaFormateada);

        return "analisis";
    }

    private String texto(Object valor) {
        return valor == null ? "" : String.valueOf(valor).trim();
    }

    // =========================================
    // MÉTODOS AUXILIARES
    // =========================================
    private long contarEnFecha(List<Document> documentos, String campo, LocalDate fecha) {
        return documentos.stream().filter(d -> fecha.equals(leerFecha(d.get(campo)))).count();
    }

    private long contarEnMes(List<Document> documentos, String campo, YearMonth mes) {
        return documentos.stream().map(d -> leerFecha(d.get(campo))).filter(f -> f != null && YearMonth.from(f).equals(mes)).count();
    }

    private LocalDate leerFecha(Object valor) {
        if (valor == null) return null;
        if (valor instanceof Date) return ((Date) valor).toInstant().atZone(ZoneId.of("America/Bogota")).toLocalDate();

        String texto = valor.toString().trim();
        if (texto.length() >= 10) {
            try { return LocalDate.parse(texto.substring(0, 10)); } catch (DateTimeParseException ignorado) {}
        }
        try { return Instant.parse(texto).atZone(ZoneId.of("America/Bogota")).toLocalDate(); } catch (DateTimeParseException ignorado) { return null; }
    }

    @GetMapping("/reportes")
    public String reportes(Model model) {
        model.addAttribute("totalPacientes", mongoTemplate.count(new Query(), "pacientes"));
        model.addAttribute("totalCitas", mongoTemplate.count(new Query(), "citas"));
        model.addAttribute("totalHistoriales", mongoTemplate.count(new Query(), "historiales"));
        return "reportes";
    }
}
