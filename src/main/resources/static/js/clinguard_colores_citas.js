
/* ==========================================
   CLINGUARD - COLORES DE CITAS MÉDICAS
   ========================================== */

(function () {
  'use strict';

  const COLORS = {
    PROGRAMADA: '#12DCC5',
    CONFIRMADA: '#A78BFA',
    REPROGRAMADA: '#D946EF'
  };

  function normalize(label) {
    return String(label ?? '')
      .normalize('NFD')
      .replace(/[\u0300-\u036f]/g, '')
      .trim()
      .toUpperCase();
  }

  function recolor(chart) {
    if (!chart ||
        !['doughnut', 'pie'].includes(chart.config?.type)) {
      return false;
    }

    const labels = chart.data?.labels;

    if (!Array.isArray(labels)) {
      return false;
    }

    const mapped = labels.map(
      label => COLORS[normalize(label)]
    );

    if (!mapped.some(Boolean)) {
      return false;
    }

    let changed = false;

    for (const dataset of chart.data.datasets || []) {
      const previous = dataset.backgroundColor;

      const existing = Array.isArray(previous)
        ? previous
        : [];

      const next = mapped.map(
        (color, index) =>
          color || existing[index] || '#64748B'
      );

      if (JSON.stringify(previous) !== JSON.stringify(next)) {
        dataset.backgroundColor = next;
        changed = true;
      }
    }

    return changed;
  }

  function init() {
    const ChartJS = window.Chart;

    if (!ChartJS) return;

    if (!window.__clinguardCitasColorsRegistered) {
      ChartJS.register({
        id: 'clinguardCitasColors',

        beforeUpdate(chart) {
          recolor(chart);
        }
      });

      window.__clinguardCitasColorsRegistered = true;
    }

    // Actualizar gráficas existentes
    const instances = ChartJS.instances || {};

    for (const chart of Object.values(instances)) {
      if (recolor(chart)) {
        chart.update('none');
      }
    }
  }

  if (document.readyState === 'loading') {
    document.addEventListener(
      'DOMContentLoaded',
      init,
      { once: true }
    );
  } else {
    init();
  }
})();
