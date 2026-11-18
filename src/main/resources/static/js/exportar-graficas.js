
/*
 * CLINGUARD - EXPORTACIÓN DE GRÁFICAS
 * CSV (Excel), PNG y PDF
 */

(() => {
    "use strict";

    // ========================================
    // DESCARGAR ARCHIVOS
    // ========================================

    function descargarArchivo(blob, nombre) {
        const url = URL.createObjectURL(blob);
        const enlace = document.createElement("a");

        enlace.href = url;
        enlace.download = nombre;

        document.body.appendChild(enlace);
        enlace.click();
        enlace.remove();

        setTimeout(() => {
            URL.revokeObjectURL(url);
        }, 1500);
    }

    // ========================================
    // NOMBRE DEL ARCHIVO
    // ========================================

    function nombreArchivo(id, extension) {
        const fecha = new Date()
            .toISOString()
            .slice(0, 10);

        return `clinguard_${id}_${fecha}.${extension}`;
    }

    // ========================================
    // OBTENER GRÁFICA
    // ========================================

    function obtenerGrafica(id) {
        const canvas = document.getElementById(id);

        if (!canvas || typeof Chart === "undefined") {
            return null;
        }

        return Chart.getChart(canvas) || null;
    }

    // ========================================
    // VERIFICAR DATOS
    // ========================================

    function tieneDatos(grafica) {
        if (!grafica) return false;

        const series = grafica.data.datasets || [];

        return series.some(serie =>
            Array.isArray(serie.data) &&
            serie.data.some(valor =>
                valor !== null &&
                valor !== undefined &&
                valor !== ""
            )
        );
    }

    // ========================================
    // EXTRAER DATOS
    // ========================================

    function obtenerDatos(grafica) {
        const etiquetas = grafica.data.labels || [];
        const series = grafica.data.datasets || [];

        const columnas = [
            "Período / Categoría",
            ...series.map((serie, index) =>
                serie.label || `Serie ${index + 1}`
            )
        ];

        const filas = etiquetas.map((etiqueta, index) => {
            return [
                Array.isArray(etiqueta)
                    ? etiqueta.join(" ")
                    : etiqueta,

                ...series.map(serie => {
                    const punto = serie.data?.[index];

                    if (
                        punto !== null &&
                        typeof punto === "object"
                    ) {
                        return punto.y ?? punto.value ?? "";
                    }

                    return punto ?? "";
                })
            ];
        });

        return { columnas, filas };
    }

    // ========================================
    // SEGURIDAD PARA CSV
    // ========================================

    function campoCSV(valor) {
        let texto = String(valor ?? "");

        if (/^\s*[=+@-]/.test(texto)) {
            texto = "'" + texto;
        }

        return '"' +
            texto.replace(/"/g, '""') +
            '"';
    }

    // ========================================
    // EXPORTAR A EXCEL (CSV)
    // ========================================

    function exportarExcel(grafica, id) {
        const { columnas, filas } = obtenerDatos(grafica);

        const contenido = [columnas, ...filas]
            .map(fila =>
                fila.map(campoCSV).join(";")
            )
            .join("\r\n");

        const blob = new Blob(
            ["\ufeff", contenido],
            {
                type: "text/csv;charset=utf-8"
            }
        );

        descargarArchivo(
            blob,
            nombreArchivo(id, "csv")
        );
    }

    // ========================================
    // EXPORTAR IMAGEN PNG
    // ========================================

    function exportarPNG(grafica, id) {
        grafica.canvas.toBlob(blob => {
            if (!blob) {
                alert("No se pudo generar la imagen.");
                return;
            }

            descargarArchivo(
                blob,
                nombreArchivo(id, "png")
            );
        }, "image/png");
    }

    // ========================================
    // PROTEGER CONTENIDO HTML
    // ========================================

    function escaparHTML(valor) {
        return String(valor ?? "")
            .replace(/[&<>"']/g, caracter => ({
                "&": "&amp;",
                "<": "&lt;",
                ">": "&gt;",
                '"': "&quot;",
                "'": "&#39;"
            })[caracter]);
    }

    // ========================================
    // EXPORTAR PDF
    // ========================================

    function exportarPDF(grafica, titulo) {
        const ventana = window.open("", "_blank");

        if (!ventana) {
            alert(
                "Permite las ventanas emergentes para exportar a PDF."
            );
            return;
        }

        const imagen = grafica.toBase64Image();
        const { columnas, filas } = obtenerDatos(grafica);

        const encabezados = columnas
            .map(columna =>
                `<th>${escaparHTML(columna)}</th>`
            )
            .join("");

        const cuerpo = filas
            .map(fila => `
                <tr>
                    ${fila.map(valor =>
                        `<td>${escaparHTML(valor)}</td>`
                    ).join("")}
                </tr>
            `)
            .join("");

        const fecha = new Date()
            .toLocaleString("es-CO");

        const contenido = `
            <!DOCTYPE html>
            <html lang="es">
            <head>
                <meta charset="UTF-8">
                <title>
                    ${escaparHTML(titulo)} - ClinGuard
                </title>
                <link
                    rel="stylesheet"
                    href="/css/styles.css"
                >
            </head>

            <body class="reporte-grafica-impresion">

                <main class="reporte-grafica-documento">

                    <h1>
                        ClinGuard - ${escaparHTML(titulo)}
                    </h1>

                    <p>
                        Generado el ${escaparHTML(fecha)}
                    </p>

                    <img
                        class="reporte-grafica-imagen"
                        src="${imagen}"
                        alt="Gráfica de ${escaparHTML(titulo)}"
                    >

                    <h2>Datos de la gráfica</h2>

                    <table class="reporte-grafica-tabla">
                        <thead>
                            <tr>${encabezados}</tr>
                        </thead>
                        <tbody>
                            ${cuerpo}
                        </tbody>
                    </table>

                    <p class="reporte-grafica-instruccion">
                        Selecciona Guardar como PDF
                        en la ventana de impresión.
                    </p>

                </main>

                <script>
                    window.addEventListener(
                        "load",
                        () => {
                            setTimeout(
                                () => window.print(),
                                350
                            );
                        }
                    );
                <\/script>

            </body>
            </html>
        `;

        ventana.document.open();
        ventana.document.write(contenido);
        ventana.document.close();
    }

    // ========================================
    // CONFIGURAR BOTONES
    // ========================================

    function configurarExportacion() {
        const grupos = document.querySelectorAll(
            ".grafica-exportacion"
        );

        grupos.forEach(grupo => {
            const id = grupo.dataset.grafica;
            const titulo = grupo.dataset.titulo || id;

            grupo.addEventListener("click", evento => {
                const boton = evento.target.closest(
                    "button[data-formato]"
                );

                if (!boton || !grupo.contains(boton)) {
                    return;
                }

                const grafica = obtenerGrafica(id);

                if (!tieneDatos(grafica)) {
                    alert(
                        "Esta gráfica todavía no tiene datos disponibles."
                    );
                    return;
                }

                const formato = boton.dataset.formato;

                switch (formato) {
                    case "csv":
                        exportarExcel(grafica, id);
                        break;

                    case "png":
                        exportarPNG(grafica, id);
                        break;

                    case "pdf":
                        exportarPDF(grafica, titulo);
                        break;

                    default:
                        console.warn(
                            "Formato no reconocido:",
                            formato
                        );
                }
            });
        });
    }

    // ========================================
    // INICIALIZACIÓN
    // ========================================

    if (document.readyState === "loading") {
        document.addEventListener(
            "DOMContentLoaded",
            configurarExportacion
        );
    } else {
        configurarExportacion();
    }

})();
