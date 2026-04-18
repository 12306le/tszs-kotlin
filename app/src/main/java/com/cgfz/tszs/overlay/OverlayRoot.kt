package com.cgfz.tszs.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * 悬浮窗根 UI。用 state hoisting 把所有交互暴露给调用方。
 * 小圆点 + 可展开主面板的设计,比 AutoJS 的手搓 floaty 布局清晰。
 */
@Composable
fun OverlayRoot(
    state: OverlayUiState,
    onCapture: () -> Unit,
    onFindTest: () -> Unit,
    onCompareTest: () -> Unit,
    onSaveScript: () -> Unit,
    onDeleteColor: (Int) -> Unit,
    onClearColors: () -> Unit,
    onDrag: (dx: Float, dy: Float) -> Unit,
    onToggle: () -> Unit,
) {
    if (!state.expanded) {
        HandleDot(onToggle = onToggle, onDrag = onDrag)
    } else {
        MainPanel(
            state = state,
            onCapture = onCapture,
            onFindTest = onFindTest,
            onCompareTest = onCompareTest,
            onSaveScript = onSaveScript,
            onDeleteColor = onDeleteColor,
            onClearColors = onClearColors,
            onDrag = onDrag,
            onToggle = onToggle,
        )
    }
}

@Composable
private fun HandleDot(onToggle: () -> Unit, onDrag: (Float, Float) -> Unit) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(50))
            .background(Color(0xFFFF6789))
            .pointerInput(Unit) {
                detectDragGestures { _, d -> onDrag(d.x, d.y) }
            }
            .clickable { onToggle() },
        contentAlignment = Alignment.Center,
    ) {
        Text("图色", color = Color(0xFFFFE785))
    }
}

@Composable
private fun MainPanel(
    state: OverlayUiState,
    onCapture: () -> Unit,
    onFindTest: () -> Unit,
    onCompareTest: () -> Unit,
    onSaveScript: () -> Unit,
    onDeleteColor: (Int) -> Unit,
    onClearColors: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onToggle: () -> Unit,
) {
    Surface(
        modifier = Modifier.widthIn(max = 360.dp),
        color = Color(0xFFFF6789),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("图色助手 Kt", color = Color(0xFFFFE785))
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onToggle) { Text("收起", color = Color(0xFFFFE785)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = onCapture) { Text("截屏") }
                Button(onClick = onFindTest) { Text("找色") }
                Button(onClick = onCompareTest) { Text("比色") }
                Button(onClick = onSaveScript) { Text("保存") }
            }
            state.lastMessage?.let {
                Text(it, color = Color(0xFFFFFFFF), modifier = Modifier.padding(vertical = 4.dp))
            }
            Row(Modifier.fillMaxWidth()) {
                Text("颜色记录(${state.colors.size})", color = Color(0xFFFFE785))
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClearColors) { Text("清空", color = Color.Red) }
            }
            LazyColumn(Modifier.heightIn(max = 220.dp)) {
                itemsIndexed(state.colors) { idx, c ->
                    ColorRow(c, onDelete = { onDeleteColor(idx) })
                }
            }
        }
    }
}

@Composable
private fun ColorRow(c: ColorRecord, onDelete: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onDelete) { Text("×", color = Color(0xFFFFE785)) }
        Text("${c.x}", Modifier.width(60.dp), color = Color(0xFFFFE785))
        Text("${c.y}", Modifier.width(60.dp), color = Color(0xFFFFE785))
        Text(c.hex, Modifier.width(110.dp), color = Color(0xFFFFE785))
        Box(
            Modifier
                .size(24.dp)
                .background(Color(c.argb.toInt()))
        )
    }
}

data class ColorRecord(val x: Int, val y: Int, val argb: Long, val hex: String)
data class OverlayUiState(
    val expanded: Boolean = false,
    val colors: List<ColorRecord> = emptyList(),
    val lastMessage: String? = null,
)
