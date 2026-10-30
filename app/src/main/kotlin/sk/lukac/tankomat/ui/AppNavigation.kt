package sk.lukac.tankomat.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import sk.lukac.tankomat.viewmodel.MainViewModel
import sk.lukac.tankomat.viewmodel.UpdateState

private object Routes {
    const val MAIN = "main"
    const val SETTINGS = "settings"
}

@Composable
fun AppNavigation() {
    val nav = rememberNavController()
    val vm: MainViewModel = viewModel(factory = MainViewModel.Factory)

    NavHost(navController = nav, startDestination = Routes.MAIN) {
        composable(Routes.MAIN) {
            MainScreen(
                vm = vm,
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                vm = vm,
                onBack = { nav.popBackStack() },
            )
        }
    }

    UpdateOfferDialog(vm)
}

/**
 * Ponuka novej verzie po tichej kontrole pri štarte.
 *
 * Leží nad `NavHost`, nie v jednej obrazovke — kontrola beží pri štarte a
 * používateľ môže byť medzitým kdekoľvek. Sťahovanie spúšťa až potvrdenie;
 * o zvyšok (stiahnuť, overiť, nainštalovať) sa postará `checkForUpdate`,
 * ktorý používa aj tlačidlo v Nastaveniach.
 */
@Composable
private fun UpdateOfferDialog(vm: MainViewModel) {
    val state by vm.updateState.collectAsState()
    val offer = state as? UpdateState.Available ?: return

    AlertDialog(
        onDismissRequest = { vm.dismissUpdate() },
        title = { Text("Nová verzia") },
        text = { Text("Na NAS-e je verzia ${offer.remote} (máš ${offer.current}). Nainštalovať teraz?") },
        confirmButton = {
            TextButton(onClick = { vm.checkForUpdate() }) { Text("Nainštalovať") }
        },
        dismissButton = {
            TextButton(onClick = { vm.dismissUpdate() }) { Text("Neskôr") }
        },
    )
}
