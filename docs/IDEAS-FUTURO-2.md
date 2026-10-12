# Ideas, segunda tanda — las que casi nadie está haciendo

**Fecha:** 12 de octubre de 2026 · **Versión base:** 0.2.0.

Estas no salen de copiar la lista de funciones de un punto de venta. Salen de darle vuelta al problema: ¿qué duele todos los días y ningún sistema resuelve? Cada idea trae el formato de siempre ("la normal tiene X, la nuestra Y"), las herramientas concretas, y por qué casi nadie la tiene. Todo es local: sin cuentas nuevas, sin servicios contratados, sin conectar nada hacia afuera.

---

## Grupo A — El negocio que se captura solo

La mayoría de los sistemas fallan no porque les falte una función, sino porque capturar datos da flojera. Aquí el ángulo es quitar la flojera.

### 1. Apunta esto y ya (captura en lenguaje natural)

- La normal te pide un formulario con doce campos.
- La nuestra acepta la frase como la dirías: "el martes le presté 500 a Juan", "vendí 3 kilos a la señora de la esquina, me pagó la mitad".
- Herramientas: `apunta` (interpreta la frase, pregunta solo lo que falta y registra), `apunte_bandeja` (lo que quedó a medias, para completarlo después).
- Por qué casi nadie la tiene: el software se diseña para que el usuario se adapte al sistema y no al revés.
- Esfuerzo: bajo.

### 2. El buscador que sí encuentra (el archivero)

- La normal tiene una pantalla por cada cosa y tienes que acordarte dónde la guardaste.
- La nuestra tiene una sola caja: escribes "el pedido de la señora del taller de mayo" y aparece, venga de pedidos, notas, documentos o borradores.
- Herramientas: `archivo_buscar` (una sola búsqueda sobre todo lo del negocio), `archivo_guardar` (lo que pegues queda en la caja, indexado).
- Por qué casi nadie la tiene: los sistemas se organizan por módulos (para el programador), no por memoria (para el humano).
- Esfuerzo: bajo.

### 3. Promesas hechas a clientes

- La normal guarda pedidos; de lo que prometiste de palabra, ni se enteró.
- La nuestra registra "le prometí a la clienta entregar el jueves" y te lo pone enfrente el jueves.
- Herramientas: `promesa_registrar` (con cliente y fecha), `promesas_del_dia`, `promesa_cumplida`.
- Por qué casi nadie la tiene: los incumplimientos casi nunca son por flojera, son por olvido; y el olvido no se arregla con reportes.
- Esfuerzo: bajo.

### 4. Traductor a persona (para el que no es técnico)

- La normal te da un reporte con números y te deja ahí.
- La nuestra te devuelve el mismo dato en palabras de persona, terminando con la decisión: "te quedan 12 días de stock de lo que más vendes; te conviene pedir el martes".
- Herramientas: `explicar_simple` (recibe cualquier resultado y lo cuenta según el tiempo que tengas: 30 segundos, 5 minutos o largo).
- Por qué casi nadie la tiene: se diseña para el que ya entiende el sistema.
- Esfuerzo: bajo.

---

## Grupo B — Las que cuidan tu dinero sin que las busques

No esperan a que hagas un reporte. Ellas te buscan.

### 5. Semáforo de fugas de dinero

- La normal te muestra el estado de cuenta completo y tú a ver qué encuentras.
- La nuestra busca las fugas: cobros duplicados, servicios que subieron sin avisar, suscripciones que ya no usas, el mismo pago dos veces.
- Herramientas: `fugas_detectar` (sobre tus gastos y pagos registrados), `fugas_confirmar` (marcar como real o como falsa alarma para que aprenda).
- Por qué casi nadie la tiene: nadie audita gastos chicos, y ahí es donde se van.
- Esfuerzo: bajo.

### 6. Defensor del margen

- La normal te deja poner el precio que quieras y no dice nada.
- La nuestra avisa: "en esta cotización, con el descuento que diste, ganas 3 pesos; con el costo nuevo, pierdes".
- Herramientas: `margen_vigilar` (piso de utilidad por producto o familia), `margen_alertas` (ventas que se acercaron o cayeron bajo el piso).
- Por qué casi nadie la tiene: los descuentos se dan en el momento y nadie quiere ser el que dice "no".
- Esfuerzo: bajo.

### 7. Cuadre asistido (cuando no te cuadra la caja)

- La normal te dice "hay una diferencia de 340 pesos" y ahí te quedas.
- La nuestra te dice la causa más probable con lo que registraste: "probablemente se te fue una salida de mercancía sin registrar el jueves".
- Herramientas: `cuadre_asistido` (del descuadre a hipótesis ordenadas por probabilidad, con los movimientos que la sostienen).
- Por qué casi nadie la tiene: resolver el descuadre es trabajo de detective y el software nomás cuenta.
- Esfuerzo: medio.

