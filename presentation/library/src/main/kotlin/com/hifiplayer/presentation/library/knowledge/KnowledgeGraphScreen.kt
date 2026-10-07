package com.hifiplayer.presentation.library.knowledge

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hifiplayer.core.designsystem.theme.HiFiTheme
import com.hifiplayer.domain.model.knowledge.GraphEdge
import com.hifiplayer.domain.model.knowledge.GraphNode
import com.hifiplayer.domain.model.knowledge.GraphTemplate
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnowledgeGraphScreen(
    onBackClick: () -> Unit
) {
    var selectedTemplate by remember { mutableStateOf<GraphTemplate?>(null) }
    var nodes by remember { mutableStateOf<List<GraphNode>>(emptyList()) }
    var edges by remember { mutableStateOf<List<GraphEdge>>(emptyList()) }
    var showUrlDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selectedTemplate == null) "Elegir Plantilla" else selectedTemplate?.title ?: "Grafo Neuronal") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selectedTemplate != null) selectedTemplate = null else onBackClick()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Atrás")
                    }
                },
                actions = {
                    if (selectedTemplate != null) {
                        IconButton(onClick = { showUrlDialog = true }) {
                            Icon(Icons.Default.AddLink, contentDescription = "Añadir Enlace")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (selectedTemplate == null) {
                TemplateSelector(
                    templates = GraphTemplates.allTemplates,
                    onTemplateSelected = {
                        selectedTemplate = it
                        nodes = it.defaultNodes
                        edges = it.defaultEdges
                    }
                )
            } else {
                GraphCanvas(nodes = nodes, edges = edges)
            }
        }

        if (showUrlDialog) {
            AlertDialog(
                onDismissRequest = { showUrlDialog = false },
                title = { Text("Procesar Enlace Neuronal") },
                text = { Text("Introduce un enlace web para que la red extraiga información y construya los nodos automáticamente. (En desarrollo)") },
                confirmButton = {
                    TextButton(onClick = { showUrlDialog = false }) { Text("Simular") }
                },
                dismissButton = {
                    TextButton(onClick = { showUrlDialog = false }) { Text("Cancelar") }
                }
            )
        }
    }
}

@Composable
fun TemplateSelector(
    templates: List<GraphTemplate>,
    onTemplateSelected: (GraphTemplate) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(templates) { template ->
            Card(
                onClick = { onTemplateSelected(template) },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                    Text(text = template.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = template.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = "${template.defaultNodes.size} nodos · ${template.defaultEdges.size} conexiones", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
