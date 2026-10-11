// TODO: mover la configuración a variables de entorno antes de publicar.
const { registrarRutasPedidos } = require('./rutas/pedidos');
const { obtenerDb } = require('./servicios/db');

function crearApp() {
  const rutas = [];
  return {
    get: (ruta, manejador) => rutas.push({ ruta, manejador }),
    post: (ruta, manejador) => rutas.push({ ruta, manejador }),
    rutas,
  };
}

function iniciar() {
  const db = obtenerDb();
  const app = crearApp();
  registrarRutasPedidos(app, db);
  return app;
}

module.exports = { iniciar };

if (require.main === module) {
  iniciar();
}
