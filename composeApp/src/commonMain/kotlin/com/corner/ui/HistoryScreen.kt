package com.corner.ui

import com.corner.catvodcore.viewmodel.SiteViewModel
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.WindowScope
import com.corner.catvodcore.bean.Vod
import com.corner.catvodcore.viewmodel.GlobalAppState.hideProgress
import com.corner.catvodcore.viewmodel.GlobalAppState.showProgress
import com.corner.database.dao.buildVod
import com.corner.database.entity.History
import com.corner.database.entity.Keep
import com.corner.catvodcore.keep.buildVod as keepBuildVod
import com.corner.ui.nav.data.HistoryTab
import com.corner.ui.nav.vm.HistoryViewModel
import com.corner.ui.scene.BackRow
import com.corner.ui.scene.ControlBar
import com.corner.ui.scene.AutoSizeImageWithLoading
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import lumentv_compose.composeapp.generated.resources.Res
import lumentv_compose.composeapp.generated.resources.no_img

@Composable
fun HistoryItem(
    modifier: Modifier,
    history: History,
    showSite: Boolean,
    onDelete: (String) -> Unit,
    click: (History) -> Unit
) {

    // 添加删除确认状态
    var deleteConfirmed by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.clickable(enabled = true, onClick = { click(history) }),
        elevation = CardDefaults.cardElevation(8.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        ContextMenuArea(items = {
            listOf(ContextMenuItem("删除") {
                onDelete(history.key)
            })
        }) {
            // 将Box布局改为Column以垂直排列内容
            Column(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                ) {
                    AutoSizeImageWithLoading(
                        url = history.vodPic!!,
                        contentDescription = history.vodName,
                        modifier = Modifier.height(220.dp).fillMaxWidth(),
                        contentScale = ContentScale.Crop,
                        errorPainter = { painterResource(Res.drawable.no_img) },
                        loadingIndicator = {
                            CircularProgressIndicator(
                                modifier = Modifier.size(36.dp).align(Alignment.Center),
                                strokeWidth = 3.dp,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            )
                        }
                    )
                    
                    Text(
                        text = history.vodName!!,
                        modifier = Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f))
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth().padding(0.dp, 10.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
                        maxLines = 1,
                        softWrap = true,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(textAlign = TextAlign.Center)
                    )
                    Text(
                        modifier = Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .padding(5.dp),
                        text = if (showSite) history.vodFlag ?: "" else history.vodRemarks!!,
                        fontWeight = FontWeight.Bold,
                        style = TextStyle(
                            color = Color.White,
                            shadow = Shadow(Color.Black, offset = Offset(2F, 2F), blurRadius = 1.5F)
                        )
                    )
                }
                // 添加底部删除按钮
                Button(
                    onClick = {
                        if (deleteConfirmed) {
                            onDelete(history.key)
                            deleteConfirmed = false
                        } else {
                            deleteConfirmed = true
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (deleteConfirmed) Color.Red else MaterialTheme.colorScheme.background,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Icon(
                        imageVector = if (deleteConfirmed) Icons.Default.Delete else Icons.Default.Close,
                        contentDescription = if (deleteConfirmed) "确认删除" else "删除"
                    )
                }
            }
        }
    }
}

@Composable
fun KeepItem(
    modifier: Modifier,
    keep: Keep,
    onDelete: (String) -> Unit,
    click: (Keep) -> Unit
) {
    var deleteConfirmed by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.clickable(enabled = true, onClick = { click(keep) }),
        elevation = CardDefaults.cardElevation(8.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        ContextMenuArea(items = {
            listOf(ContextMenuItem("删除") {
                onDelete(keep.key)
            })
        }) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                ) {
                    AutoSizeImageWithLoading(
                        url = keep.vodPic.orEmpty(),
                        contentDescription = keep.vodName,
                        modifier = Modifier.height(220.dp).fillMaxWidth(),
                        contentScale = ContentScale.Crop,
                        errorPainter = { painterResource(Res.drawable.no_img) },
                        loadingIndicator = {
                            CircularProgressIndicator(
                                modifier = Modifier.size(36.dp).align(Alignment.Center),
                                strokeWidth = 3.dp,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            )
                        }
                    )

                    Text(
                        text = keep.vodName.orEmpty(),
                        modifier = Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f))
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth().padding(0.dp, 10.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
                        maxLines = 1,
                        softWrap = true,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(textAlign = TextAlign.Center)
                    )
                    Text(
                        modifier = Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .padding(5.dp),
                        text = keep.siteName.orEmpty(),
                        fontWeight = FontWeight.Bold,
                        style = TextStyle(
                            color = Color.White,
                            shadow = Shadow(Color.Black, offset = Offset(2F, 2F), blurRadius = 1.5F)
                        )
                    )
                }
                Button(
                    onClick = {
                        if (deleteConfirmed) {
                            onDelete(keep.key)
                            deleteConfirmed = false
                        } else {
                            deleteConfirmed = true
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (deleteConfirmed) Color.Red else MaterialTheme.colorScheme.background,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Icon(
                        imageVector = if (deleteConfirmed) Icons.Default.Delete else Icons.Default.Close,
                        contentDescription = if (deleteConfirmed) "确认删除" else "删除"
                    )
                }
            }
        }
    }
}

