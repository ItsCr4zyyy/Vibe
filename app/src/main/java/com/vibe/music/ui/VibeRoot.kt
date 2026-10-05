package com.vibe.music.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.vibe.music.ui.browse.AlbumScreen
import com.vibe.music.ui.browse.ArtistScreen
import com.vibe.music.ui.common.LocalContainer
import com.vibe.music.ui.common.LocalNavigator
import com.vibe.music.ui.common.Navigator
import com.vibe.music.ui.common.vibeViewModel
import com.vibe.music.ui.home.HomeScreen
import com.vibe.music.ui.importer.ImportSheet
import com.vibe.music.ui.importer.ImportViewModel
import com.vibe.music.ui.library.LibraryScreen
import com.vibe.music.ui.player.MiniPlayer
import com.vibe.music.ui.player.PlayerScreen
import com.vibe.music.ui.playlist.PlaylistScreen
import com.vibe.music.ui.search.SearchScreen
import com.vibe.music.ui.settings.EqualizerScreen
import com.vibe.music.ui.stats.StatsScreen
import com.vibe.music.ui.stats.WrappedScreen
import com.vibe.music.ui.settings.SettingsScreen
import com.vibe.music.ui.theme.VibeColors

private const val SETTINGS_ROUTE = "settings"
private const val EQUALIZER_ROUTE = "equalizer"
private const val STATS_ROUTE = "stats"
private const val WRAPPED_ROUTE = "wrapped"

/** Obrazovky přes celý displej – bez spodní navigace a mini přehrávače. */
private val FULLSCREEN_ROUTES = setOf(SETTINGS_ROUTE, EQUALIZER_ROUTE, WRAPPED_ROUTE)

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    HOME("home", "Domů", Icons.Rounded.Home),
    SEARCH("search", "Hledat", Icons.Rounded.Search),
    LIBRARY("library", "Knihovna", Icons.Rounded.LibraryMusic),
}

