package com.example.videoplayer

import android.app.PendingIntent
import android.app.RemoteAction
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Bundle
import android.net.Uri
import com.example.videoplayer.service.FloatingPlayerService
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavHostController
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntOffset
import com.example.videoplayer.data.model.MediaItem
import com.example.videoplayer.data.repository.MediaRepository
import com.example.videoplayer.ui.screens.*
import com.example.videoplayer.ui.theme.ObsidianBg
import com.example.videoplayer.ui.theme.VideoPlayerTheme

class MainActivity : ComponentActivity() {
    companion object {
        private const val ACTION_PIP_PLAY_PAUSE = "com.example.videoplayer.PIP_PLAY_PAUSE"
        private const val ACTION_PIP_PREV = "com.example.videoplayer.PIP_PREV"
        private const val ACTION_PIP_NEXT = "com.example.videoplayer.PIP_NEXT"

        val isInPipMode = mutableStateOf(false)
        @Volatile var isVideoPlaying = false
        @Volatile var pipCanPrev = false
        @Volatile var pipCanNext = false
    }

    private lateinit var repository: MediaRepository
    private val pendingRestoreState = mutableStateOf(false)
    private var navController: NavHostController? = null

    private val pipReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            handlePipIntent(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val filter = android.content.IntentFilter().apply {
            addAction(ACTION_PIP_PLAY_PAUSE)
            addAction(ACTION_PIP_PREV)
            addAction(ACTION_PIP_NEXT)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(pipReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(pipReceiver, filter)
        }

        // Initialize the repository
        repository = MediaRepository(applicationContext)

        if (intent?.getBooleanExtra("EXTRA_RESTORE_FLOATING", false) == true) {
            pendingRestoreState.value = true
        }

        setContent {
            VideoPlayerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = ObsidianBg
                ) {
                    val navController = rememberNavController()
                    this@MainActivity.navController = navController
                    
                    // Restore from floating player if active
                    androidx.compose.runtime.LaunchedEffect(navController, pendingRestoreState.value) {
                        if (pendingRestoreState.value || FloatingPlayerManager.isFloating) {
                            val currentItem = FloatingPlayerManager.playlist.getOrNull(FloatingPlayerManager.currentIndex)
                            if (currentItem != null) {
                                pendingRestoreState.value = false
                                FloatingPlayerManager.isFloating = false
                                // Stop the floating service
                                val serviceIntent = Intent(applicationContext, FloatingPlayerService::class.java)
                                stopService(serviceIntent)
                                // Navigate to video player screen
                                navController.navigate("video/${currentItem.id}/${Uri.encode(currentItem.folderName)}")
                            }
                        }
                    }
                    
                    // Stateful list of media files loaded in-memory to share across players
                    val mediaItemsState = remember { mutableStateOf<List<MediaItem>>(emptyList()) }
                    val mainSelectedTab = rememberSaveable { mutableIntStateOf(0) }

                    // iOS slide-transition configurations
                    val slideTween = tween<IntOffset>(durationMillis = 350)
                    val fadeTween = tween<Float>(durationMillis = 350)
                    val scaleTween = tween<Float>(durationMillis = 300)

                    NavHost(
                        navController = navController,
                        startDestination = "main"
                    ) {
                        // Main Tabbed Dashboard Screen
                        composable(
                            route = "main",
                            exitTransition = {
                                slideOutHorizontally(targetOffsetX = { -it / 3 }, animationSpec = slideTween) + fadeOut(animationSpec = fadeTween)
                            },
                            popEnterTransition = {
                                if (initialState.destination.route?.startsWith("video") == true) {
                                    fadeIn(animationSpec = fadeTween)
                                } else {
                                    slideInHorizontally(initialOffsetX = { -it / 3 }, animationSpec = slideTween) + fadeIn(animationSpec = fadeTween)
                                }
                            }
                        ) {
                            MainScreen(
                                repository = repository,
                                selectedTab = mainSelectedTab.intValue,
                                onSelectedTabChange = { mainSelectedTab.intValue = it },
                                onNavigateToFolder = { folderName ->
                                    navController.navigate("folder/${Uri.encode(folderName)}")
                                },
                                onNavigateToVideo = { itemId, folderName ->
                                    navController.navigate("video/$itemId/${Uri.encode(folderName)}")
                                },
                                onNavigateToAudio = { itemId, folderName ->
                                    navController.navigate("audio/$itemId/${Uri.encode(folderName)}")
                                },
                                onNavigateToPhoto = { itemId, folderName ->
                                    navController.navigate("photo/$itemId/${Uri.encode(folderName)}")
                                },
                                onNavigateToSettings = {
                                    navController.navigate("settings")
                                },
                                onMediaItemsLoaded = { mediaItemsState.value = it }
                            )
                        }

                        composable(
                            route = "settings",
                            enterTransition = {
                                slideInHorizontally(initialOffsetX = { it }, animationSpec = slideTween) + fadeIn(animationSpec = fadeTween)
                            },
                            exitTransition = {
                                slideOutHorizontally(targetOffsetX = { -it / 3 }, animationSpec = slideTween) + fadeOut(animationSpec = fadeTween)
                            },
                            popEnterTransition = {
                                slideInHorizontally(initialOffsetX = { -it / 3 }, animationSpec = slideTween) + fadeIn(animationSpec = fadeTween)
                            },
                            popExitTransition = {
                                slideOutHorizontally(targetOffsetX = { it }, animationSpec = slideTween) + fadeOut(animationSpec = fadeTween)
                            }
                        ) {
                            SettingsScreen(
                                repository = repository,
                                onBackClick = { navController.popBackStack() }
                            )
                        }

                        // Folder Depth Screen
                        composable(
                            route = "folder/{folderName}",
                            arguments = listOf(navArgument("folderName") { type = NavType.StringType }),
                            enterTransition = {
                                slideInHorizontally(initialOffsetX = { it }, animationSpec = slideTween) + fadeIn(animationSpec = fadeTween)
                            },
                            exitTransition = {
                                slideOutHorizontally(targetOffsetX = { -it / 3 }, animationSpec = slideTween) + fadeOut(animationSpec = fadeTween)
                            },
                            popEnterTransition = {
                                if (initialState.destination.route?.startsWith("video") == true) {
                                    fadeIn(animationSpec = fadeTween)
                                } else {
                                    slideInHorizontally(initialOffsetX = { -it / 3 }, animationSpec = slideTween) + fadeIn(animationSpec = fadeTween)
                                }
                            },
                            popExitTransition = {
                                slideOutHorizontally(targetOffsetX = { it }, animationSpec = slideTween) + fadeOut(animationSpec = fadeTween)
                            }
                        ) { backStackEntry ->
                            val folderName = backStackEntry.arguments?.getString("folderName") ?: ""
                            FolderDetailScreen(
                                folderName = folderName,
                                mediaItems = mediaItemsState.value,
                                repository = repository,
                                onBackClick = { navController.popBackStack() },
                                onNavigateToVideo = { itemId, fName ->
                                    navController.navigate("video/$itemId/${Uri.encode(fName)}")
                                },
                                onNavigateToAudio = { itemId, fName ->
                                    navController.navigate("audio/$itemId/${Uri.encode(fName)}")
                                },
                                onNavigateToPhoto = { itemId, fName ->
                                    navController.navigate("photo/$itemId/${Uri.encode(fName)}")
                                }
                            )
                        }

                        // Immersive Video Screen (Wraps Media3 Player - Apple TV fade & scale style)
                        composable(
                            route = "video/{itemId}/{folderName}",
                            arguments = listOf(
                                navArgument("itemId") { type = NavType.LongType },
                                navArgument("folderName") { type = NavType.StringType }
                            ),
                            enterTransition = {
                                fadeIn(animationSpec = fadeTween) + scaleIn(initialScale = 0.94f, animationSpec = scaleTween)
                            },
                            exitTransition = {
                                fadeOut(animationSpec = fadeTween) + scaleOut(targetScale = 0.94f, animationSpec = scaleTween)
                            },
                            popEnterTransition = {
                                fadeIn(animationSpec = fadeTween)
                            },
                            popExitTransition = {
                                fadeOut(animationSpec = fadeTween) + scaleOut(targetScale = 0.94f, animationSpec = scaleTween)
                            }
                        ) { backStackEntry ->
                            val itemId = backStackEntry.arguments?.getLong("itemId") ?: 0L
                            val folderName = backStackEntry.arguments?.getString("folderName") ?: ""
                            VideoPlayerScreen(
                                itemId = itemId,
                                folderName = folderName,
                                mediaItems = mediaItemsState.value,
                                repository = repository,
                                onBackClick = { navController.popBackStack() }
                            )
                        }

                        // Futuristic Equalized Audio Screen (Fade & Scale)
                        composable(
                            route = "audio/{itemId}/{folderName}",
                            arguments = listOf(
                                navArgument("itemId") { type = NavType.LongType },
                                navArgument("folderName") { type = NavType.StringType }
                            ),
                            enterTransition = {
                                fadeIn(animationSpec = fadeTween) + scaleIn(initialScale = 0.94f, animationSpec = scaleTween)
                            },
                            exitTransition = {
                                fadeOut(animationSpec = fadeTween) + scaleOut(targetScale = 0.94f, animationSpec = scaleTween)
                            },
                            popEnterTransition = {
                                fadeIn(animationSpec = fadeTween)
                            },
                            popExitTransition = {
                                fadeOut(animationSpec = fadeTween) + scaleOut(targetScale = 0.94f, animationSpec = scaleTween)
                            }
                        ) { backStackEntry ->
                            val itemId = backStackEntry.arguments?.getLong("itemId") ?: 0L
                            val folderName = backStackEntry.arguments?.getString("folderName") ?: ""
                            AudioPlayerScreen(
                                itemId = itemId,
                                folderName = folderName,
                                mediaItems = mediaItemsState.value,
                                repository = repository,
                                onBackClick = { navController.popBackStack() }
                            )
                        }

                        // Pinch Zoom Gallery View (Fade & Scale)
                        composable(
                            route = "photo/{itemId}/{folderName}",
                            arguments = listOf(
                                navArgument("itemId") { type = NavType.LongType },
                                navArgument("folderName") { type = NavType.StringType }
                            ),
                            enterTransition = {
                                fadeIn(animationSpec = fadeTween) + scaleIn(initialScale = 0.94f, animationSpec = scaleTween)
                            },
                            exitTransition = {
                                fadeOut(animationSpec = fadeTween) + scaleOut(targetScale = 0.94f, animationSpec = scaleTween)
                            },
                            popEnterTransition = {
                                fadeIn(animationSpec = fadeTween)
                            },
                            popExitTransition = {
                                fadeOut(animationSpec = fadeTween) + scaleOut(targetScale = 0.94f, animationSpec = scaleTween)
                            }
                        ) { backStackEntry ->
                            val itemId = backStackEntry.arguments?.getLong("itemId") ?: 0L
                            val folderName = backStackEntry.arguments?.getString("folderName") ?: ""
                            PhotoViewerScreen(
                                itemId = itemId,
                                folderName = folderName,
                                mediaItems = mediaItemsState.value,
                                repository = repository,
                                onBackClick = { navController.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }

    var pipParamsBuilder: android.app.PictureInPictureParams.Builder? = null
    var onPipPlayPause: (() -> Unit)? = null
    var onPipPrev: (() -> Unit)? = null
    var onPipNext: (() -> Unit)? = null

    fun updatePipParams(aspectRatio: android.util.Rational?) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val builder = pipParamsBuilder ?: android.app.PictureInPictureParams.Builder()
            if (aspectRatio != null && aspectRatio.numerator > 0 && aspectRatio.denominator > 0) {
                val floatValue = aspectRatio.toFloat()
                val safeRatio = when {
                    floatValue < 1f / 2.39f -> android.util.Rational(100, 239)
                    floatValue > 2.39f -> android.util.Rational(239, 100)
                    else -> aspectRatio
                }
                builder.setAspectRatio(safeRatio)
            }
            builder.setActions(buildPipActions())
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                builder.setSeamlessResizeEnabled(true)
                builder.setAutoEnterEnabled(repository.isPipEnabled())
            }
            pipParamsBuilder = builder
            setPictureInPictureParams(builder.build())
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handlePipIntent(intent)
        if (intent.getBooleanExtra("EXTRA_RESTORE_FLOATING", false) == true) {
            pendingRestoreState.value = true
        }
    }

    private fun handlePipIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_PIP_PLAY_PAUSE -> onPipPlayPause?.invoke()
            ACTION_PIP_PREV -> onPipPrev?.invoke()
            ACTION_PIP_NEXT -> onPipNext?.invoke()
        }
    }

    private fun buildPipActions(): List<RemoteAction> {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return emptyList()
        return buildList {
            add(
                pipAction(
                    action = ACTION_PIP_PREV,
                    iconRes = android.R.drawable.ic_media_previous,
                    title = "上一个",
                    enabled = pipCanPrev
                )
            )
            add(
                pipAction(
                    action = ACTION_PIP_PLAY_PAUSE,
                    iconRes = if (isVideoPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    title = if (isVideoPlaying) "暂停" else "播放",
                    enabled = true
                )
            )
            add(
                pipAction(
                    action = ACTION_PIP_NEXT,
                    iconRes = android.R.drawable.ic_media_next,
                    title = "下一个",
                    enabled = pipCanNext
                )
            )
        }
    }

    private fun pipAction(action: String, iconRes: Int, title: String, enabled: Boolean): RemoteAction {
        val intent = Intent(action).setPackage(packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val pendingIntent = PendingIntent.getBroadcast(this, action.hashCode(), intent, flags)
        return RemoteAction(
            Icon.createWithResource(this, iconRes),
            title,
            title,
            pendingIntent
        ).apply {
            isEnabled = enabled
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val currentRoute = navController?.currentBackStackEntry?.destination?.route
        val isPlayerScreenActive = currentRoute?.startsWith("video") == true
        if (isPlayerScreenActive && isVideoPlaying && repository.isPipEnabled()) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M &&
                android.provider.Settings.canDrawOverlays(this)
            ) {
                FloatingPlayerManager.isFloating = true
                val serviceIntent = Intent(this, FloatingPlayerService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            } else {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    val params = pipParamsBuilder?.build() ?: android.app.PictureInPictureParams.Builder().build()
                    enterPictureInPictureMode(params)
                }
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipMode.value = isInPictureInPictureMode
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(pipReceiver)
        } catch (e: Exception) {
            // ignore
        }
        onPipPlayPause = null
        onPipPrev = null
        onPipNext = null
        super.onDestroy()
    }
}
