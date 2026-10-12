# Checklist para que la suite se pueda cobrar

**Fecha:** 12 de octubre de 2026.
**Punto de partida:** 11 módulos, 68 herramientas, 186 pruebas, claves de API por
módulo y por empresa, auditoría, respaldos manuales, panel y landing. Funciona
en local y por HTTP. Lo que sigue es lo que falta para que alguien pague.

**Cómo se lee:** tres bloques. El A es obligatorio antes de cobrar el primer
peso. El B es para que el mes a mes no se caiga. El C es para crecer. Cada
punto lleva el por qué en la misma línea.

---

## Bloque A: antes del primer peso

### La puerta de entrada

- [ ] **Cuentas de persona, no solo claves.** Hoy hay claves de API por módulo y
  por empresa, pero no un usuario con nombre. Sin eso no se puede saber quién
  hizo qué, ni dar de baja a alguien sin cambiar todas las claves.
- [ ] **Papeles claros:** dueño del despacho, contador, auxiliar, cliente.
  Comprobados en el servidor, no escondidos en la pantalla. Un auxiliar no debe
  poder ver la empresa de otro aunque sepa la dirección.
- [ ] **Alta de un despacho en menos de una hora**, con datos reales, hecha por
  alguien que no seas tú. Si el alta depende de ti, el precio lo pones tú y el
  techo también.

### La confianza

- [ ] **Separación de datos de verdad entre despachos.** Hoy la multiempresa
  separa de forma lógica; para alojar a varios clientes hace falta separación
  física o por esquema, para que un error de código no pueda mezclar datos de
  dos clientes.
- [ ] **Respaldos automáticos, con comprobación y aviso.** Existe el respaldo
  manual y el de la cadena de migración. Falta que corra solo, que verifique que
  el respaldo se puede restaurar (ya existe la herramienta) y que avise cuando
  quede bien o mal.
- [ ] **Respaldo guardado fuera del mismo servidor.** Un respaldo en la misma
  máquina que se cae no es respaldo.
- [ ] **Registro de accesos y cambios visible para el cliente.** La auditoría ya
  existe puertas adentro; el despacho tiene que poder verla y exportarla. Esto
  es media venta: es lo que un despacho presume ante su cliente.
- [ ] **Aviso de privacidad y términos de servicio.** Obligatorios al cobrar, y
  en México con la ley de datos personales en la mano. Sin esto no se puede
  firmar con un despacho formal.

### La continuidad

- [ ] **Monitoreo con aviso.** Saber que el servicio se cayó antes que el
  cliente. Ya hay endpoint de salud; falta quien lo revise y avise.
- [ ] **Plan de recuperación escrito y probado una vez**, con el dato que importa:
  cuánto tardas en levantar si se cae todo.
- [ ] **Actualizaciones que no rompan datos.** La maquinaria de migración con
  respaldo previo y verificación ya existe en el taller; hay que usarla para
  entregar versiones nuevas a clientes en producción.
- [ ] **Cómo se actualiza el cliente sin llamarte.** Una sola instrucción clara,
  y que se pueda volver atrás.

### El dinero

- [ ] **Página de precios clara**, con la prueba gratis de 15 días, qué incluye
  cada plan y qué no. La landing ya existe; falta la página de precios.
- [ ] **Cobro en línea sin hablar contigo.** Aquí sí hace falta una pasarela
  (Mercado Pago o Stripe). Es el único punto donde la regla de "sin servicios
  externos" tiene que ceder, porque cobrar sin pasarela es cobrar a mano.
- [ ] **Factura de la suscripción.** Si cobras, tienes que facturar. Se resuelve
  siendo cliente de un PAC o usando el propio módulo de facturación con timbrado
  real. Es la ironía del proyecto: la suite factura, pero todavía no factura sola.
- [ ] **Costo de operar por escrito.** Cuánto cuesta al mes el servidor, el
  dominio, el correo y la pasarela, y cuántos clientes hacen falta para no perder.
  Con precio de 690 al mes, el punto de equilibrio son dos clientes.

### El acompañamiento

- [ ] **Manual de arranque de media hora.** Instalar, conectar su IA, cargar sus
  datos, emitir la primera factura. Si necesita un curso, no se vende solo.
- [ ] **Canal de soporte con horario dicho en voz alta.** Aunque sea un correo y
  dos horas al día. Lo que rompe la confianza no es tener poco soporte, es no
  decir cuál es.
- [ ] **Prueba real con un despacho** (aunque sea gratis) antes de pedir dinero.
  Que use el sistema una semana y anotes cada duda: eso es la lista de lo que
  falta de verdad.

---

## Bloque B: para que aguante el mes a mes

