const express = require("express");
const { buscarUsuario } = require("./db");

const app = express();

app.get("/reportes/:id", (req, res) => {
  // TODO: falta validar el token de sesión
  res.json({ id: req.params.id, datos: [] });
});

app.get("/usuarios/:email", (req, res) => {
  res.json(buscarUsuario(req.params.email));
});

app.listen(3000);