@Composable
fun VibeRoot(sharedText: String?, onSharedConsumed: () -> Unit) {
    val c = LocalContainer.current
    val nav = rememberNavController()
    val player by c.player.state.collectAsStateWithLifecycle()
    val importVm = vibeViewModel { ImportViewModel(it) }

    var playerExpanded by rememberSaveable { mutableStateOf(false) }
    var importOpen by rememberSaveable { mutableStateOf(false) }
    var importText by rememberSaveable { mutableStateOf("") }
    var lastTab by rememberSaveable { mutableStateOf(Tab.HOME) }

    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    LaunchedEffect(route) { Tab.entries.firstOrNull { it.route == route }?.let { lastTab = it } }

    fun openImport(text: String = "") {
        importVm.reset()
        importText = text
        importOpen = true
    }

    // Odkaz poslaný přes „Sdílet“ → rovnou importovat
    LaunchedEffect(sharedText) {
        if (sharedText != null) {
            openImport(sharedText)
            importVm.start(sharedText)
            onSharedConsumed()
        }
    }

    val navigator = remember(nav) {
        Navigator(navigate = { nav.navigate(it) }, beforeNavigate = { playerExpanded = false })
    }

    CompositionLocalProvider(LocalNavigator provides navigator) {
    Box(Modifier.fillMaxSize().background(VibeColors.Background)) {
        Scaffold(
            containerColor = VibeColors.Background,
            contentWindowInsets = WindowInsets(0),
            bottomBar = {
                // Nastavení je samostatná obrazovka přes celý displej – bez navigace a mini přehrávače
                if (route !in FULLSCREEN_ROUTES) Column {
                    // Přechod jen za mini přehrávačem – navigace má plné pozadí, aby pod ní neprosvítal obsah
                    Box(Modifier.background(Brush.verticalGradient(listOf(Color.Transparent, VibeColors.Background)))) {
                        MiniPlayer(onExpand = { playerExpanded = true })
                    }
                    NavigationBar(containerColor = VibeColors.Background, windowInsets = WindowInsets.navigationBars) {
                        Tab.entries.forEach { tab ->
                            NavigationBarItem(
                                selected = lastTab == tab,
                                onClick = {
                                    // Opakované ťuknutí na aktivní záložku vrátí na její začátek (jako Spotify)
                                    if (lastTab != tab || !nav.popBackStack(tab.route, inclusive = false)) {
                                        nav.navigateToTab(tab.route)
                                    }
                                },
                                icon = { Icon(tab.icon, null) },
                                label = { Text(tab.label) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Color.White,
                                    selectedTextColor = Color.White,
                                    indicatorColor = Color.Transparent,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                            )
                        }
                    }
                }
            },
        ) { padding ->
            NavHost(nav, startDestination = Tab.HOME.route, modifier = Modifier.fillMaxSize()) {
                composable(Tab.HOME.route) {
                    HomeScreen(
                        contentPadding = padding,
                        onOpenPlaylist = { nav.navigate("playlist/$it") },
                        onOpenCollection = { nav.navigate("playlist/$it") },
                        onOpenSettings = { nav.navigate(SETTINGS_ROUTE) },
                        onOpenStats = { nav.navigate(STATS_ROUTE) },
                        onImport = { openImport() },
                        onSearch = { nav.navigateToTab(Tab.SEARCH.route) },
                    )
                }
                composable(Tab.SEARCH.route) {
                    SearchScreen(contentPadding = padding, onImportLink = { openImport(it) })
                }
                composable(Tab.LIBRARY.route) {
                    LibraryScreen(
                        contentPadding = padding,
                        onOpenPlaylist = { nav.navigate("playlist/$it") },
                        onOpenCollection = { nav.navigate("playlist/$it") },
                        onImport = { openImport() },
                        onOpenStats = { nav.navigate(STATS_ROUTE) },
                    )
                }
                composable(STATS_ROUTE) {
                    StatsScreen(contentPadding = padding, onBack = { nav.popBackStack() }, onOpenWrapped = { nav.navigate(WRAPPED_ROUTE) })
                }
                composable(WRAPPED_ROUTE) {
                    WrappedScreen(onClose = { nav.popBackStack() })
                }
                composable("playlist/{key}") { entry ->
                    PlaylistScreen(
                        key = entry.arguments?.getString("key").orEmpty(),
                        contentPadding = padding,
                        onBack = { nav.popBackStack() },
                    )
                }
                composable("artist/{key}") { entry ->
                    ArtistScreen(
                        key = entry.arguments?.getString("key").orEmpty(),
                        contentPadding = padding,
                        onBack = { nav.popBackStack() },
                    )
                }
                composable("album/{key}") { entry ->
                    AlbumScreen(
                        key = entry.arguments?.getString("key").orEmpty(),
                        contentPadding = padding,
                        onBack = { nav.popBackStack() },
                    )
                }
                composable(SETTINGS_ROUTE) {
                    SettingsScreen(
                        contentPadding = WindowInsets.navigationBars.asPaddingValues(),
                        onBack = { nav.popBackStack() },
                        onOpenEqualizer = { nav.navigate(EQUALIZER_ROUTE) },
                    )
                }
                composable(EQUALIZER_ROUTE) {
                    EqualizerScreen(
                        contentPadding = WindowInsets.navigationBars.asPaddingValues(),
                        onBack = { nav.popBackStack() },
                    )
                }
            }
        }

        // Ztmavení pod stavovou lištou na hlavních záložkách, aby obsah nekolidoval s hodinami
        if (route != WRAPPED_ROUTE && (Tab.entries.any { it.route == route } || route in FULLSCREEN_ROUTES || route == STATS_ROUTE)) {
            Column(Modifier.fillMaxWidth()) {
                // Plné pozadí pod hodinami + krátký přechod, aby obsah neprosvítal
                Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(VibeColors.Background))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(12.dp)
                        .background(Brush.verticalGradient(listOf(VibeColors.Background, Color.Transparent)))
                )
            }
        }

        AnimatedVisibility(
            visible = playerExpanded && player.current != null,
            enter = slideInVertically(tween(320)) { it } + fadeIn(tween(200)),
            exit = slideOutVertically(tween(280)) { it } + fadeOut(tween(200)),
        ) {
            PlayerScreen(onCollapse = { playerExpanded = false })
        }
    }


    if (importOpen) {
        ImportSheet(
            vm = importVm,
            initialText = importText,
            onOpenPlaylist = { id ->
                importOpen = false
                playerExpanded = false
                nav.navigate("playlist/$id")
            },
            onDismiss = { importOpen = false },
        )
    }
    }
}

private fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
