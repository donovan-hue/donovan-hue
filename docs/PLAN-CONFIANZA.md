# Plan de confianza: qué se hace, cómo se comprueba

**Fecha:** 12 de octubre de 2026.
**Para qué:** que un despacho o un negocio le entregue sus datos al sistema y
pague por él sin miedo. Está escrito contra el código de verdad, no contra
deseos: cada punto dice qué existe hoy, qué falta y cómo se comprueba.

**Cómo se lee:** seis aspectos. En cada uno: qué espera el cliente, qué hay
hoy, qué se hace, cómo se comprueba y cuánto pesa.

---

## 1. Confianza: sus datos aparte y respaldo que se puede mostrar

**Qué espera el cliente:** que sus datos no estén mezclados con los de otro
negocio, y que si algo se borra, se pueda volver atrás. Dicho de otro modo:
que se pueda demostrar, no que le pidamos que nos crea.

**Qué hay hoy:**

- La multiempresa separa de forma lógica: una sola base SQLite con el campo de
  empresa filtrando cada consulta. Sirve para un dueño con dos giros; no basta
  para alojar a dos clientes distintos que no se conocen.
- El respaldo ya es serio: se hace en caliente con `VACUUM INTO` (copia íntegra
  sin detener el servidor), incluye la auditoría y los datos de trabajo, escribe
  un manifiesto con hash sha256 de cada archivo, rota y conserva los últimos, y
  deja rastro en la auditoría. Existe el comando y también una biblioteca.
- La maquinaria de "respaldo que se probó de verdad" ya existe: en el taller de
  Kronos se hizo la cadena respaldo, verificación por checksum, restauración a
  una base nueva y conteo de documentos.

**Qué falta:**

1. Una base por cliente (o por despacho). En SQLite esto es lo natural y lo
   barato: cada cliente su propio archivo. La separación deja de depender de
   que el código no se equivoque.
2. Respaldo automático programado, no a petición.
3. Verificación del respaldo: restaurarlo en una base temporal y contar
   registros, no solo comprobar el hash. Un hash demuestra que el archivo no
   cambió, no que los datos vuelvan a entrar.
4. Copia fuera del servidor. El respaldo en la misma máquina que se cae no es
   respaldo. Alcanza un servicio de almacenamiento de objetos.
5. Aviso: que el respaldo corrió bien, y sobre todo que falló.
6. Aviso de privacidad y contrato de servicio, revisados por un abogado. Sin
   esto, un despacho formal no firma.

**Cómo se comprueba:** se borra a propósito un registro, se restaura el
respaldo del día anterior y el registro vuelve. Ese día se puede enseñar la
prueba, con fecha y hora.

**Pesa:** 4 a 6 días míos (puntos 1 al 5) y el abogado es tuyo.

---

## 2. Control: saber quién entró y qué movió

**Qué espera el cliente:** poder decir "esto lo hizo la auxiliar tal el martes",
y poder darle de baja a alguien sin cambiarle la llave a todos.

**Qué hay hoy:**

- Auditoría append-only en NDJSON: nunca se reescribe el archivo, cada evento
  lleva hora, tipo, cliente, herramienta, módulo, duración, resultado y un
  resumen truncado de los argumentos (nunca secretos completos). Se puede
  consultar por tipo, herramienta, cliente y fecha.
- Claves de API por módulo y atadas a una empresa, con revocación auditada.

**Qué falta:**

1. Cuentas de persona: usuario con nombre y contraseña, no solo una llave de
   máquina.
2. Papeles: dueño del despacho, contador, auxiliar, cliente. Comprobados en el
   servidor.
3. Que la auditoría se pueda entregar: exportar a CSV o JSON por rango de
   fechas, con un botón en el panel del despacho.
4. Política de retención escrita: cuánto tiempo se guarda cada cosa y qué se
   borra.
5. Vista del cliente en la que vea sus propios eventos, sin poder ver los de
   otro.

**Cómo se comprueba:** el auxiliar intenta abrir una empresa que no le toca y
el servidor lo rechaza (queda registrado el intento); el dueño exporta la
auditoría de la semana y le cuadra con lo que se hizo.

**Pesa:** 5 a 8 días míos.

---

## 3. Continuidad: que se caiga y se levante, y que se entere por ti

**Qué espera el cliente:** que el sistema esté arriba, y si se cae, que él no
sea el último en enterarse.

**Qué hay hoy:**

- Endpoint de salud y métricas de uso por herramienta y por cliente.
- Los flujos de trabajo ya miden y reportan cada corrida.
- Maquinaria de migración con respaldo previo obligatorio y verificación.

**Qué falta:**

1. Un vigilante externo que revise el servicio cada pocos minutos y avise por
   correo o mensaje cuando no responda. Tiene que ser externo: el que se cae no
   puede avisar de que se cayó.
2. Publicar una página de estado simple: arriba o abajo, y el histórico de las
   últimas incidencias.
3. Plan de recuperación escrito y probado una vez, con el número que importa:
   cuántos minutos tardas en levantar todo.
4. Actualizaciones que no rompan datos: la maquinaria ya está, falta usarla
   como procedimiento fijo para entregar versiones a clientes en producción.
5. Aviso a los clientes cuando haya mantenimiento, con antelación.

**Cómo se comprueba:** se apaga el servicio a propósito un rato; el aviso llega
antes que la llamada del cliente, y el plan de recuperación se ejecuta en el
tiempo que dice.

**Pesa:** 3 a 5 días míos, más el servicio de vigilancia (unos cientos de pesos
al año).

---

## 4. Claridad de precio: un número que se puede predecir

