# Ideas para lo que sigue — herramientas nuevas para la suite

**Fecha:** 12 de octubre de 2026 · **Versión base:** 0.2.0 (76 herramientas, 12 módulos).

**Cómo leer esta lista:** cada idea trae el formato de siempre — qué hace la herramienta típica del mercado y qué haría la nuestra — más las herramientas concretas que se agregarían, el esfuerzo y para quién es. Nada de la lista necesita contratar servicios externos ni crear cuentas: todo corre local, como el resto de la suite. Marca con una "x" lo que quieras que se construya.

---

## Horizonte 1 — Lo cercano (semanas; uso diario, todo local)

### 1. Cobranza que persigue el pago

- La normal te deja la factura o el pedido ahí, esperando a que alguien se acuerde.
- La nuestra te dice quién te debe, desde cuándo, y te escribe el recordatorio.
- Herramientas: `cobranza_estado` (antigüedad de saldos: al día, 30, 60, 90+ días), `cobranza_recordatorio` (texto listo para copiar y pegar, con tono suave o firme según los días).
- Para quién: cualquiera que venda a crédito. Es la herramienta que más dinero recupera por peso invertido.
- Esfuerzo: bajo.

### 2. Ventas del día y corte de caja

- La normal te obliga a comprar un punto de venta completo.
- La nuestra registra la venta en una llamada, descuenta inventario y hace el corte.
- Herramientas: `venta_registrar` (venta rápida con o sin cliente y con forma de pago), `caja_corte` (corte del día: ventas, entradas, salidas, arqueo y diferencia).
- Para quién: tienda, panadería, taller, ferretería. El uso más repetido del día.
- Esfuerzo: medio.

### 3. Compras y reorden sugerido

- La normal controla lo que vendes; de lo que compras, ni habla.
- La nuestra también lleva compras y te dice qué pedir antes de quedarte sin stock.
- Herramientas: `compra_registrar` (entrada de mercancía con proveedor y costo), `reorden_sugerido` (qué pedir y cuánto, según ventas recientes y tiempo de entrega), `proveedor_comparar` (quién te da mejor precio por producto).
- Para quién: cualquier negocio con inventario. Cierra el ciclo: comprar, vender, reponer.
- Esfuerzo: medio.

### 4. Gastos: del estado de cuenta a "en qué se me va el dinero"

- La normal te da un CSV del banco y ahí te las arreglas.
- La nuestra lo clasifica y te dice en qué se va, por categoría y por mes.
- Herramientas: `gastos_importar` (CSV del banco, con simulacro), `gastos_resumen` (por categoría, mes y proveedor), `gastos_anomalias` (cargos repetidos o fuera de lo normal).
- Nota: se lee un archivo, no se conecta a ningún banco. Sin APIs bancarias, como acordamos.
- Esfuerzo: bajo.

### 5. Kit de arranque por giro

- La normal te deja la hoja en blanco y miles de campos por llenar.
- La nuestra te arma el negocio en un minuto según a qué te dedicas.
- Herramientas: `negocio_arranque` (elige giro: abarrotes, ferretería, panadería, taller, papelería... y crea productos típicos, categorías, plantillas y precios sugeridos), `negocio_giros` (catálogo de giros disponibles).
- Para quién: el usuario nuevo, y los despachos que dan de alta clientes todo el tiempo.
- Esfuerzo: bajo (es contenido más que código).

### 6. Alertas que te buscan a ti

- La normal espera a que tú entres a revisar.
- La nuestra te recibe con el resumen: qué se venció, qué falta, qué se rompió.
- Herramientas: `alertas_revisar` (un solo llamado que junta: stock bajo, pedidos atorados, cobranza vencida, respaldos viejos, aprobaciones pendientes), `alertas_configurar` (qué quieres vigilar y con qué umbrales).
- Esfuerzo: bajo.

### 7. Reportes imprimibles (sin servicios de PDF)

- La normal te manda a un servicio de pago para sacar un PDF.
- La nuestra genera el documento listo para imprimir o guardar como PDF desde el navegador.
- Herramientas: `reporte_imprimible` (estado de cuenta, cotización, corte de caja, orden de compra, ficha de producto: HTML limpio con formato de hoja).
- Esfuerzo: bajo.

