package com.hifiplayer.domain.model.knowledge

import java.util.UUID

/**
 * Entidad base para el Knowledge Graph (proyectos, tareas, presupuestos, lotes).
 */
data class GraphNode(
    val id: String = UUID.randomUUID().toString(),
    val type: String, // ej. "Proyecto", "Tarea", "Presupuesto", "Enlace"
    val title: String,
    val content: String = "",
    val properties: Map<String, String> = emptyMap(),
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Conexión entre dos nodos en el grafo.
 */
data class GraphEdge(
    val id: String = UUID.randomUUID().toString(),
    val sourceId: String,
    val targetId: String,
    val relationType: String // ej. "depende_de", "pertenece_a"
)

/**
 * Plantilla predefinida para iniciar un grafo.
 */
data class GraphTemplate(
    val id: String,
    val title: String,
    val description: String,
    val defaultNodes: List<GraphNode>,
    val defaultEdges: List<GraphEdge>
)
