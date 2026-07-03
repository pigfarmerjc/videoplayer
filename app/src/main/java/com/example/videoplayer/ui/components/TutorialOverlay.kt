package com.example.videoplayer.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.videoplayer.ui.theme.PrimaryNeonPurple
import com.example.videoplayer.ui.theme.SecondaryNeonCyan

// ──────────────────────────────────────────────────────────────────────────────
// 播放器手势教程覆盖层
// 展示分区域操作说明（首次播放自动显示，设置里可再次查看）
// ──────────────────────────────────────────────────────────────────────────────
@Composable
fun PlayerTutorialOverlay(
    visible: Boolean,
    onDismiss: () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = androidx.compose.animation.core.tween(350)),
        exit = fadeOut(animationSpec = androidx.compose.animation.core.tween(250))
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.88f))
                .pointerInput(Unit) {
                    // 拦截所有触摸，防止穿透到播放器
                    detectTapGestures { onDismiss() }
                }
        ) {
            // ── 顶部标题 ──
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(top = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "手势操作说明",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "点击任意位置关闭",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 13.sp
                )
            }

            // ── 中央区域分割线说明 ──
            Box(modifier = Modifier.fillMaxSize()) {
                // 左上角 — 下拉关闭区域
                TutorialBadge(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 24.dp, top = 100.dp),
                    icon = Icons.Default.KeyboardArrowDown,
                    title = "左上角下滑",
                    desc = "丝滑关闭当前视频",
                    color = Color(0xFFEC4899)
                )

                // 左侧中央 — 亮度
                TutorialBadge(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 16.dp),
                    icon = Icons.Default.BrightnessMedium,
                    title = "左侧上下滑",
                    desc = "调节屏幕亮度",
                    color = Color(0xFFF59E0B)
                )

                // 右侧中央 — 音量
                TutorialBadge(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 16.dp),
                    icon = Icons.AutoMirrored.Filled.VolumeUp,
                    title = "右侧上下滑",
                    desc = "调节音量",
                    color = SecondaryNeonCyan,
                    alignEnd = true
                )

                // 上半部分 — 拖拽进度
                TutorialBadge(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 130.dp),
                    icon = Icons.Default.SlowMotionVideo,
                    title = "上半部 左右滑",
                    desc = "拖拽跳转视频进度",
                    color = Color(0xFF10B981)
                )

                // 下半部分 — 左右切换视频
                TutorialBadge(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 130.dp),
                    icon = Icons.Default.SwapHoriz,
                    title = "下半部 左右滑",
                    desc = "切换上下一个视频",
                    color = PrimaryNeonPurple
                )

                // 左侧快退
                TutorialBadge(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 24.dp, bottom = 180.dp),
                    icon = Icons.Default.Replay10,
                    title = "左侧双击",
                    desc = "快退 N 秒",
                    color = Color(0xFF6366F1)
                )

                // 右侧快进
                TutorialBadge(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 24.dp, bottom = 180.dp),
                    icon = Icons.Default.Forward10,
                    title = "右侧双击",
                    desc = "快进 N 秒",
                    color = Color(0xFF6366F1),
                    alignEnd = true
                )

                // 长按 2x 速
                TutorialBadge(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset(y = 40.dp),
                    icon = Icons.Default.FastForward,
                    title = "长按画面",
                    desc = "临时 2.0x 倍速播放",
                    color = Color(0xFFF97316)
                )
            }

            // ── 底部关闭按钮 ──
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 40.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(
                        Brush.linearGradient(listOf(PrimaryNeonPurple, SecondaryNeonCyan))
                    )
                    .clickable { onDismiss() }
                    .padding(horizontal = 36.dp, vertical = 12.dp)
            ) {
                Text(
                    text = "我知道了",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// 主界面功能教程覆盖层
// ──────────────────────────────────────────────────────────────────────────────
@Composable
fun MainTutorialOverlay(
    visible: Boolean,
    onDismiss: () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = androidx.compose.animation.core.tween(350)),
        exit = fadeOut(animationSpec = androidx.compose.animation.core.tween(250))
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.88f))
                .pointerInput(Unit) {
                    detectTapGestures { onDismiss() }
                }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(top = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "欢迎使用黑猫播放器",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "点击任意位置关闭",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 13.sp
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.Center)
                    .padding(horizontal = 32.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                MainTutorialItem(
                    icon = Icons.Default.GridView,
                    color = SecondaryNeonCyan,
                    title = "网格/列表切换",
                    desc = "顶部工具栏图标，视频/音频 Tab 下可切换网格或列表显示"
                )
                MainTutorialItem(
                    icon = Icons.Default.TextFields,
                    color = Color(0xFFF59E0B),
                    title = "网格尺寸（S/M/L）",
                    desc = "网格模式下，工具栏出现 S/M/L 按钮，可切换缩略图大小"
                )
                MainTutorialItem(
                    icon = Icons.Default.Folder,
                    color = PrimaryNeonPurple,
                    title = "文件夹视图",
                    desc = "按文件夹分组浏览，长按媒体文件可多选批量操作"
                )
                MainTutorialItem(
                    icon = Icons.Default.Star,
                    color = Color(0xFFF97316),
                    title = "收藏与播放列表",
                    desc = "在列表项右侧收藏视频，或多选后加入播放列表"
                )
                MainTutorialItem(
                    icon = Icons.Default.PictureInPicture,
                    color = Color(0xFF10B981),
                    title = "悬浮小窗",
                    desc = "播放时按返回键或按小窗按钮，可开启悬浮小窗；双指捏合可缩放"
                )
                MainTutorialItem(
                    icon = Icons.Default.Settings,
                    color = Color(0xFF6366F1),
                    title = "设置",
                    desc = "主页右上角设置图标，可调整快进秒数、解码策略等"
                )
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 40.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(
                        Brush.linearGradient(listOf(PrimaryNeonPurple, SecondaryNeonCyan))
                    )
                    .clickable { onDismiss() }
                    .padding(horizontal = 36.dp, vertical = 12.dp)
            ) {
                Text(
                    text = "开始使用",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// ── 共用子组件 ──

@Composable
private fun TutorialBadge(
    modifier: Modifier,
    icon: ImageVector,
    title: String,
    desc: String,
    color: Color,
    alignEnd: Boolean = false
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start
    ) {
        if (!alignEnd) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(10.dp))
        }
        Column(horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
            Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(desc, color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp, textAlign = if (alignEnd) TextAlign.End else TextAlign.Start)
        }
        if (alignEnd) {
            Spacer(Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
            }
        }
    }
}

@Composable
private fun MainTutorialItem(
    icon: ImageVector,
    color: Color,
    title: String,
    desc: String
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(color.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(desc, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
        }
    }
}