**Qué espera el cliente:** saber cuánto va a pagar, por qué, y qué pasa si un
mes usa más.

**Qué hay hoy:** una landing pública con la propuesta de valor. Nada de
precios.

**Qué falta:**

1. Página de precios con dos o tres planes, en pesos, con IVA dicho aparte.
2. Prueba gratis de 15 días sin tarjeta, como la competencia.
3. Qué incluye y qué no, en renglones: número de negocios, número de usuarios,
   llamadas incluidas al mes, respaldos, soporte.
4. Qué pasa si se pasa de uso: aviso y tope, jamás un cobro sorpresa. Un agente
   en un bucle puede hacer mil llamadas por descuido; eso no se le cobra al
   cliente, se le avisa.
5. Cobro en línea (pasarela) y factura de la suscripción. Es el único punto
   donde tiene que ceder la regla de "sin servicios externos", porque cobrar
   sin pasarela es cobrar a mano.

**Cómo se comprueba:** alguien que no eres tú lee la página y puede decir sin
ayuda cuánto pagaría, qué recibe y cómo se da de baja.

**Pesa:** 3 a 4 días míos la página y el alta; la pasarela es tuyo el trámite.

---

## 5. Soporte: poco, pero dicho de frente

**Qué espera el cliente:** saber a quién le habla, cuándo le contestan y que
hay un manual que no lo hace sentir tonto.

**Qué hay hoy:** nada formal.

**Qué falta:**

1. Un canal (correo con el dominio propio y un grupo de mensajes), con horario
   declarado: por ejemplo "de 9 a 6, de lunes a viernes, contestamos el mismo
   día".
2. El manual de arranque de media hora: instalar, conectar su IA, cargar sus
   datos, emitir la primera factura y hacer el primer respaldo.
3. Una página de problemas comunes, empezando por los diez que salgan de la
   prueba con el despacho.
4. Decir qué NO cubre el soporte (tu contabilidad no la lleva el sistema, el
   CSD y el timbrado son tuyos, etc.).

**Cómo se comprueba:** alguien ajeno al proyecto hace el arranque completo
siguiendo solo el manual, y anotas cada duda: esa lista es el material real.

**Pesa:** 2 días míos, más lo que salga de la prueba.

---

## 6. Salida: que se pueda ir y llevarse todo

**Qué espera el cliente:** que no quede cautivo. Suena contrario a vender, y es
lo que más confianza da.

**Qué hay hoy:** se pueden exportar datasets sueltos a JSON. No hay una salida
completa, ni forma de borrar una empresa.

**Qué falta:**

1. Exportar todo: un botón o herramienta que saque todo lo de un negocio en
   formato abierto (JSON y CSV por tabla, más un resumen), con sus documentos y
   su auditoría. Formatos que cualquiera puede abrir, no un respaldo que solo
   sirve si sigues con nosotros.
2. Borrado real de un negocio, en dos pasos: se solicita, se entrega el archivo
   de salida y la segunda confirmación borra. Con rastro en la auditoría.
3. Constancia de borrado: acuse de que se borró y qué se borró. Es lo que un
   despacho necesita para dar de baja a su cliente con papeles.
4. Que todo esto lo pueda hacer el cliente solo, sin pedirte permiso.

**Cómo se comprueba:** se crea un negocio de prueba con datos, se exporta todo,
se restaura en una instalación limpia y todo está ahí; luego se borra y no
queda nada.

**Pesa:** 3 a 4 días míos.

---

## Orden recomendado

**Etapa 1, mínimo para cobrar por implementación (10 a 15 días míos).**
Respaldo automático, verificado y con copia fuera del servidor; exportar todo;
borrado en dos pasos; auditoría exportable; cuentas con papeles.

*Con esto ya se puede entrar a un despacho y trabajar: si algo se rompe, hay
vuelta atrás; si se quiere ir, se puede ir.*

**Etapa 2, para cobrar suscripción (2 a 3 semanas).**
Una base por cliente; vigilante externo con aviso; página de estado; manual de
arranque; soporte con horario; panel del despacho con el estado de sus clientes.

*Con esto ya se puede alojar el servicio y cobrar al mes sin que la operación
dependa de que estés tú.*

**Etapa 3, para vender solo (2 semanas).**
Página de precios, prueba de 15 días, pasarela de cobro, factura de la
suscripción, darse de alta sin ti.

---

## Lo que no hace falta y conviene no gastar

- Certificaciones (ISO, SOC 2) y pruebas de penetración externas: son para
  vender a empresas grandes, no al despacho que empieza. Se consideran cuando
  haya un cliente que las pida por escrito.
- Cifrado por columna: las bases de datos ya se pueden cifrar a nivel de disco,
  y el respaldo se puede cifrar al salir del servidor. Cifrar dentro del sistema
  complica y no cambia la conversación de venta de este tamaño.
- Aplicación móvil, SSO empresarial, y todo lo que pida un corporativo.
- Un panel nuevo desde cero: ya hay panel y mapa vivo; se extiende, no se
  rehace.

---

## La prueba de fuego

Cuando estos cinco se puedan enseñar con fecha, está listo para que alguien
pague:

1. El respaldo de ayer se restauró hoy y devolvió los datos (con el conteo).
2. La copia del respaldo vive fuera del servidor y se puede mostrar dónde.
3. La auditoría de la semana se entregó al despacho en un archivo.
4. El negocio de prueba se exportó completo, se abrió en otra instalación y
   luego se borró sin dejar rastro.
5. Un despacho que no eres tú hizo el alta y el arranque siguiendo el manual.