@Composable
fun WindowScope.HistoryScene(vm: HistoryViewModel, onClickItem: (Vod) -> Unit, onClickBack: () -> Unit) {
    val model = vm.state.collectAsState()
    var chooseHistory by remember { mutableStateOf<History?>(null) }
    val selectedTab = model.value.selectedTab

    LaunchedEffect(Unit) {
        showProgress()
        SiteViewModel.viewModelScope.launch {
            try {
                vm.fetchHistoryList()
                vm.fetchKeepList()
            } finally {
                hideProgress()
            }
        }
    }
    Box(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
            WindowDraggableArea {
                ControlBar(
                    leading = {
                        BackRow(modifier = Modifier.align(Alignment.Start), { onClickBack() }) {
                            Row(
                                modifier = Modifier.fillMaxWidth().align(Alignment.CenterHorizontally),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (selectedTab == HistoryTab.HISTORY) "历史记录" else "我的收藏",
                                    style = MaterialTheme.typography.headlineMedium.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        letterSpacing = 0.15.sp,
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    },
                    actions = {
                        var showConfirmDialog by remember { mutableStateOf(false) }

                        // 主按钮
                        FilledTonalButton(
                            modifier = Modifier
                                .height(40.dp)
                                .padding(end = 12.dp),
                            onClick = { showConfirmDialog = true },
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            ),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                            )
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "清空全部",
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    "清空",
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }

                        // 确认对话框
                        if (showConfirmDialog) {
                            AlertDialog(
                                onDismissRequest = { showConfirmDialog = false },
                                title = {
                                    Text(
                                        if (selectedTab == HistoryTab.HISTORY) "确认清空历史记录" else "确认清空收藏",
                                        style = MaterialTheme.typography.headlineSmall
                                    )
                                },
                                text = {
                                    Text(
                                        if (selectedTab == HistoryTab.HISTORY) {
                                            "此操作将永久删除所有历史记录，且不可恢复。"
                                        } else {
                                            "此操作将永久删除所有收藏记录，且不可恢复。"
                                        },
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                },
                                confirmButton = {
                                    FilledTonalButton(
                                        onClick = {
                                            showConfirmDialog = false
                                            if (selectedTab == HistoryTab.HISTORY) {
                                                vm.clearHistory()
                                                vm.fetchHistoryList()
                                            } else {
                                                vm.clearKeep()
                                            }
                                        },
                                        colors = ButtonDefaults.filledTonalButtonColors(
                                            containerColor = MaterialTheme.colorScheme.error,
                                            contentColor = MaterialTheme.colorScheme.onError
                                        ),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text("确认清空")
                                    }
                                },
                                dismissButton = {
                                    OutlinedButton(
                                        onClick = { showConfirmDialog = false },
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text("取消")
                                    }
                                }
                            )
                        }
                    }
                )
            }
            TabRow(
                selectedTabIndex = selectedTab.ordinal,
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                Tab(
                    selected = selectedTab == HistoryTab.HISTORY,
                    onClick = { vm.selectTab(HistoryTab.HISTORY) },
                    text = { Text("历史记录") }
                )
                Tab(
                    selected = selectedTab == HistoryTab.KEEP,
                    onClick = { vm.selectTab(HistoryTab.KEEP) },
                    text = { Text("我的收藏") }
                )
            }
            val gridState = remember { LazyGridState() }
            LazyVerticalGrid(
                modifier = Modifier.padding(horizontal = 10.dp),
                columns = GridCells.Adaptive(140.dp),
                state = gridState,
                contentPadding = PaddingValues(5.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                userScrollEnabled = true,
            ) {
                if (selectedTab == HistoryTab.HISTORY) {
                    itemsIndexed(items = model.value.historyList) { _: Int, it: History ->
                        HistoryItem(
                            Modifier,
                            it, showSite = false, onDelete = { key ->
                                vm.deleteBatchHistory(listOf(key))
                            }) {
                            onClickItem(it.buildVod())
                            chooseHistory = it
                        }
                    }
                } else {
                    itemsIndexed(items = model.value.keepList) { _: Int, it: Keep ->
                        KeepItem(
                            Modifier,
                            it,
                            onDelete = { key -> vm.deleteKeep(key) }
                        ) {
                            onClickItem(it.keepBuildVod())
                        }
                    }
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(gridState))
        }
    }
}