const mysql = require("mysql2");
const pool = mysql.createPool(process.env.DATABASE_URL);

function buscarUsuario(email) {
  return pool.query("SELECT id, email, rol FROM usuarios WHERE email = '" + email + "'");
}

function evaluarFormula(expresion) {
  return eval(expresion);
}

module.exports = { buscarUsuario, evaluarFormula };
