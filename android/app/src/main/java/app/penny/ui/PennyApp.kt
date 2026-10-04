package app.penny.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.penny.PennyApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private object Routes {
    const val SETUP = "setup"
    const val HOME = "home"
    const val ACCOUNT = "account/{id}"
    const val ADD = "add?accountId={accountId}"
    const val SETTINGS = "settings"
    const val HIDDEN_ACCOUNTS = "settings/hidden"

    fun account(id: String) = "account/${android.net.Uri.encode(id)}"
    fun add(accountId: String?) = if (accountId == null) "add" else "add?accountId=${android.net.Uri.encode(accountId)}"
}

@Composable
fun PennyApp(app: PennyApplication) {
    val repository = app.repository
    // Wait for the stored pairing before choosing the first screen.
    val paired by produceState<Boolean?>(null) { value = app.settings.bridge.first() != null }
    val isPaired = paired ?: run {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val nav = rememberNavController()
    val toSetup = { nav.navigate(Routes.SETUP) { popUpTo(0) { inclusive = true } } }

    // Refresh whenever the app comes to the foreground; a no-op until paired.
    val scope = rememberCoroutineScope()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { scope.launch { repository.refresh() } }

    // While locked the screens leave the composition entirely (so their dialogs can't show above the lock screen),
    // and the state holder keeps their saved state for when they come back.
    val locked by app.appLock.locked.collectAsStateWithLifecycle()
    val screens = rememberSaveableStateHolder()
    var resetAfterUnlock by rememberSaveable { mutableStateOf(false) }
    if (locked) {
        LockScreen(app.appLock, onReset = {
            scope.launch {
                repository.unpair()
                app.appLock.disable()
                resetAfterUnlock = true
            }
        })
        return
    }
    if (resetAfterUnlock) {
        LaunchedEffect(Unit) {
            toSetup()
            resetAfterUnlock = false
        }
    }

    screens.SaveableStateProvider("screens") {
        Screens(app, nav, startDestination = if (isPaired) Routes.HOME else Routes.SETUP, toSetup)
    }
}

@Composable
private fun Screens(app: PennyApplication, nav: NavHostController, startDestination: String, toSetup: () -> Unit) {
    val repository = app.repository
    NavHost(navController = nav, startDestination = startDestination) {
        composable(Routes.SETUP) {
            val vm: SetupViewModel = viewModel(factory = viewModelFactory {
                initializer { SetupViewModel(repository, app.discovery) }
            })
            SetupScreen(
                vm,
                onPaired = { nav.navigate(Routes.HOME) { popUpTo(0) { inclusive = true } } },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.HOME) {
            val vm: TransactionsViewModel = viewModel(key = "recent", factory = viewModelFactory {
                initializer { TransactionsViewModel(repository, null) }
            })
            HomeScreen(
                repository = repository,
                recent = vm,
                hiddenAccounts = app.preferences.hidden,
                onOpenAccount = { nav.navigate(Routes.account(it)) },
                onAdd = { nav.navigate(Routes.add(null)) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onOpenHiddenAccounts = { nav.navigate(Routes.HIDDEN_ACCOUNTS) },
            )
        }
        composable(Routes.ACCOUNT, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            val vm: TransactionsViewModel = viewModel(key = "account-$id", factory = viewModelFactory {
                initializer { TransactionsViewModel(repository, id) }
            })
            AccountScreen(
                repository = repository,
                accountId = id,
                transactions = vm,
                onBack = { nav.popBackStack() },
                onAdd = { nav.navigate(Routes.add(id)) },
            )
        }
        composable(
            Routes.ADD,
            arguments = listOf(navArgument("accountId") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { entry ->
            val accountId = entry.arguments?.getString("accountId")
            val vm: AddTransactionViewModel = viewModel(factory = viewModelFactory {
                initializer { AddTransactionViewModel(repository, app.preferences.hidden, accountId) }
            })
            AddTransactionScreen(
                repository = repository,
                hiddenAccounts = app.preferences.hidden,
                vm = vm,
                onDone = { nav.popBackStack() },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                preferences = app.preferences,
                lock = app.appLock,
                settings = app.settings,
                repository = repository,
                onBack = { nav.popBackStack() },
                onUnpaired = toSetup,
                onOpenHiddenAccounts = { nav.navigate(Routes.HIDDEN_ACCOUNTS) },
            )
        }
        composable(Routes.HIDDEN_ACCOUNTS) {
            HiddenAccountsScreen(preferences = app.preferences, repository = repository, onBack = { nav.popBackStack() })
        }
    }
}
