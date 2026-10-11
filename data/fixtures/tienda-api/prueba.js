const fs = require('fs');
const db = fs.readFileSync('src/db.js', 'utf8');
const errores = [];
if (db.includes('eval(')) errores.push('eval sigue presente en src/db.js');
if (!db.includes('pool.query("SELECT id, email, rol FROM usuarios WHERE email = ?"')) errores.push('la consulta SQL no está parametrizada');
if (errores.length) {
  console.error('FALLO: ' + errores.join(' | '));
  process.exit(1);
}
console.log('2 passing');
