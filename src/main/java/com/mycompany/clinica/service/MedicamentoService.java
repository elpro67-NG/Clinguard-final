package com.mycompany.clinica.service;

import com.mycompany.clinica.entity.Medicamento;
import com.mycompany.clinica.repository.MedicamentoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class MedicamentoService {

    private static final Logger log = LoggerFactory.getLogger(MedicamentoService.class);

    private final MedicamentoRepository medicamentoRepository;
    private final NotificacionService notificacionService;

    public MedicamentoService(MedicamentoRepository medicamentoRepository,
            NotificacionService notificacionService) {
        this.medicamentoRepository = medicamentoRepository;
        this.notificacionService = notificacionService;
    }

    public List<Medicamento> listarTodos() {
        return medicamentoRepository.findAll();
    }

    public Medicamento guardar(Medicamento medicamento) {
        if (medicamento == null) {
            throw new IllegalArgumentException("Debes ingresar los datos del medicamento.");
        }
        if (medicamento.getId() != null && !medicamento.getId().isBlank()) {
            return actualizar(medicamento.getId(), medicamento);
        }
        validarRangosDosis(medicamento);
        validarStock(medicamento.getStock(), false);

        medicamento.setId(null);
        if (medicamento.getEstado() == null || medicamento.getEstado().isBlank()) {
            medicamento.setEstado("ACTIVO");
        }
        if (medicamento.getStock() == null) {
            medicamento.setStock(0);
        }

        Medicamento guardado = medicamentoRepository.save(medicamento);
        log.info("Medicamento creado: id={}, rangosMgKg=[{}, {}]",
                guardado.getId(), guardado.getDosisMinimaMgKg(), guardado.getDosisMaximaMgKg());
        enviarAlertaStockBajo(guardado, null);
        return guardado;
    }

    public Optional<Medicamento> buscarPorId(String id) {
        return medicamentoRepository.findById(id);
    }

    public List<Medicamento> buscarPorNombre(String nombre) {
        return medicamentoRepository.findByNombreContainingIgnoreCase(nombre);
    }

    public List<Medicamento> buscarPorPrincipioActivo(String principioActivo) {
        return medicamentoRepository.findByPrincipioActivoContainingIgnoreCase(principioActivo);
    }

    public List<Medicamento> listarActivos() {
        return medicamentoRepository.findByEstado("ACTIVO");
    }

    public Medicamento actualizar(String id, Medicamento datos) {
        if (datos == null) {
            throw new IllegalArgumentException("Debes ingresar los datos del medicamento.");
        }
        Medicamento medicamento = medicamentoRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Medicamento no encontrado."));
        validarRangosDosis(datos);
        validarStock(datos.getStock(), true);

        Integer stockAnterior = medicamento.getStock();
        medicamento.setNombre(datos.getNombre());
        medicamento.setPrincipioActivo(datos.getPrincipioActivo());
        medicamento.setPresentacion(datos.getPresentacion());
        medicamento.setCantidadMedicamento(datos.getCantidadMedicamento());
        medicamento.setUnidadMedicamento(datos.getUnidadMedicamento());
        medicamento.setCantidadVehiculo(datos.getCantidadVehiculo());
        medicamento.setUnidadVehiculo(datos.getUnidadVehiculo());
        medicamento.setViaAdministracion(datos.getViaAdministracion());
        medicamento.setStock(datos.getStock());
        medicamento.setEstado(datos.getEstado());
        medicamento.setDosisMinimaMgKg(datos.getDosisMinimaMgKg());
        medicamento.setDosisMaximaMgKg(datos.getDosisMaximaMgKg());
        medicamento.setDescripcion(datos.getDescripcion() != null
                ? datos.getDescripcion()
                : medicamento.getDescripcion());

        Medicamento guardado = medicamentoRepository.save(medicamento);
        log.info("Medicamento actualizado: id={}, rangosMgKg=[{}, {}]",
                guardado.getId(), guardado.getDosisMinimaMgKg(), guardado.getDosisMaximaMgKg());
        enviarAlertaStockBajo(guardado, stockAnterior);
        return guardado;
    }

    private void validarRangosDosis(Medicamento medicamento) {
        Double minimo = medicamento.getDosisMinimaMgKg();
        Double maximo = medicamento.getDosisMaximaMgKg();
        if (minimo == null && maximo == null) {
            return;
        }
        if (minimo == null || maximo == null) {
            throw new IllegalArgumentException("Completa ambos rangos mg/kg o deja ambos vacíos.");
        }
        if (!Double.isFinite(minimo) || !Double.isFinite(maximo)
                || minimo < 0 || maximo <= 0 || minimo > maximo) {
            throw new IllegalArgumentException("El rango mg/kg es inválido: revisa mínimo y máximo.");
        }
    }

    private void validarStock(Integer stock, boolean obligatorio) {
        if ((obligatorio && stock == null) || (stock != null && stock < 0)) {
            throw new IllegalArgumentException("El stock debe ser un número igual o mayor que cero.");
        }
    }

    private void enviarAlertaStockBajo(Medicamento medicamento, Integer stockAnterior) {
        if (medicamento == null || medicamento.getStock() == null) {
            return;
        }
        try {
            notificacionService.notificarStockBajo(
                    medicamento.getId(), medicamento.getNombre(),
                    stockAnterior, medicamento.getStock());
        } catch (RuntimeException e) {
            log.error("No fue posible notificar stock bajo del medicamento {}",
                    medicamento.getId(), e);
        }
    }

    public void eliminar(String id) {
        medicamentoRepository.deleteById(id);
    }
}