### 8. Multimoneda sin sorpresas

- La normal te cobra aparte por multimoneda o te esconde el tipo de cambio.
- La nuestra convierte con el tipo de cambio que TÚ registras (el que te dio tu banco), y te dice cuánto ganaste o perdiste por la diferencia.
- Herramientas: `moneda_cotizar` (cotización en pesos o dólares con tu tipo de cambio), `tipo_cambio_registrar`.
- Por qué casi nadie la tiene: se asume que el tipo de cambio es un dato "de sistema" y no una decisión del dueño.
- Esfuerzo: bajo.

---

## Grupo C — Las que te defienden

Aquí el sistema deja de ser un empleado y se vuelve un guardaespaldas.

### 9. Escudo contra estafas

- La normal no dice nada cuando llega el correo que pide cambiar la cuenta bancaria.
- La nuestra revisa el mensaje antes de que caigas: dominio parecido (pero no igual), prisa artificial, cambio de cuenta a última hora, y te lo marca con rojo.
- Herramientas: `estafa_revisar` (pegas el correo o mensaje; te devuelve las señales de alarma explicadas), `estafa_avisar` (deja avisado al resto del equipo).
- Por qué casi nadie la tiene: los sistemas protegen contra el crimen grande, no contra el correo que sí te va a llegar.
- Esfuerzo: bajo. Aviso justo: son señales simples, no magia; ayuda a detenerte, no garantiza.

### 10. Abogado del diablo (antes de mandar la cotización o el contrato)

- La normal te ayuda a hacer las cosas rápido.
- La nuestra te ayuda a hacerlas bien: antes de que mandes ese pedido grande, señala los huecos ("sin anticipo", "plazo imposible", "sin penalización si no pagan", "con este cliente ya te quedaron mal dos veces").
- Herramientas: `socio_revisar` (revisa una cotización, pedido o contrato pegado y lista los riesgos en palabras simples).
- Por qué casi nadie la tiene: todo el software está diseñado para decir que sí.
- Esfuerzo: medio.

### 11. Rebobinar el inventario

- La normal solo te muestra el ahora.
- La nuestra te deja ver qué tenías el día que quieras y desde cuándo empezó el descuadre.
- Herramientas: `inventario_rebobinar` (existencia a una fecha), `inventario_historial` (quién movió qué, cuándo y por qué).
- Por qué casi nadie la tiene: se guarda el saldo, no la película.
- Esfuerzo: medio.

### 12. Rastrear de dónde vino y a dónde se fue (lotes)

- La normal te dice "hay 8 en existencia".
- La nuestra te dice cuáles de esos 8 llegaron en qué compra, a qué costo, y a quién se le vendieron los otros, para responder una devolución o una garantía sin adivinar.
- Herramientas: `lote_entrada`, `lote_rastrear`, `lote_reclamar` (arma el caso de la devolución con la evidencia).
- Por qué casi nadie la tiene: el trazado de lote existe en farmacias y fábricas, no en el negocio común; pero el problema es el mismo.
- Esfuerzo: medio.

---

## Grupo D — Las que piensan por ti

Estas no esperan a que les preguntes.

### 13. Detector de oportunidades

- La normal te da reportes de lo que ya pasó.
- La nuestra te propone qué hacer: "estos 12 clientes ya les toca repetir pedido", "quien compra cable compra cinta nueve de cada diez veces".
- Herramientas: `oportunidad_sugerir` (acciones concretas con nombre y apellido), `oportunidad_descartar` (para que aprenda qué no te interesa).
- Por qué casi nadie la tiene: las recomendaciones viven en el comercio grande; aquí es sobre tus clientes, con tus datos.
- Esfuerzo: medio.

### 14. Plan de la semana que sí cabe

- La normal te da una lista de pendientes interminable.
- La nuestra te dice qué cabe de verdad según tu capacidad y tu historial: "estas 9 cosas sí; estas 4, muévelas al jueves o pasa la entrega".
- Herramientas: `semana_planear` (acomoda pedidos, entregas y promesas en los días reales), `semana_ajustar` (cuando algo se atora, recalcula).
- Por qué casi nadie la tiene: las listas de tareas no saben cuántas horas tienes, ni cuántas salieron mal la semana pasada.
- Esfuerzo: medio.

### 15. Memoria con su porqué

