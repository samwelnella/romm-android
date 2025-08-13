package com.romm.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

data class DisplayPattern(
    val step: Int,
    val allLetters: List<String>,
    val hiddenIndices: Set<Int>
)

sealed class DisplayItem {
    data class Letter(val letter: String) : DisplayItem()
    object Dot : DisplayItem()
}

@Composable
fun AlphabetScrubber(
    modifier: Modifier = Modifier,
    onLetterSelected: (String) -> Unit
) {
    val allLetters = listOf(
        "#", "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M",
        "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z"
    )
    
    var selectedLetter by remember { mutableStateOf<String?>(null) }
    var isDragging by remember { mutableStateOf(false) }
    var componentSize by remember { mutableStateOf(IntSize.Zero) }
    var touchY by remember { mutableStateOf(0f) }
    val density = LocalDensity.current
    
    // Calculate display pattern based on screen height with more conservative approach
    val displayPattern = remember(componentSize) {
        val availableHeight = componentSize.height
        if (availableHeight == 0) return@remember DisplayPattern(1, allLetters, emptySet())
        
        // Much more conservative calculation to ensure letters are never cut off
        // Account for font size, padding, touch target, and extra breathing room
        val minItemHeight = with(density) { 28.dp.toPx() } // Increased significantly
        val maxItemsToShow = (availableHeight / minItemHeight).toInt().coerceAtLeast(6)
        
        when {
            maxItemsToShow >= 30 -> DisplayPattern(1, allLetters, emptySet()) // Show all - very tall screens
            maxItemsToShow >= 22 -> DisplayPattern(2, allLetters, emptySet()) // Show every other
            maxItemsToShow >= 15 -> DisplayPattern(3, allLetters, emptySet()) // Show every 3rd
            maxItemsToShow >= 12 -> DisplayPattern(4, allLetters, emptySet()) // Show every 4th
            maxItemsToShow >= 9 -> DisplayPattern(5, allLetters, emptySet()) // Show every 5th
            else -> DisplayPattern(6, allLetters, emptySet()) // Show every 6th - very small screens
        }
    }
    
    // Generate display items (letters and dots) based on pattern
    val displayItems = remember(displayPattern) {
        buildDisplayItems(allLetters, displayPattern.step)
    }
    
    Box(
        modifier = modifier
            .width(32.dp) // Fixed width for the entire component
            .fillMaxHeight()
    ) {
        // The scrubber bar
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(16.dp))
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
                )
                .onGloballyPositioned { coordinates ->
                    componentSize = coordinates.size
                }
                .pointerInput(Unit) {
                    var currentY = 0f
                    detectDragGestures(
                        onDragStart = { offset ->
                            isDragging = true
                            currentY = offset.y
                            touchY = offset.y
                            // Map position to full alphabet, not just displayed items
                            val letterIndex = calculateLetterIndex(currentY, componentSize.height, allLetters.size)
                            val letter = allLetters.getOrNull(letterIndex)
                            if (letter != null) {
                                selectedLetter = letter
                                onLetterSelected(letter)
                            }
                        },
                        onDragEnd = {
                            isDragging = false
                            selectedLetter = null
                        },
                        onDrag = { _, dragAmount ->
                            // Update current position
                            currentY += dragAmount.y
                            currentY = currentY.coerceIn(0f, componentSize.height.toFloat())
                            touchY = currentY
                            // Map position to full alphabet, not just displayed items
                            val letterIndex = calculateLetterIndex(currentY, componentSize.height, allLetters.size)
                            val letter = allLetters.getOrNull(letterIndex)
                            if (letter != null && letter != selectedLetter) {
                                selectedLetter = letter
                                onLetterSelected(letter)
                            }
                        }
                    )
                }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(vertical = 8.dp, horizontal = 2.dp), // Increased padding for breathing room
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                displayItems.forEach { item ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        when (item) {
                            is DisplayItem.Letter -> {
                                val fontSize = when {
                                    displayItems.size <= 10 -> 12.sp  // Fewer items = larger text
                                    displayItems.size <= 15 -> 11.sp
                                    displayItems.size <= 20 -> 10.sp
                                    else -> 8.sp // Never go below 8sp for readability
                                }
                                
                                Text(
                                    text = item.letter,
                                    fontSize = fontSize,
                                    fontWeight = if (item.letter == selectedLetter) FontWeight.Bold else FontWeight.Normal,
                                    color = if (item.letter == selectedLetter) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    textAlign = TextAlign.Center,
                                    maxLines = 1, // Ensure single line
                                    softWrap = false // Prevent text wrapping
                                )
                            }
                            is DisplayItem.Dot -> {
                                Box(
                                    modifier = Modifier
                                        .size(3.dp)
                                        .background(
                                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                            androidx.compose.foundation.shape.CircleShape
                                        )
                                )
                            }
                        }
                    }
                }
            }
        }
        
        // Touch feedback bubble - positioned well to the left of the scrubber
        if (isDragging && selectedLetter != null) {
            Box(
                modifier = Modifier
                    .offset(x = (-80).dp, y = with(density) { touchY.toDp() - 24.dp })
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .zIndex(1f), // Slight elevation to ensure visibility
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = selectedLetter!!,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}

private fun calculateLetterIndex(yOffset: Float, componentHeight: Int, letterCount: Int): Int {
    val normalizedY = yOffset.coerceIn(0f, componentHeight.toFloat())
    val index = (normalizedY / componentHeight * letterCount).roundToInt()
    return index.coerceIn(0, letterCount - 1)
}

private fun buildDisplayItems(allLetters: List<String>, step: Int): List<DisplayItem> {
    val items = mutableListOf<DisplayItem>()
    
    allLetters.forEachIndexed { index, letter ->
        if (index % step == 0) {
            // Show this letter
            items.add(DisplayItem.Letter(letter))
            
            // Add dots for hidden letters (except for the last shown letter)
            if (index + step < allLetters.size) {
                val hiddenCount = minOf(step - 1, allLetters.size - index - 1)
                if (hiddenCount > 0) {
                    // Add a single dot to represent hidden letters
                    items.add(DisplayItem.Dot)
                }
            }
        }
    }
    
    return items
}