package com.forbiddenstreams.vod

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.forbiddenstreams.vod.data.MediaKind
import com.forbiddenstreams.vod.ui.VodTheme
import com.forbiddenstreams.vod.ui.detail.MovieDetailScreen
import com.forbiddenstreams.vod.ui.detail.SeriesDetailScreen
import com.forbiddenstreams.vod.ui.home.BrowseScreen
import com.forbiddenstreams.vod.ui.home.LandingScreen
import com.forbiddenstreams.vod.ui.home.MyListScreen
import com.forbiddenstreams.vod.ui.login.LoginScreen
import com.forbiddenstreams.vod.ui.search.SearchScreen
import com.forbiddenstreams.vod.ui.settings.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val signedIn = appContainer().account != null

        setContent {
            VodTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val nav = rememberNavController()
                    val openMovie: (Int) -> Unit = { id -> nav.navigate("movie/$id") }
                    val openSeries: (Int) -> Unit = { id -> nav.navigate("series/$id") }
                    val back: () -> Unit = { nav.popBackStack() }

                    NavHost(navController = nav, startDestination = if (signedIn) "home" else "login") {
                        composable("login") {
                            LoginScreen(onSignedIn = {
                                nav.navigate("home") { popUpTo("login") { inclusive = true } }
                            })
                        }
                        composable("home") {
                            LandingScreen(
                                onMovies = { nav.navigate("browse/${MediaKind.MOVIE}") },
                                onSeries = { nav.navigate("browse/${MediaKind.SERIES}") },
                                onMyList = { nav.navigate("mylist") },
                                onSettings = { nav.navigate("settings") },
                            )
                        }
                        composable("browse/{kind}") { entry ->
                            val kind = entry.arguments?.getString("kind") ?: MediaKind.MOVIE
                            BrowseScreen(
                                kind = kind,
                                onBack = back,
                                onSearch = { nav.navigate("search/$kind") },
                                onSettings = { nav.navigate("settings") },
                                onOpen = if (kind == MediaKind.SERIES) openSeries else openMovie,
                            )
                        }
                        composable("search/{kind}") { entry ->
                            val kind = entry.arguments?.getString("kind") ?: MediaKind.MOVIE
                            SearchScreen(
                                kind = kind,
                                onBack = back,
                                onOpen = if (kind == MediaKind.SERIES) openSeries else openMovie,
                            )
                        }
                        composable("mylist") {
                            MyListScreen(onBack = back, onOpenMovie = openMovie, onOpenSeries = openSeries)
                        }
                        composable("settings") {
                            SettingsScreen(onBack = back, onSignedOut = { nav.toLogin() })
                        }
                        composable(
                            "movie/{id}",
                            arguments = listOf(navArgument("id") { type = NavType.IntType }),
                        ) { entry ->
                            MovieDetailScreen(id = entry.arguments?.getInt("id") ?: 0, onBack = back)
                        }
                        composable(
                            "series/{id}",
                            arguments = listOf(navArgument("id") { type = NavType.IntType }),
                        ) { entry ->
                            SeriesDetailScreen(id = entry.arguments?.getInt("id") ?: 0, onBack = back)
                        }
                    }
                }
            }
        }
    }
}

/** Clears everything (home, settings, details) and shows the sign-in screen. */
private fun NavHostController.toLogin() {
    navigate("login") {
        popUpTo(graph.id) { inclusive = true }
    }
}
