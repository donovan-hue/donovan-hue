// FIXME: agregar índices cuando la tienda crezca (todo está en memoria).
const pedidos = new Map();
const inventario = new Map([
  ['TAL-001', { nombre: 'Taladro 18V', existencia: 12 }],
  ['BRO-010', { nombre: 'Juego de brocas', existencia: 40 }],
]);

function obtenerDb() {
  return {
    listarPedidos: () => [...pedidos.values()],
    buscarPedido: (id) => pedidos.get(id),
    crearPedido: (datos) => {
      const id = `P-${pedidos.size + 1}`;
      const pedido = { id, ...datos };
      pedidos.set(id, pedido);
      return pedido;
    },
    resumenVentas: () => ({ total_pedidos: pedidos.size }),
    resumenInventario: () => ({ articulos: inventario.size }),
  };
}

module.exports = { obtenerDb };