### 8. Prueba de restauración de respaldos

- La normal respalda y reza para que el respaldo sirva.
- La nuestra restaura en una carpeta desechable y comprueba que los datos están completos.
- Herramientas: `respaldo_probar` (restaura el último respaldo, cuenta filas y compara, borra la prueba).
- Esfuerzo: bajo. Da mucha tranquilidad para el uso en serio.

### 9. El que explica: "¿cómo le hago?"

- La normal asume que ya sabes usar todo.
- La nuestra responde en español, paso a paso, y te dice qué dato falta.
- Herramientas: `guia_pasos` ("quiero facturar", "quiero dar de alta un producto", "quiero cerrar el mes": pasos concretos con lo que ya tienes y lo que falta).
- Esfuerzo: bajo. Es lo que hace que el usuario común no se rinda.

### 10. Cartera de clientes

- La normal guarda clientes, pero no te dice nada de ellos.
- La nuestra te avisa si dependes de pocos clientes o quién compra cada vez menos.
- Herramientas: `clientes_importar` (CSV), `clientes_estado` (historial, frecuencia, concentración de ventas).
- Esfuerzo: bajo.

---

## Horizonte 2 — Lo no tan lejano (meses; cambian cómo se usa)

### 11. La suite en el celular (PWA, sin tiendas de apps)

- La normal te pide descargar una app de la tienda y crear otra cuenta.
- La nuestra se instala desde el navegador y funciona sin internet.
- Cómo: el panel y el mapa ya existen; se les agrega instalación, modo offline y atajos. Sin código nuevo de negocio.
- Esfuerzo: bajo.

### 12. Tareas programadas locales

- La normal te pide un servicio en la nube para "correr algo cada lunes".
- La nuestra lo corre sola en tu máquina y deja el resultado listo.
- Herramientas: `tarea_programar` ("cada lunes a las 8: hazme el corte de la semana"), `tarea_listar`, `tarea_correr` (correr una ahora).
- Esfuerzo: medio.

### 13. Cierre mensual para tu contador

- La normal te obliga a juntar papeles a mano cada mes.
- La nuestra te arma la carpeta del mes: ventas, gastos, cartera, inventario y movimientos, con una hoja de resumen.
- Herramientas: `cierre_mensual` (genera carpeta con todo lo del mes y su resumen), `paquete_contador` (versión limpia, sin notas internas, para entregar).
- Nota: organiza tus números; no calcula impuestos ni toca temas del SAT.
- Esfuerzo: medio.

### 14. Simulador de decisiones

- La normal te da una calculadora y ahí te ves.
- La nuestra te responde "¿me conviene?" con tus propios números.
- Herramientas: `decision_simular` (comprar la máquina nueva, contratar a alguien, subir precios, abrir segundo local: punto de equilibrio, tiempo de recuperación, escenarios bueno/esperado/malo).
- Esfuerzo: medio.

### 15. Riesgos del negocio, sin que los pidas

- La normal te avisa cuando ya es tarde.
- La nuestra te dice antes: dependes mucho de un cliente, este producto no rota, el margen viene cayendo.
- Herramientas: `riesgos_negocio` (concentración de ventas, productos sin rotación, márgenes en baja, exceso de stock).
- Esfuerzo: medio.

### 16. Crédito interno con historial propio

- La normal te vende un buró de crédito externo.
- La nuestra te deja decidir con tu propia historia: cómo pagó este cliente las últimas 10 veces.
- Herramientas: `credito_evaluar` (semáforo con tu historial: pagos a tiempo, demoras, monto máximo sugerido).
- Esfuerzo: medio.

---

## Horizonte 3 — Lo de fondo (preparación de plataforma)

### 17. Tareas largas con folio (lista para la nueva spec de MCP)

- La normal se queda colgada mientras analiza un proyecto enorme.
- La nuestra te da un folio al instante y avisa cuando termina.
- Por qué importa: el protocolo MCP va hacia tareas de segundo plano; llegar antes que la mayoría posiciona a la suite.
- Esfuerzo: alto.

