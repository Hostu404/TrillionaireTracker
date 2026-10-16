package com.hostu404.trilliontracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import coil.imageLoader
import coil.request.ImageRequest
import com.hostu404.trilliontracker.ui.TrackerViewModel
import com.hostu404.trilliontracker.ui.components.edgeSwipeBack
import com.hostu404.trilliontracker.ui.screens.FamilyHistoryScreen
import com.hostu404.trilliontracker.ui.screens.PersonDetailScreen
import com.hostu404.trilliontracker.ui.screens.TrackerScreen
import com.hostu404.trilliontracker.ui.theme.TT
import com.hostu404.trilliontracker.ui.theme.TrillionaireTrackerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TrillionaireTrackerTheme {
                App()
            }
        }
    }
}

/** Every screen sits on plain black: no background image or effects. */
@Composable
private fun App() {
    val viewModel: TrackerViewModel = viewModel(factory = TrackerViewModel.Factory)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navController = rememberNavController()

    // Every person's photoUrl is already known as soon as the snapshot
    // loads — nobody has to open a detail screen first to reveal it — so
    // warm Coil's cache for all of them right away instead of only on
    // first view. By the time someone actually taps into a person, the
    // portrait Coil hands back on PersonDetailScreen is a cache hit, not a
    // fresh network fetch: same one request per photo either way, just
    // moved earlier, not a repeated poll against Wikimedia.
    val context = LocalContext.current
    val photoUrls = state.people.mapNotNull { it.photoUrl }
    LaunchedEffect(photoUrls) {
        photoUrls.forEach { url ->
            context.imageLoader.enqueue(ImageRequest.Builder(context).data(url).build())
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TT.plane)
    ) {
        NavHost(
            navController = navController,
            startDestination = "tracker",
            // None of the three destinations below override these, which
            // means every navigate()/popBackStack() was picking up
            // NavHost's own default animation — a timed crossfade between
            // the outgoing and incoming destination. That default is
            // exactly the kind of thing that can produce the sort of
            // one-off, unreproducible blank frame reported after hitting
            // back: for one frame mid-crossfade, depending on exactly when
            // the incoming destination's first composition lands relative
            // to the outgoing one's fade-out, there's a real (if narrow)
            // window where neither is opaque yet — which reads as "nothing
            // rendered, just the background."
            // Every screen here is a full page replacing the last, not
            // a peek-through/parallax transition, so there's no visual
            // reason to animate between them in the first place — an
            // instant cut removes the crossfade window entirely rather than
            // just narrowing it, which is the only way to be sure this
            // specific class of gap can't recur.
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            composable("tracker") {
                TrackerScreen(
                    state = state,
                    onPersonClick = { id -> navController.navigate("person/$id") },
                    onRefresh = { viewModel.refreshNow() }
                )
            }

            // Neither destination below takes an onBack callback for a
            // rendered button any more — both used to draw their own in-app
            // back button, removed 2026-09-24 as pure redundancy against the
            // phone's own system back, which Navigation Compose already
            // wires up to popBackStack() automatically for every
            // destination, button or not. [edgeSwipeBack] below adds a
            // second, gesture-driven path to that same popBackStack() call
            // rather than a visible button — see its own doc comment for
            // why one's needed at all (the Android Studio emulator's
            // gesture-nav mode has no reachable back affordance of its own).
            composable("person/{personId}") { entry ->
                Box(
                    Modifier
                        .fillMaxSize()
                        .edgeSwipeBack(onBack = { navController.popBackStack() })
                ) {
                    PersonDetailScreen(
                        personId = entry.arguments?.getString("personId").orEmpty(),
                        state = state,
                        onOpenFamilyHistory = { id -> navController.navigate("familyHistory/$id") }
                    )
                }
            }

            composable("familyHistory/{personId}") { entry ->
                val id = entry.arguments?.getString("personId").orEmpty()
                Box(
                    Modifier
                        .fillMaxSize()
                        .edgeSwipeBack(onBack = { navController.popBackStack() })
                ) {
                    FamilyHistoryScreen(
                        personId = id,
                        personName = state.personById(id)?.name ?: id
                    )
                }
            }
        }
    }
}