- [ ] Medición de uso por cliente y por plan, para saber a quién le toca qué plan.
- [ ] Límites del plan aplicados en el servidor, no en la página de precios.
- [ ] Aviso de uso anómalo: un agente en bucle puede hacer mil llamadas por
  descuido y arruinar el margen.
- [ ] Pagos recurrentes con reintento, aviso de vencimiento y suspensión amable
  (no cortar el servicio el día 1 sin avisar).
- [ ] Registro de uso visible para el cliente, con lo que le costó y lo que usó.
- [ ] Copia de la base fuera del servidor y prueba de restauración periódica.
- [ ] Panel del despacho con el estado de todos sus clientes en una pantalla.
- [ ] Avisos por correo: factura generada, respaldo hecho, aprobación pendiente.
- [ ] Baja ordenada: exportar todo en formato abierto y borrar de verdad cuando
  el cliente lo pida. Saber que puede irse es lo que hace que se quede.
- [ ] Timbrado real con PAC si vas a vender a despachos: hoy la suite genera el
  XML sin sellar y eso detiene a cualquier despacho serio.
- [ ] Papeles de encargado de datos: quién accede a qué, por cuánto tiempo y con
  qué rastro (la auditoría ya sirve, falta la política escrita).
- [ ] Embudo mínimo: cuántos ven la página, cuántos instalan, cuántos pagan,
  cuántos se van. Sin esto se vende a ciegas.
- [ ] Revisión de seguridad propia una vez: secretos, dependencias, cabeceras.
  El CI ya corre auditoría; falta la revisión de la aplicación.
- [ ] Precio anual con descuento, para tener dinero por adelantado.
- [ ] Prueba gratuita acotada, con tope de llamadas para que no la usen de gratis
  para siempre.

---

## Bloque C: para crecer

- [ ] Producto de autoservicio para el negocio suelto (199 a 399 al mes), sin
  despacho de por medio.
- [ ] Ficha en los directorios MCP y en el registro oficial, con instalación de
  una línea. Ya está lista la ficha técnica; falta publicarla.
- [ ] Programa de despachos: comisión por referido y material de venta listo.
- [ ] Casos escritos en español: "facturé 200 tickets en una tarde", con números.
- [ ] Integración con ERP y CRM por webhooks (fase 5B del alcance).
- [ ] Decisión de licencia cuando exista servicio alojado: MIT deja que
  cualquiera lo aloje; AGPL lo obliga a compartir mejoras.
- [ ] Métricas de resultado útil, no de llamadas: facturas emitidas, tiempo
  ahorrado, cierres hechos. Es lo que se muestra al renovar.
- [ ] Segunda persona que pueda dar soporte y hacer un alta.
- [ ] Versión en otro país de habla hispana, si aparece la oportunidad.

---

## Los seis aspectos que un producto de paga debe tener

No son tareas, son condiciones. Si falta una, el cliente no firma o se va
cuando llega el primer problema.

1. **Confianza:** sus datos separados de los de otros, y respaldo demostrable.
2. **Control:** el cliente sabe qué entró, quién lo movió y puede sacar todo.
3. **Continuidad:** se cae, se levanta, y él se entera por ti y no por sus
   clientes.
4. **Claridad de precio:** un número que puede predecir, sin sorpresas por uso.
5. **Soporte con nombre y horario:** aunque sea poco, dicho de frente.
6. **Salida:** puede irse cuando quiera y llevarse todo. Es lo que hace que
   confíe en quedarse.

---

## Cuánto cuesta operar (estimado, hay que confirmar precios)

| Concepto | Al mes | Nota |
| --- | --- | --- |
| Servidor | 250 a 600 | máquina pequeña alcanza para los primeros clientes |
| Dominio | alrededor de 20 | pagado por año |
| Correo profesional | 100 a 150 | con el dominio propio |
| Pasarela de cobro | 3.5 a 4 % + IVA por cobro | no es fijo, es comisión |
| Timbres, si facturas la suscripción | aparte | por timbre |
| **Total fijo** | **alrededor de 500 a 800** | |

Con la suscripción propuesta (690 al mes hasta 10 negocios), el punto de
equilibrio son dos clientes. El tercero ya es ganancia. Sirve para decidir sin
miedo: esto no necesita diez clientes para empezar a pagarse solo.

---

## La prueba final

Se puede cobrar cuando estas tres cosas sean ciertas:

1. Un despacho que no seas tú hizo el alta solo, siguiendo el manual.
2. Los respaldos corren, se verifican y avisan, sin que nadie los pida.
3. Cobrar y facturar la suscripción no depende de que tú estés en el proceso.

Mientras alguna sea falsa, se puede cobrar igual, pero cobrando por
implementación y acompañamiento, no por suscripción.
