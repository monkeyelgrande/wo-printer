"""
Generador de archivos Excel de prueba para wo-printer.
Imita el formato de exportación de WorldOffice (ExportarAExcelSQL).

Uso: python _generar.py
Salida: 5 archivos .xlsx en esta misma carpeta.
"""
from openpyxl import Workbook
from datetime import datetime
from pathlib import Path

HEADERS = [
    "Documento", "Prefijo", "DocumentoNumero", "Fecha", "Empresa",
    "Vendedor", "Cliente", "Sucursal", "Direccion", "Contacto",
    "Concepto", "Clasif", "Telefono", "Ciudad", "Forma_Pago",
    "Moneda", "Anulado", "Verificado", "Exportacion",
    "Inventario", "Bodega", "Medida", "Cantidad", "Iva",
    "Monto_Monetario_U", "ImpoConsumo", "PorcImpoConsumo", "Total"
]

# Productos reales por bodega (validados contra la BD)
BOD_PRINCIPAL = [   # id=2
    ("A14661", 'BOLA ACERO FORJADA 3 1/2" X KL', 5.0),
    ("A13410", 'BOLA ACERO FORJADA 1"',         3.0),
    ("A13775", 'BOLA ACERO FORJADA 2"',         7.0),
]
BOD_PISO1 = [        # id=3
    ("8426",   'TUERCA 1/4"',                   50.0),
    ("5913",   'TUERCA 9/16"',                  20.0),
    ("3489",   'CANCAMO OJO CERRADO #  6',      10.0),
]
BOD_PISO2 = [        # id=4
    ("A4060", 'GUANTE NITRILO NYLON TEXTURADO STEELPRO', 12.0),
    ("A4155", 'POLIESTER 1/8 #3 X METROS',               25.0),
    ("A4140", 'LASO ALGODON 1/4 LHAURA #6 X MTS',         8.0),
]
# Códigos no existentes en la BD
FAKE = [
    ("ZZZ99999",     "PRODUCTO FAKE PARA PRUEBA",  4.0),
    ("INEXISTENTE1", "OTRO CODIGO INEXISTENTE",    2.0),
    ("FAKE123",      "UN PRODUCTO MAS QUE NO EXISTE", 1.0),
]


def escribir_factura(ws, numero, titulo, items):
    """Escribe la cabecera + una fila por ítem, replicando el layout de WorldOffice."""
    ws.append(HEADERS)
    base = dict(
        Documento="FV",
        Prefijo="FVE",
        DocumentoNumero=numero,
        Fecha=datetime(2026, 4, 23),
        Empresa="AGROINSUMOS LA SERRANIA SAS ZOMAC",
        Vendedor="VENDEDOR PRUEBA WO-PRINTER",
        Cliente="CLIENTE PRUEBA " + titulo.upper(),
        Direccion="DIRECCION PRUEBA 123",
        Concepto=f"FACTURA DE VENTA - PRUEBA {titulo} *{900000 + numero}",
        Telefono="3001234567",
        Ciudad="Santa Rosa Del Sur",
        Forma_Pago="Credito",
        Moneda=0, Anulado=0, Verificado=0, Exportacion=0,
    )
    for codigo, desc, cant in items:
        row = [
            base["Documento"], base["Prefijo"], base["DocumentoNumero"],
            base["Fecha"], base["Empresa"], base["Vendedor"], base["Cliente"],
            None,                     # Sucursal
            base["Direccion"],
            None,                     # Contacto
            base["Concepto"],
            None,                     # Clasif
            base["Telefono"], base["Ciudad"], base["Forma_Pago"],
            base["Moneda"], base["Anulado"], base["Verificado"], base["Exportacion"],
            f"{codigo} {desc}",       # T: Inventario (codigo + desc)
            "Principal",              # U: Bodega (ignorado por nuestro sistema)
            "Unidad",                 # V: Medida
            cant,                     # W: Cantidad
            0.19,                     # X: Iva
            1000.0,                   # Y: Monto_Monetario_U (irrelevante para orden)
            0, 0,                     # Z, AA
            cant * 1000.0 * 1.19,     # AB: Total
        ]
        ws.append(row)


def crear(nombre, numero, titulo, items):
    wb = Workbook()
    ws = wb.active
    ws.title = "ExportarAExcelSQL"
    escribir_factura(ws, numero, titulo, items)
    out = Path(__file__).parent / nombre
    wb.save(out)
    print(f"  {out.name}  (factura FVE-{numero}, {len(items)} items)")


if __name__ == "__main__":
    print("Generando archivos de prueba...")

    # Caso 1: todo de UNA bodega (PRINCIPAL)
    crear("test_1_una_bodega.xlsx", 90001, "UNA_BODEGA", BOD_PRINCIPAL)

    # Caso 2: DOS bodegas (PRINCIPAL + PISO1)
    crear("test_2_dos_bodegas.xlsx", 90002, "DOS_BODEGAS",
          BOD_PRINCIPAL[:2] + BOD_PISO1[:2])

    # Caso 3: TRES bodegas (PRINCIPAL + PISO1 + PISO2)
    crear("test_3_tres_bodegas.xlsx", 90003, "TRES_BODEGAS",
          BOD_PRINCIPAL[:1] + BOD_PISO1[:1] + BOD_PISO2[:1])

    # Caso 4: DOS bodegas + productos que NO existen
    crear("test_4_dos_bodegas_y_novedades.xlsx", 90004, "CON_NOVEDADES",
          BOD_PISO1[:2] + BOD_PISO2[:1] + FAKE[:2])

    # Caso 5: NINGÚN producto válido (solo inexistentes)
    crear("test_5_sin_productos_validos.xlsx", 90005, "TODO_NOVEDAD", FAKE)

    print("Listo.")