### 18. Plantillas por giro compartibles

- La normal te vende el software y el contenido aparte.
- La nuestra te deja importar/exportar un giro completo (productos, plantillas, formatos) en un archivo.
- Herramientas: `giro_exportar`, `giro_importar`.
- Esfuerzo: bajo, encima del kit de arranque.

### 19. Panel en dos idiomas

- La normal nace en inglés y te traduce a medias.
- La nuestra nació en español y puede ofrecer inglés para zonas turísticas.
- Esfuerzo: medio. Se justifica solo si aparece ese usuario.

---

## Dónde ya vamos adelante (para presumir, no para construir)

- Español nativo de México: IVA, RFC, pesos, formatos y tono. Casi todo el ecosistema MCP está en inglés.
- Multi-empresa real: un servidor, varias empresas aisladas, con claves atadas a cada una. Casi nadie lo trae.
- Local primero: tus datos no salen de tu máquina. Privacidad por diseño, no por promesa.
- Aprobaciones humanas para lo delicado, y nunca para deshacer. Reparar es libre; romper pide permiso.
- Coste por resultado útil: medimos si la tarea sirvió, no cuántos tokens se gastaron.
- Protocolo al día: el registro MCP y el paquete ya cumplen lo que exige el ecosistema en 2026.

---

## Resumen para decidir

| # | Idea | Esfuerzo | Impacto | Depende de |
|---|---|---|---|---|
| 1 | Cobranza que persigue el pago | Bajo | Muy alto | Nada |
| 2 | Ventas del día y corte | Medio | Muy alto | Nada |
| 5 | Kit de arranque por giro | Bajo | Alto | Nada |
| 6 | Alertas que te buscan | Bajo | Alto | Nada |
| 4 | Gastos desde el CSV | Bajo | Alto | Nada |
| 9 | El que explica | Bajo | Alto | Nada |
| 3 | Compras y reorden | Medio | Alto | Nada |
| 13 | Cierre mensual | Medio | Alto (despachos) | Nada |
| 11 | Suite en el celular | Bajo | Medio | Nada |
| 8 | Prueba de respaldos | Bajo | Medio | Nada |
| 12 | Tareas programadas | Medio | Medio | Nada |
| 14 | Simulador de decisiones | Medio | Medio | Nada |
| 15 | Riesgos del negocio | Medio | Medio | Nada |
| 7 | Reportes imprimibles | Bajo | Medio | Nada |
| 10 | Cartera de clientes | Bajo | Medio | Nada |
| 16 | Crédito interno | Medio | Medio | Nada |
| 18 | Giros compartibles | Bajo | Bajo | Idea 5 |
| 17 | Tareas largas con folio | Alto | Alto | Nada |
| 19 | Panel bilingüe | Medio | Bajo | Nada |

**Mi recomendación de arranque:** las ideas 1, 2 y 5 juntas. Cobranza porque recupera dinero; ventas del día porque se usa todos los días; kit de arranque porque hace que un usuario nuevo vea valor en el primer minuto. Con esas tres, la suite pasa de "está completa" a "me sirve a diario".

---

## Lista para marcar

- [ ] 1. Cobranza que persigue el pago
- [ ] 2. Ventas del día y corte de caja
- [ ] 3. Compras y reorden sugerido
- [ ] 4. Gastos desde el CSV del banco
- [ ] 5. Kit de arranque por giro
- [ ] 6. Alertas que te buscan a ti
- [ ] 7. Reportes imprimibles
- [ ] 8. Prueba de restauración de respaldos
- [ ] 9. El que explica ("¿cómo le hago?")
- [ ] 10. Cartera de clientes
- [ ] 11. Suite en el celular (PWA)
- [ ] 12. Tareas programadas locales
- [ ] 13. Cierre mensual para tu contador
- [ ] 14. Simulador de decisiones
- [ ] 15. Riesgos del negocio
- [ ] 16. Crédito interno con historial propio
- [ ] 17. Tareas largas con folio
- [ ] 18. Plantillas por giro compartibles
- [ ] 19. Panel en dos idiomas
