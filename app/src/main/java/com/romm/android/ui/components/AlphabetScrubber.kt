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
import kotlin.math.roundToInt

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
    val density = LocalDensity.current
    
    // Dynamically determine which letters to show based on screen height
    val letters = remember(componentSize) {
        val availableHeight = componentSize.height
        if (availableHeight == 0) return@remember allLetters
        
        // Be more aggressive - calculate space needed per letter with more padding
        // Account for font size, padding, and visual breathing room
        val minLetterHeight = with(density) { 22.dp.toPx() } // Increased from 16dp to 22dp
        val maxLettersToShow = (availableHeight / minLetterHeight).toInt().coerceAtLeast(8)
        
        when {
            maxLettersToShow >= 27 -> allLetters
            maxLettersToShow >= 20 -> {
                // Skip some less common letters
                listOf("#", "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P", "R", "S", "T", "U", "V", "W", "Y", "Z")
            }
            maxLettersToShow >= 16 -> {
                // Show most vowels and common consonants
                listOf("#", "A", "B", "C", "D", "E", "F", "G", "H", "I", "K", "L", "M", "N", "O", "P", "R", "S", "T", "U", "W", "Z")
            }
            maxLettersToShow >= 14 -> {
                // Skip more consonants
                listOf("#", "A", "C", "E", "G", "I", "K", "M", "O", "Q", "S", "U", "W", "Y")
            }
            maxLettersToShow >= 12 -> {
                // Even more aggressive culling
                listOf("#", "A", "C", "F", "I", "L", "O", "R", "U", "X")
            }
            else -> {
                // Very small screens - minimal set
                listOf("#", "A", "F", "L", "R", "X")
            }
        }
    }
    
    Box(
        modifier = modifier
            .width(32.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (isDragging) {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                }
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
                        val letterIndex = calculateLetterIndex(currentY, componentSize.height, letters.size)
                        val letter = letters.getOrNull(letterIndex)
                        if (letter != null) {
                            selectedLetter = letter
                            // Map displayed letter to closest actual letter for scrolling
                            val targetLetter = findClosestActualLetter(letter, allLetters)
                            onLetterSelected(targetLetter)
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
                        val letterIndex = calculateLetterIndex(currentY, componentSize.height, letters.size)
                        val letter = letters.getOrNull(letterIndex)
                        if (letter != null && letter != selectedLetter) {
                            selectedLetter = letter
                            // Map displayed letter to closest actual letter for scrolling
                            val targetLetter = findClosestActualLetter(letter, allLetters)
                            onLetterSelected(targetLetter)
                        }
                    }
                )
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            letters.forEach { letter ->
                val fontSize = when {
                    letters.size <= 10 -> 12.sp
                    letters.size <= 15 -> 11.sp
                    letters.size <= 20 -> 10.sp
                    else -> 9.sp
                }
                
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = letter,
                        fontSize = fontSize,
                        fontWeight = if (letter == selectedLetter) FontWeight.Bold else FontWeight.Normal,
                        color = if (letter == selectedLetter) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
    
    // Show selected letter overlay when dragging
    if (isDragging && selectedLetter != null) {
        Box(
            modifier = Modifier
                .offset(x = (-60).dp)
                .size(48.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = selectedLetter!!,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}

private fun calculateLetterIndex(yOffset: Float, componentHeight: Int, letterCount: Int): Int {
    val normalizedY = yOffset.coerceIn(0f, componentHeight.toFloat())
    val index = (normalizedY / componentHeight * letterCount).roundToInt()
    return index.coerceIn(0, letterCount - 1)
}

private fun findClosestActualLetter(displayedLetter: String, allLetters: List<String>): String {
    // If the displayed letter exists in all letters, return it
    if (allLetters.contains(displayedLetter)) {
        return displayedLetter
    }
    
    // Otherwise, find the closest letter alphabetically
    val displayedIndex = when (displayedLetter) {
        "#" -> -1
        else -> displayedLetter.first().code - 'A'.code
    }
    
    // Find the closest letter that actually exists
    for (letter in allLetters) {
        val letterIndex = when (letter) {
            "#" -> -1
            else -> letter.first().code - 'A'.code
        }
        
        if (letterIndex >= displayedIndex) {
            return letter
        }
    }
    
    // If no letter found ahead, return the last one
    return allLetters.lastOrNull() ?: "#"
}