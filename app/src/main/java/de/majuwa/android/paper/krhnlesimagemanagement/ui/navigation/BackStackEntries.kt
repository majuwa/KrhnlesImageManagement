package de.majuwa.android.paper.krhnlesimagemanagement.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController

/**
 * Resolves the back-stack entry of [parentRoute] so a child destination can share
 * state (e.g. a ViewModel) scoped to that parent.
 *
 * The lookup is remembered with [currentEntry] — the child's own [NavBackStackEntry] —
 * as key, so it is re-resolved whenever the current entry changes and never keeps a
 * stale parent entry after the back stack has been rebuilt. Never key this lookup on a
 * constant (such as the route string): that degenerates to `remember { }` and is
 * exactly what the `UnrememberedGetBackStackEntry` lint check guards against.
 */
@Composable
internal fun rememberParentEntry(
    navController: NavController,
    currentEntry: NavBackStackEntry,
    parentRoute: String,
): NavBackStackEntry = remember(currentEntry) { navController.getBackStackEntry(parentRoute) }
