package com.forbiddenstreams.vod

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.forbiddenstreams.vod.ui.VodTheme
import com.forbiddenstreams.vod.ui.detail.MovieDetailScreen
import com.forbiddenstreams.vod.ui.detail.SeriesDetailScreen
import com.forbiddenstreams.vod.ui.home.HomeScreen
import com.forbiddenstreams.vod.ui.login.LoginScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val signedIn = appContainer().account != null

        setContent {
            VodTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val nav = rememberNavController()
                    NavHost(navController = nav, startDestination = if (signedIn) "home" else "login") {
                        composable("login") {
                            LoginScreen(onSignedIn = {
                                nav.navigate("home") { popUpTo("login") { inclusive = true } }
                            })
                        }
                        composable("home") {
                            HomeScreen(
                                onOpenMovie = { id -> nav.navigate("movie/$id") },
                                onOpenSeries = { id -> nav.navigate("series/$id") },
                                onSignedOut = {
                                    nav.navigate("login") { popUpTo("home") { inclusive = true } }
                                },
                            )
                        }
                        composable(
                            "movie/{id}",
                            arguments = listOf(navArgument("id") { type = NavType.IntType }),
                        ) { entry ->
                            MovieDetailScreen(
                                id = entry.arguments?.getInt("id") ?: 0,
                                onBack = { nav.popBackStack() },
                            )
                        }
                        composable(
                            "series/{id}",
                            arguments = listOf(navArgument("id") { type = NavType.IntType }),
                        ) { entry ->
                            SeriesDetailScreen(
                                id = entry.arguments?.getInt("id") ?: 0,
                                onBack = { nav.popBackStack() },
                            )
                        }
                    }
                }
            }
        }
    }
}
