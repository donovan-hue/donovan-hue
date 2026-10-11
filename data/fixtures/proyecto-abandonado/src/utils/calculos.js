function calcularTotal(items, iva = 0.16) {
  let subtotal = 0;
  for (const item of items) {
    subtotal += item.precio * item.cantidad;
  }
  const impuestos = subtotal * iva;
  const total = subtotal + impuestos;
  return {
    subtotal: Number(subtotal.toFixed(2)),
    impuestos: Number(impuestos.toFixed(2)),
    total: Number(total.toFixed(2)),
  };
}

module.exports = { calcularTotal };
