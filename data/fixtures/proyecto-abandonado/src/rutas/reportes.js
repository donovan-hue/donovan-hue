// FIXME: este módulo nunca se conectó al servidor principal.
function registrarRutasReportes(app, db) {
  app.get('/api/reportes/ventas', (req, res) => {
    res.json(db.resumenVentas());
  });

  app.get('/api/reportes/inventario', (req, res) => {
    res.json(db.resumenInventario());
  });
}

module.exports = { registrarRutasReportes };
