# Cómo cobrar (y qué no cobrar)

**Fecha:** 12 de octubre de 2026.

**La pregunta corta:** conviene cobrar, sí. Pero no por donde parece.

---

## 1. El paquete de npm no se puede cobrar

Publicar en npm es gratis y para siempre. Lo único que npm cobra son los
paquetes privados (7 dólares por usuario al mes) y eso es para *no* publicar
el código, no para venderlo. Además npm no tiene forma de cobrarle a quien
descarga: el archivo se copia y se acabó. Un candado dentro del paquete se
abre editando un archivo.

Conclusión: el paquete va gratis. Cobrar el paquete no es una opción técnica
que exista.

## 2. El mercado MCP, hoy

Cifras de este año, para no decidir a ciegas:

- Hay más de 20 000 servidores MCP publicados. Menos del 5 % gana algo.
- Los que cobran, en herramientas para desarrolladores, andan entre 19 y 49
  dólares al mes.
- El dinero de verdad está en dos lados: alojar el servicio (suscripción o
  por uso) y el trabajo de implementación para un cliente concreto. Los
  proyectos de integración hechos a la medida van de 8 000 a 50 000 dólares,
  más un acompañamiento mensual de 400 a 1 500.
- Dentro del cliente de IA todavía no se puede cobrar: no existe forma nativa
  de decir "esta herramienta cuesta". Por eso los que cobran lo hacen fuera del
  protocolo: clave de acceso, suscripción, o una capa que cobra por llamada.

## 3. Lo que sí conviene: software gratis, servicio de pago

Es lo que ya hacen los grandes: Zapier mete su MCP en el plan y cobra el plan;
Alegra incluye su conector MCP y cobra la suscripción. Nadie vende el
conector: venden que el conector funcione sin que el cliente lo mantenga.

Para nosotros: el paquete local es la puerta de entrada (gratis, sin registro,
funciona en la computadora del negocio), y lo que se cobra es la versión
alojada y el acompañamiento.

**Dos avisos que conviene saber ahora:**

- **Alegra ya tiene conector MCP** y se anuncia como el primero de Latinoamérica.
  No vamos primero en MCP para contabilidad mexicana. El hueco que queda es
  otro: nuestro paquete corre en la máquina del negocio, con sus datos, sin
  nube obligatoria, multiempresa y en español, y no depende de que Alegra
  quiera o no darnos acceso.
- **La licencia actual (MIT) permite que cualquiera tome el código** y ofrezca
  su propio servicio con él. Si eso preocupa, se cambia a AGPL, que obliga a
  publicar el código de quien lo aloje como servicio. Mi recomendación: dejarlo
  MIT por ahora, porque el riesgo real hoy no es que nos copien, es que nadie
  lo use. Ya habrá tiempo de cerrarlo cuando haya clientes.

## 4. Precios del mercado mexicano

Para anclar los nuestros, en pesos al mes sin IVA:

| Producto | Precio | Qué incluye |
| --- | --- | --- |
| Alegra, Plan Contador | 990 | RFC ilimitados, multiempresa, conector MCP |
| Alegra, punto de venta | 199 a 1 299 | según plan |
| Contalink, por usuario | alrededor de 590 | portal del despacho |
| Contalink, cliente del despacho | 190 (persona física) y 290 (persona moral) | contabilidad del cliente |
| CONTPAQi, licencia | 2 690 facturación, 4 590 contabilidad | licencia anual, no suscripción |

Lo que dicen esos números: el despacho ya paga entre 600 y 1 000 pesos al mes
por herramienta, y el cliente chico paga 200 o 300. Cobrar más que eso pide una
razón muy buena.

## 5. Precios propuestos (punto de partida, no verdad revelada)

| Etapa | Qué se cobra | Cuánto | Cuándo |
| --- | --- | --- | --- |
| 1 | Nada: el paquete local es gratis | 0 | ahora |
| 2 | Versión alojada para el despacho | 690 al mes hasta 10 negocios, 990 sin límite | cuando exista la versión alojada |
| 3 | Alta e implementación | 8 000 a 25 000, una vez | por despacho que entra |
| 4 | Acompañamiento | 1 500 a 4 000 al mes | opcional, para quien lo pida |
| 5 | Negocio suelto (no despacho) | 199 a 399 al mes | después de la etapa 2 |

Por qué así:

- La etapa 2 queda por debajo del Plan Contador de Alegra (990) y con precio
  fijo, que es justo lo que un despacho quiere: que crecer la cartera no le
  suba la factura.
- La etapa 3 es donde está el dinero hoy en el mundo MCP, y es trabajo que ya
  sabemos hacer: migrar datos, configurar, capacitar.
- La etapa 5 existe para que el negocio chico pueda entrar solo, sin despacho,
  y sea el despacho quien lo suba a la etapa 2.

## 6. Qué falta para poder cobrar

El paquete local ya sirve, pero no hay nada que impida copiarlo, así que lo que
se cobra es el servicio. Para eso falta, en orden:

1. Cuentas y accesos por despacho: quién entra, a qué negocios y con qué papel.
2. Separación de datos de verdad entre empresas (hoy la multiempresa ya separa
   lógicamente; para alojar hace falta además separación física o por esquema).
3. Respaldos automáticos con aviso de que ya se hicieron, que es media venta
   para un despacho.
