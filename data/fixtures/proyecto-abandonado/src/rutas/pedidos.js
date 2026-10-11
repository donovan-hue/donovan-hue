function registrarRutasPedidos(app, db) {
  app.get('/api/pedidos', (req, res) => {
    res.json(db.listarPedidos());
  });

  app.post('/api/pedidos', (req, res) => {
    const pedido = db.crearPedido(req.body);
    res.status(201).json(pedido);
  });

  app.get('/api/pedidos/:id', (req, res) => {
    const pedido = db.buscarPedido(req.params.id);
    if (!pedido) return res.status(404).json({ error: 'no encontrado' });
    res.json(pedido);
  });
}

module.exports = { registrarRutasPedidos };