- La normal guarda qué se hizo.
- La nuestra guarda POR QUÉ se decidió: "dejamos de venderle al proveedor X porque entregó dos veces mal; revisar en marzo".
- Herramientas: `memoria_guardar` (decisión, razón, fecha, quién la tomó), `memoria_preguntar` ("¿por qué ya no trabajamos con X?" con las citas de tus notas).
- Por qué casi nadie la tiene: el conocimiento del negocio vive en la cabeza del dueño y se va de vacaciones con él.
- Esfuerzo: bajo.

### 16. El manual vivo del negocio

- La normal te pide que escribas un manual (nadie lo hace).
- La nuestra lo escribe sola con lo que ya sabe: proveedores y teléfonos, precios, excepciones, "con este cliente no se puede fiar", "esto se pide los miércoles".
- Herramientas: `manual_vivo` (genera el documento del negocio tal como opera HOY, y lo regenera cuando las cosas cambian), `manual_actualizar` (corrige lo que no aplica).
- Por qué casi nadie la tiene: los manuales se escriben una vez y mueren; este se escribe desde los datos y sobrevive.
- Esfuerzo: medio.

### 17. Cliente que se enfría

- La normal te dice a quién le vendiste.
- La nuestra te avisa: "don Manuel compraba cada 15 días; lleva 45 sin aparecer".
- Herramientas: `cliente_enfriar` (clientes que dejaron de venir, ordenados por lo que valían), `cliente_reactivar` (mensaje listo para mandarle).
- Por qué casi nadie la tiene: recuperar a uno que ya te conocía cuesta menos que conseguir uno nuevo, pero nadie lo mide.
- Esfuerzo: bajo.

---

## Resumen para decidir

| # | Idea | Esfuerzo | Impacto | Lo que la hace rara |
|---|---|---|---|---|
| 1 | Apunta esto y ya | Bajo | Muy alto | Quita los formularios |
| 9 | Escudo contra estafas | Bajo | Muy alto | Nadie protege el correo del día |
| 16 | Manual vivo del negocio | Medio | Muy alto | Se escribe solo y no envejece |
| 5 | Semáforo de fugas | Bajo | Alto | Audita lo chico que nadie ve |
| 6 | Defensor del margen | Bajo | Alto | Dice lo que nadie quiere decir |
| 15 | Memoria con su porqué | Bajo | Alto | Guarda razones, no datos |
| 17 | Cliente que se enfría | Bajo | Alto | Mide lo que ya perdiste |
| 3 | Promesas de clientes | Bajo | Alto | Ataca el olvido, no la flojera |
| 2 | El buscador que sí encuentra | Bajo | Alto | Una caja, no veinte pantallas |
| 10 | Abogado del diablo | Medio | Alto | Está diseñado para decir no |
| 13 | Detector de oportunidades | Medio | Medio | Recomienda sobre tus datos |
| 14 | Plan de la semana que sí cabe | Medio | Medio | Sabe cuántas horas tienes |
| 11 | Rebobinar el inventario | Medio | Medio | Guarda la película, no la foto |
| 7 | Cuadre asistido | Medio | Medio | Deja de contar y detectivea |
| 12 | Rastrear lotes | Medio | Medio | Trazabilidad para negocio común |
| 8 | Multimoneda sin sorpresas | Bajo | Medio | El tipo de cambio es tuyo |
| 4 | Traductor a persona | Bajo | Medio | Explica según tu prisa |

**Mi recomendación de las 17:** la 1 (apunta esto y ya), la 9 (escudo contra estafas) y la 16 (manual vivo). Las tres son cosas que ningún sistema de negocios hace hoy, las tres se sienten al primer uso, y las tres usan lo que ya construimos. Con el apuntador, capturar deja de ser un estorbo; con el escudo, el sistema demuestra que te cuida; con el manual, el conocimiento deja de irse contigo cuando te enfermas.

---

## Lista para marcar

- [ ] 1. Apunta esto y ya (captura en lenguaje natural)
- [ ] 2. El buscador que sí encuentra (el archivero)
- [ ] 3. Promesas hechas a clientes
- [ ] 4. Traductor a persona
- [ ] 5. Semáforo de fugas de dinero
- [ ] 6. Defensor del margen
- [ ] 7. Cuadre asistido
- [ ] 8. Multimoneda sin sorpresas
- [ ] 9. Escudo contra estafas
- [ ] 10. Abogado del diablo
- [ ] 11. Rebobinar el inventario
- [ ] 12. Rastrear de dónde vino y a dónde se fue (lotes)
- [ ] 13. Detector de oportunidades
- [ ] 14. Plan de la semana que sí cabe
- [ ] 15. Memoria con su porqué
- [ ] 16. El manual vivo del negocio
- [ ] 17. Cliente que se enfría
