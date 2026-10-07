package com.hifiplayer.presentation.library.knowledge

import com.hifiplayer.domain.model.knowledge.GraphEdge
import com.hifiplayer.domain.model.knowledge.GraphNode
import com.hifiplayer.domain.model.knowledge.GraphTemplate

/**
 * Plantillas predefinidas para el Grafo de Conocimiento (Knowledge Graph).
 */
object GraphTemplates {

    val TemplateBlank = GraphTemplate(
        id = "blank",
        title = "Plantilla en Blanco (0)",
        description = "Empieza desde cero para crear tu red neuronal personalizada.",
        defaultNodes = emptyList(),
        defaultEdges = emptyList()
    )

    val TemplateExperiences = GraphTemplate(
        id = "experiencias",
        title = "Catálogo de Experiencias",
        description = "Catálogo de experiencias para empresas de todos los pedidos, lotes y casos de éxito.",
        defaultNodes = listOf(
            GraphNode(id = "n1", type = "Categoría", title = "Casos de Éxito"),
            GraphNode(id = "n2", type = "Experiencia", title = "Empresa A - Lote 500"),
            GraphNode(id = "n3", type = "Experiencia", title = "Empresa B - Logística"),
            GraphNode(id = "n4", type = "Pedido", title = "Pedido #1024"),
            GraphNode(id = "n5", type = "Lote", title = "Lote de Producción X")
        ),
        defaultEdges = listOf(
            GraphEdge(sourceId = "n1", targetId = "n2", relationType = "contiene"),
            GraphEdge(sourceId = "n1", targetId = "n3", relationType = "contiene"),
            GraphEdge(sourceId = "n2", targetId = "n4", relationType = "origen_de"),
            GraphEdge(sourceId = "n4", targetId = "n5", relationType = "compuesto_por")
        )
    )

    val TemplateProjects = GraphTemplate(
        id = "proyectos",
        title = "Gestión de Proyectos",
        description = "Organiza proyectos y desglosa en tareas y entregables.",
        defaultNodes = listOf(
            GraphNode(id = "p1", type = "Proyecto", title = "Proyecto Alpha"),
            GraphNode(id = "p2", type = "Fase", title = "Diseño"),
            GraphNode(id = "p3", type = "Fase", title = "Desarrollo"),
            GraphNode(id = "p4", type = "Tarea", title = "Wireframes"),
            GraphNode(id = "p5", type = "Tarea", title = "Base de Datos")
        ),
        defaultEdges = listOf(
            GraphEdge(sourceId = "p1", targetId = "p2", relationType = "tiene_fase"),
            GraphEdge(sourceId = "p1", targetId = "p3", relationType = "tiene_fase"),
            GraphEdge(sourceId = "p2", targetId = "p4", relationType = "requiere"),
            GraphEdge(sourceId = "p3", targetId = "p5", relationType = "requiere")
        )
    )

    val TemplateCRM = GraphTemplate(
        id = "crm",
        title = "Presupuestos y Ventas",
        description = "Mapea clientes, presupuestos aprobados y flujos de ventas.",
        defaultNodes = listOf(
            GraphNode(id = "c1", type = "Cliente", title = "Cliente Corporativo"),
            GraphNode(id = "c2", type = "Contacto", title = "Director de Compras"),
            GraphNode(id = "c3", type = "Presupuesto", title = "Cotización 2026"),
            GraphNode(id = "c4", type = "Servicio", title = "Consultoría IT")
        ),
        defaultEdges = listOf(
            GraphEdge(sourceId = "c1", targetId = "c2", relationType = "emplea"),
            GraphEdge(sourceId = "c1", targetId = "c3", relationType = "solicita"),
            GraphEdge(sourceId = "c3", targetId = "c4", relationType = "incluye")
        )
    )

    val TemplateNetwork = GraphTemplate(
        id = "red",
        title = "Red Neuronal / Topología",
        description = "Simula una red neuronal o infraestructura tecnológica.",
        defaultNodes = listOf(
            GraphNode(id = "r1", type = "Core", title = "Nodo Central"),
            GraphNode(id = "r2", type = "Nodo", title = "Capa Oculta 1"),
            GraphNode(id = "r3", type = "Nodo", title = "Capa Oculta 2"),
            GraphNode(id = "r4", type = "Salida", title = "Output A"),
            GraphNode(id = "r5", type = "Salida", title = "Output B")
        ),
        defaultEdges = listOf(
            GraphEdge(sourceId = "r1", targetId = "r2", relationType = "conecta"),
            GraphEdge(sourceId = "r1", targetId = "r3", relationType = "conecta"),
            GraphEdge(sourceId = "r2", targetId = "r4", relationType = "activa"),
            GraphEdge(sourceId = "r3", targetId = "r5", relationType = "activa")
        )
    )

    val allTemplates = listOf(
        TemplateBlank,
        TemplateExperiences,
        TemplateProjects,
        TemplateCRM,
        TemplateNetwork
    )
}
