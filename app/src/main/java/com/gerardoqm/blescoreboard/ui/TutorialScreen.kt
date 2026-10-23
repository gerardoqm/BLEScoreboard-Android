package com.gerardoqm.blescoreboard.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gerardoqm.blescoreboard.R

private data class TutorialPage(val icon: ImageVector, val title: Int, val text: Int)

private val pages = listOf(
    TutorialPage(Icons.Filled.BluetoothSearching, R.string.tutorial_1_title, R.string.tutorial_1_text),
    TutorialPage(Icons.Filled.Link, R.string.tutorial_2_title, R.string.tutorial_2_text),
    TutorialPage(Icons.Filled.Timer, R.string.tutorial_3_title, R.string.tutorial_3_text),
    TutorialPage(Icons.Filled.Tune, R.string.tutorial_4_title, R.string.tutorial_4_text),
    TutorialPage(Icons.Filled.Palette, R.string.tutorial_5_title, R.string.tutorial_5_text),
    TutorialPage(Icons.Filled.PlayCircle, R.string.tutorial_6_title, R.string.tutorial_6_text),
    TutorialPage(Icons.Filled.Watch, R.string.tutorial_7_title, R.string.tutorial_7_text),
    TutorialPage(Icons.Filled.Tv, R.string.tutorial_8_title, R.string.tutorial_8_text),
    TutorialPage(Icons.Filled.ScreenRotation, R.string.tutorial_9_title, R.string.tutorial_9_text),
    TutorialPage(Icons.Filled.SystemUpdate, R.string.tutorial_10_title, R.string.tutorial_10_text),
)

/** Paged walkthrough of the app. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TutorialScreen(onDone: () -> Unit) {
    BackHandler(onBack = onDone)
    val pager = rememberPagerState { pages.size }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tutorial)) },
                actions = { TextButton(onClick = onDone) { Text(stringResource(R.string.done)) } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            HorizontalPager(pager, Modifier.weight(1f)) { index ->
                val page = pages[index]
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Spacer(Modifier.height(24.dp))
                    Box(Modifier.size(160.dp).background(Palette.go.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(page.icon, null, tint = Palette.go, modifier = Modifier.size(84.dp))
                    }
                    Text(stringResource(page.title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Text(stringResource(page.text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
            }
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
                repeat(pages.size) { i ->
                    Box(
                        Modifier.padding(4.dp).size(8.dp).background(
                            if (i == pager.currentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape,
                        )
                    )
                }
            }
        }
    }
}
