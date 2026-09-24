package com.hostu404.trilliontracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import coil.imageLoader
import coil.request.ImageRequest
import com.hostu404.trilliontracker.ui.TrackerViewModel
import com.hostu404.trilliontracker.ui.components.DigitalFxOverlay
import com.hostu404.trilliontracker.ui.components.ScanlineOverlay
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

/**
 * Every screen sits over the same background painting rather than a flat
 * color — Goya's "Saturn Devouring His Son" (Museo del Prado, painted
 * 1819–1823; public domain everywhere, as it has been since Goya died in
 * 1828). [bg_saturn] is a pre-processed asset, not the original: a duotone
 * (dark ember-red shadows up to a hot red highlight, matching the app's
 * red/black HUD) rather than a literal photo, and blurred down to a glow/
 * mood texture so it reads as cyberpunk ambient lighting rather than a
 * legible, disturbing image — but it's meant to actually read as a visible
 * red wash behind the HUD, not to hide near-invisibly under it, so the scrim
 * above it stays moderate rather than heavy — it's the HUD panels
 * ([TT.surface]/[TT.surfaceRaised], both ~85% alpha) that do the rest of the
 * legibility work by sitting as translucent glass over the glow instead of
 * solid tiles. [alignment] is biased down from center: the source painting's
 * negative space sits above Saturn's head, so without the bias the top of
 * every screen would show flat dark instead of the red glow.
 *
 * The painting is drawn through a brightening [ColorMatrix] (a flat ~1.45x
 * channel scale plus a small lift, so shadows lift too rather than just the
 * highlights blowing out) and the scrim above it dropped from 33% to 20%
 * alpha — both toward the same end: more of the actual image reading
 * through, not just implied by a red wash. [DigitalFxOverlay] (a drifting
 * grid + scan beam) and [ScanlineOverlay] (a static fine texture) stack
 * above that, coarsest/most-alive first, each one more very-low-alpha layer
 * so neither competes with the live numbers sitting on top of them.
 */
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

    // Scale every channel up and lift the floor a touch so dark shadow
    // detail brightens along with the highlights, rather than the image
    // just clipping to white at the top end.
    val brightenFilter = remember {
        ColorFilter.colorMatrix(
            ColorMatrix(
                floatArrayOf(
                    1.45f, 0f, 0f, 0f, 18f,
                    0f, 1.45f, 0f, 0f, 18f,
                    0f, 0f, 1.45f, 0f, 18f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(id = R.drawable.bg_saturn),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alignment = BiasAlignment(0f, 0.4f),
            colorFilter = brightenFilter
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(TT.plane.copy(alpha = 0.20f))
        )
        DigitalFxOverlay()
        ScanlineOverlay()

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
            // rendered, just the background," since App()'s Saturn image/
            // scrim/overlays sit underneath and keep painting regardless.
            // Every screen here is a full HUD panel replacing the last, not
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

            composable("person/{personId}") { entry ->
                PersonDetailScreen(
                    personId = entry.arguments?.getString("personId").orEmpty(),
                    state = state,
                    onBack = { navController.popBackStack() },
                    onOpenFamilyHistory = { id -> navController.navigate("familyHistory/$id") }
                )
            }

            composable("familyHistory/{personId}") { entry ->
                val id = entry.arguments?.getString("personId").orEmpty()
                FamilyHistoryScreen(
                    personId = id,
                    personName = state.personById(id)?.name ?: id,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
