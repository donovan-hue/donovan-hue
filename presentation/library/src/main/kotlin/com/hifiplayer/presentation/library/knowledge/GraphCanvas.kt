package com.hifiplayer.presentation.library.knowledge

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.hifiplayer.domain.model.knowledge.GraphEdge
import com.hifiplayer.domain.model.knowledge.GraphNode
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun GraphCanvas(
    nodes: List<GraphNode>,
    edges: List<GraphEdge>
) {
    val nodePositions = remember(nodes) { mutableStateMapOf<String, Offset>() }
    var canvasSize by remember { mutableStateOf(Offset.Zero) }

    val primaryColor = MaterialTheme.colorScheme.primary
    val lineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (canvasSize == Offset.Zero) {
                canvasSize = Offset(size.width, size.height)
                val center = Offset(size.width / 2, size.height / 2)
                val radius = minOf(size.width, size.height) * 0.35f
                nodes.forEachIndexed { index, node ->
                    val angle = (2 * Math.PI * index) / maxOf(1, nodes.size)
                    val x = center.x + radius * cos(angle).toFloat()
                    val y = center.y + radius * sin(angle).toFloat()
                    nodePositions[node.id] = Offset(x, y)
                }
            }

            // Dibuja las conexiones
            edges.forEach { edge ->
                val start = nodePositions[edge.sourceId]
                val end = nodePositions[edge.targetId]
                if (start != null && end != null) {
                    drawLine(color = lineColor, start = start, end = end, strokeWidth = 3f)
                }
            }

            // Dibuja los nodos
            nodePositions.forEach { (_, pos) ->
                drawCircle(color = primaryColor, radius = 30f, center = pos)
            }
        }

        // Overlay interactivo para arrastrar nodos y mostrar títulos
        nodePositions.forEach { (id, pos) ->
            val node = nodes.find { it.id == id }
            if (node != null) {
                Box(
                    modifier = Modifier
                        .offset { IntOffset(pos.x.toInt() - 50, pos.y.toInt() - 50) }
                        .size(100.dp)
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                val current = nodePositions[id] ?: return@detectDragGestures
                                nodePositions[id] = current + dragAmount
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = node.title,
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.offset(y = 40.dp)
                    )
                }
            }
        }
    }
}