4. Cobro: página donde pagar sin hablar conmigo. Esto sí pide un servicio
   externo de pagos (Stripe o Mercado Pago), que es el único punto donde la
   regla de "sin servicios externos por ahora" tendría que ceder. Se decide
   cuando llegue la etapa 2, no antes.
5. Página de precios en el sitio, con la prueba gratis de 15 días como los
   competidores.

## 7. Lo que no conviene

- Cobrar el paquete de npm (no se puede, y espanta la adopción que es lo que
  hace falta ahora).
- Candados dentro del paquete local: se rompen editando un archivo y dejan
  mala fama entre quienes los encuentran.
- Cobrar por llamada de herramienta: nadie puede predecir cuántas llamadas va
  a hacer su agente, y un agente con un bucle puede hacer mil por descuido.
  Precio fijo y predecible.
- Cobrar antes de que el sistema funcione sin mí. Hoy la suite funciona sola,
  pero la venta todavía soy yo.

---

## 8. Topes por plan: cuántos usuarios y cuánto uso

**La decisión de fondo: no cobrar por usuario.** Cobrar por persona castiga al
despacho por crecer y convierte cada contratación en una negociación. La
competencia lo hace de las dos formas: Contalink cobra por usuario (alrededor de
590) y Alegra no cobra por usuario. Se cobra por negocio, que es lo que de
verdad se administra, y los usuarios van incluidos con holgura.

### Topes propuestos

| Tope | Negocio suelto (199-399) | Despacho (690) | Despacho sin límite (990) |
| --- | --- | --- | --- |
| Negocios | 1 | hasta 10 | aviso a los 50, sin corte |
| Usuarios incluidos | 2 | 5 | 15 |
| Usuario extra | 49 | 79 | 79 |
| Usuarios por negocio (portal del cliente) | 2 | 2 | 2 |
| Llamadas incluidas al mes | 2 000 | 8 000 | 20 000 |
| Bloque extra de llamadas | 100 por 5 000 | 100 por 5 000 | 100 por 5 000 |
| Almacenamiento de datos | 2 GB | 10 GB | 25 GB |
| Respaldos diarios conservados | 7 | 30 | 30, más uno mensual por 12 meses |
| Auditoría en línea | 12 meses | 12 meses | 12 meses |
| Sesiones a la vez por usuario | 3 | 5 | 5 |
| Llamadas por minuto por cuenta | 60 | 120 | 120 |

La prueba gratis: 15 días, 1 negocio, 3 usuarios, 1 000 llamadas y 1 GB.
Alcanza para evaluar de verdad y no para operar un mes gratis.

### Por qué esos números

- **5 usuarios incluidos** cubren al despacho típico (el dueño y dos a cuatro
  auxiliares) sin que nadie tenga que pedir permiso para entrar. El usuario
  extra a 79 es más barato que cualquier competidor: la idea es que nadie se
  sienta robado por dar de alta a alguien.
- **800 llamadas por negocio al mes** son unas 27 al día: sobra para un negocio
  real y protege el margen de un agente en bucle que haga mil por descuido.
- **El almacenamiento casi no se toca**: los datos son texto y ocupan poco; lo
  que pesa son los documentos y los archivos exportados.
- **No hay tope duro de usuarios.** Arriba de 100 usuarios en una sola cuenta
  se avisa y se conversa: a esa altura no es un despacho chico, y suele ser
  reventa de acceso, que conviene ordenar, no prohibir.

### Las cuatro reglas que importan más que los números

1. **Aviso al 80 %, tope al 100 %.** Al llegar al tope se pausan las acciones
   nuevas; las consultas, los datos y la exportación siguen abiertos. Nunca un
   corte seco ni un cobro sorpresa.
2. **Nunca cortar la salida.** Aunque haya tope o una mensualidad vencida, el
   cliente puede exportar todo y llevárselo. Su contabilidad es suya, y en
   México hay que conservarla cinco años: el sistema no borra nada solo. Si el
   almacenamiento llega a su tope, se avisa y se ofrece archivar negocios o
   subir de plan; jamás se borra por cuenta propia.
3. **Los topes viven en el servidor.** Se comprueban ahí, no en la página de
   precios. Un tope que solo está escrito no es un tope.
4. **Subir un tope es decisión de minutos**, sin recotizar ni cambiar de plan.

### Y técnicamente, ¿aguanta?

El límite real no son los usuarios, son las llamadas a la vez y por minuto. El
limitador ya existe y hoy está en 120 por minuto con ráfaga de 30: eso aguanta
cientos de usuarios con holgura. Los topes de la tabla son comerciales y de
protección, no de capacidad.

### Cómo se aplica en el código

- Una tabla o archivo de límites por cuenta (plan, negocios, usuarios,
  llamadas, almacenamiento) en la misma base.
- Una comprobación en el servidor antes de cada herramienta: si está dentro,
  pasa; si llegó al 80 %, se deja pasar y se registra el aviso; si llegó al
  100 %, se responde con el mensaje que explica cómo subir y cómo exportar.
- Dos eventos nuevos en la auditoría: `limite_aviso` y `limite_tope`, para poder
  enseñarle al cliente por qué se detuvo y cuándo.
- El mismo archivo de límites sirve para la prueba gratis: al día 15 se
  convierte en el plan de pago sin migrar nada.
