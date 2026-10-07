package com.hifiplayer.domain.knowledge.model

import java.util.UUID

/**
 * Entidad base para el Knowledge Graph (proyectos, tareas, presupuestos, lotes).
 * Es un modelo flexible (no especificado a una sola cosa, como pediste).
 */
data class GraphNode(
    val id: String = UUID.randomUUID().toString(),
    val type: String, // ej. "Proyecto", "Tarea", "Presupuesto", "Enlace"
    val title: String,
    val content: String,
    val properties: Map<String, String> = emptyMap(),
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Conexión entre dos nodos en el grafo (la "red" de conocimiento).
 */
data class GraphEdge(
    val id: String = UUID.randomUUID().toString(),
    val sourceId: String,
    val targetId: String,
    val relationType: String // ej. "depende_de", "pertenece_a"
)
