package com.example.videoplayer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.videoplayer.data.repository.MediaRepository
import com.example.videoplayer.ui.theme.CardObsidian
import com.example.videoplayer.ui.theme.ObsidianBg
import com.example.videoplayer.ui.theme.SecondaryNeonCyan
import com.example.videoplayer.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    repository: MediaRepository,
    onBackClick: () -> Unit
) {
    var skipSeconds by remember { mutableIntStateOf(repository.getSkipSeconds()) }
    var autoReplay by remember { mutableStateOf(repository.isAutoReplayEnabled()) }
    var autoPlayNext by remember { mutableStateOf(repository.isAutoPlayNextEnabled()) }
    var watchedLast by remember { mutableStateOf(repository.isWatchedLastEnabled()) }
    var isBackgroundPlayEnabled by remember { mutableStateOf(repository.isBackgroundPlayEnabled()) }
    var isPipEnabled by remember { mutableStateOf(repository.isPipEnabled()) }
    // 设置页查看教程状态
    var showPlayerTutorial by remember { mutableStateOf(false) }
    var showMainTutorial by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置", color = Color.White, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ObsidianBg)
            )
        },
        containerColor = ObsidianBg
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SettingsSection(icon = Icons.Default.PlayCircle, title = "播放") {
                Text("双击快进/后退：$skipSeconds 秒", color = Color.White, fontSize = 14.sp)
                Slider(
                    value = skipSeconds.toFloat(),
                    onValueChange = {
                        skipSeconds = it.toInt()
                        repository.setSkipSeconds(skipSeconds)
                    },
                    valueRange = 3f..60f,
                    steps = 56,
                    colors = SliderDefaults.colors(thumbColor = SecondaryNeonCyan, activeTrackColor = SecondaryNeonCyan)
                )
                SettingSwitch("单视频循环", autoReplay) {
                    autoReplay = it
                    repository.setAutoReplayEnabled(it)
                }
                SettingSwitch("列表自动连播", autoPlayNext) {
                    autoPlayNext = it
                    repository.setAutoPlayNextEnabled(it)
                }
                SettingSwitch("已看内容置底", watchedLast) {
                    watchedLast = it
                    repository.setWatchedLastEnabled(it)
                }
                SettingSwitch("开启后台播放音频", isBackgroundPlayEnabled) {
                    isBackgroundPlayEnabled = it
                    repository.setBackgroundPlayEnabled(it)
                }
                SettingSwitch("自动开启小窗播放", isPipEnabled) {
                    isPipEnabled = it
                    repository.setPipEnabled(it)
                }
            }

            SettingsSection(icon = Icons.Default.Speed, title = "手势与进度") {
                SettingInfo("右侧上下滑动", "调节音量")
                SettingInfo("左侧上下滑动", "调节亮度")
                SettingInfo("左上角下滑", "丝滑关闭当前视频")
                SettingInfo("上半部左右滑", "切换上下一个视频")
                SettingInfo("下半部左右滑", "拖拽跳转视频进度")
                SettingInfo("拖动进度条", "精确预览和定位画面")
                SettingInfo("长按画面", "临时 2.0x 播放")
            }

            SettingsSection(icon = Icons.Default.Storage, title = "媒体库") {
                SettingInfo("普通启动", "优先读取系统媒体库，避免全盘扫描")
                SettingInfo("手动刷新", "补充扫描常用目录和 TF 卡目录")
                SettingInfo("最近播放/最近添加", "自动维护虚拟文件夹")
                SettingInfo("播放列表", "在列表项或多选后加入")
                SettingInfo("文件夹视图", "记住网格/列表浏览模式")
            }

            SettingsSection(icon = Icons.Default.Memory, title = "解码与缓存") {
                SettingInfo("解码策略", "默认使用 ExoPlayer 硬件加速，遇错或 PCM S24LE 自动切到 VLC")
                SettingInfo("PCM S24LE", "已接入 VLC 兜底，播放器设置里也可手动切换")
                SettingInfo("缩略图", "内存 + 磁盘缓存，刷新后优先复用封面")
                SettingInfo("播放进度", "降低写入频率，减少播放中卡顿")
            }

            SettingsSection(icon = Icons.Default.Cast, title = "投屏") {
                SettingInfo("播放器入口", "底部工具栏已加入投屏按钮")
                SettingInfo("当前策略", "打开系统投屏/无线显示设置，避免引入大体积 SDK")
            }

            // 操作教程入口
            SettingsSection(icon = Icons.Default.Info, title = "操作教程") {
                TutorialButton(
                    text = "播放器手势教程",
                    desc = "查看播放器内音量/亮度/切换视频等手势说明",
                    onClick = { showPlayerTutorial = true }
                )
                TutorialButton(
                    text = "主界面功能教程",
                    desc = "查看网格/列表、尺寸切换、收藏、小窗播放等功能说明",
                    onClick = { showMainTutorial = true }
                )
            }
        }
    }

    // 播放器教程覆盖层
    com.example.videoplayer.ui.components.PlayerTutorialOverlay(
        visible = showPlayerTutorial,
        onDismiss = { showPlayerTutorial = false }
    )

    // 主界面教程覆盖层
    com.example.videoplayer.ui.components.MainTutorialOverlay(
        visible = showMainTutorial,
        onDismiss = { showMainTutorial = false }
    )
}

@Composable
private fun SettingsSection(
    icon: ImageVector,
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(CardObsidian)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = SecondaryNeonCyan, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        content()
    }
}

@Composable
private fun SettingSwitch(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = Color.White, fontSize = 14.sp)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SettingInfo(label: String, value: String) {
    Column {
        Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(value, color = TextSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun TutorialButton(text: String, desc: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(desc, color = TextSecondary, fontSize = 12.sp)
        }
        Icon(
            imageVector = Icons.Default.Info,
            contentDescription = null,
            tint = SecondaryNeonCyan,
            modifier = Modifier.size(20.dp)
        )
    }
}
